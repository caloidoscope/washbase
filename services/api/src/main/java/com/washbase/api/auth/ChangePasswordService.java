package com.washbase.api.auth;

import com.washbase.api.user.PasswordPolicy;
import com.washbase.api.user.UserAccount;
import com.washbase.api.user.UserAccountRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Choosing a new password at first sign-in (CAR-21). The person is identified by the signed-in session
 * ({@code userId} = the principal's name = the user's UUID), never by anything typed.
 */
@Service
class ChangePasswordService {

	static final String MISMATCH = "The passwords don't match.";

	static final String SAME_AS_GIVEN = "Choose a password different from the one you were given.";

	private final UserAccountRepository users;

	private final PasswordEncoder passwordEncoder;

	private final JdbcOperations jdbc;

	ChangePasswordService(UserAccountRepository users, PasswordEncoder passwordEncoder, JdbcOperations jdbc) {
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.jdbc = jdbc;
	}

	/** Whether this signed-in person must choose a new password before anything else. */
	@Transactional(readOnly = true)
	boolean mustChangePassword(String userId) {
		return find(userId).map(UserAccount::isMustChangePassword).orElse(false);
	}

	/**
	 * Replaces the password when it is acceptable: clears the "must change" flag and ends every existing
	 * authorization (codes, tokens) of the person.
	 * @return the message to show when refused, or empty when the password was changed
	 */
	@Transactional
	Optional<String> change(String userId, String newPassword, String confirmation) {
		Optional<String> tooShort = PasswordPolicy.violation(newPassword);
		if (tooShort.isPresent()) {
			return tooShort;
		}
		if (!newPassword.equals(confirmation)) {
			return Optional.of(MISMATCH);
		}
		UserAccount account = find(userId).orElseThrow(() -> new IllegalStateException("Signed-in user not found"));
		if (passwordEncoder.matches(newPassword, account.getPasswordHash())) {
			return Optional.of(SAME_AS_GIVEN);
		}
		account.changePassword(passwordEncoder.encode(newPassword));
		users.save(account);
		// Cascades to oauth2_replaced_refresh_token.
		jdbc.update("delete from oauth2_authorization where principal_name = ?", userId);
		return Optional.empty();
	}

	private Optional<UserAccount> find(String userId) {
		try {
			return users.findById(UUID.fromString(userId));
		}
		catch (IllegalArgumentException ex) {
			return Optional.empty();
		}
	}

}
