package com.washbase.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.transaction.support.TransactionOperations;

@ExtendWith(OutputCaptureExtension.class)
class RefreshTokenReuseDetectingAuthorizationServiceTests {

	private static final RegisteredClient CLIENT = RegisteredClient.withId("washbase-web")
		.clientId("washbase-web")
		.clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
		.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
		.redirectUri("http://localhost:3000/auth/callback")
		.build();

	private static final Instant EXPIRES = Instant.now().plus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);

	private final OAuth2AuthorizationService delegate = mock(OAuth2AuthorizationService.class);

	private final JdbcOperations jdbc = mock(JdbcOperations.class);

	private final RefreshTokenReuseDetectingAuthorizationService service = new RefreshTokenReuseDetectingAuthorizationService(
			delegate, jdbc, TransactionOperations.withoutTransaction());

	@Test
	@DisplayName("Saving a renewal remembers the SHA-256 hash and expiry of the replaced refresh token")
	void saveRemembersReplacedTokenHash() {
		when(delegate.findById("auth-1")).thenReturn(authorization("auth-1", "refresh-A"));
		OAuth2Authorization renewed = authorization("auth-1", "refresh-B");

		service.save(renewed);

		verify(jdbc).update(anyString(), eq(RefreshTokenReuseDetectingAuthorizationService.hash("refresh-A")),
				eq("auth-1"), eq(Timestamp.from(EXPIRES)));
		verify(delegate).save(renewed);
	}

	@Test
	@DisplayName("Saving without a new refresh token (e.g. revocation) or a first save remembers nothing")
	void saveWithoutReplacementRemembersNothing() {
		when(delegate.findById("auth-1")).thenReturn(authorization("auth-1", "refresh-A"));
		service.save(authorization("auth-1", "refresh-A"));
		service.save(authorization("auth-2", "refresh-C"));

		verifyNoInteractions(jdbc);
	}

	@Test
	@DisplayName("The hash is lowercase hex SHA-256 of the token value")
	void hashIsLowercaseHexSha256() {
		assertThat(RefreshTokenReuseDetectingAuthorizationService.hash("abc"))
			.isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
	}

	@Test
	@DisplayName("A replaced refresh token presented again removes the whole authorization and finds nothing")
	void reusedRefreshTokenRemovesAuthorization(CapturedOutput output) {
		OAuth2Authorization current = authorization("auth-1", "refresh-B");
		when(jdbc.queryForList(anyString(), eq(String.class),
				eq(RefreshTokenReuseDetectingAuthorizationService.hash("refresh-A"))))
			.thenReturn(List.of("auth-1"));
		when(delegate.findById("auth-1")).thenReturn(current);

		assertThat(service.findByToken("refresh-A", OAuth2TokenType.REFRESH_TOKEN)).isNull();

		verify(delegate).remove(current);
		assertThat(output.getAll()).contains("WARN")
			.contains("Refresh token reuse detected: authorization auth-1 removed")
			.doesNotContain("refresh-A")
			.doesNotContain("refresh-B");
	}

	@Test
	@DisplayName("A replaced refresh token presented to the revocation endpoint (no token type) also ends the authorization")
	void reusedTokenWithoutTypeRemovesAuthorization() {
		OAuth2Authorization current = authorization("auth-1", "refresh-B");
		when(jdbc.queryForList(anyString(), eq(String.class), anyString())).thenReturn(List.of("auth-1"));
		when(delegate.findById("auth-1")).thenReturn(current);

		assertThat(service.findByToken("refresh-A", null)).isNull();

		verify(delegate).remove(current);
	}

	@Test
	@DisplayName("An unknown or expired replaced token finds nothing and removes nothing")
	void unknownTokenRemovesNothing(CapturedOutput output) {
		when(jdbc.queryForList(anyString(), eq(String.class), anyString())).thenReturn(List.of());

		assertThat(service.findByToken("refresh-X", OAuth2TokenType.REFRESH_TOKEN)).isNull();

		verify(delegate, never()).remove(any());
		assertThat(output.getAll()).doesNotContain("reuse detected");
	}

	@Test
	@DisplayName("A current token is returned as found, without looking at replaced tokens")
	void currentTokenFound() {
		OAuth2Authorization current = authorization("auth-1", "refresh-B");
		when(delegate.findByToken("refresh-B", OAuth2TokenType.REFRESH_TOKEN)).thenReturn(current);

		OAuth2Authorization found = service.findByToken("refresh-B", OAuth2TokenType.REFRESH_TOKEN);

		assertThat(found.getId()).isEqualTo("auth-1");
		assertThat(found.getRefreshToken().getToken().getTokenValue()).isEqualTo("refresh-B");
		assertThat(found.<String>getAttribute(RefreshTokenReuseDetectingAuthorizationService.PRESENTED_REFRESH_TOKEN_HASH))
			.isEqualTo(RefreshTokenReuseDetectingAuthorizationService.hash("refresh-B"));
		verifyNoInteractions(jdbc);
	}

	@Test
	@DisplayName("Saving a renewal whose presented refresh token is still the stored one succeeds, without storing the check")
	void saveOfWinningRenewalStoresWithoutPresentedHash() {
		when(delegate.findByToken("refresh-A", OAuth2TokenType.REFRESH_TOKEN))
			.thenReturn(authorization("auth-1", "refresh-A"));
		when(jdbc.queryForList(anyString(), eq(String.class), eq("auth-1"))).thenReturn(List.of("refresh-A"));
		OAuth2Authorization found = service.findByToken("refresh-A", OAuth2TokenType.REFRESH_TOKEN);

		service.save(renewal(found, "refresh-B"));

		ArgumentCaptor<OAuth2Authorization> stored = ArgumentCaptor.forClass(OAuth2Authorization.class);
		verify(delegate).save(stored.capture());
		assertThat(stored.getValue().getRefreshToken().getToken().getTokenValue()).isEqualTo("refresh-B");
		assertThat(stored.getValue().getAttributes())
			.doesNotContainKey(RefreshTokenReuseDetectingAuthorizationService.PRESENTED_REFRESH_TOKEN_HASH);
	}

	@Test
	@DisplayName("Of two concurrent renewals with the same refresh token, the one saved second is refused (invalid_grant)")
	void concurrentRenewalLoserRefused() {
		when(delegate.findByToken("refresh-A", OAuth2TokenType.REFRESH_TOKEN))
			.thenReturn(authorization("auth-1", "refresh-A"));
		OAuth2Authorization first = service.findByToken("refresh-A", OAuth2TokenType.REFRESH_TOKEN);
		OAuth2Authorization second = service.findByToken("refresh-A", OAuth2TokenType.REFRESH_TOKEN);
		// The row (locked by each save in turn) holds refresh-A for the first save, then refresh-B once it committed.
		when(jdbc.queryForList(anyString(), eq(String.class), eq("auth-1"))).thenReturn(List.of("refresh-A"),
				List.of("refresh-B"));
		when(delegate.findById("auth-1")).thenReturn(authorization("auth-1", "refresh-A"));
		OAuth2Authorization winner = renewal(first, "refresh-B");
		service.save(winner);

		assertThatExceptionOfType(OAuth2AuthenticationException.class)
			.isThrownBy(() -> service.save(renewal(second, "refresh-C")))
			.satisfies(ex -> assertThat(ex.getError().getErrorCode()).isEqualTo(OAuth2ErrorCodes.INVALID_GRANT));
		ArgumentCaptor<OAuth2Authorization> stored = ArgumentCaptor.forClass(OAuth2Authorization.class);
		verify(delegate).save(stored.capture());
		assertThat(stored.getValue().getRefreshToken().getToken().getTokenValue()).isEqualTo("refresh-B");
	}

	@Test
	@DisplayName("A renewal whose authorization was removed meanwhile is refused (invalid_grant)")
	void renewalOfRemovedAuthorizationRefused() {
		when(delegate.findByToken("refresh-A", OAuth2TokenType.REFRESH_TOKEN))
			.thenReturn(authorization("auth-1", "refresh-A"));
		OAuth2Authorization found = service.findByToken("refresh-A", OAuth2TokenType.REFRESH_TOKEN);
		when(jdbc.queryForList(anyString(), eq(String.class), eq("auth-1"))).thenReturn(List.of());

		assertThatExceptionOfType(OAuth2AuthenticationException.class)
			.isThrownBy(() -> service.save(renewal(found, "refresh-B")));
		verify(delegate, never()).save(any());
	}

	private static OAuth2Authorization renewal(OAuth2Authorization found, String newRefreshToken) {
		return OAuth2Authorization.from(found)
			.refreshToken(new OAuth2RefreshToken(newRefreshToken, EXPIRES.minus(30, ChronoUnit.DAYS), EXPIRES))
			.build();
	}

	@Test
	@DisplayName("Access token lookups never look at replaced refresh tokens")
	void accessTokenLookupSkipsReplacedTokens() {
		assertThat(service.findByToken("an-access-token", OAuth2TokenType.ACCESS_TOKEN)).isNull();

		verifyNoInteractions(jdbc);
	}

	@Test
	@DisplayName("A reused token whose authorization is already gone just finds nothing")
	void reusedTokenAuthorizationAlreadyGone() {
		when(jdbc.queryForList(anyString(), eq(String.class), anyString())).thenReturn(List.of("auth-1"));

		assertThat(service.findByToken("refresh-A", OAuth2TokenType.REFRESH_TOKEN)).isNull();

		verify(delegate, never()).remove(any());
	}

	private static OAuth2Authorization authorization(String id, String refreshToken) {
		return OAuth2Authorization.withRegisteredClient(CLIENT)
			.id(id)
			.principalName("user-1")
			.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
			.refreshToken(new OAuth2RefreshToken(refreshToken, EXPIRES.minus(30, ChronoUnit.DAYS), EXPIRES))
			.build();
	}

}
