package com.washbase.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.washbase.api.ApiApplication;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real start-ups (and restarts) of the whole API against one database, with different signing-key settings. Each
 * start uses a random port, so it never clashes with a running API.
 */
@ExtendWith(OutputCaptureExtension.class)
class SigningKeyStartupTests {

	private static final String PASSWORD = "Start-Here-2026";

	private static PostgreSQLContainer postgres;

	@BeforeAll
	static void startDatabase() {
		postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:17"));
		postgres.start();
	}

	@AfterAll
	static void stopDatabase() {
		postgres.stop();
	}

	@Test
	@DisplayName("Signing key: start-up is refused when none is configured and generation is not allowed")
	void startUpRefusedWithoutKey(CapturedOutput output) {
		assertThatThrownBy(() -> start("--washbase.auth.signing-key=", "--washbase.auth.allow-generated-signing-key=false"))
			.rootCause()
			.isInstanceOf(IllegalStateException.class)
			.hasMessage(SigningKeyConfig.MISSING_KEY_MESSAGE);
		assertThat(output.getAll()).contains("WASHBASE_AUTH_SIGNING_KEY").doesNotContain("Started ApiApplication");
	}

	@Test
	@DisplayName("Signing key: start-up with a malformed key is refused without key material in the log")
	void startUpRefusedWithMalformedKey(CapturedOutput output) {
		String body = TestSigningKeys.base64Body(TestSigningKeys.rsa(2048));
		String fragment = body.substring(0, 300);
		String malformed = "-----BEGIN PRIVATE KEY-----\n" + fragment + "\n-----END PRIVATE KEY-----";

		assertThatThrownBy(() -> start("--washbase.auth.signing-key=" + malformed)).rootCause()
			.hasMessage(SigningKeyConfig.INVALID_KEY_MESSAGE);
		assertThat(output.getAll()).contains("WASHBASE_AUTH_SIGNING_KEY")
			.doesNotContain(fragment.substring(0, 64))
			.doesNotContain(fragment.substring(65, 129));
	}

	@Test
	@DisplayName("Signing key: a token issued before a restart with the same key is still accepted after it")
	void tokenValidAfterRestartWithSameKey(CapturedOutput output) throws Exception {
		String pem = TestSigningKeys.pkcs8Pem(TestSigningKeys.rsa(2048));
		String accessToken;
		String adminId;
		try (ConfigurableApplicationContext first = start("--washbase.auth.signing-key=" + pem,
				"--washbase.auth.allow-generated-signing-key=false")) {
			MockMvc mockMvc = mockMvc(first);
			accessToken = new SignInFlow(mockMvc).signIn("admin@example.com", PASSWORD);
			adminId = me(mockMvc, accessToken);
		}

		try (ConfigurableApplicationContext restarted = start("--washbase.auth.signing-key=" + pem,
				"--washbase.auth.allow-generated-signing-key=false")) {
			assertThat(me(mockMvc(restarted), accessToken)).isEqualTo(adminId);
		}

		// For contrast: a restart that generates a new key signs everyone out.
		try (ConfigurableApplicationContext generated = start("--washbase.auth.signing-key=",
				"--washbase.auth.allow-generated-signing-key=true")) {
			mockMvc(generated).perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
				.andExpect(status().isUnauthorized());
		}
		// Neither the password nor any line of the key reaches the logs.
		assertThat(output.getAll()).doesNotContain(PASSWORD);
		pem.lines().filter(line -> !line.startsWith("-----")).forEach(line -> assertThat(output.getAll())
			.doesNotContain(line));
	}

	private static String me(MockMvc mockMvc, String accessToken) throws Exception {
		String body = mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.role").value("ADMIN"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		return JsonPath.read(body, "$.id");
	}

	private static MockMvc mockMvc(ConfigurableApplicationContext context) {
		return MockMvcBuilders.webAppContextSetup((WebApplicationContext) context).apply(springSecurity()).build();
	}

	private static ConfigurableApplicationContext start(String... settings) {
		List<String> args = new ArrayList<>(List.of("--server.port=0", "--spring.main.banner-mode=off",
				"--spring.datasource.url=" + postgres.getJdbcUrl(),
				"--spring.datasource.username=" + postgres.getUsername(),
				"--spring.datasource.password=" + postgres.getPassword(), "--washbase.admin.email=admin@example.com",
				"--washbase.admin.mobile=", "--washbase.admin.initial-password=" + PASSWORD));
		args.addAll(List.of(settings));
		return new SpringApplicationBuilder(ApiApplication.class).run(args.toArray(String[]::new));
	}

}
