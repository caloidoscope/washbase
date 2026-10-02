package com.washbase.api.auth;

import static com.washbase.api.auth.SignInFlow.SIGN_IN_ERROR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.washbase.api.MutableClock;
import com.washbase.api.TestClockConfiguration;
import com.washbase.api.TestcontainersConfiguration;
import com.washbase.api.auth.SignInFlow.Tokens;
import com.washbase.api.user.AdminBootstrapping;
import com.washbase.api.user.Role;
import com.washbase.api.user.UserAccount;
import com.washbase.api.user.UserAccountRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * CAR-19's scenarios: sign-in is paused after repeated wrong passwords. Through the real sign-in page
 * ({@code POST /login} with CSRF), authorization server and database ({@link SignInFlow}); times of day come from a
 * {@link MutableClock} that each step sets (Manila time, on one day).
 *
 * <p>"Paused" means: redirected to {@code /login?paused}, the page shows {@link #PAUSED}, not signed in, and the
 * authorization request still asks for sign-in (no code). "Signed in" means the whole {@link SignInFlow} completes
 * and {@code /api/v1/me} answers for the Admin.
 */
@ExtendWith(OutputCaptureExtension.class)
@Import({ TestcontainersConfiguration.class, TestClockConfiguration.class })
@SpringBootTest
@AutoConfigureMockMvc
class SignInPauseTests {

	/** Exactly what the sign-in page shows while sign-in is paused (CAR-19). */
	static final String PAUSED = "Too many attempts. Try again in 15 minutes.";

	private static final String ADMIN_EMAIL = "admin@example.com";

	private static final String PASSWORD = "Start-Here-2026";

	private static final String WRONG_PASSWORD = "Wrong-Guess-2026";

	private static final LocalDate DAY = LocalDate.of(2026, 10, 5);

	private static final ZoneId MANILA = ZoneId.of("Asia/Manila");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ApplicationContext context;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private MutableClock clock;

	@Autowired
	private UserAccountRepository users;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@BeforeEach
	void givenTheAdmin() {
		jdbc.update("delete from sign_in_failure");
		jdbc.update("delete from sign_in_pause");
		jdbc.update("delete from oauth2_authorization");
		jdbc.update("delete from users");
		// The Admin account "admin@example.com" with password "Start-Here-2026" (and a mobile number, for the
		// Scenario Outline's mobile row).
		assertThat(AdminBootstrapping.startWithChosenPassword(context, ADMIN_EMAIL, "09171234567", PASSWORD)).isEqualTo("CREATED");
		at("09:00");
	}

	@Test
	@DisplayName("Scenario: The sixth attempt after five wrong passwords is paused")
	void sixthAttemptPaused(CapturedOutput output) throws Exception {
		wrongPasswords(ADMIN_EMAIL, "09:00", "09:01", "09:02", "09:03", "09:04");
		int logBefore = output.getAll().length();

		at("09:05");
		assertPaused(ADMIN_EMAIL, PASSWORD);

		// Nothing about the paused attempt reaches the logs: no password, identifier, key or hash.
		String key = SignInAttempts.identifierKey(ADMIN_EMAIL).orElseThrow();
		assertThat(output.getAll().substring(logBefore)).doesNotContain(PASSWORD)
			.doesNotContain(ADMIN_EMAIL)
			.doesNotContain(key)
			.doesNotContain(SignInAttempts.identifierHash(key));
		assertThat(output.getAll()).doesNotContain(PASSWORD).doesNotContain(WRONG_PASSWORD);
	}

	@Test
	@DisplayName("Scenario: Sign-in works again after the pause")
	void signInWorksAfterPause() throws Exception {
		wrongPasswords(ADMIN_EMAIL, "09:00", "09:01", "09:02", "09:03", "09:04");

		at("09:20");

		assertSignedInAsAdmin(ADMIN_EMAIL);
	}

	@Test
	@DisplayName("Scenario: A successful sign-in resets the count")
	void successfulSignInResetsCount() throws Exception {
		wrongPasswords(ADMIN_EMAIL, "09:00", "09:01", "09:02", "09:03");
		at("09:04");
		SignInFlow browser = new SignInFlow(mockMvc);
		Tokens tokens = browser.signInForTokens(ADMIN_EMAIL, PASSWORD);
		assertThat(browser.revoke(tokens.refreshToken()).getStatus()).isEqualTo(200);
		browser.logout(tokens.idToken(), "http://localhost:3000/");

		wrongPasswords(ADMIN_EMAIL, "09:05");
		at("09:06");

		assertSignedInAsAdmin(ADMIN_EMAIL);
	}

	@Test
	@DisplayName("Scenario: Wrong passwords spread over more than 15 minutes don't pause sign-in")
	void spreadOutWrongPasswordsDontPause() throws Exception {
		wrongPasswords(ADMIN_EMAIL, "09:00", "09:05", "09:10", "09:16", "09:21");

		at("09:22");

		assertSignedInAsAdmin(ADMIN_EMAIL);
	}

	@Test
	@DisplayName("Scenario: Pausing one account doesn't affect another")
	void pausingOneAccountDoesntAffectAnother() throws Exception {
		wrongPasswords(ADMIN_EMAIL, "09:00", "09:01", "09:02", "09:03", "09:04");
		at("09:05");
		assertPaused(ADMIN_EMAIL, PASSWORD);
		// Set up directly: no account-creation screen exists yet.
		users.save(new UserAccount("Second", "second@example.com", null, passwordEncoder.encode("Second-Pass-2026"),
				Role.CLIENT, false));

		String accessToken = new SignInFlow(mockMvc).signIn("second@example.com", "Second-Pass-2026");

		me(accessToken).andExpect(status().isOk()).andExpect(jsonPath("$.email").value("second@example.com"));
		assertPaused(ADMIN_EMAIL, PASSWORD);
	}

	@Test
	@DisplayName("Scenario: Unknown accounts are paused the same way")
	void unknownAccountsPausedTheSameWay(CapturedOutput output) throws Exception {
		assertThat(users.findByEmail("nobody@example.com")).isEmpty();
		wrongPasswords("nobody@example.com", "09:00", "09:01", "09:02", "09:03", "09:04");
		int logBefore = output.getAll().length();

		at("09:05");
		assertPaused("nobody@example.com", WRONG_PASSWORD);

		String key = SignInAttempts.identifierKey("nobody@example.com").orElseThrow();
		assertThat(output.getAll().substring(logBefore)).doesNotContain("nobody@example.com")
			.doesNotContain(key)
			.doesNotContain(SignInAttempts.identifierHash(key))
			.doesNotContain(WRONG_PASSWORD);
	}

	@ParameterizedTest(
			name = "Scenario Outline: The same pause applies whichever form of the identifier is used ({0}, {1})")
	@CsvSource({ "09171234567, +639171234567", "Admin@Example.com, admin@example.com" })
	void samePauseForEveryFormOfIdentifier(String first, String second) throws Exception {
		wrongPasswords(first, "09:00", "09:01", "09:02", "09:03", "09:04");

		at("09:05");

		assertPaused(second, PASSWORD);
	}

	@Test
	@DisplayName("Failures and pauses are stored only as SHA-256 hashes of the identifier")
	void onlyHashesStored() throws Exception {
		wrongPasswords(ADMIN_EMAIL, "09:00", "09:01", "09:02", "09:03");
		wrongPasswords("nobody@example.com", "09:00", "09:01", "09:02", "09:03", "09:04");

		String adminHash = SignInAttempts.identifierHash(SignInAttempts.identifierKey(ADMIN_EMAIL).orElseThrow());
		String nobodyHash = SignInAttempts
			.identifierHash(SignInAttempts.identifierKey("nobody@example.com").orElseThrow());
		assertThat(jdbc.queryForList("select identifier_hash from sign_in_failure", String.class)).hasSize(4)
			.containsOnly(adminHash);
		assertThat(jdbc.queryForList("select identifier_hash from sign_in_pause", String.class))
			.containsExactly(nobodyHash);
		assertThat(adminHash).matches("[0-9a-f]{64}");
	}

	@Test
	@DisplayName("A paused attempt isn't counted and doesn't extend the pause")
	void pausedAttemptDoesntExtendPause() throws Exception {
		wrongPasswords(ADMIN_EMAIL, "09:00", "09:01", "09:02", "09:03", "09:04");
		at("09:18");
		assertPaused(ADMIN_EMAIL, WRONG_PASSWORD);
		assertPaused(ADMIN_EMAIL, PASSWORD);

		assertThat(jdbc.queryForObject("select count(*) from sign_in_failure", Integer.class)).isZero();
		at("09:19:01");
		assertSignedInAsAdmin(ADMIN_EMAIL);
	}

	@Test
	@DisplayName("The failures that started a pause can't pause sign-in again right after it ends")
	void failuresClearedWhenPauseStarts() throws Exception {
		wrongPasswords(ADMIN_EMAIL, "09:00", "09:01", "09:02", "09:03", "09:04");

		wrongPasswords(ADMIN_EMAIL, "09:20");
		at("09:21");

		assertSignedInAsAdmin(ADMIN_EMAIL);
	}

	@Test
	@DisplayName("Failures older than the window are removed when the next failure is recorded")
	void oldFailuresRemoved() throws Exception {
		wrongPasswords(ADMIN_EMAIL, "09:00", "09:05");

		wrongPasswords(ADMIN_EMAIL, "09:16");

		assertThat(jdbc.queryForObject("select count(*) from sign_in_failure", Integer.class)).isEqualTo(2);
	}

	/** Sets the clock to this time of day ({@code HH:mm} or {@code HH:mm:ss}, Manila). */
	private void at(String time) {
		clock.set(DAY.atTime(LocalTime.parse(time)).atZone(MANILA).toInstant());
	}

	private Instant now() {
		return clock.instant();
	}

	/** One wrong password for {@code identifier} at each time; each is refused with the usual message. */
	private void wrongPasswords(String identifier, String... times) throws Exception {
		for (String time : times) {
			at(time);
			SignInFlow browser = new SignInFlow(mockMvc);
			MockHttpServletResponse response = browser.submitSignIn(identifier, WRONG_PASSWORD);
			assertThat(response.getRedirectedUrl()).as("wrong password at %s (%s)", time, now())
				.isEqualTo("/login?error");
			assertThat(browser.page(response.getRedirectedUrl())).contains(SIGN_IN_ERROR).doesNotContain(PAUSED);
		}
	}

	private void assertPaused(String identifier, String password) throws Exception {
		SignInFlow browser = new SignInFlow(mockMvc);
		browser.authorize();

		MockHttpServletResponse response = browser.submitSignIn(identifier, password);

		assertThat(response.getRedirectedUrl()).isEqualTo("/login?paused");
		assertThat(browser.page(response.getRedirectedUrl())).contains(PAUSED).doesNotContain(SIGN_IN_ERROR);
		assertThat(browser.isSignedIn()).isFalse();
		assertThat(browser.authorize().getRedirectedUrl()).as("no code: the authorization request asks to sign in")
			.endsWith("/login");
	}

	private void assertSignedInAsAdmin(String identifier) throws Exception {
		String accessToken = new SignInFlow(mockMvc).signIn(identifier, PASSWORD);
		me(accessToken).andExpect(status().isOk()).andExpect(jsonPath("$.role").value("ADMIN"));
	}

	private ResultActions me(String accessToken) throws Exception {
		return mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken));
	}

}
