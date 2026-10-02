package com.washbase.api.user;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Accounts for trying the app by hand. Local only (Spring profile {@code local}, which {@code pnpm dev:all}
 * activates): never loaded in any other profile. Idempotent; runs after the Admin bootstrap. Accounts and password
 * match {@code scripts/local-env.mjs}.
 *
 * <p>{@value #NEW_OWNER_EMAIL} still has to choose a new password (CAR-21): use it to see that page. Once it has
 * chosen one, run {@code pnpm db:reset} to get it back in its initial state.
 */
@Profile("local")
@Component
@Order(2)
class LocalSeed implements ApplicationRunner {

	static final String NEW_OWNER_EMAIL = "newowner@example.com";

	static final String PASSWORD = "Washbase-Local-1";

	private static final Logger log = LoggerFactory.getLogger(LocalSeed.class);

	private final UserAccountRepository users;

	private final PasswordEncoder passwordEncoder;

	LocalSeed(UserAccountRepository users, PasswordEncoder passwordEncoder) {
		this.users = users;
		this.passwordEncoder = passwordEncoder;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (users.findByEmail(NEW_OWNER_EMAIL).isEmpty()) {
			users.save(new UserAccount("New Owner", NEW_OWNER_EMAIL, null, passwordEncoder.encode(PASSWORD), Role.OWNER,
					true));
			log.info("Local seed: created {} (must choose a new password at first sign-in).", NEW_OWNER_EMAIL);
		}
	}

}
