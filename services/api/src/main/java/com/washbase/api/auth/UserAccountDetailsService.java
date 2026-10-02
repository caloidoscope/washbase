package com.washbase.api.auth;

import com.washbase.api.user.EmailAddresses;
import com.washbase.api.user.PhoneNumbers;
import com.washbase.api.user.UserAccount;
import com.washbase.api.user.UserAccountRepository;
import java.util.Optional;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Finds the person signing in on {@code /login} by the "Email or mobile number" field (form parameter
 * {@code username}).
 *
 * <ul>
 * <li>Input containing {@code @}: {@link EmailAddresses#normalize} then {@code findByEmail} (case-insensitive).
 * Otherwise: {@link PhoneNumbers#normalize} then {@code findByMobile} ({@code 09…} and {@code +639…} are the same
 * number); an invalid number is "not found".</li>
 * <li>Not found, inactive, or no password hash: {@link UsernameNotFoundException}. The provider turns it into the
 * same bad-credentials failure (with its timing mitigation), so the page can't reveal whether an account exists or
 * is active. The typed identifier never reaches the exception message or a log.</li>
 * <li>Returns Spring Security's own {@link User} (the principal is stored as JSON in {@code oauth2_authorization},
 * and only allow-listed types deserialize) with username = the user's UUID, so the token's {@code sub} is the user
 * ID; password = the stored hash; authority {@code ROLE_<role>}.</li>
 * </ul>
 */
@Service
class UserAccountDetailsService implements UserDetailsService {

	/** The same message for every failure; it says nothing about the account. */
	static final String NOT_FOUND = "No active account with a password matches";

	private final UserAccountRepository users;

	UserAccountDetailsService(UserAccountRepository users) {
		this.users = users;
	}

	@Override
	@Transactional(readOnly = true)
	public UserDetails loadUserByUsername(String emailOrMobile) throws UsernameNotFoundException {
		UserAccount account = find(emailOrMobile).filter(UserAccount::isActive)
			.filter(user -> user.getPasswordHash() != null && !user.getPasswordHash().isBlank())
			.orElseThrow(() -> new UsernameNotFoundException(NOT_FOUND));
		return User.withUsername(account.getId().toString())
			.password(account.getPasswordHash())
			.roles(account.getRole().name())
			.build();
	}

	private Optional<UserAccount> find(String emailOrMobile) {
		if (emailOrMobile == null || emailOrMobile.isBlank()) {
			return Optional.empty();
		}
		if (emailOrMobile.contains("@")) {
			return users.findByEmail(EmailAddresses.normalize(emailOrMobile));
		}
		return PhoneNumbers.normalize(emailOrMobile).flatMap(users::findByMobile);
	}

}
