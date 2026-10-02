package com.washbase.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.washbase.api.auth.PublicClientTokenAuthenticationConverter.Endpoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;

/** Which requests {@link PublicClientTokenAuthenticationConverter} takes (CAR-20), and which it leaves to Spring's. */
class PublicClientTokenAuthenticationConverterTests {

	private final PublicClientTokenAuthenticationConverter converter = new PublicClientTokenAuthenticationConverter(
			AuthorizationServerSettings.builder().build());

	@Test
	@DisplayName("A renewal with client_id only is a public client authentication marked for the token endpoint")
	void refreshWithClientIdOnly() {
		MockHttpServletRequest request = form("/oauth2/token");
		request.addParameter("grant_type", "refresh_token");
		request.addParameter("refresh_token", "a-refresh-token");
		request.addParameter("client_id", "washbase-mobile");

		OAuth2ClientAuthenticationToken token = (OAuth2ClientAuthenticationToken) converter.convert(request);

		assertThat(token).isNotNull();
		assertThat(token.getPrincipal()).isEqualTo("washbase-mobile");
		assertThat(token.getClientAuthenticationMethod()).isEqualTo(ClientAuthenticationMethod.NONE);
		assertThat(token.getCredentials()).isNull();
		assertThat(token.isAuthenticated()).isFalse();
		assertThat(token.getAdditionalParameters())
			.containsEntry(PublicClientTokenAuthenticationConverter.ENDPOINT_PARAMETER, Endpoint.TOKEN)
			.containsEntry("grant_type", "refresh_token")
			.containsEntry("refresh_token", "a-refresh-token")
			.doesNotContainKey("client_id");
	}

	@Test
	@DisplayName("A revocation with client_id only is a public client authentication marked for the revocation endpoint")
	void revokeWithClientIdOnly() {
		MockHttpServletRequest request = form("/oauth2/revoke");
		request.addParameter("token", "a-refresh-token");
		request.addParameter("token_type_hint", "refresh_token");
		request.addParameter("client_id", "washbase-mobile");

		OAuth2ClientAuthenticationToken token = (OAuth2ClientAuthenticationToken) converter.convert(request);

		assertThat(token).isNotNull();
		assertThat(token.getPrincipal()).isEqualTo("washbase-mobile");
		assertThat(token.getAdditionalParameters())
			.containsEntry(PublicClientTokenAuthenticationConverter.ENDPOINT_PARAMETER, Endpoint.REVOCATION);
	}

	@Test
	@DisplayName("Other grants at the token endpoint are left to Spring's converters (the code exchange needs PKCE)")
	void otherGrantsNotTaken() {
		for (String grantType : new String[] { "authorization_code", "client_credentials", "password" }) {
			MockHttpServletRequest request = form("/oauth2/token");
			request.addParameter("grant_type", grantType);
			request.addParameter("code", "a-code");
			request.addParameter("client_id", "washbase-mobile");

			assertThat(converter.convert(request)).as(grantType).isNull();
		}
		MockHttpServletRequest noGrant = form("/oauth2/token");
		noGrant.addParameter("client_id", "washbase-mobile");
		assertThat(converter.convert(noGrant)).isNull();
	}

	@Test
	@DisplayName("Other endpoints are left alone (introspection, authorize, an unknown path)")
	void otherEndpointsNotTaken() {
		for (String path : new String[] { "/oauth2/introspect", "/oauth2/authorize", "/oauth2/token/extra" }) {
			MockHttpServletRequest request = form(path);
			request.addParameter("grant_type", "refresh_token");
			request.addParameter("token", "a-token");
			request.addParameter("client_id", "washbase-mobile");

			assertThat(converter.convert(request)).as(path).isNull();
		}
	}

