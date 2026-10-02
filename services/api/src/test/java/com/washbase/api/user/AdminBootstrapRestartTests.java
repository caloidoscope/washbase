package com.washbase.api.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.washbase.api.TestcontainersConfiguration;
import com.washbase.api.user.AdminBootstrap.Outcome;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Restarts are simulated by running the bootstrap again against the real database with new settings, which is
 * exactly what {@link AdminBootstrapRunner} does on each start-up.
 */
@ExtendWith(OutputCaptureExtension.class)
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class AdminBootstrapRestartTests {

	@Autowired
	private AdminBootstrap adminBootstrap;

	@Autowired
	private UserAccountRepository users;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private JdbcTemplate jdbc;

	private UUID adminId;

	@BeforeEach
	void givenAnActiveAdmin() {
		jdbc.update("delete from users");
		assertThat(adminBootstrap.bootstrap(new AdminProperties("admin@example.com", null, "Start-Here-2026")))
			.isEqualTo(Outcome.CREATED);
		adminId = onlyAdmin().getId();
	}

	@Test
	@DisplayName("Scenario: Restarting does not create a second Admin")
	void restartDoesNotCreateSecondAdmin(CapturedOutput output) {
		Outcome outcome = adminBootstrap
			.bootstrap(new AdminProperties("other-admin@example.com", null, "Another-2026"));

		assertThat(outcome).isEqualTo(Outcome.ACTIVE_ADMIN_EXISTS);
		UserAccount admin = onlyAdmin();
		assertThat(admin.getId()).isEqualTo(adminId);
		assertThat(admin.getEmail()).isEqualTo("admin@example.com");
		assertThat(users.findByEmail("other-admin@example.com")).isEmpty();
		assertThat(passwordEncoder.matches("Another-2026", admin.getPasswordHash())).isFalse();
		assertThat(passwordEncoder.matches("Start-Here-2026", admin.getPasswordHash())).isTrue();
		assertThat(output.getAll()).doesNotContain("Another-2026").doesNotContain("Start-Here-2026");
	}

	@Test
	@DisplayName("Scenario: A deactivated Admin is restored by restarting with the Admin settings")
	void deactivatedAdminRestored(CapturedOutput output) {
		// The operator deactivates the Admin directly in the database.
		jdbc.update("update users set active = false, must_change_password = false where id = ?", adminId);

		Outcome outcome = adminBootstrap.bootstrap(new AdminProperties("admin@example.com", null, "Recover-2026"));

		assertThat(outcome).isEqualTo(Outcome.REACTIVATED);
		UserAccount admin = onlyAdmin();
		assertThat(admin.getId()).isEqualTo(adminId);
		assertThat(admin.isActive()).isTrue();
		assertThat(admin.isMustChangePassword()).isTrue();
		assertThat(passwordEncoder.matches("Recover-2026", admin.getPasswordHash())).isTrue();
		assertThat(output.getAll()).doesNotContain("Recover-2026");
	}

	@Test
	@DisplayName("A deactivated Admin is restored when the restart uses its mobile number instead of its email")
	void deactivatedAdminRestoredByMobile() {
		jdbc.update("delete from users");
		assertThat(adminBootstrap.bootstrap(new AdminProperties(null, "+639171234567", "Start-Here-2026")))
			.isEqualTo(Outcome.CREATED);
		UUID id = onlyAdmin().getId();
		jdbc.update("update users set active = false where id = ?", id);

		assertThat(adminBootstrap.bootstrap(new AdminProperties(null, "09171234567", "Recover-2026")))
			.isEqualTo(Outcome.REACTIVATED);
		assertThat(onlyAdmin().getId()).isEqualTo(id);
		assertThat(onlyAdmin().isActive()).isTrue();
	}

	@Test
	@DisplayName("A deactivated Admin and different settings: a new Admin is created, the old one stays inactive")
	void deactivatedAdminWithOtherSettings() {
		jdbc.update("update users set active = false where id = ?", adminId);

		assertThat(adminBootstrap.bootstrap(new AdminProperties("new-admin@example.com", null, "Recover-2026")))
			.isEqualTo(Outcome.CREATED);
		List<UserAccount> admins = admins();
		assertThat(admins).hasSize(2);
		assertThat(admins).filteredOn(UserAccount::isActive)
			.singleElement()
			.extracting(UserAccount::getEmail)
			.isEqualTo("new-admin@example.com");
	}

	private UserAccount onlyAdmin() {
		List<UserAccount> admins = admins();
		assertThat(admins).hasSize(1);
		return admins.getFirst();
	}

	private List<UserAccount> admins() {
		return users.findAll().stream().filter(u -> u.getRole() == Role.ADMIN).toList();
	}

}
