package com.washbase.api.auth;

import static com.washbase.api.auth.SignInFlow.SIGN_IN_ERROR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.washbase.api.TestcontainersConfiguration;
import com.washbase.api.auth.SignInFlow.Tokens;
import com.washbase.api.user.AdminBootstrapping;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * CAR-20's scenarios at API level, and the security rules of the public mobile client ({@code washbase-mobile},
 * ADR-001 Amendment 2), through the real authorization server and resource server against the real database. Driven
 * the way the mobile app does it ({@link SignInFlow#mobile}): the sign-in page in the in-app browser, PKCE, the
 * redirect to {@code washbase://auth/callback}, and every token request (code exchange, renewal, revocation) with
 * {@code client_id} only. The phone's side of the scenarios is checked in {@code apps/mobile} and in Expo Go.
 */
@ExtendWith(OutputCaptureExtension.class)
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class MobileSignInTests {

	private static final String PASSWORD = "Start-Here-2026";

	private static final String ADMIN_EMAIL = "admin@example.com";

	private static final String ADMIN_MOBILE = "09171234567";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ApplicationContext context;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private JwtDecoder jwtDecoder;

	@Autowired
	private AuthProperties authProperties;

	@BeforeEach
	void givenTheAdmin() {
		// A paused Admin (CAR-19) from an earlier test's wrong passwords would break sign-in here.
		jdbc.update("delete from sign_in_failure");
		jdbc.update("delete from sign_in_pause");
		jdbc.update("delete from oauth2_authorization");
		jdbc.update("delete from users");
		AdminBootstrapping.startWith(context, ADMIN_EMAIL, ADMIN_MOBILE, PASSWORD);
	}

	// ----- Scenarios -----

	@Test
	@DisplayName("Scenario: The Admin signs in on the mobile app with their email address")
	void adminSignsInWithEmail(CapturedOutput output) throws Exception {
		Tokens tokens = SignInFlow.mobile(mockMvc).signInForTokens(ADMIN_EMAIL, PASSWORD);

		assertThat(tokens.accessToken()).isNotBlank();
		assertThat(tokens.refreshToken()).as("a refresh token, so the app stays signed in").isNotBlank();
		assertThat(tokens.idToken()).isNotBlank();
		// The home screen shows "Signed in as Admin (Admin)" from /api/v1/me.
		me(tokens.accessToken()).andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("Admin"))
			.andExpect(jsonPath("$.role").value("ADMIN"));
		assertThat(output.getAll()).doesNotContain(PASSWORD);
	}

	@Test
	@DisplayName("Scenario: The Admin signs in on the mobile app with their mobile number")
	void adminSignsInWithMobileNumber() throws Exception {
		Tokens tokens = SignInFlow.mobile(mockMvc).signInForTokens(ADMIN_MOBILE, PASSWORD);

		assertThat(tokens.refreshToken()).isNotBlank();
		me(tokens.accessToken()).andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("Admin"))
			.andExpect(jsonPath("$.role").value("ADMIN"))
			.andExpect(jsonPath("$.mobile").value("+639171234567"));
	}

	@Test
	@DisplayName("Scenario: A wrong password on mobile is refused")
	void wrongPasswordRefused(CapturedOutput output) throws Exception {
		SignInFlow phone = SignInFlow.mobile(mockMvc);
		phone.authorize();

		MockHttpServletResponse response = phone.submitSignIn(ADMIN_EMAIL, "wrong-password");

		assertThat(response.getRedirectedUrl()).isEqualTo("/login?error");
		assertThat(phone.page(response.getRedirectedUrl())).contains(SIGN_IN_ERROR);
		// No code was issued: nothing for the app, which stays on the welcome screen.
		assertThat(phone.isSignedIn()).isFalse();
		assertThat(phone.authorize().getRedirectedUrl()).endsWith("/login");
		assertThat(jdbc.queryForObject("select count(*) from oauth2_authorization", Integer.class)).isZero();
		assertThat(output.getAll()).doesNotContain("wrong-password");
	}

	@Test
	@DisplayName("Scenario: The Admin is still signed in after reopening the app")
	void stillSignedInAfterReopening() throws Exception {
		Tokens signIn = SignInFlow.mobile(mockMvc).signInForTokens(ADMIN_EMAIL, PASSWORD);

		// Reopening the app renews with the stored refresh token and client_id only.
		Tokens renewed = renew(signIn.refreshToken());

		assertThat(renewed.accessToken()).isNotEqualTo(signIn.accessToken());
		assertThat(renewed.refreshToken()).as("rotated").isNotEqualTo(signIn.refreshToken());
		me(renewed.accessToken()).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Admin"));
		// The old refresh token is now replaced (remembered for reuse detection), and the new one renews again.
		assertThat(jdbc.queryForList("select token_hash from oauth2_replaced_refresh_token", String.class))
			.containsExactly(RefreshTokenReuseDetectingAuthorizationService.hash(signIn.refreshToken()));
		assertThat(renew(renewed.refreshToken()).refreshToken()).isNotEqualTo(renewed.refreshToken());
	}

	@Test
	@DisplayName("Scenario: The Admin signs out on the mobile app")
	void adminSignsOut() throws Exception {
		SignInFlow phone = SignInFlow.mobile(mockMvc);
		Tokens tokens = phone.signInForTokens(ADMIN_EMAIL, PASSWORD);

		MockHttpServletResponse revoke = phone.revoke(tokens.refreshToken());

		assertThat(revoke.getStatus()).as("revoke: %s", revoke.getContentAsString()).isEqualTo(200);
		// Reopening the app can't renew: it shows the welcome screen.
		assertInvalidGrant(phone.refresh(tokens.refreshToken()));
		me(tokens.accessToken()).andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("Scenario: Signing out on mobile asks for the password again next time")
	void signingOutAsksForPasswordAgain() throws Exception {
		SignInFlow phone = SignInFlow.mobile(mockMvc);
		Tokens tokens = phone.signInForTokens(ADMIN_EMAIL, PASSWORD);
		phone.revoke(tokens.refreshToken());

		MockHttpServletResponse authorize = phone.authorize();

		assertThat(authorize.getStatus()).isEqualTo(302);
		assertThat(authorize.getRedirectedUrl()).as("not signed in automatically").endsWith("/login");
		assertThat(phone.page("/login")).contains("Email or mobile number").contains("Password");
	}

	@Test
	@DisplayName("Scenario: The mobile session ends after 30 days without use")
	void sessionEndsAfter30DaysWithoutUse() throws Exception {
		Tokens tokens = SignInFlow.mobile(mockMvc).signInForTokens(ADMIN_EMAIL, PASSWORD);
		// Signed in on 1 October, not opened again; now it is 1 November: the refresh token has expired.
		jdbc.update("update oauth2_authorization set refresh_token_issued_at = ?, refresh_token_expires_at = ?",
				Timestamp.from(Instant.now().minus(31, ChronoUnit.DAYS)),
				Timestamp.from(Instant.now().minus(1, ChronoUnit.DAYS)));

		assertInvalidGrant(SignInFlow.mobile(mockMvc).refresh(tokens.refreshToken()));
	}

	@Test
	@DisplayName("Scenario: Signing out on the phone does not sign out the web app")
	void signingOutOnPhoneKeepsWebSignedIn() throws Exception {
		SignInFlow browser = new SignInFlow(mockMvc);
		Tokens web = browser.signInForTokens(ADMIN_EMAIL, PASSWORD);
		SignInFlow phone = SignInFlow.mobile(mockMvc);
		Tokens mobile = phone.signInForTokens(ADMIN_EMAIL, PASSWORD);

		assertThat(phone.revoke(mobile.refreshToken()).getStatus()).isEqualTo(200);

		me(web.accessToken()).andExpect(status().isOk());
		MockHttpServletResponse webRenewal = browser.refresh(web.refreshToken());
		assertThat(webRenewal.getStatus()).as("web renewal: %s", webRenewal.getContentAsString()).isEqualTo(200);
		me(mobile.accessToken()).andExpect(status().isUnauthorized());
	}

	// ----- Tokens -----

	@Test
	@DisplayName("The mobile app's access token has the same claims and lifetime as the web app's; its ID token is for washbase-mobile")
	void mobileTokensLikeWebTokens() throws Exception {
		String adminId = jdbc.queryForObject("select id::text from users where email = ?", String.class, ADMIN_EMAIL);

		Tokens tokens = SignInFlow.mobile(mockMvc).signInForTokens(ADMIN_EMAIL, PASSWORD);

		Jwt access = jwtDecoder.decode(tokens.accessToken());
		assertThat(access.getHeaders()).containsEntry("alg", "RS256");
		assertThat(access.getIssuer()).hasToString(authProperties.issuer());
		assertThat(access.getAudience()).containsExactly("washbase-api");
		assertThat(access.getSubject()).isEqualTo(adminId);
		assertThat(access.getClaimAsStringList("roles")).containsExactly("ADMIN");
		assertThat(access.getClaimAsStringList("scope")).contains("openid");
		assertThat(Duration.between(access.getIssuedAt(), access.getExpiresAt())).isEqualTo(Duration.ofMinutes(15));
		assertThat(access.getClaims()).doesNotContainKey("cnf");
		JWTClaimsSet idToken = SignedJWT.parse(tokens.idToken()).getJWTClaimsSet();
		assertThat(idToken.getAudience()).containsExactly(SignInFlow.MOBILE_CLIENT_ID);
		assertThat(idToken.getStringClaim("azp")).isEqualTo(SignInFlow.MOBILE_CLIENT_ID);
		assertThat(idToken.getIssuer()).isEqualTo(authProperties.issuer());
		// Same refresh token lifetime as the web app's: 30 days from now.
		Instant expiry = jdbc.queryForObject("select refresh_token_expires_at from oauth2_authorization", Timestamp.class)
			.toInstant();
		assertThat(expiry).isBetween(Instant.now().plus(authProperties.refreshTokenTtl()).minus(1, ChronoUnit.MINUTES),
				Instant.now().plus(authProperties.refreshTokenTtl()));
	}

	@Test
	@DisplayName("No token value appears in the logs: mobile sign-in, renewal, reuse detection and sign-out")
	void noTokensInLogs(CapturedOutput output) throws Exception {
		SignInFlow phone = SignInFlow.mobile(mockMvc);
		Tokens signIn = phone.signInForTokens(ADMIN_EMAIL, PASSWORD);
		Tokens renewed = renew(signIn.refreshToken());
		phone.refresh(signIn.refreshToken());
		Tokens again = SignInFlow.mobile(mockMvc).signInForTokens(ADMIN_EMAIL, PASSWORD);
		phone.revoke(again.refreshToken());

		for (Tokens tokens : List.of(signIn, renewed, again)) {
			assertThat(output.getAll()).doesNotContain(tokens.accessToken())
				.doesNotContain(tokens.refreshToken())
				.doesNotContain(tokens.idToken());
		}
	}

	// ----- Security: client_id-only authentication is for washbase-mobile's renewal and revocation only -----

	@Test
	@DisplayName("A web app refresh token can't be renewed as washbase-mobile, and still works for the web app")
	void webRefreshTokenRefusedForMobile() throws Exception {
		SignInFlow browser = new SignInFlow(mockMvc);
		Tokens web = browser.signInForTokens(ADMIN_EMAIL, PASSWORD);

		assertInvalidGrant(SignInFlow.mobile(mockMvc).refresh(web.refreshToken()));

		assertThat(browser.refresh(web.refreshToken()).getStatus()).isEqualTo(200);
	}

	@Test
	@DisplayName("A web app refresh token revoked as washbase-mobile is refused and isn't revoked")
	void webRefreshTokenRevokedAsMobileHasNoEffect() throws Exception {
		SignInFlow browser = new SignInFlow(mockMvc);
		Tokens web = browser.signInForTokens(ADMIN_EMAIL, PASSWORD);

		MockHttpServletResponse revoke = SignInFlow.mobile(mockMvc).revoke(web.refreshToken());

		// The revocation endpoint answers every error with 400 (Spring Authorization Server).
		assertThat(revoke.getStatus()).isEqualTo(400);
		assertThat(revoke.getContentAsString()).contains("invalid_client");
		me(web.accessToken()).andExpect(status().isOk());
		assertThat(browser.refresh(web.refreshToken()).getStatus()).isEqualTo(200);
	}

	@Test
	@DisplayName("washbase-web can't renew or revoke with its client_id alone (invalid_client)")
	void webClientIdAloneRefused() throws Exception {
		SignInFlow browser = new SignInFlow(mockMvc);
		Tokens web = browser.signInForTokens(ADMIN_EMAIL, PASSWORD);

		MockHttpServletResponse refresh = browser.refreshAs(SignInFlow.CLIENT_ID, null, web.refreshToken());
		MockHttpServletResponse revoke = browser.revokeAs(SignInFlow.CLIENT_ID, null, web.refreshToken());

		for (MockHttpServletResponse response : List.of(refresh, revoke)) {
			assertThat(response.getStatus()).isEqualTo(401);
			assertThat(response.getContentAsString()).contains("invalid_client").doesNotContain("access_token");
		}
		me(web.accessToken()).andExpect(status().isOk());
		assertThat(browser.refresh(web.refreshToken()).getStatus()).isEqualTo(200);
	}

	@Test
	@DisplayName("An unknown client_id can't renew or revoke (invalid_client)")
	void unknownClientIdRefused() throws Exception {
		Tokens mobile = SignInFlow.mobile(mockMvc).signInForTokens(ADMIN_EMAIL, PASSWORD);
		SignInFlow someone = SignInFlow.mobile(mockMvc);

		assertThat(someone.refreshAs("someone-else", null, mobile.refreshToken()).getContentAsString())
			.contains("invalid_client");
		assertThat(someone.revokeAs("someone-else", null, mobile.refreshToken()).getStatus()).isEqualTo(401);
		me(mobile.accessToken()).andExpect(status().isOk());
	}

	@Test
	@DisplayName("Reusing a replaced mobile refresh token ends the whole mobile sign-in")
	void mobileRefreshTokenReuseEndsAuthorization(CapturedOutput output) throws Exception {
		Tokens signIn = SignInFlow.mobile(mockMvc).signInForTokens(ADMIN_EMAIL, PASSWORD);
		Tokens renewed = renew(signIn.refreshToken());

		assertInvalidGrant(SignInFlow.mobile(mockMvc).refresh(signIn.refreshToken()));

		assertInvalidGrant(SignInFlow.mobile(mockMvc).refresh(renewed.refreshToken()));
		me(renewed.accessToken()).andExpect(status().isUnauthorized());
		assertThat(jdbc.queryForObject("select count(*) from oauth2_authorization", Integer.class)).isZero();
		assertThat(output.getAll()).containsOnlyOnce("Refresh token reuse detected");
	}

	@ParameterizedTest(name = "An unregistered mobile redirect URI is refused without redirecting ({0})")
	@ValueSource(strings = { "washbase://evil", "exp://10.0.0.9:8081/--/auth/callback", "washbase://auth/callback/evil",
			"washbase://auth/callback?next=evil", "washbase://auth/callbackevil", "https://evil.example.com/auth/callback" })
	void unregisteredRedirectUriRefused(String redirectUri) throws Exception {
		SignInFlow phone = SignInFlow.mobile(mockMvc);

		MockHttpServletResponse response = mockMvc
			.perform(get(phone.authorizeUri(redirectUri, true)).accept(MediaType.TEXT_HTML))
			.andReturn()
			.getResponse();

		assertThat(response.getStatus()).isEqualTo(400);
		assertThat(response.getHeader(HttpHeaders.LOCATION)).isNull();
	}

	@Test
	@DisplayName("A mobile authorization request without PKCE is refused: no code is issued")
	void authorizeWithoutPkceRefused() throws Exception {
		SignInFlow phone = SignInFlow.mobile(mockMvc);

		MockHttpServletResponse response = mockMvc
			.perform(get(phone.authorizeUri(SignInFlow.MOBILE_REDIRECT_URI, false)).accept(MediaType.TEXT_HTML))
			.andReturn()
			.getResponse();

		// The redirect URI is registered, so the error goes back to the app (RFC 6749 section 4.1.2.1).
		String location = response.getHeader(HttpHeaders.LOCATION);
		assertThat(location).startsWith(SignInFlow.MOBILE_REDIRECT_URI + "?");
		var query = UriComponentsBuilder.fromUriString(location).build().getQueryParams();
		assertThat(query.getFirst("error")).isEqualTo("invalid_request");
		assertThat(query.getFirst("code")).isNull();
	}

	@Test
	@DisplayName("A mobile code can't be exchanged without the PKCE code verifier, or with the wrong one")
	void codeExchangeWithoutVerifierRefused() throws Exception {
		SignInFlow phone = SignInFlow.mobile(mockMvc);
		String code = phone.authorizationCode(ADMIN_EMAIL, PASSWORD);

		// Without a code_verifier no client authentication applies at all (client_id alone is only for renewal and
		// revocation), so the token endpoint refuses the unauthenticated request.
		MockHttpServletResponse noVerifier = mockMvc
			.perform(post("/oauth2/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.accept(MediaType.APPLICATION_JSON)
				.param("grant_type", "authorization_code")
				.param("code", code)
				.param("redirect_uri", SignInFlow.MOBILE_REDIRECT_URI)
				.param("client_id", SignInFlow.MOBILE_CLIENT_ID))
			.andReturn()
			.getResponse();
		assertThat(noVerifier.getStatus()).isEqualTo(401);
		assertThat(noVerifier.getContentAsString()).doesNotContain("access_token");
		assertThat(phone.tokenRequest(code, null, null).getContentAsString()).as("nor without an Accept header")
			.doesNotContain("access_token");

		MockHttpServletResponse wrongVerifier = phone.tokenRequest(code, "x".repeat(43), null);
		assertThat(wrongVerifier.getStatus()).isEqualTo(400);
		assertThat(wrongVerifier.getContentAsString()).contains("invalid_grant").doesNotContain("access_token");
	}

	@Test
	@DisplayName("A form parameter named like the public-client marker can't skip the PKCE check")
	void forgedMarkerCantSkipPkce() throws Exception {
		SignInFlow phone = SignInFlow.mobile(mockMvc);
		String code = phone.authorizationCode(ADMIN_EMAIL, PASSWORD);

		MockHttpServletResponse response = mockMvc
			.perform(post("/oauth2/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("grant_type", "authorization_code")
				.param("code", code)
				.param("redirect_uri", SignInFlow.MOBILE_REDIRECT_URI)
				.param("client_id", SignInFlow.MOBILE_CLIENT_ID)
				.param("code_verifier", "x".repeat(43))
				.param(PublicClientTokenAuthenticationConverter.ENDPOINT_PARAMETER, "TOKEN"))
			.andReturn()
			.getResponse();

		assertThat(response.getStatus()).isEqualTo(400);
		assertThat(response.getContentAsString()).contains("invalid_grant").doesNotContain("access_token");
	}

	@Test
	@DisplayName("A web app code can't be exchanged by washbase-mobile")
	void webCodeRefusedForMobile() throws Exception {
		SignInFlow browser = new SignInFlow(mockMvc);
		String code = browser.authorizationCode(ADMIN_EMAIL, PASSWORD);

		MockHttpServletResponse response = mockMvc
			.perform(post("/oauth2/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("grant_type", "authorization_code")
				.param("code", code)
				.param("redirect_uri", SignInFlow.REDIRECT_URI)
				.param("client_id", SignInFlow.MOBILE_CLIENT_ID)
				.param("code_verifier", browser.codeVerifier()))
			.andReturn()
			.getResponse();

		assertThat(response.getStatus()).isEqualTo(400);
		assertThat(response.getContentAsString()).contains("invalid_grant").doesNotContain("access_token");
	}

	private Tokens renew(String refreshToken) throws Exception {
		MockHttpServletResponse response = SignInFlow.mobile(mockMvc).refresh(refreshToken);
		assertThat(response.getStatus()).as("refresh response: %s", response.getContentAsString()).isEqualTo(200);
		return Tokens.from(response);
	}

	private ResultActions me(String accessToken) throws Exception {
		return mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken));
	}

	private static void assertInvalidGrant(MockHttpServletResponse response) throws Exception {
		assertThat(response.getStatus()).as("token response").isEqualTo(400);
		assertThat(response.getContentAsString()).contains("invalid_grant").doesNotContain("access_token");
	}

}
