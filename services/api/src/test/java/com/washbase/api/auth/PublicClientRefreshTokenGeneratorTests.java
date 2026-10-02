package com.washbase.api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.washbase.api.MutableClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.token.DefaultOAuth2TokenContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2RefreshTokenGenerator;

/** {@link PublicClientRefreshTokenGenerator}: refresh tokens for public clients too (CAR-20). */
@ExtendWith(OutputCaptureExtension.class)
class PublicClientRefreshTokenGeneratorTests {

	private static final Instant NOW = Instant.parse("2026-10-01T01:00:00Z");

	private final RegisteredClients clients = RegisteredClients.fromConfiguration(
			new AuthProperties("http://localhost:8080", "washbase-api", null, true,
					new AuthProperties.WebClient("washbase-web", "a-secret", "http://localhost:3000/auth/callback"),
					Duration.ofDays(30), AuthProperties.MobileClient.defaults()),
			PasswordEncoderFactories.createDelegatingPasswordEncoder());

	private final PublicClientRefreshTokenGenerator generator = new PublicClientRefreshTokenGenerator(
			new MutableClock(NOW));

	@Test
	@DisplayName("washbase-mobile (a public client) gets a refresh token for the code exchange, which Spring's generator refuses")
	void publicClientGetsRefreshToken(CapturedOutput output) {
		RegisteredClient mobile = clients.findByClientId("washbase-mobile");
		DefaultOAuth2TokenContext context = codeExchange(mobile, ClientAuthenticationMethod.NONE,
				OAuth2TokenType.REFRESH_TOKEN);

		OAuth2RefreshToken token = generator.generate(context);

		assertThat(new OAuth2RefreshTokenGenerator().generate(context)).as("Spring's own generator").isNull();
		assertThat(token).isNotNull();
		assertThat(token.getIssuedAt()).isEqualTo(NOW);
		assertThat(token.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(30)));
		// 96 random bytes, Base64 URL-safe without padding: 128 characters.
		assertThat(token.getTokenValue()).hasSize(128).matches("[A-Za-z0-9_-]+");
		assertThat(generator.generate(context).getTokenValue()).isNotEqualTo(token.getTokenValue());
		assertThat(output.getAll()).doesNotContain(token.getTokenValue());
	}

	@Test
	@DisplayName("washbase-web's refresh tokens are like Spring's: same format and lifetime")
	void confidentialClientLikeSpring() {
		RegisteredClient web = clients.findByClientId("washbase-web");
		DefaultOAuth2TokenContext context = codeExchange(web, ClientAuthenticationMethod.CLIENT_SECRET_BASIC,
				OAuth2TokenType.REFRESH_TOKEN);

		OAuth2RefreshToken ours = generator.generate(context);
		OAuth2RefreshToken spring = new OAuth2RefreshTokenGenerator().generate(context);

		assertThat(ours.getTokenValue()).hasSameSizeAs(spring.getTokenValue());
		assertThat(Duration.between(ours.getIssuedAt(), ours.getExpiresAt()))
			.isEqualTo(Duration.between(spring.getIssuedAt(), spring.getExpiresAt()));
	}

	@Test
	@DisplayName("No refresh token for other token types, or for a client without the refresh_token grant")
	void nothingElse() {
		RegisteredClient mobile = clients.findByClientId("washbase-mobile");
		RegisteredClient noRefresh = RegisteredClient.from(mobile)
			.authorizationGrantTypes(grants -> grants.remove(AuthorizationGrantType.REFRESH_TOKEN))
			.build();

		assertThat(generator.generate(codeExchange(mobile, ClientAuthenticationMethod.NONE,
				OAuth2TokenType.ACCESS_TOKEN)))
			.isNull();
		assertThat(generator.generate(codeExchange(noRefresh, ClientAuthenticationMethod.NONE,
				OAuth2TokenType.REFRESH_TOKEN)))
			.isNull();
	}

	private static DefaultOAuth2TokenContext codeExchange(RegisteredClient client, ClientAuthenticationMethod method,
			OAuth2TokenType tokenType) {
		OAuth2ClientAuthenticationToken clientPrincipal = new OAuth2ClientAuthenticationToken(client, method, null);
		OAuth2AuthorizationCodeAuthenticationToken grant = new OAuth2AuthorizationCodeAuthenticationToken("a-code",
				clientPrincipal, "washbase://auth/callback", null);
		return DefaultOAuth2TokenContext.builder()
			.registeredClient(client)
			.principal(new TestingAuthenticationToken("user", null))
			.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
			.authorizationGrant(grant)
			.tokenType(tokenType)
			.build();
	}

}
