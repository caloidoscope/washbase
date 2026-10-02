package com.washbase.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
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
		assertThat(web.getAuthorizationGrantTypes()).containsExactlyInAnyOrder(AuthorizationGrantType.AUTHORIZATION_CODE,
				AuthorizationGrantType.REFRESH_TOKEN);
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
	@DisplayName("washbase-web gets opaque refresh tokens, rotated on every use, with the configured lifetime, and signs out to the web app's home")
	void webClientRefreshTokensAndSignOut() {
		AuthProperties settings = new AuthProperties("http://localhost:8080", "washbase-api", null, true,
				new AuthProperties.WebClient("washbase-web", SECRET, "http://localhost:13000/auth/callback"),
				Duration.ofDays(7), AuthProperties.MobileClient.defaults());

		RegisteredClient web = RegisteredClients.fromConfiguration(settings, passwordEncoder)
			.findByClientId("washbase-web");

		assertThat(web.getAuthorizationGrantTypes()).contains(AuthorizationGrantType.REFRESH_TOKEN);
		assertThat(web.getTokenSettings().isReuseRefreshTokens()).as("rotated on every use").isFalse();
		assertThat(web.getTokenSettings().getRefreshTokenTimeToLive()).isEqualTo(Duration.ofDays(7));
		assertThat(web.getPostLogoutRedirectUris()).containsExactly("http://localhost:13000/");
	}

	@Test
	@DisplayName("The web client isn't registered when its secret is unset (only the mobile client is), and one WARN is logged")
	void noWebClientWithoutSecret(CapturedOutput output) {
		RegisteredClients clients = RegisteredClients.fromConfiguration(settings(""), passwordEncoder);

		assertThat(clients.findByClientId("washbase-web")).isNull();
		assertThat(clients.findById("washbase-web")).isNull();
		assertThat(clients.findByClientId("washbase-mobile")).isNotNull();
		assertThat(output.getAll()).containsOnlyOnce(RegisteredClients.WEB_CLIENT_MISSING_WARNING).contains("WARN");
	}

	@Test
	@DisplayName("washbase-mobile is registered as a public Authorization Code + PKCE client with the web app's token settings")
	void mobileClientRegistered() {
		RegisteredClients clients = RegisteredClients.fromConfiguration(settings(SECRET), passwordEncoder);

		RegisteredClient mobile = clients.findByClientId("washbase-mobile");
		RegisteredClient web = clients.findByClientId("washbase-web");
		assertThat(mobile).isNotNull();
		assertThat(clients.findById("washbase-mobile")).isSameAs(mobile);
		assertThat(mobile.getClientName()).isEqualTo("Washbase mobile app");
		assertThat(mobile.getClientSecret()).as("public: no secret").isNull();
		assertThat(mobile.getClientAuthenticationMethods()).containsExactly(ClientAuthenticationMethod.NONE);
		assertThat(mobile.getAuthorizationGrantTypes()).containsExactlyInAnyOrder(
				AuthorizationGrantType.AUTHORIZATION_CODE, AuthorizationGrantType.REFRESH_TOKEN);
		assertThat(mobile.getRedirectUris()).containsExactly("washbase://auth/callback");
		assertThat(mobile.getPostLogoutRedirectUris()).isEmpty();
		assertThat(mobile.getScopes()).containsExactly("openid");
		assertThat(mobile.getClientSettings().isRequireProofKey()).isTrue();
		assertThat(mobile.getClientSettings().isRequireAuthorizationConsent()).isFalse();
		assertThat(mobile.getTokenSettings().getSettings()).isEqualTo(web.getTokenSettings().getSettings());
		assertThat(mobile.getTokenSettings().getAccessTokenTimeToLive()).isEqualTo(Duration.ofMinutes(15));
		assertThat(mobile.getTokenSettings().isReuseRefreshTokens()).as("rotated on every use").isFalse();
		assertThat(mobile.getTokenSettings().getRefreshTokenTimeToLive()).isEqualTo(Duration.ofDays(30));
	}

	@Test
	@DisplayName("washbase-mobile's redirect URIs and client ID come from configuration")
	void mobileClientFromConfiguration() {
		AuthProperties settings = new AuthProperties("http://localhost:8080", "washbase-api", null, true,
				new AuthProperties.WebClient("washbase-web", SECRET, "http://localhost:13000/auth/callback"),
				Duration.ofDays(30), new AuthProperties.MobileClient("washbase-mobile-test",
						List.of("washbase://auth/callback", "exp://192.168.1.20:8081/--/auth/callback"), true));

		RegisteredClient mobile = RegisteredClients.fromConfiguration(settings, passwordEncoder)
			.findByClientId("washbase-mobile-test");

		assertThat(mobile).isNotNull();
		assertThat(mobile.getRedirectUris()).containsExactlyInAnyOrder("washbase://auth/callback",
				"exp://192.168.1.20:8081/--/auth/callback");
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
				new AuthProperties.WebClient("washbase-web", secret, "http://localhost:13000/auth/callback"),
				Duration.ofDays(30), AuthProperties.MobileClient.defaults());
	}

}
