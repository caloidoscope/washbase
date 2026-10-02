package com.washbase.api.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.washbase.api.user.AdminBootstrap.Outcome;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith({ MockitoExtension.class, OutputCaptureExtension.class })
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminBootstrapTests {

	private static final String PASSWORD = "Start-Here-2026";

	@Mock
	private UserAccountRepository users;

	@Mock
	private PasswordEncoder passwordEncoder;

	private AdminBootstrap bootstrap;

	@BeforeEach
	void setUp() {
		bootstrap = new AdminBootstrap(users, passwordEncoder);
		given(passwordEncoder.encode(anyString())).willReturn("{bcrypt}hash");
		given(users.findByEmail(anyString())).willReturn(Optional.empty());
		given(users.findByMobile(anyString())).willReturn(Optional.empty());
	}

	@Test
	@DisplayName("Creates the Admin from the email setting: name Admin, role ADMIN, must change password")
	void createsFromEmail(CapturedOutput output) {
		Outcome outcome = bootstrap.bootstrap(new AdminProperties(" Admin@Example.com ", null, PASSWORD));

		assertThat(outcome).isEqualTo(Outcome.CREATED);
		UserAccount saved = savedAccount();
		assertThat(saved.getName()).isEqualTo("Admin");
		assertThat(saved.getEmail()).isEqualTo("admin@example.com");
		assertThat(saved.getMobile()).isNull();
		assertThat(saved.getRole()).isEqualTo(Role.ADMIN);
		assertThat(saved.isActive()).isTrue();
		assertThat(saved.isMustChangePassword()).isTrue();
		assertThat(saved.getPasswordHash()).isEqualTo("{bcrypt}hash");
		verify(passwordEncoder).encode(PASSWORD);
		assertThat(output).doesNotContain(PASSWORD).doesNotContain("admin@example.com");
	}

	@ParameterizedTest(name = "Creates the Admin from the mobile setting \"{0}\" stored as {1}")
	@CsvSource({ "09171234567, +639171234567", "+639171234567, +639171234567" })
	void createsFromMobile(String configured, String stored) {
		assertThat(bootstrap.bootstrap(new AdminProperties("", configured, PASSWORD))).isEqualTo(Outcome.CREATED);

		UserAccount saved = savedAccount();
		assertThat(saved.getEmail()).isNull();
		assertThat(saved.getMobile()).isEqualTo(stored);
	}

	@Test
	@DisplayName("Creates the Admin with both email and mobile when both are set")
	void createsFromEmailAndMobile() {
		assertThat(bootstrap.bootstrap(new AdminProperties("admin@example.com", "09171234567", PASSWORD)))
			.isEqualTo(Outcome.CREATED);

		UserAccount saved = savedAccount();
		assertThat(saved.getEmail()).isEqualTo("admin@example.com");
		assertThat(saved.getMobile()).isEqualTo("+639171234567");
	}

	@Test
	@DisplayName("Does nothing when an active Admin exists, even if the settings differ")
	void skipsWhenActiveAdminExists() {
		given(users.existsByRoleAndActiveTrue(Role.ADMIN)).willReturn(true);

		assertThat(bootstrap.bootstrap(new AdminProperties("other-admin@example.com", null, "Another-2026")))
			.isEqualTo(Outcome.ACTIVE_ADMIN_EXISTS);
		verify(users, never()).saveAndFlush(any());
		verify(passwordEncoder, never()).encode(anyString());
	}

	@ParameterizedTest(name = "Warns and starts anyway when the settings are incomplete (email \"{0}\", mobile \"{1}\", "
			+ "password \"{2}\")")
	@CsvSource(nullValues = "null",
			value = { "null, null, null", "'', '', ''", "' ', ' ', ' '", "admin@example.com, null, null",
					"null, 09171234567, ''", "null, null, Start-Here-2026" })
	void warnsWhenNotConfigured(String email, String mobile, String password, CapturedOutput output) {
		assertThat(bootstrap.bootstrap(new AdminProperties(email, mobile, password)))
			.isEqualTo(Outcome.NOT_CONFIGURED);
		verify(users, never()).saveAndFlush(any());
		assertThat(output).contains("WARN").contains("No Admin account is configured").doesNotContain(PASSWORD);
	}

	@ParameterizedTest(name = "Refuses an invalid setting (email \"{0}\", mobile \"{1}\") without crashing")
	@CsvSource(nullValues = "null", value = { "null, 0917123456", "null, +15551234567", "admin@example.com, 12345",
			"not-an-email, null" })
	void refusesInvalidSettings(String email, String mobile, CapturedOutput output) {
		assertThat(bootstrap.bootstrap(new AdminProperties(email, mobile, PASSWORD)))
			.isEqualTo(Outcome.INVALID_SETTINGS);
		verify(users, never()).saveAndFlush(any());
		assertThat(output).contains("ERROR").doesNotContain(PASSWORD);
	}

	@Test
	@DisplayName("Reactivates an inactive Admin with the configured email and resets its password")
	void reactivatesByEmail() {
		UserAccount inactive = account(Role.ADMIN, "admin@example.com", null, false);
		given(users.findByEmail("admin@example.com")).willReturn(Optional.of(inactive));

		assertThat(bootstrap.bootstrap(new AdminProperties("ADMIN@example.com", null, "Recover-2026")))
			.isEqualTo(Outcome.REACTIVATED);

		assertThat(savedAccount()).isSameAs(inactive);
		assertThat(inactive.isActive()).isTrue();
		assertThat(inactive.getPasswordHash()).isEqualTo("{bcrypt}hash");
		assertThat(inactive.isMustChangePassword()).isTrue();
		verify(passwordEncoder).encode("Recover-2026");
	}

	@Test
	@DisplayName("Reactivates an inactive Admin with the configured mobile in the other format")
	void reactivatesByMobile() {
		UserAccount inactive = account(Role.ADMIN, null, "+639171234567", false);
		given(users.findByMobile("+639171234567")).willReturn(Optional.of(inactive));

		assertThat(bootstrap.bootstrap(new AdminProperties(null, "09171234567", "Recover-2026")))
			.isEqualTo(Outcome.REACTIVATED);
		assertThat(inactive.isActive()).isTrue();
	}

	@Test
	@DisplayName("Reactivates when email and mobile both match the same inactive Admin")
	void reactivatesWhenBothMatchSameAccount() {
		UserAccount inactive = account(Role.ADMIN, "admin@example.com", "+639171234567", false);
		given(users.findByEmail("admin@example.com")).willReturn(Optional.of(inactive));
		given(users.findByMobile("+639171234567")).willReturn(Optional.of(inactive));

		assertThat(bootstrap.bootstrap(new AdminProperties("admin@example.com", "09171234567", "Recover-2026")))
			.isEqualTo(Outcome.REACTIVATED);
	}

	@ParameterizedTest(name = "Skips without crashing when the configured identifier belongs to a {0}")
	@CsvSource({ "OWNER", "STAFF", "CLIENT" })
	void refusesNonAdminAccount(Role role, CapturedOutput output) {
		given(users.findByEmail("admin@example.com"))
			.willReturn(Optional.of(account(role, "admin@example.com", null, true)));

		assertThat(bootstrap.bootstrap(new AdminProperties("admin@example.com", null, PASSWORD)))
			.isEqualTo(Outcome.CONFLICT);
		verify(users, never()).saveAndFlush(any());
		assertThat(output).contains("non-Admin account").doesNotContain(PASSWORD);
	}

	@Test
	@DisplayName("Skips without crashing when the email and mobile belong to two different accounts")
	void refusesTwoDifferentAccounts() {
		given(users.findByEmail("admin@example.com"))
			.willReturn(Optional.of(account(Role.ADMIN, "admin@example.com", null, false)));
		given(users.findByMobile("+639171234567"))
			.willReturn(Optional.of(account(Role.ADMIN, null, "+639171234567", false)));

		assertThat(bootstrap.bootstrap(new AdminProperties("admin@example.com", "09171234567", PASSWORD)))
			.isEqualTo(Outcome.CONFLICT);
		verify(users, never()).saveAndFlush(any());
	}

	@Test
	@DisplayName("The Admin settings never show the initial password when printed")
	void propertiesMaskPassword() {
		assertThat(new AdminProperties("admin@example.com", "09171234567", PASSWORD).toString())
			.doesNotContain(PASSWORD)
			.contains("admin@example.com")
			.contains("******");
		assertThat(new AdminProperties(null, null, null).toString()).contains("initialPassword=<unset>");
	}

	private UserAccount savedAccount() {
		ArgumentCaptor<UserAccount> captor = ArgumentCaptor.forClass(UserAccount.class);
		verify(users).saveAndFlush(captor.capture());
		return captor.getValue();
	}

	private static UserAccount account(Role role, String email, String mobile, boolean active) {
		UserAccount account = new UserAccount("Someone", email, mobile, "{bcrypt}old", role, false);
		ReflectionTestUtils.setField(account, "id", UUID.randomUUID());
		ReflectionTestUtils.setField(account, "active", active);
		return account;
	}

}
