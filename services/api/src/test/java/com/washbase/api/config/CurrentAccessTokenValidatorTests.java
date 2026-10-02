package com.washbase.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

class CurrentAccessTokenValidatorTests {

	private static final Instant ISSUED = Instant.now().truncatedTo(ChronoUnit.SECONDS);

	private static final Instant EXPIRES = ISSUED.plus(15, ChronoUnit.MINUTES);

	private static final RegisteredClient CLIENT = RegisteredClient.withId("washbase-web")
		.clientId("washbase-web")
		.clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
		.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
		.redirectUri("http://localhost:3000/auth/callback")
		.build();

	private final OAuth2AuthorizationService authorizations = mock(OAuth2AuthorizationService.class);

	private final CurrentAccessTokenValidator validator = new CurrentAccessTokenValidator(authorizations);

	@Test
	@DisplayName("The current access token of an active authorization is accepted")
	void currentTokenAccepted() {
		when(authorizations.findByToken("token-1", OAuth2TokenType.ACCESS_TOKEN))
			.thenReturn(authorization("token-1", false));

		assertThat(validator.validate(jwt("token-1")).hasErrors()).isFalse();
	}

	@Test
	@DisplayName("An access token the authorization server doesn't know (replaced, or authorization removed) is refused")
	void unknownTokenRefused() {
		assertInvalidToken(validator.validate(jwt("token-1")));
	}

	@Test
	@DisplayName("A revoked (invalidated) access token is refused")
	void invalidatedTokenRefused() {
		when(authorizations.findByToken("token-1", OAuth2TokenType.ACCESS_TOKEN))
			.thenReturn(authorization("token-1", true));

		assertInvalidToken(validator.validate(jwt("token-1")));
	}

	@Test
	@DisplayName("An authorization that holds another access token refuses this one")
	void otherCurrentTokenRefused() {
		when(authorizations.findByToken("token-1", OAuth2TokenType.ACCESS_TOKEN))
			.thenReturn(authorization("token-2", false));

		assertInvalidToken(validator.validate(jwt("token-1")));
	}

	private static void assertInvalidToken(OAuth2TokenValidatorResult result) {
		assertThat(result.hasErrors()).isTrue();
		assertThat(result.getErrors()).singleElement()
			.satisfies(error -> assertThat(error.getErrorCode()).isEqualTo(OAuth2ErrorCodes.INVALID_TOKEN));
	}

	private static Jwt jwt(String value) {
		return Jwt.withTokenValue(value).header("alg", "RS256").subject("user-1").issuedAt(ISSUED).expiresAt(EXPIRES)
			.build();
	}

	private static OAuth2Authorization authorization(String accessToken, boolean invalidated) {
		OAuth2AccessToken token = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, accessToken, ISSUED,
				EXPIRES);
		OAuth2Authorization.Builder builder = OAuth2Authorization.withRegisteredClient(CLIENT)
			.principalName("user-1")
			.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
			.accessToken(token);
		if (invalidated) {
			builder.invalidate(token);
		}
		return builder.build();
	}

}
