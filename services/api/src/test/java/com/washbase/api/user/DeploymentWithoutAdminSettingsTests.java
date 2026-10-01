package com.washbase.api.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.washbase.api.TestcontainersConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A real start-up of a new deployment with the Admin settings explicitly blank. The explicit properties give this
 * class its own context (and empty database), so the start-up log is captured here.
 */
@ExtendWith(OutputCaptureExtension.class)
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = { "washbase.admin.email=", "washbase.admin.mobile=",
		"washbase.admin.initial-password=" })
@AutoConfigureMockMvc
class DeploymentWithoutAdminSettingsTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserAccountRepository users;

	@Test
	@DisplayName("Scenario: A deployment without Admin settings still starts")
	void startsWithoutAdminSettings(CapturedOutput output) throws Exception {
		mockMvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
		assertThat(output).contains("WARN").contains("No Admin account is configured");
		assertThat(users.count()).isZero();
	}

}
