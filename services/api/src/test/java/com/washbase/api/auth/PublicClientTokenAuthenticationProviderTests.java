package com.washbase.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.washbase.api.auth.PublicClientTokenAuthenticationConverter.Endpoint;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

/** {@link PublicClientTokenAuthenticationProvider}: client_id-only authentication for public clients only (CAR-20). */
class PublicClientTokenAuthenticationProviderTests {

	private final RegisteredClients clients = RegisteredClients.fromConfiguration(
			new AuthProperties("http://localhost:8080", "washbase-api", null, true,
					new AuthProperties.WebClient("washbase-web", "a-secret", "http://localhost:3000/auth/callback"),
					Duration.ofDays(30), AuthProperties.MobileClient.defaults()),
			PasswordEncoderFactories.createDelegatingPasswordEncoder());

	private final PublicClientTokenAuthenticationProvider provider = new PublicClientTokenAuthenticationProvider(
			clients);

	@Test
	@DisplayName("washbase-mobile is authenticated by client_id alone for a renewal and for a revocation")
	void mobileAuthenticated() {
		for (Endpoint endpoint : Endpoint.values()) {
			OAuth2ClientAuthenticationToken result = (OAuth2ClientAuthenticationToken) provider
				.authenticate(request("washbase-mobile", endpoint));

			assertThat(result).as(endpoint.name()).isNotNull();
			assertThat(result.isAuthenticated()).isTrue();
			assertThat(result.getRegisteredClient()).isEqualTo(clients.findByClientId("washbase-mobile"));
			assertThat(result.getClientAuthenticationMethod()).isEqualTo(ClientAuthenticationMethod.NONE);
			assertThat(result.getCredentials()).isNull();
		}
	}

	@Test
	@DisplayName("washbase-web (client_secret_basic) can never be authenticated by client_id alone: invalid_client")
	void webClientRefused() {
		for (Endpoint endpoint : Endpoint.values()) {
			assertInvalidClient(request("washbase-web", endpoint));
		}
	}

	@Test
	@DisplayName("An unknown client_id is invalid_client")
	void unknownClientRefused() {
		assertInvalidClient(request("someone-else", Endpoint.TOKEN));
		assertInvalidClient(request("someone-else", Endpoint.REVOCATION));
	}

	@Test
	@DisplayName("A public client without the refresh_token grant can't renew by client_id alone, but may revoke")
	void publicClientWithoutRefreshGrant() {
		RegisteredClient noRefresh = RegisteredClient.withId("no-refresh")
			.clientId("no-refresh")
			.clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
			.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
			.redirectUri("washbase://auth/callback")
			.build();
		PublicClientTokenAuthenticationProvider custom = new PublicClientTokenAuthenticationProvider(
				new InMemoryRegisteredClientRepository(noRefresh));

		assertThatExceptionOfType(OAuth2AuthenticationException.class)
			.isThrownBy(() -> custom.authenticate(request("no-refresh", Endpoint.TOKEN)))
			.satisfies(ex -> assertThat(ex.getError().getErrorCode()).isEqualTo(OAuth2ErrorCodes.INVALID_CLIENT));
		assertThat(custom.authenticate(request("no-refresh", Endpoint.REVOCATION))).isNotNull();
	}

	@Test
	@DisplayName("Requests not marked by the converter are left to the other providers (null)")
	void unmarkedRequestsDeclined() {
		// Spring's PKCE code exchange: method none, no marker.
		Map<String, Object> codeExchange = new HashMap<>();
		codeExchange.put("grant_type", "authorization_code");
		codeExchange.put("code", "a-code");
		codeExchange.put("code_verifier", "a-verifier");
		assertThat(provider.authenticate(
				new OAuth2ClientAuthenticationToken("washbase-mobile", ClientAuthenticationMethod.NONE, null,
						codeExchange)))
			.isNull();
		// The marker's name sent as a form parameter is a string, never the converter's Endpoint.
		Map<String, Object> forged = new HashMap<>(codeExchange);
		forged.put(PublicClientTokenAuthenticationConverter.ENDPOINT_PARAMETER, "TOKEN");
		assertThat(provider.authenticate(
				new OAuth2ClientAuthenticationToken("washbase-mobile", ClientAuthenticationMethod.NONE, null, forged)))
			.isNull();
		// Another authentication method, even with the marker.
		Map<String, Object> marked = Map.of(PublicClientTokenAuthenticationConverter.ENDPOINT_PARAMETER,
				Endpoint.TOKEN);
		assertThat(provider.authenticate(new OAuth2ClientAuthenticationToken("washbase-web",
				ClientAuthenticationMethod.CLIENT_SECRET_BASIC, "a-secret", marked)))
			.isNull();
	}

	@Test
	@DisplayName("Supports client authentication tokens only")
	void supports() {
		assertThat(provider.supports(OAuth2ClientAuthenticationToken.class)).isTrue();
		assertThat(provider.supports(
				org.springframework.security.authentication.UsernamePasswordAuthenticationToken.class))
			.isFalse();
	}

	private void assertInvalidClient(OAuth2ClientAuthenticationToken request) {
		assertThatExceptionOfType(OAuth2AuthenticationException.class).isThrownBy(() -> provider.authenticate(request))
			.satisfies(ex -> assertThat(ex.getError().getErrorCode()).isEqualTo(OAuth2ErrorCodes.INVALID_CLIENT));
	}

	private static OAuth2ClientAuthenticationToken request(String clientId, Endpoint endpoint) {
		Map<String, Object> parameters = new HashMap<>();
		parameters.put(PublicClientTokenAuthenticationConverter.ENDPOINT_PARAMETER, endpoint);
		parameters.put("refresh_token", "a-refresh-token");
		return new OAuth2ClientAuthenticationToken(clientId, ClientAuthenticationMethod.NONE, null, parameters);
	}

}
