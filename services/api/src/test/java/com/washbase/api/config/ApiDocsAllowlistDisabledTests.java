package com.washbase.api.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.washbase.api.TestcontainersConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/** The public allowlist as configured in production ({@code WASHBASE_PUBLIC_API_DOCS=false}). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "washbase.security.public-api-docs=false")
@AutoConfigureMockMvc
class ApiDocsAllowlistDisabledTests {

	@Autowired
	private MockMvc mockMvc;

	@Test
	@DisplayName("Allowlist: /v3/api-docs and Swagger UI need a token when washbase.security.public-api-docs=false")
	void apiDocsProtected() throws Exception {
		mockMvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
	}

}