	@Test
	@DisplayName("Requests carrying a client credential are left to Spring's converters")
	void credentialsNotTaken() {
		MockHttpServletRequest basic = refresh();
		basic.addHeader(HttpHeaders.AUTHORIZATION, "Basic d2FzaGJhc2Utd2ViOnNlY3JldA==");
		MockHttpServletRequest secret = refresh();
		secret.addParameter("client_secret", "a-secret");
		MockHttpServletRequest assertion = refresh();
		assertion.addParameter("client_assertion", "a.jwt.assertion");

		assertThat(converter.convert(basic)).isNull();
		assertThat(converter.convert(secret)).isNull();
		assertThat(converter.convert(assertion)).isNull();
	}

	@Test
	@DisplayName("Only form POSTs are taken: not GET, not JSON, not a client_id in the query string")
	void onlyFormPosts() {
		MockHttpServletRequest get = refresh();
		get.setMethod("GET");
		MockHttpServletRequest json = refresh();
		json.setContentType(MediaType.APPLICATION_JSON_VALUE);
		MockHttpServletRequest noContentType = refresh();
		noContentType.setContentType(null);
		MockHttpServletRequest query = form("/oauth2/token");
		query.addParameter("grant_type", "refresh_token");
		query.addParameter("client_id", "washbase-mobile");
		query.setQueryString("client_id=washbase-mobile");

		assertThat(converter.convert(get)).isNull();
		assertThat(converter.convert(json)).isNull();
		assertThat(converter.convert(noContentType)).isNull();
		assertThat(converter.convert(query)).isNull();
	}

	@Test
	@DisplayName("Without a client_id the request isn't taken")
	void noClientId() {
		MockHttpServletRequest request = form("/oauth2/token");
		request.addParameter("grant_type", "refresh_token");
		request.addParameter("refresh_token", "a-refresh-token");

		assertThat(converter.convert(request)).isNull();
	}

	@Test
	@DisplayName("A blank or repeated client_id is invalid_request")
	void blankOrRepeatedClientId() {
		MockHttpServletRequest blank = form("/oauth2/token");
		blank.addParameter("grant_type", "refresh_token");
		blank.addParameter("client_id", " ");
		MockHttpServletRequest repeated = form("/oauth2/revoke");
		repeated.addParameter("client_id", "washbase-mobile", "washbase-web");

		for (MockHttpServletRequest request : new MockHttpServletRequest[] { blank, repeated }) {
			assertThatExceptionOfType(OAuth2AuthenticationException.class).isThrownBy(() -> converter.convert(request))
				.satisfies(ex -> assertThat(ex.getError().getErrorCode()).isEqualTo(OAuth2ErrorCodes.INVALID_REQUEST));
		}
	}

	@Test
	@DisplayName("The endpoint paths come from the authorization server settings")
	void pathsFromSettings() {
		PublicClientTokenAuthenticationConverter custom = new PublicClientTokenAuthenticationConverter(
				AuthorizationServerSettings.builder().tokenEndpoint("/token").tokenRevocationEndpoint("/revoke").build());
		MockHttpServletRequest token = form("/token");
		token.addParameter("grant_type", "refresh_token");
		token.addParameter("client_id", "washbase-mobile");
		MockHttpServletRequest revoke = form("/revoke");
		revoke.addParameter("client_id", "washbase-mobile");

		Authentication renewal = custom.convert(token);
		Authentication revocation = custom.convert(revoke);

		assertThat(renewal).isNotNull();
		assertThat(revocation).isNotNull();
		assertThat(custom.convert(refresh())).as("the default path is no longer the token endpoint").isNull();
	}

	private static MockHttpServletRequest refresh() {
		MockHttpServletRequest request = form("/oauth2/token");
		request.addParameter("grant_type", "refresh_token");
		request.addParameter("refresh_token", "a-refresh-token");
		request.addParameter("client_id", "washbase-mobile");
		return request;
	}

	private static MockHttpServletRequest form(String path) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
		request.setServletPath(path);
		request.setContentType(MediaType.APPLICATION_FORM_URLENCODED_VALUE);
		return request;
	}

}
