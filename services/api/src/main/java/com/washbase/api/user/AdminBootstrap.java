package com.washbase.api.user;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates, or restores, the deployment's single Admin from {@link AdminProperties} (ADR-001, CAR-17). It is the
 * only way an Admin is ever created. Never logs the initial password or the configured identifiers.
 */
@Service
class AdminBootstrap {

	enum Outcome {

		/** An active Admin already exists; the settings are ignored, even if they differ. */
		ACTIVE_ADMIN_EXISTS,
		/** No active Admin and no usable settings: a warning is logged and start-up continues. */
		NOT_CONFIGURED,
		/** The settings are present but invalid (e.g. not a Philippine mobile number). */
		INVALID_SETTINGS,
		/** The configured email or mobile belongs to a non-Admin account, or to two different accounts. */
		CONFLICT,
		/** A new Admin was created. */
		CREATED,
		/** An inactive Admin with the configured email or mobile was reactivated and its password reset. */
		REACTIVATED

	}

	static final String ADMIN_NAME = "Admin";

	private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

	private final UserAccountRepository users;

	private final PasswordEncoder passwordEncoder;

	private final JdbcOperations jdbc;

	/** Whether a newly created Admin must choose a new password at first sign-in (always, except local and E2E runs). */
	private final boolean requirePasswordChange;


	@Autowired
	AdminBootstrap(UserAccountRepository users, PasswordEncoder passwordEncoder, JdbcOperations jdbc,
			@Value("${washbase.admin.require-password-change:true}") boolean requirePasswordChange) {
		this.jdbc = jdbc;
		this.requirePasswordChange = requirePasswordChange;
		this.users = users;
		this.passwordEncoder = passwordEncoder;
	}

	@Transactional
	Outcome bootstrap(AdminProperties settings) {
		if (users.existsByRoleAndActiveTrue(Role.ADMIN)) {
			log.info("An active Admin account exists; Admin bootstrap skipped.");
			return Outcome.ACTIVE_ADMIN_EXISTS;
		}

		String email = EmailAddresses.normalize(settings.email());
		boolean mobileConfigured = hasText(settings.mobile());
		if ((email == null && !mobileConfigured) || !hasText(settings.initialPassword())) {
			log.warn("No Admin account is configured: there is no active Admin, and WASHBASE_ADMIN_EMAIL or "
					+ "WASHBASE_ADMIN_MOBILE plus WASHBASE_ADMIN_INITIAL_PASSWORD are not set. Nobody can sign in "
					+ "as Admin until they are set and the API is restarted.");
			return Outcome.NOT_CONFIGURED;
		}
		if (email != null && !email.contains("@")) {
			log.error("WASHBASE_ADMIN_EMAIL is not an email address; Admin bootstrap skipped.");
			return Outcome.INVALID_SETTINGS;
		}
		String mobile = null;
		if (mobileConfigured) {
			Optional<String> normalized = PhoneNumbers.normalize(settings.mobile());
			if (normalized.isEmpty()) {
				log.error("WASHBASE_ADMIN_MOBILE is not a Philippine mobile number (09XXXXXXXXX or +639XXXXXXXXX); "
						+ "Admin bootstrap skipped.");
				return Outcome.INVALID_SETTINGS;
			}
			mobile = normalized.get();
		}

		Optional<UserAccount> byEmail = (email != null) ? users.findByEmail(email) : Optional.empty();
		Optional<UserAccount> byMobile = (mobile != null) ? users.findByMobile(mobile) : Optional.empty();
		if (byEmail.isPresent() && byMobile.isPresent() && !byEmail.get().getId().equals(byMobile.get().getId())) {
			log.error("WASHBASE_ADMIN_EMAIL and WASHBASE_ADMIN_MOBILE belong to two different accounts; "
					+ "Admin bootstrap skipped.");
			return Outcome.CONFLICT;
		}
		Optional<UserAccount> existing = byEmail.or(() -> byMobile);
		if (existing.isPresent()) {
			UserAccount account = existing.get();
			if (account.getRole() != Role.ADMIN) {
				log.error("The configured Admin email or mobile number already belongs to a non-Admin account; "
						+ "Admin bootstrap skipped.");
				return Outcome.CONFLICT;
			}
			// No active Admin exists, so this one is inactive: restore it instead of creating a duplicate.
			account.reactivate(passwordEncoder.encode(settings.initialPassword()));
			users.saveAndFlush(account);
			// The old password may have been stolen: sessions and tokens from before the restore end now (CAR-21).
			jdbc.update("delete from oauth2_authorization where principal_name = ?", account.getId().toString());
			log.warn("Reactivated the inactive Admin account matching the Admin settings and reset its password; "
					+ "the Admin must change it at the next sign-in.");
			return Outcome.REACTIVATED;
		}

		users.saveAndFlush(new UserAccount(ADMIN_NAME, email, mobile,
				passwordEncoder.encode(settings.initialPassword()), Role.ADMIN, requirePasswordChange));
		log.info("Created the Admin account from the Admin settings; the Admin must change the initial password "
				+ "at the first sign-in.");
		return Outcome.CREATED;
	}

	private static boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

}
