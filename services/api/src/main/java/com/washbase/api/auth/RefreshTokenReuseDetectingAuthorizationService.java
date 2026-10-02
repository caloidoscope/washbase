package com.washbase.api.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Refresh-token reuse detection (CAR-18, ADR-001) around the JDBC authorization service.
 *
 * <p>Refresh tokens are rotated on every use ({@code reuseRefreshTokens(false)}, see {@link RegisteredClients}):
 * Spring Authorization Server replaces the refresh token in {@code oauth2_authorization} and forgets the old one.
 * This decorator remembers every replaced refresh token in {@code oauth2_replaced_refresh_token} (Flyway
 * {@code V3__refresh_token_rotation.sql}), as a lowercase hex SHA-256 hash only, until it would have expired:
 * <ul>
 * <li>{@link #save}: when the stored refresh token differs from the one being saved, its hash and expiry are
 * recorded, in the same transaction as the update.</li>
 * <li>{@link #findByToken}: when a token looked up as a refresh token (or with an unknown type, as the revocation
 * endpoint does) matches no authorization but matches an unexpired replaced hash, a stolen or replayed copy is in
 * use: the whole authorization is removed (its current refresh and access tokens stop working at once) and
 * {@code null} is returned, so the token endpoint answers {@code invalid_grant}. One WARN is logged with the
 * authorization ID only. A replaced token presented after its own expiry is simply unknown and ends nothing.</li>
 * <li>Only one renewal per refresh token can win: an authorization found by its current refresh token is returned
 * with that token's hash as a transient attribute ({@link #PRESENTED_REFRESH_TOKEN_HASH}, removed before storing).
 * {@link #save} locks the row ({@code select ... for update}) and refuses with {@code invalid_grant} if the stored
 * refresh token is no longer the one presented, i.e. a concurrent renewal or revocation with the same token won.
 * Without this, two simultaneous renewals would both succeed and the last write would silently win.</li>
 * </ul>
 * Token values are never stored by this class and never logged.
 */
final class RefreshTokenReuseDetectingAuthorizationService implements OAuth2AuthorizationService {

	static final String REUSE_DETECTED_WARNING = "Refresh token reuse detected: authorization {} removed, sign-in ended";

	/**
	 * Transient attribute: the SHA-256 hash of the refresh token an authorization was found by, so {@link #save} can
	 * check that it is still the stored one. Never stored (removed in {@link #save}).
	 */
	static final String PRESENTED_REFRESH_TOKEN_HASH = RefreshTokenReuseDetectingAuthorizationService.class.getName()
			+ ".PRESENTED_REFRESH_TOKEN_HASH";

	private static final Logger log = LoggerFactory.getLogger(RefreshTokenReuseDetectingAuthorizationService.class);

	/** Only if a refresh token has no expiry (not with Washbase's clients): remember it "forever". */
	private static final Instant NO_EXPIRY = Instant.parse("9999-12-31T00:00:00Z");

	private static final String INSERT_REPLACED_SQL = """
			insert into oauth2_replaced_refresh_token (token_hash, authorization_id, expires_at)
			values (?, ?, ?)
			on conflict (token_hash) do nothing""";

	/** Locks the authorization's row until the end of the transaction, so renewals of it are serialised. */
	private static final String LOCK_CURRENT_REFRESH_TOKEN_SQL = """
			select refresh_token_value from oauth2_authorization where id = ? for update""";

	private static final String FIND_REPLACED_SQL = """
			select authorization_id from oauth2_replaced_refresh_token
			where token_hash = ? and expires_at > now()""";

	private final OAuth2AuthorizationService delegate;

	private final JdbcOperations jdbcOperations;

	private final TransactionOperations transactions;

	RefreshTokenReuseDetectingAuthorizationService(OAuth2AuthorizationService delegate,
			JdbcOperations jdbcOperations, TransactionOperations transactions) {
		this.delegate = delegate;
		this.jdbcOperations = jdbcOperations;
		this.transactions = transactions;
	}

	@Override
	public void save(OAuth2Authorization authorization) {
		String presentedHash = authorization.getAttribute(PRESENTED_REFRESH_TOKEN_HASH);
		OAuth2Authorization toStore = (presentedHash == null) ? authorization
				: OAuth2Authorization.from(authorization)
					.attributes(attributes -> attributes.remove(PRESENTED_REFRESH_TOKEN_HASH))
					.build();
		transactions.executeWithoutResult(status -> {
			if (presentedHash != null) {
				ensureStillCurrent(toStore.getId(), presentedHash);
			}
			OAuth2Authorization stored = delegate.findById(toStore.getId());
			OAuth2RefreshToken replaced = replacedRefreshToken(stored, toStore);
			if (replaced != null) {
				Instant expiresAt = (replaced.getExpiresAt() != null) ? replaced.getExpiresAt() : NO_EXPIRY;
				jdbcOperations.update(INSERT_REPLACED_SQL, hash(replaced.getTokenValue()), toStore.getId(),
						Timestamp.from(expiresAt));
			}
			delegate.save(toStore);
		});
	}

	@Override
	public void remove(OAuth2Authorization authorization) {
		// The replaced-token rows go with it (on delete cascade).
		delegate.remove(authorization);
	}

	@Override
	public @Nullable OAuth2Authorization findById(String id) {
		return delegate.findById(id);
	}

	@Override
	public @Nullable OAuth2Authorization findByToken(String token, @Nullable OAuth2TokenType tokenType) {
		OAuth2Authorization authorization = delegate.findByToken(token, tokenType);
		if (tokenType != null && !OAuth2TokenType.REFRESH_TOKEN.equals(tokenType)) {
			return authorization;
		}
		if (authorization != null) {
			return isCurrentRefreshToken(authorization, token) ? OAuth2Authorization.from(authorization)
				.attribute(PRESENTED_REFRESH_TOKEN_HASH, hash(token))
				.build() : authorization;
		}
		List<String> reused = jdbcOperations.queryForList(FIND_REPLACED_SQL, String.class, hash(token));
		if (!reused.isEmpty()) {
			endAuthorization(reused.getFirst());
		}
		return null;
	}

	/**
	 * Locks the authorization's row and refuses ({@code invalid_grant}) if its refresh token is no longer the one
	 * presented: a concurrent renewal or revocation with the same token committed first, or the authorization is
	 * gone. The loser's token has then been replaced, so presenting it again triggers reuse detection.
	 */
	private void ensureStillCurrent(String authorizationId, String presentedHash) {
		List<String> current = jdbcOperations.queryForList(LOCK_CURRENT_REFRESH_TOKEN_SQL, String.class,
				authorizationId);
		if (current.isEmpty() || current.getFirst() == null || !hash(current.getFirst()).equals(presentedHash)) {
			throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_GRANT);
		}
	}

	private static boolean isCurrentRefreshToken(OAuth2Authorization authorization, String token) {
		OAuth2Authorization.Token<OAuth2RefreshToken> refreshToken = authorization.getRefreshToken();
		return refreshToken != null && token.equals(refreshToken.getToken().getTokenValue());
	}

	private void endAuthorization(String authorizationId) {
		OAuth2Authorization authorization = delegate.findById(authorizationId);
		if (authorization != null) {
			delegate.remove(authorization);
		}
		log.warn(REUSE_DETECTED_WARNING, authorizationId);
	}

	/** @return the stored refresh token if {@code next} replaces (or drops) it, otherwise {@code null} */
	private static @Nullable OAuth2RefreshToken replacedRefreshToken(@Nullable OAuth2Authorization stored,
			OAuth2Authorization next) {
		if (stored == null || stored.getRefreshToken() == null) {
			return null;
		}
		OAuth2RefreshToken current = stored.getRefreshToken().getToken();
		OAuth2Authorization.Token<OAuth2RefreshToken> nextToken = next.getRefreshToken();
		if (nextToken != null && current.getTokenValue().equals(nextToken.getToken().getTokenValue())) {
			return null;
		}
		return current;
	}

	/** Lowercase hex SHA-256 of the token value (what {@code oauth2_replaced_refresh_token.token_hash} holds). */
	static String hash(String tokenValue) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(tokenValue.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is not available", ex);
		}
	}

}
