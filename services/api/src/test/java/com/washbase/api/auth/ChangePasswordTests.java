package com.washbase.api.auth;

import static com.washbase.api.auth.SignInFlow.SIGN_IN_ERROR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.washbase.api.TestcontainersConfiguration;
import com.washbase.api.user.AdminBootstrapping;
import com.washbase.api.user.UserAccount;
import com.washbase.api.user.UserAccountRepository;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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

/**
 * CAR-21's scenarios at API level: the Admin is made to choose a new password at first sign-in, through the real
 * sign-in page, "Choose a new password" page, authorization endpoint and token endpoint.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ChangePasswordTests {

	private static final String EMAIL = "admin@example.com";

	private static final String INITIAL = "Start-Here-2026";

	private static final String CHOSEN = "Blue-Basket-77";

	private static final String CHANGE_PASSWORD = "/change-password";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ApplicationContext context;

	@Autowired
	private UserAccountRepository users;

	@Autowired
	private JdbcTemplate jdbc;

	@BeforeEach
	void emptyDeployment() {
		jdbc.update("delete from sign_in_failure");
		jdbc.update("delete from sign_in_pause");
		jdbc.update("delete from oauth2_authorization");
		jdbc.update("delete from users");
		// A brand-new Admin created from the settings: must choose a new password.
		assertThat(AdminBootstrapping.startWith(context, EMAIL, null, INITIAL)).isEqualTo("CREATED");
	}

	/** Starts an authorization request and signs in with the initial password: where the browser is sent next. */
	private String signInWithInitialPassword(SignInFlow browser) throws Exception {
		browser.authorize();
		return browser.submitSignIn(EMAIL, INITIAL).getRedirectedUrl();
	}

	@Test
	@DisplayName("Scenario: The Admin is asked to choose a new password at first sign-in")
	void askedAtFirstSignIn() throws Exception {
		SignInFlow browser = new SignInFlow(mockMvc);

		String location = signInWithInitialPassword(browser);

		assertThat(location).isEqualTo(CHANGE_PASSWORD);
		String page = browser.open(location).getContentAsString(StandardCharsets.UTF_8);
		assertThat(page).contains("Choose a new password")
			.contains("New password")
			.contains("Confirm new password")
			.contains(">Save<")
			.contains("name=\"_csrf\"");
	}

	@Test
	@DisplayName("Scenario: The Admin chooses a new password and reaches the home page")
	void choosesNewPassword() throws Exception {
		SignInFlow browser = new SignInFlow(mockMvc);
		signInWithInitialPassword(browser);

		MockHttpServletResponse saved = browser.submitNewPassword(CHOSEN, CHOSEN);

		// The interrupted authorization request resumes, so the app receives its code.
		assertThat(saved.getRedirectedUrl()).contains("/oauth2/authorize");
		String accessToken = browser.completeSignIn(saved.getRedirectedUrl());
		// The web app's home page shows "Signed in as {name} ({role})" from /api/v1/me.
		mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("Admin"))
			.andExpect(jsonPath("$.role").value("ADMIN"));
		assertThat(users.findByEmail(EMAIL).orElseThrow().isMustChangePassword()).isFalse();
	}

	@Test
	@DisplayName("Scenario: The initial password stops working after it is replaced")
	void initialPasswordStopsWorking() throws Exception {
		SignInFlow admin = new SignInFlow(mockMvc);
		signInWithInitialPassword(admin);
		admin.submitNewPassword(CHOSEN, CHOSEN);

		SignInFlow someone = new SignInFlow(mockMvc);
		someone.authorize();
		MockHttpServletResponse response = someone.submitSignIn(EMAIL, INITIAL);

		assertThat(response.getRedirectedUrl()).isEqualTo("/login?error");
		assertThat(someone.page(response.getRedirectedUrl())).contains(SIGN_IN_ERROR);
	}

	@Test
	@DisplayName("Scenario: The next sign-in goes straight to the home page")
	void nextSignInGoesStraightThrough() throws Exception {
		SignInFlow first = new SignInFlow(mockMvc);
		signInWithInitialPassword(first);
		first.submitNewPassword(CHOSEN, CHOSEN);

		// signIn() asserts the sign-in goes straight back to the authorization request: no password-change page.
		String accessToken = new SignInFlow(mockMvc).signIn(EMAIL, CHOSEN);

		mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("Admin"));
	}

	@ParameterizedTest(name = "Scenario Outline: An unacceptable new password is refused ({0}, {1} → {2})")
	@CsvSource(delimiter = '|', value = { "Short-1 | Short-1 | Use at least 8 characters.",
			"Blue-Basket-77 | Blue-Basket-78 | The passwords don't match.",
			"Start-Here-2026 | Start-Here-2026 | Choose a password different from the one you were given." })
	void unacceptablePasswordRefused(String newPassword, String confirmation, String message) throws Exception {
		SignInFlow browser = new SignInFlow(mockMvc);
		signInWithInitialPassword(browser);

		MockHttpServletResponse response = browser.submitNewPassword(newPassword, confirmation);

		assertThat(response.getStatus()).isEqualTo(200);
		String page = response.getContentAsString(StandardCharsets.UTF_8);
		// The apostrophe is HTML-escaped in the page, as a browser shows it.
		assertThat(page.replace("&#39;", "'")).contains(message).contains("Choose a new password");
		// Nothing changed: still must choose.
		assertThat(users.findByEmail(EMAIL).orElseThrow().isMustChangePassword()).isTrue();
		assertThat(browser.authorize().getRedirectedUrl()).isEqualTo(CHANGE_PASSWORD);
	}

	@Test
	@DisplayName("Scenario: The Admin can't skip choosing a new password")
	void cannotSkip() throws Exception {
		SignInFlow browser = new SignInFlow(mockMvc);
		signInWithInitialPassword(browser);

		// The web app's home page starts a new authorization request; the signed-in Admin is sent back.
		MockHttpServletResponse response = browser.authorize();

		assertThat(response.getStatus()).isEqualTo(302);
		assertThat(response.getRedirectedUrl()).isEqualTo(CHANGE_PASSWORD);
	}

	@Test
	@DisplayName("Scenario: The Admin chooses a new password on the mobile app")
	void choosesNewPasswordOnMobile() throws Exception {
		SignInFlow browser = SignInFlow.mobile(mockMvc);
		signInWithInitialPassword(browser);
		assertThat(browser.open(CHANGE_PASSWORD).getContentAsString(StandardCharsets.UTF_8))
			.contains("Choose a new password");

		MockHttpServletResponse saved = browser.submitNewPassword(CHOSEN, CHOSEN);

		// The app's public client receives its code on its own scheme and exchanges it without a secret.
		String accessToken = browser.completeSignIn(saved.getRedirectedUrl());
		mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("Admin"))
			.andExpect(jsonPath("$.role").value("ADMIN"));
	}

	@Test
	@DisplayName("Scenario: Restoring the Admin with the bootstrap settings asks for a new password again")
	void restoredAdminAsksAgain() throws Exception {
		// The Admin chose a password, then was deactivated.
		jdbc.update("update users set active = false, must_change_password = false");

		assertThat(AdminBootstrapping.startWith(context, EMAIL, null, "Recover-2026")).isEqualTo("REACTIVATED");

		SignInFlow browser = new SignInFlow(mockMvc);
		browser.authorize();
		String location = browser.submitSignIn(EMAIL, "Recover-2026").getRedirectedUrl();
		assertThat(location).isEqualTo(CHANGE_PASSWORD);
		assertThat(browser.open(location).getContentAsString(StandardCharsets.UTF_8)).contains("Choose a new password");
	}

	@Test
	@DisplayName("Scenario: Sessions that existed before the password change are ended")
	void existingAuthorizationsAreRevoked() throws Exception {
		UserAccount admin = users.findByEmail(EMAIL).orElseThrow();
		// An earlier session: signed in and holding tokens.
		jdbc.update("update users set must_change_password = false");
		SignInFlow earlier = new SignInFlow(mockMvc);
		SignInFlow.Tokens tokens = earlier.signInForTokens(EMAIL, INITIAL);
		jdbc.update("update users set must_change_password = true");
		SignInFlow browser = new SignInFlow(mockMvc);
		signInWithInitialPassword(browser);

		browser.submitNewPassword(CHOSEN, CHOSEN);

		assertThat(jdbc.queryForObject("select count(*) from oauth2_authorization where principal_name = ?",
				Integer.class, admin.getId().toString())).isZero();
		mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.accessToken()))
			.andExpect(status().isUnauthorized());
		assertThat(earlier.refresh(tokens.refreshToken()).getStatus()).isEqualTo(400);
	}


	@Test
	@DisplayName("Scenario: Restoring the Admin ends the sessions and refresh tokens from before the restore")
	void restoreRevokesOldAuthorizations() throws Exception {
		jdbc.update("update users set must_change_password = false");
		SignInFlow earlier = new SignInFlow(mockMvc);
		SignInFlow.Tokens tokens = earlier.signInForTokens(EMAIL, INITIAL);
		jdbc.update("update users set active = false");

		assertThat(AdminBootstrapping.startWith(context, EMAIL, null, "Recover-2026")).isEqualTo("REACTIVATED");

		assertThat(jdbc.queryForObject("select count(*) from oauth2_authorization", Integer.class)).isZero();
		assertThat(earlier.refresh(tokens.refreshToken()).getStatus()).isEqualTo(400);
	}
	@Test
	@DisplayName("Scenario: The change-password page needs the sign-in session")
	void needsSignInSession() throws Exception {
		MockHttpServletResponse page = mockMvc.perform(get(CHANGE_PASSWORD).accept(MediaType.TEXT_HTML))
			.andReturn()
			.getResponse();
		assertThat(page.getRedirectedUrl()).endsWith("/login");

		mockMvc.perform(post(CHANGE_PASSWORD).param("newPassword", CHOSEN).param("confirmPassword", CHOSEN))
			.andExpect(status().isForbidden());
		assertThat(users.findByEmail(EMAIL).orElseThrow().isMustChangePassword()).isTrue();
	}

}
