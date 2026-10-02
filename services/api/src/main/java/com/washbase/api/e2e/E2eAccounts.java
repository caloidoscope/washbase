package com.washbase.api.e2e;

import com.washbase.api.user.EmailAddresses;
import com.washbase.api.user.Role;
import com.washbase.api.user.UserAccount;
import com.washbase.api.user.UserAccountRepository;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test data for the Playwright E2E specs, unauthenticated. Exists only under Spring profile {@code e2e} (see
 * {@code E2eSecurityConfig}); without it the paths are not controllers and are denied. Hidden from the OpenAPI spec.
 *
 * <p>A deployment has at most one active Admin (database rule). Creating an Admin here therefore deactivates the
 * active one(s), normally the bootstrapped {@code admin@example.com}; {@code DELETE /e2e/accounts?email=} removes the
 * account again and, when no active Admin is left, restores that bootstrapped Admin (active, no password change).
 * So Admin specs must run one at a time and delete their Admin when done.
 */
@Hidden
@Profile("e2e")
@RestController
class E2eAccounts {

	static final String BOOTSTRAPPED_ADMIN = "admin@example.com";

	record CreateAccount(@NotBlank String email, @NotBlank String password, @NotBlank String name, @NotNull Role role,
			boolean mustChangePassword) {
	}

	private final UserAccountRepository users;

	private final PasswordEncoder passwordEncoder;

	private final JdbcOperations jdbc;

	E2eAccounts(UserAccountRepository users, PasswordEncoder passwordEncoder, JdbcOperations jdbc) {
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.jdbc = jdbc;
	}

	/** {@code 201} with the new account's {@code id}; {@code 409} when the email is taken. */
	@PostMapping("/e2e/accounts")
	@Transactional
	ResponseEntity<Map<String, String>> create(@Valid @RequestBody CreateAccount request) {
		String email = EmailAddresses.normalize(request.email());
		if (users.findByEmail(email).isPresent()) {
			return ResponseEntity.status(HttpStatus.CONFLICT).build();
		}
		if (request.role() == Role.ADMIN) {
			jdbc.update("update users set active = false where role = 'ADMIN' and active");
		}
		try {
			UserAccount account = users.saveAndFlush(new UserAccount(request.name(), email, null,
					passwordEncoder.encode(request.password()), request.role(), request.mustChangePassword()));
			return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", account.getId().toString()));
		}
		catch (DataIntegrityViolationException ex) {
			return ResponseEntity.status(HttpStatus.CONFLICT).build();
		}
	}

	/** {@code 204}: the account (and its sessions) is gone; the bootstrapped Admin is restored if none is active. */
	@DeleteMapping("/e2e/accounts")
	@Transactional
	ResponseEntity<Void> delete(@RequestParam String email) {
		String normalized = EmailAddresses.normalize(email);
		if (!BOOTSTRAPPED_ADMIN.equals(normalized)) {
			jdbc.update("delete from oauth2_authorization where principal_name in "
					+ "(select id::text from users where email = ?)", normalized);
			jdbc.update("delete from users where email = ?", normalized);
		}
		if (!users.existsByRoleAndActiveTrue(Role.ADMIN)) {
			jdbc.update("update users set active = true, must_change_password = false where email = ?",
					BOOTSTRAPPED_ADMIN);
		}
		return ResponseEntity.noContent().build();
	}

}
