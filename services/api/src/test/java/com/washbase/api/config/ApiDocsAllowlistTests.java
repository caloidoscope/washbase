package com.washbase.api.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.washbase.api.TestcontainersConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/** The public allowlist with the default {@code washbase.security.public-api-docs=true}. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ApiDocsAllowlistTests {

	@Autowired
	private MockMvc mockMvc;

	@Test
	@DisplayName("Allowlist: /v3/api-docs and Swagger UI are public when washbase.security.public-api-docs=true")
	void apiDocsPublicByDefault() throws Exception {
		mockMvc.perform(get("/v3/api-docs"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
			.andExpect(jsonPath("$.paths['/api/v1/me'].get.operationId").value("getMe"));
		mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
	}

	@Test
	@DisplayName("Allowlist: /actuator/health is public")
	void healthPublic() throws Exception {
		mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
	}

	@Test
	@DisplayName("Default deny: a path outside the allowlist and /api/** needs a token")
	void otherPathsDenied() throws Exception {
		mockMvc.perform(get("/actuator/info")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/v1/does-not-exist")).andExpect(status().isUnauthorized());
	}

}
