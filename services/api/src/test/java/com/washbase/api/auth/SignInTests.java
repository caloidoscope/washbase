package com.washbase.api.auth;

import static com.washbase.api.auth.SignInFlow.SIGN_IN_ERROR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.washbase.api.TestcontainersConfiguration;
import com.washbase.api.user.AdminBootstrapping;
import com.washbase.api.user.Role;
import com.washbase.api.user.UserAccount;
import com.washbase.api.user.UserAccountRepository;
import java.time.Duration;
import java.util.List;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * CAR-17's sign-in scenarios at API level, through the real authorization server, sign-in page, token endpoint and
 * resource server, against the real database. Each test starts from an empty {@code users} table and creates the
 * Admin with the real bootstrap and the scenario's settings (a "start-up").
 */
@ExtendWith(OutputCaptureExtension.class)
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class SignInTests {

	private static final String PASSWORD = "Start-Here-2026";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ApplicationContext context;

	@Autowired
	private UserAccountRepository users;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private JwtDecoder jwtDecoder;

	@Autowired
	private AuthProperties authProperties;

	@Autowired
	private JdbcTemplate jdbc;

	@BeforeEach
	void emptyDeployment() {
		// A paused Admin (CAR-19) from an earlier test's wrong passwords would break sign-in here.
		jdbc.update("delete from sign_in_failure");
		jdbc.update("delete from sign_in_pause");
		jdbc.update("delete from oauth2_authorization");
		jdbc.update("delete from users");
	}

	@Test
	@DisplayName("Scenario: The Admin signs in with their email address")
	void adminSignsInWithEmail(CapturedOutput output) throws Exception {
		assertThat(AdminBootstrapping.startWith(context, "admin@example.com", null, PASSWORD)).isEqualTo("CREATED");
		UserAccount admin = users.findByEmail("admin@example.com").orElseThrow();

		String accessToken = new SignInFlow(mockMvc).signIn("admin@example.com", PASSWORD);

		// The web app's home page shows "Signed in as {name} ({role})" from /api/v1/me.
		mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(admin.getId().toString()))
			.andExpect(jsonPath("$.name").value("Admin"))
			.andExpect(jsonPath("$.role").value("ADMIN"))
			.andExpect(jsonPath("$.email").value("admin@example.com"));

		// The step-1 resource server decoder accepts the token as issued (typ, iss, aud, exp, signature).
		Jwt jwt = jwtDecoder.decode(accessToken);
		assertThat(jwt.getHeaders()).containsEntry("alg", "RS256");
		assertThat(jwt.getIssuer()).hasToString(authProperties.issuer());
		assertThat(jwt.getAudience()).containsExactly("washbase-api");
		assertThat(jwt.getSubject()).isEqualTo(admin.getId().toString());
		assertThat(jwt.getClaimAsStringList("roles")).containsExactly("ADMIN");
		assertThat(jwt.getClaimAsStringList("scope")).contains("openid");
		assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(15));
		assertThat(output.getAll()).doesNotContain(PASSWORD).doesNotContain(SignInFlow.CLIENT_SECRET);
	}

	@ParameterizedTest(
			name = "Scenario Outline: The Admin signs in with their mobile number in either common format ({0}, {1})")
	@CsvSource({ "09171234567, 09171234567", "09171234567, +639171234567", "+639171234567, 09171234567" })
	void adminSignsInWithMobile(String configured, String typed) throws Exception {
		assertThat(AdminBootstrapping.startWith(context, null, configured, PASSWORD)).isEqualTo("CREATED");

		String accessToken = new SignInFlow(mockMvc).signIn(typed, PASSWORD);

		mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("Admin"))
			.andExpect(jsonPath("$.role").value("ADMIN"))
			.andExpect(jsonPath("$.mobile").value("+639171234567"));
	}

	@Test
	@DisplayName("Scenario: Signing in with a wrong password is refused")
	void wrongPasswordRefused(CapturedOutput output) throws Exception {
		AdminBootstrapping.startWith(context, "admin@example.com", null, PASSWORD);
		SignInFlow browser = new SignInFlow(mockMvc);
		browser.authorize();

		MockHttpServletResponse response = browser.submitSignIn("admin@example.com", "wrong-password");

		assertThat(response.getRedirectedUrl()).isEqualTo("/login?error");
		assertThat(browser.page(response.getRedirectedUrl())).contains(SIGN_IN_ERROR);
		// Not signed in: the session holds no user, and the authorization request still asks for sign-in.
		assertThat(browser.isSignedIn()).isFalse();
		assertThat(browser.authorize().getRedirectedUrl()).endsWith("/login");
		assertThat(output.getAll()).doesNotContain("wrong-password").doesNotContain(PASSWORD);
	}

	@Test
	@DisplayName("Scenario: Signing in with an email that has no account shows the same message")
	void unknownEmailSameMessage(CapturedOutput output) throws Exception {
		AdminBootstrapping.startWith(context, "admin@example.com", null, PASSWORD);
		SignInFlow browser = new SignInFlow(mockMvc);
		browser.authorize();

		MockHttpServletResponse response = browser.submitSignIn("nobody@example.com", PASSWORD);

		assertThat(response.getRedirectedUrl()).isEqualTo("/login?error");
		assertThat(browser.page(response.getRedirectedUrl())).contains(SIGN_IN_ERROR);
		assertThat(browser.isSignedIn()).isFalse();
		assertThat(output.getAll()).doesNotContain("nobody@example.com");
	}

	@Test
	@DisplayName("Emails are matched case-insensitively (Admin@Example.com = admin@example.com)")
	void emailCaseInsensitive() throws Exception {
		AdminBootstrapping.startWith(context, "admin@example.com", null, PASSWORD);

		String accessToken = new SignInFlow(mockMvc).signIn("  Admin@Example.com ", PASSWORD);

		mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.role").value("ADMIN"));
	}

	@Test
	@DisplayName("A deactivated user with the right password is refused with the same message")
	void inactiveUserRefused() throws Exception {
		jdbc.update("update users set active = false where id = ?",
				users.save(new UserAccount("Sam Staff", "staff@example.com", null, passwordEncoder.encode(PASSWORD),
						Role.STAFF, false))
					.getId());
		SignInFlow browser = new SignInFlow(mockMvc);

		MockHttpServletResponse response = browser.submitSignIn("staff@example.com", PASSWORD);

		assertThat(response.getRedirectedUrl()).isEqualTo("/login?error");
		assertThat(browser.page(response.getRedirectedUrl())).contains(SIGN_IN_ERROR);
		assertThat(browser.isSignedIn()).isFalse();
	}

	@Test
	@DisplayName("An account without a password can't sign in and gets the same message")
	void userWithoutPasswordRefused() throws Exception {
		users.save(new UserAccount("Cora Client", null, "+639181234567", null, Role.CLIENT, false));
		SignInFlow browser = new SignInFlow(mockMvc);

		MockHttpServletResponse response = browser.submitSignIn("09181234567", "");

		assertThat(response.getRedirectedUrl()).isEqualTo("/login?error");
		assertThat(browser.isSignedIn()).isFalse();
	}

	@Test
	@DisplayName("Signing in as Staff puts roles [STAFF] in the access token")
	void staffTokenCarriesStaffRole() throws Exception {
		UserAccount staff = users.save(new UserAccount("Sam Staff", "staff@example.com", null,
				passwordEncoder.encode(PASSWORD), Role.STAFF, false));

		Jwt jwt = jwtDecoder.decode(new SignInFlow(mockMvc).signIn("staff@example.com", PASSWORD));

		assertThat(jwt.getSubject()).isEqualTo(staff.getId().toString());
		assertThat(jwt.getClaimAsStringList("roles")).containsExactly("STAFF");
	}

	@Test
	@DisplayName("The sign-in page has the \"Email or mobile number\" and \"Password\" fields and a CSRF token")
	void signInPage() throws Exception {
		mockMvc.perform(get("/login").accept(MediaType.TEXT_HTML))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("<title>Sign in · Washbase</title>")))
			.andExpect(content().string(containsString("<label for=\"username\">Email or mobile number</label>")))
			.andExpect(content().string(containsString("<label for=\"password\">Password</label>")))
			.andExpect(content().string(containsString("name=\"_csrf\"")))
			.andExpect(content().string(not(containsString(SIGN_IN_ERROR))));
	}

	@Test
	@DisplayName("Signing in without a CSRF token is refused (403)")
	void signInWithoutCsrfRefused() throws Exception {
		AdminBootstrapping.startWith(context, "admin@example.com", null, PASSWORD);

		mockMvc.perform(post("/login").param("username", "admin@example.com").param("password", PASSWORD))
			.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("Opening /login directly and signing in goes to the web app's home page")
	void directSignInGoesToWebApp() throws Exception {
		AdminBootstrapping.startWith(context, "admin@example.com", null, PASSWORD);
		SignInFlow browser = new SignInFlow(mockMvc);

		MockHttpServletResponse response = browser.submitSignIn("admin@example.com", PASSWORD);

		assertThat(response.getRedirectedUrl()).isEqualTo("http://localhost:3000/");
		assertThat(browser.isSignedIn()).isTrue();
		assertThat(users.findAll()).extracting(UserAccount::getRole).isEqualTo(List.of(Role.ADMIN));
	}

}
