package com.washbase.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.washbase.api.TestcontainersConfiguration;
import com.washbase.api.user.AdminBootstrapping;
import java.net.URI;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The authorization server allows only Authorization Code + PKCE for the registered {@code washbase-web} client
 * (ADR-001): everything else is refused. Same configuration as {@link SignInTests}, so the context is shared.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AuthorizationEndpointsTests {

	private static final String PASSWORD = "Start-Here-2026";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ApplicationContext context;

	@Autowired
	private AuthProperties authProperties;

	@Autowired
	private JdbcTemplate jdbc;

	@BeforeEach
	void givenTheAdmin() {
		// A paused Admin (CAR-19) from an earlier test's wrong passwords would break sign-in here.
		jdbc.update("delete from sign_in_failure");
		jdbc.update("delete from sign_in_pause");
		jdbc.update("delete from oauth2_authorization");
		jdbc.update("delete from users");
		AdminBootstrapping.startWithChosenPassword(context, "admin@example.com", null, PASSWORD);
	}

	@Test
	@DisplayName("The password grant is refused")
	void passwordGrantRefused() throws Exception {
		mockMvc
			.perform(post("/oauth2/token").with(httpBasic(SignInFlow.CLIENT_ID, SignInFlow.CLIENT_SECRET))
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("grant_type", "password")
				.param("username", "admin@example.com")
				.param("password", PASSWORD)
				.param("scope", "openid"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").value("unsupported_grant_type"))
			.andExpect(jsonPath("$.access_token").doesNotExist());
	}

	@Test
	@DisplayName("The client credentials grant is refused for the web app")
	void clientCredentialsGrantRefused() throws Exception {
		mockMvc
			.perform(post("/oauth2/token").with(httpBasic(SignInFlow.CLIENT_ID, SignInFlow.CLIENT_SECRET))
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("grant_type", "client_credentials"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").value("unauthorized_client"))
			.andExpect(jsonPath("$.access_token").doesNotExist());
	}

	@Test
	@DisplayName("An authorization request without PKCE is refused")
	void authorizeWithoutPkceRefused() throws Exception {
		SignInFlow browser = new SignInFlow(mockMvc);

		MockHttpServletResponse response = mockMvc
			.perform(get(browser.authorizeUri(SignInFlow.REDIRECT_URI, false)).accept(MediaType.TEXT_HTML))
			.andReturn()
			.getResponse();

		// The redirect URI is valid, so the error goes back to the web app; no code is issued.
		String location = response.getHeader(HttpHeaders.LOCATION);
		assertThat(location).startsWith(SignInFlow.REDIRECT_URI + "?");
		var query = UriComponentsBuilder.fromUriString(location).build().getQueryParams();
		assertThat(query.getFirst("error")).isEqualTo("invalid_request");
		assertThat(query.getFirst("code")).isNull();
	}

	@Test
	@DisplayName("A code can't be exchanged without the PKCE code verifier, or with the wrong one")
	void tokenWithoutCodeVerifierRefused() throws Exception {
		SignInFlow browser = new SignInFlow(mockMvc);
		String code = browser.authorizationCode("admin@example.com", PASSWORD);

		MockHttpServletResponse wrongVerifier = browser.tokenRequest(code, "x".repeat(43), SignInFlow.CLIENT_SECRET);
		assertThat(wrongVerifier.getStatus()).isEqualTo(400);
		assertThat(wrongVerifier.getContentAsString()).contains("invalid_grant").doesNotContain("access_token");

		SignInFlow other = new SignInFlow(mockMvc);
		String otherCode = other.authorizationCode("admin@example.com", PASSWORD);
		MockHttpServletResponse noVerifier = other.tokenRequest(otherCode, null, SignInFlow.CLIENT_SECRET);
		assertThat(noVerifier.getStatus()).isEqualTo(400);
		assertThat(noVerifier.getContentAsString()).doesNotContain("access_token");
	}

	@Test
	@DisplayName("An authorization request with another redirect URI is refused without redirecting")
	void wrongRedirectUriRefused() throws Exception {
		SignInFlow browser = new SignInFlow(mockMvc);

		MockHttpServletResponse response = mockMvc
			.perform(get(browser.authorizeUri("https://evil.example.com/auth/callback", true))
				.accept(MediaType.TEXT_HTML))
			.andReturn()
			.getResponse();

		assertThat(response.getStatus()).isEqualTo(400);
		assertThat(response.getHeader(HttpHeaders.LOCATION)).isNull();
	}

	@Test
	@DisplayName("An unknown client is refused without redirecting")
	void unknownClientRefused() throws Exception {
		URI uri = URI.create(new SignInFlow(mockMvc).authorizeUri(SignInFlow.REDIRECT_URI, true)
			.toString()
			.replace("client_id=washbase-web", "client_id=someone-else"));

		MockHttpServletResponse response = mockMvc.perform(get(uri).accept(MediaType.TEXT_HTML))
			.andReturn()
			.getResponse();

		assertThat(response.getStatus()).isEqualTo(400);
		assertThat(response.getHeader(HttpHeaders.LOCATION)).isNull();
	}

	@Test
	@DisplayName("Exchanging a code with the wrong client secret is refused (401)")
	void wrongClientSecretRefused() throws Exception {
		SignInFlow browser = new SignInFlow(mockMvc);
		String code = browser.authorizationCode("admin@example.com", PASSWORD);

		MockHttpServletResponse response = browser.tokenRequest(code, browser.codeVerifier(), "not-the-secret");

		assertThat(response.getStatus()).isEqualTo(401);
		assertThat(response.getContentAsString()).contains("invalid_client").doesNotContain("access_token");
	}

	@Test
	@DisplayName("A code can be exchanged only once")
	void codeSingleUse() throws Exception {
		SignInFlow browser = new SignInFlow(mockMvc);
		String code = browser.authorizationCode("admin@example.com", PASSWORD);
		browser.exchange(code, browser.codeVerifier(), SignInFlow.CLIENT_SECRET);

		MockHttpServletResponse again = browser.tokenRequest(code, browser.codeVerifier(), SignInFlow.CLIENT_SECRET);

		assertThat(again.getStatus()).isEqualTo(400);
		assertThat(again.getContentAsString()).contains("invalid_grant");
	}

	@Test
	@DisplayName("The stored authorization can be read back: OIDC UserInfo accepts the access token")
	void userInfoWithAccessToken() throws Exception {
		String accessToken = new SignInFlow(mockMvc).signIn("admin@example.com", PASSWORD);
		String adminId = jdbc.queryForObject("select id::text from users where email = 'admin@example.com'",
				String.class);

		mockMvc.perform(get("/userinfo").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.sub").value(adminId));
	}

	@Test
	@DisplayName("OIDC discovery shows the configured issuer, and the JWKS publishes the signing key's public part")
	void discoveryAndJwks() throws Exception {
		mockMvc.perform(get("/.well-known/openid-configuration"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.issuer").value(authProperties.issuer()))
			.andExpect(jsonPath("$.grant_types_supported").isArray());

		String jwks = mockMvc.perform(get("/oauth2/jwks"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.keys.length()").value(1))
			.andExpect(jsonPath("$.keys[0].kty").value("RSA"))
			.andExpect(jsonPath("$.keys[0].alg").value("RS256"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		RSAKey published = JWKSet.parse(jwks).getKeys().getFirst().toRSAKey();
		assertThat(published.isPrivate()).isFalse();
		assertThat(published.getKeyID()).isEqualTo(published.computeThumbprint().toString());
	}

	@Test
	@DisplayName("Default deny still applies to /api/**, and the sign-in page is public")
	void apiStillDefaultDeny() throws Exception {
		mockMvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/login")).andExpect(status().isOk());
	}

}
