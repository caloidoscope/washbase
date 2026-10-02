package com.washbase.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;

@ExtendWith(OutputCaptureExtension.class)
class RegisteredClientsTests {

	private static final String SECRET = "a-web-client-secret-for-tests";

	private final PasswordEncoder passwordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();

	@Test
	@DisplayName("washbase-web is registered as a confidential Authorization Code + PKCE client with 15-minute JWT access tokens")
	void webClientRegistered(CapturedOutput output) {
		RegisteredClients clients = RegisteredClients.fromConfiguration(settings(SECRET), passwordEncoder);

		RegisteredClient web = clients.findByClientId("washbase-web");
		assertThat(web).isNotNull();
		assertThat(clients.findById("washbase-web")).isSameAs(web);
		assertThat(web.getClientName()).isEqualTo("Washbase web app");
		assertThat(web.getClientSecret()).isNotEqualTo(SECRET).startsWith("{bcrypt}");
		assertThat(passwordEncoder.matches(SECRET, web.getClientSecret())).isTrue();
		assertThat(web.getClientAuthenticationMethods())
			.containsExactly(ClientAuthenticationMethod.CLIENT_SECRET_BASIC);
		assertThat(web.getAuthorizationGrantTypes()).containsExactly(AuthorizationGrantType.AUTHORIZATION_CODE);
		assertThat(web.getRedirectUris()).containsExactly("http://localhost:13000/auth/callback");
		assertThat(web.getScopes()).containsExactly("openid");
		assertThat(web.getClientSettings().isRequireProofKey()).isTrue();
		assertThat(web.getClientSettings().isRequireAuthorizationConsent()).isFalse();
		assertThat(web.getTokenSettings().getAccessTokenTimeToLive()).isEqualTo(Duration.ofMinutes(15));
		assertThat(web.getTokenSettings().getAccessTokenFormat()).isEqualTo(OAuth2TokenFormat.SELF_CONTAINED);
		assertThat(web.getTokenSettings().getIdTokenSignatureAlgorithm()).isEqualTo(SignatureAlgorithm.RS256);
		assertThat(output.getAll()).doesNotContain(RegisteredClients.WEB_CLIENT_MISSING_WARNING)
			.doesNotContain(SECRET);
	}

	@Test
	@DisplayName("No client is registered when the web client secret is unset, and one WARN is logged")
	void noClientWithoutSecret(CapturedOutput output) {
		RegisteredClients clients = RegisteredClients.fromConfiguration(settings(""), passwordEncoder);

		assertThat(clients.findByClientId("washbase-web")).isNull();
		assertThat(clients.findById("washbase-web")).isNull();
		assertThat(output.getAll()).containsOnlyOnce(RegisteredClients.WEB_CLIENT_MISSING_WARNING).contains("WARN");
	}

	@Test
	@DisplayName("Registered clients can't be changed at runtime")
	void readOnly() {
		RegisteredClients clients = RegisteredClients.fromConfiguration(settings(SECRET), passwordEncoder);

		assertThatThrownBy(() -> clients.save(clients.findByClientId("washbase-web")))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	@DisplayName("The web client settings' toString() never shows the secret")
	void toStringMasksSecret() {
		assertThat(settings(SECRET).toString()).contains("secret=******").doesNotContain(SECRET);
		assertThat(settings(null).toString()).contains("secret=<unset>");
	}

	private static AuthProperties settings(String secret) {
		return new AuthProperties("http://localhost:8080", "washbase-api", null, true,
				new AuthProperties.WebClient("washbase-web", secret, "http://localhost:13000/auth/callback"));
	}

}
