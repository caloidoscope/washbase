package com.washbase.api.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.washbase.api.TestcontainersConfiguration;
import com.washbase.api.user.UserAccountRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** The E2E-only account endpoint exists under profile {@code e2e} and nowhere else. */
class E2eAccountsTests {

	private static String body(String email) {
		return "{\"email\":\"" + email + "\",\"password\":\"Start-Here-2026\",\"name\":\"Admin\","
				+ "\"role\":\"ADMIN\",\"mustChangePassword\":true}";
	}

	private static final String UNIQUE = Long.toString(System.nanoTime());

	private static final String BODY = body("E2e-One-" + UNIQUE + "@Example.com");

	@Import(TestcontainersConfiguration.class)
	@SpringBootTest
	@AutoConfigureMockMvc
	@ActiveProfiles("e2e")
	@Nested
	class WithE2eProfile {

		@Autowired
		MockMvc mockMvc;

		@Autowired
		UserAccountRepository users;

		@Test
		@DisplayName("E2E seeding: POST /e2e/accounts creates an account without a token")
		void createsAccount() throws Exception {
			mockMvc.perform(post("/e2e/accounts").contentType(MediaType.APPLICATION_JSON).content(BODY))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").isNotEmpty());
			var account = users.findByEmail("e2e-one-" + UNIQUE + "@example.com").orElseThrow();
			assertThat(account.isMustChangePassword()).isTrue();
			assertThat(account.getPasswordHash()).isNotEqualTo("Start-Here-2026");
		}


		@Test
		@DisplayName("E2E seeding: deleting the created Admin restores the bootstrapped one")
		void deleteRestoresBootstrappedAdmin() throws Exception {
			// The deployment's bootstrapped Admin (the test database has no bootstrap settings).
			mockMvc.perform(post("/e2e/accounts").contentType(MediaType.APPLICATION_JSON)
				.content(body("admin@example.com")));
			String email = "e2e-del-" + UNIQUE + "@example.com";
			mockMvc.perform(post("/e2e/accounts").contentType(MediaType.APPLICATION_JSON).content(body(email)))
				.andExpect(status().isCreated());

			mockMvc.perform(delete("/e2e/accounts").param("email", email)).andExpect(status().isNoContent());

			assertThat(users.findByEmail(email)).isEmpty();
			assertThat(users.findByEmail("admin@example.com").orElseThrow().isActive()).isTrue();
		}

		@Test
		@DisplayName("E2E seeding: a duplicate email is refused")
		void duplicateRefused() throws Exception {
			String body = body("e2e-dup-" + UNIQUE + "@example.com");
			mockMvc.perform(post("/e2e/accounts").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated());
			mockMvc.perform(post("/e2e/accounts").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isConflict());
		}

	}

	@Import(TestcontainersConfiguration.class)
	@SpringBootTest
	@AutoConfigureMockMvc
	@Nested
	class WithoutE2eProfile {

		@Autowired
		MockMvc mockMvc;

		@Autowired
		ApplicationContext context;

		@Test
		@DisplayName("E2E seeding: the endpoint is not reachable without the e2e profile")
		void notReachable() throws Exception {
			assertThat(context.getBeansOfType(E2eAccounts.class)).isEmpty();
			assertThat(context.getBeansOfType(E2eSecurityConfig.class)).isEmpty();
			mockMvc.perform(post("/e2e/accounts").contentType(MediaType.APPLICATION_JSON).content(BODY))
				.andExpect(status().isUnauthorized());
		}

	}

}
