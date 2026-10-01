package com.washbase.api.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.washbase.api.TestcontainersConfiguration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * A real start-up of a new deployment (own context, so its own empty database) configured with Admin settings.
 * Output capture starts before the context loads, so the captured output includes the whole start-up log.
 */
@ExtendWith(OutputCaptureExtension.class)
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = { "washbase.admin.email=admin@example.com",
		"washbase.admin.initial-password=Start-Here-2026" })
class AdminCreatedOnFirstStartTests {

	@Autowired
	private UserAccountRepository users;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Test
	@DisplayName("Scenario: The Admin account is created when a new deployment first starts")
	void adminCreatedOnFirstStart(CapturedOutput output) {
		List<UserAccount> admins = users.findAll().stream().filter(u -> u.getRole() == Role.ADMIN).toList();
		assertThat(admins).singleElement().satisfies(admin -> {
			assertThat(admin.getEmail()).isEqualTo("admin@example.com");
			assertThat(admin.getMobile()).isNull();
			assertThat(admin.getName()).isEqualTo("Admin");
			assertThat(admin.isActive()).isTrue();
			assertThat(admin.isMustChangePassword()).isTrue();
			assertThat(admin.getPasswordHash()).startsWith("{bcrypt}");
			assertThat(passwordEncoder.matches("Start-Here-2026", admin.getPasswordHash())).isTrue();
		});
		// The capture really holds the start-up log, and the initial password is nowhere in it.
		assertThat(output).contains("Started").contains("Created the Admin account");
		assertThat(output.getAll()).doesNotContain("Start-Here-2026");
	}

}
