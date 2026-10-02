package com.washbase.api.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import org.jspecify.annotations.Nullable;
import org.springframework.security.crypto.keygen.Base64StringKeyGenerator;
import org.springframework.security.crypto.keygen.StringKeyGenerator;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;

/**
 * Refresh tokens for every client registered with the {@code refresh_token} grant, public clients included (CAR-20,
 * ADR-001 Amendment 2).
 *
 * <p>Spring Authorization Server 7.1.1's {@code OAuth2RefreshTokenGenerator} returns {@code null} for a public
 * client (authentication method {@code none}) in the authorization code grant, so {@code washbase-mobile} would get no
 * refresh token and nobody could stay signed in on the phone. OAuth 2.1 (section 4.3.1) allows refresh tokens for
 * public clients when they are sender-constrained <em>or rotated</em>: Washbase rotates them on every use with reuse
 * detection ({@link RefreshTokenReuseDetectingAuthorizationService}), the same as for the web app.
 *
 * <p>Contract:
 * <ul>
 * <li>returns {@code null} unless {@code context.getTokenType()} is {@code REFRESH_TOKEN} and the registered client
 * has the {@code refresh_token} grant type;</li>
 * <li>otherwise a new opaque token: 96 random bytes, Base64 URL-safe without padding (as Spring's generator),
 * {@code issuedAt} = now from the injected {@link java.time.Clock}, {@code expiresAt} = issuedAt + the client's
 * {@code refreshTokenTimeToLive} ({@code washbase.auth.refresh-token-ttl});</li>
 * <li>never logs the token.</li>
 * </ul>
 * Wired as the refresh-token part of the authorization server's {@code OAuth2TokenGenerator} bean
 * ({@code DelegatingOAuth2TokenGenerator(JwtGenerator with AccessTokenCustomizer, this)}), replacing Spring's default.
 */
final class PublicClientRefreshTokenGenerator implements OAuth2TokenGenerator<OAuth2RefreshToken> {

	/** As Spring's {@code OAuth2RefreshTokenGenerator}: 96 bytes from a {@code SecureRandom}, Base64 URL-safe. */
	private final StringKeyGenerator tokenValues = new Base64StringKeyGenerator(Base64.getUrlEncoder().withoutPadding(),
			96);

	private final Clock clock;

	PublicClientRefreshTokenGenerator(Clock clock) {
		this.clock = clock;
	}

	@Override
	public @Nullable OAuth2RefreshToken generate(OAuth2TokenContext context) {
		if (!OAuth2TokenType.REFRESH_TOKEN.equals(context.getTokenType())) {
			return null;
		}
		RegisteredClient client = context.getRegisteredClient();
		if (client == null || !client.getAuthorizationGrantTypes().contains(AuthorizationGrantType.REFRESH_TOKEN)) {
			return null;
		}
		Instant issuedAt = clock.instant();
		Instant expiresAt = issuedAt.plus(client.getTokenSettings().getRefreshTokenTimeToLive());
		return new OAuth2RefreshToken(tokenValues.generateKey(), issuedAt, expiresAt);
	}

}
