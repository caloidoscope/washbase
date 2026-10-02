package com.washbase.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.washbase.api.TestcontainersConfiguration;
import com.washbase.api.auth.SignInFlow.Tokens;
import com.washbase.api.user.AdminBootstrapping;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * CAR-18's scenarios at API level: refresh tokens rotated on every use, reuse detection, revocation, RP-initiated
 * logout, the API's sign-in session ending after sign-in, and access tokens refused as soon as they are revoked or
 * replaced. Through the real authorization server and resource server, against the real database, driven the way
 * the web app's server does it ({@link SignInFlow}). The web side of the scenarios is tested in {@code apps/web}.
 */
@ExtendWith(OutputCaptureExtension.class)
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class StaySignedInTests {

	private static final String PASSWORD = "Start-Here-2026";

	private static final String ADMIN_EMAIL = "admin@example.com";

	private static final String WEB_APP_HOME = "http://localhost:3000/";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ApplicationContext context;

	@Autowired
	private AuthProperties authProperties;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private OAuth2AuthorizationService authorizationService;

	@BeforeEach
	void givenTheAdmin() {
		// A paused Admin (CAR-19) from an earlier test's wrong passwords would break sign-in here.
		jdbc.update("delete from sign_in_failure");
		jdbc.update("delete from sign_in_pause");
		jdbc.update("delete from oauth2_authorization");
		jdbc.update("delete from users");
		AdminBootstrapping.startWithChosenPassword(context, ADMIN_EMAIL, null, PASSWORD);
	}

	@Test
	@DisplayName("Scenario: The Admin is still signed in after the short-lived sign-in token expires")
	void stillSignedInAfterAccessTokenExpires() throws Exception {
		Tokens signIn = new SignInFlow(mockMvc).signInForTokens(ADMIN_EMAIL, PASSWORD);

		Tokens renewed = renew(signIn.refreshToken());

		assertThat(renewed.accessToken()).isNotEqualTo(signIn.accessToken());
		assertThat(renewed.refreshToken()).as("rotated").isNotEqualTo(signIn.refreshToken());
		assertThat(renewed.idToken()).isNotBlank();
		// The home page shows "Signed in as Admin (Admin)" from /api/v1/me.
		me(renewed.accessToken()).andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("Admin"))
			.andExpect(jsonPath("$.role").value("ADMIN"));
		// And the renewed refresh token renews again.
		assertThat(renew(renewed.refreshToken()).refreshToken()).isNotEqualTo(renewed.refreshToken());
	}

	@Test
	@DisplayName("Scenario: The Admin signs out")
	void adminSignsOut() throws Exception {
		SignInFlow browser = new SignInFlow(mockMvc);
		Tokens tokens = browser.signInForTokens(ADMIN_EMAIL, PASSWORD);

		assertThat(browser.revoke(tokens.refreshToken()).getStatus()).isEqualTo(200);
		MockHttpServletResponse logout = browser.logout(tokens.idToken(), WEB_APP_HOME);

		assertThat(logout.getStatus()).as("logout: %s", logout.getErrorMessage()).isEqualTo(302);
		assertThat(logout.getRedirectedUrl()).isEqualTo(WEB_APP_HOME);
		// Ended on the server: neither token works any more.
		assertInvalidGrant(browser.refresh(tokens.refreshToken()));
		me(tokens.accessToken()).andExpect(status().isUnauthorized());
		assertThat(browser.authorize().getRedirectedUrl()).as("opening the app again asks to sign in")
			.endsWith("/login");
	}

	@Test
	@DisplayName("Scenario: Signing out asks for the password again next time")
	void signingOutAsksForPasswordAgain() throws Exception {
		SignInFlow browser = new SignInFlow(mockMvc);
		Tokens tokens = browser.signInForTokens(ADMIN_EMAIL, PASSWORD);

		// The API's own sign-in session ended as soon as the code was issued.
		assertThat(browser.isSignedIn()).isFalse();
		assertThat(browser.authorize().getRedirectedUrl()).endsWith("/login");

		browser.revoke(tokens.refreshToken());
		browser.logout(tokens.idToken(), WEB_APP_HOME);

		MockHttpServletResponse authorize = browser.authorize();
		assertThat(authorize.getStatus()).isEqualTo(302);
		assertThat(authorize.getRedirectedUrl()).as("not signed in automatically").endsWith("/login");
		assertThat(browser.page("/login")).contains("Email or mobile number").contains("Password");
	}

	@Test
	@DisplayName("Scenario: A copied session can't be used after signing out")
	void copiedSessionRefusedAfterSignOut() throws Exception {
		SignInFlow browser = new SignInFlow(mockMvc);
		Tokens tokens = browser.signInForTokens(ADMIN_EMAIL, PASSWORD);
		Tokens copy = new Tokens(tokens.accessToken(), tokens.refreshToken(), tokens.idToken());
		me(copy.accessToken()).andExpect(status().isOk());

		browser.revoke(tokens.refreshToken());
		browser.logout(tokens.idToken(), WEB_APP_HOME);

		me(copy.accessToken()).andExpect(status().isUnauthorized())
			.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, Matchers.containsString("invalid_token")));
		assertInvalidGrant(new SignInFlow(mockMvc).refresh(copy.refreshToken()));
		mockMvc.perform(get("/userinfo").header(HttpHeaders.AUTHORIZATION, "Bearer " + copy.accessToken()))
			.andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("Scenario: The session ends after 30 days without use")
	void sessionEndsAfter30DaysWithoutUse() throws Exception {
		Tokens tokens = new SignInFlow(mockMvc).signInForTokens(ADMIN_EMAIL, PASSWORD);
		// Signed in on 1 October, not used again; now it is 1 November: the refresh token has expired.
		jdbc.update("update oauth2_authorization set refresh_token_issued_at = ?, refresh_token_expires_at = ?",
				Timestamp.from(Instant.now().minus(31, ChronoUnit.DAYS)),
				Timestamp.from(Instant.now().minus(1, ChronoUnit.DAYS)));

		assertInvalidGrant(new SignInFlow(mockMvc).refresh(tokens.refreshToken()));
	}

	@Test
	@DisplayName("Scenario: Using the app keeps the session going")
	void usingTheAppKeepsTheSessionGoing() throws Exception {
		Tokens tokens = new SignInFlow(mockMvc).signInForTokens(ADMIN_EMAIL, PASSWORD);
		// Signed in on 1 October; it is now 25 October, so the refresh token has 6 days left.
		Instant oldExpiry = Instant.now().plus(6, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
		jdbc.update("update oauth2_authorization set refresh_token_issued_at = ?, refresh_token_expires_at = ?",
				Timestamp.from(Instant.now().minus(24, ChronoUnit.DAYS)), Timestamp.from(oldExpiry));

		Tokens renewed = renew(tokens.refreshToken());

		// Each use gives the full lifetime again: on 20 November the session is still going.
		Instant newExpiry = refreshTokenExpiry();
		assertThat(newExpiry).isAfter(oldExpiry)
			.isCloseTo(Instant.now().plus(authProperties.refreshTokenTtl()), within(1, ChronoUnit.MINUTES))
			.isAfter(Instant.now().plus(26, ChronoUnit.DAYS));
		me(renewed.accessToken()).andExpect(status().isOk()).andExpect(jsonPath("$.role").value("ADMIN"));
	}

	@Test
	@DisplayName("Scenario: An already-used session renewal is refused")
	void alreadyUsedRenewalRefused(CapturedOutput output) throws Exception {
		Tokens signIn = new SignInFlow(mockMvc).signInForTokens(ADMIN_EMAIL, PASSWORD);
		String authorizationId = jdbc.queryForObject("select id from oauth2_authorization", String.class);
		Tokens renewed = renew(signIn.refreshToken());

		// The renewal from before is presented again.
		assertInvalidGrant(new SignInFlow(mockMvc).refresh(signIn.refreshToken()));

		// The session is ended: the current refresh and access tokens stop working too.
		assertInvalidGrant(new SignInFlow(mockMvc).refresh(renewed.refreshToken()));
		me(renewed.accessToken()).andExpect(status().isUnauthorized());
		assertThat(jdbc.queryForObject("select count(*) from oauth2_authorization", Integer.class)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from oauth2_replaced_refresh_token", Integer.class)).isZero();
		assertThat(output.getAll()).containsOnlyOnce("Refresh token reuse detected")
			.contains("authorization " + authorizationId + " removed");
		// And they must sign in again.
		assertThat(new SignInFlow(mockMvc).signInForTokens(ADMIN_EMAIL, PASSWORD).accessToken()).isNotBlank();
	}

	@Test
	@DisplayName("Of two simultaneous renewals with the same refresh token only one wins; the other is refused")
	void simultaneousRenewalsOnlyOneWins() throws Exception {
		Tokens signIn = new SignInFlow(mockMvc).signInForTokens(ADMIN_EMAIL, PASSWORD);
		// Both renewals found the authorization by the same refresh token before either saved.
		OAuth2Authorization first = authorizationService.findByToken(signIn.refreshToken(),
				OAuth2TokenType.REFRESH_TOKEN);
		OAuth2Authorization second = authorizationService.findByToken(signIn.refreshToken(),
				OAuth2TokenType.REFRESH_TOKEN);

		authorizationService.save(renewal(first, "renewal-winner"));

		assertThatExceptionOfType(OAuth2AuthenticationException.class)
			.isThrownBy(() -> authorizationService.save(renewal(second, "renewal-loser")))
			.satisfies(ex -> assertThat(ex.getError().getErrorCode()).isEqualTo(OAuth2ErrorCodes.INVALID_GRANT));
		assertThat(jdbc.queryForObject("select refresh_token_value from oauth2_authorization", String.class))
			.isEqualTo("renewal-winner");
		assertThat(storedAuthorizations()).allSatisfy(json -> assertThat(json)
			.doesNotContain(RefreshTokenReuseDetectingAuthorizationService.PRESENTED_REFRESH_TOKEN_HASH));
		// The presented token has been replaced, so presenting it again is reuse: the session ends.
		assertInvalidGrant(new SignInFlow(mockMvc).refresh(signIn.refreshToken()));
		assertThat(jdbc.queryForObject("select count(*) from oauth2_authorization", Integer.class)).isZero();
	}

	@Test
	@DisplayName("Two renewal requests racing with the same refresh token: exactly one gets new tokens")
	void racingRenewalRequestsExactlyOneSucceeds() throws Exception {
		Tokens signIn = new SignInFlow(mockMvc).signInForTokens(ADMIN_EMAIL, PASSWORD);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			List<Future<Integer>> statuses = new ArrayList<>();
			for (int i = 0; i < 2; i++) {
				statuses.add(executor.submit(() -> {
					start.await();
					return new SignInFlow(mockMvc).refresh(signIn.refreshToken()).getStatus();
				}));
			}
			start.countDown();
			List<Integer> results = new ArrayList<>();
			for (Future<Integer> status : statuses) {
				results.add(status.get(30, TimeUnit.SECONDS));
			}
			// Whether they truly overlapped or ran one after the other, never two winners.
			assertThat(results).containsExactlyInAnyOrder(200, 400);
		}
		finally {
			executor.shutdownNow();
		}
	}

	@Test
	@DisplayName("The access token from before a renewal is refused (401)")
	void accessTokenFromBeforeRenewalRefused() throws Exception {
		Tokens signIn = new SignInFlow(mockMvc).signInForTokens(ADMIN_EMAIL, PASSWORD);

		Tokens renewed = renew(signIn.refreshToken());

		me(signIn.accessToken()).andExpect(status().isUnauthorized());
		me(renewed.accessToken()).andExpect(status().isOk());
	}

	@Test
	@DisplayName("Revoking a replaced refresh token also ends the whole session")
	void revokingReplacedTokenEndsSession() throws Exception {
		SignInFlow browser = new SignInFlow(mockMvc);
		Tokens signIn = browser.signInForTokens(ADMIN_EMAIL, PASSWORD);
		Tokens renewed = renew(signIn.refreshToken());

		assertThat(browser.revoke(signIn.refreshToken()).getStatus()).isEqualTo(200);

		assertInvalidGrant(browser.refresh(renewed.refreshToken()));
		me(renewed.accessToken()).andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("A replaced refresh token presented after its own expiry is refused without ending the session")
	void expiredReplacedTokenDoesNotEndSession(CapturedOutput output) throws Exception {
		Tokens signIn = new SignInFlow(mockMvc).signInForTokens(ADMIN_EMAIL, PASSWORD);
		Tokens renewed = renew(signIn.refreshToken());
		jdbc.update("update oauth2_replaced_refresh_token set expires_at = ?",
				Timestamp.from(Instant.now().minus(1, ChronoUnit.MINUTES)));

		assertInvalidGrant(new SignInFlow(mockMvc).refresh(signIn.refreshToken()));

		me(renewed.accessToken()).andExpect(status().isOk());
		assertThat(renew(renewed.refreshToken()).accessToken()).isNotBlank();
		assertThat(output.getAll()).doesNotContain("Refresh token reuse detected");
	}

	@Test
	@DisplayName("Signing out redirects only to the registered post-logout URI")
	void logoutRefusesUnregisteredRedirect() throws Exception {
		SignInFlow browser = new SignInFlow(mockMvc);
		Tokens tokens = browser.signInForTokens(ADMIN_EMAIL, PASSWORD);

		MockHttpServletResponse logout = browser.logout(tokens.idToken(), "https://evil.example.com/");

		assertThat(logout.getStatus()).isEqualTo(400);
		assertThat(logout.getErrorMessage()).contains("post_logout_redirect_uri");
		assertThat(logout.getHeader(HttpHeaders.LOCATION)).isNull();
	}

	@Test
	@DisplayName("OIDC discovery lists the revocation and end-session endpoints, and the refresh token grant")
	void discoveryListsSignOutEndpoints() throws Exception {
		mockMvc.perform(get("/.well-known/openid-configuration"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.revocation_endpoint").value(authProperties.issuer() + "/oauth2/revoke"))
			.andExpect(jsonPath("$.end_session_endpoint").value(authProperties.issuer() + "/connect/logout"))
			.andExpect(jsonPath("$.grant_types_supported", Matchers.hasItem("refresh_token")));
	}

	@Test
	@DisplayName("The stored authorization never contains the password hash, after sign-in and after a renewal")
	void storedAuthorizationHasNoPasswordHash() throws Exception {
		String passwordHash = jdbc.queryForObject("select password_hash from users where email = ?", String.class,
				ADMIN_EMAIL);
		Tokens signIn = new SignInFlow(mockMvc).signInForTokens(ADMIN_EMAIL, PASSWORD);
		assertNoPasswordIn(storedAuthorizations(), passwordHash);

		renew(signIn.refreshToken());

		assertNoPasswordIn(storedAuthorizations(), passwordHash);
	}

	@Test
	@DisplayName("Only SHA-256 hashes of replaced refresh tokens are stored, never the tokens")
	void onlyHashesOfReplacedTokensStored() throws Exception {
		Tokens first = new SignInFlow(mockMvc).signInForTokens(ADMIN_EMAIL, PASSWORD);
		Tokens second = renew(first.refreshToken());
		Tokens third = renew(second.refreshToken());

		List<Map<String, Object>> rows = jdbc
			.queryForList("select token_hash, authorization_id, expires_at::text as expires_at "
					+ "from oauth2_replaced_refresh_token");
		assertThat(rows).extracting(row -> row.get("token_hash"))
			.containsExactlyInAnyOrder(RefreshTokenReuseDetectingAuthorizationService.hash(first.refreshToken()),
					RefreshTokenReuseDetectingAuthorizationService.hash(second.refreshToken()));
		assertThat(rows).allSatisfy(row -> assertThat((String) row.get("token_hash")).matches("[0-9a-f]{64}"));
		String everything = rows.toString();
		for (String token : List.of(first.refreshToken(), second.refreshToken(), third.refreshToken())) {
			assertThat(everything).doesNotContain(token);
		}
	}

	@Test
	@DisplayName("No token value appears in the logs: sign-in, renewal, reuse detection, revocation and sign-out")
	void noTokensInLogs(CapturedOutput output) throws Exception {
		SignInFlow browser = new SignInFlow(mockMvc);
		Tokens signIn = browser.signInForTokens(ADMIN_EMAIL, PASSWORD);
		Tokens renewed = renew(signIn.refreshToken());
		browser.refresh(signIn.refreshToken());
		me(renewed.accessToken());
		Tokens again = new SignInFlow(mockMvc).signInForTokens(ADMIN_EMAIL, PASSWORD);
		browser.revoke(again.refreshToken());
		browser.logout(again.idToken(), WEB_APP_HOME);

		for (Tokens tokens : List.of(signIn, renewed, again)) {
			assertThat(output.getAll()).doesNotContain(tokens.accessToken())
				.doesNotContain(tokens.refreshToken())
				.doesNotContain(tokens.idToken())
				.doesNotContain(RefreshTokenReuseDetectingAuthorizationService.hash(tokens.refreshToken()));
		}
		assertThat(output.getAll()).doesNotContain(PASSWORD).doesNotContain(SignInFlow.CLIENT_SECRET);
	}

	private Tokens renew(String refreshToken) throws Exception {
		MockHttpServletResponse response = new SignInFlow(mockMvc).refresh(refreshToken);
		assertThat(response.getStatus()).as("refresh response").isEqualTo(200);
		return Tokens.from(response);
	}

	private static OAuth2Authorization renewal(OAuth2Authorization found, String newRefreshToken) {
		Instant now = Instant.now();
		return OAuth2Authorization.from(found)
			.refreshToken(new OAuth2RefreshToken(newRefreshToken, now, now.plus(30, ChronoUnit.DAYS)))
			.build();
	}

	private ResultActions me(String accessToken) throws Exception {
		return mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken));
	}

	private Instant refreshTokenExpiry() {
		return jdbc.queryForObject("select refresh_token_expires_at from oauth2_authorization", Timestamp.class)
			.toInstant();
	}

	private List<String> storedAuthorizations() {
		return jdbc.queryForList("select attributes from oauth2_authorization", String.class);
	}

	private static void assertNoPasswordIn(List<String> attributes, String passwordHash) {
		assertThat(attributes).isNotEmpty()
			.allSatisfy(json -> assertThat(json).doesNotContain(passwordHash).doesNotContain(PASSWORD));
	}

	private static void assertInvalidGrant(MockHttpServletResponse response) throws Exception {
		assertThat(response.getStatus()).as("token response").isEqualTo(400);
		assertThat(response.getContentAsString()).contains("invalid_grant").doesNotContain("access_token");
	}

}
