package com.washbase.api.user;

import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Lets tests in other packages simulate a start-up with given Admin settings: it runs the real
 * {@link AdminBootstrap}, exactly as {@link AdminBootstrapRunner} does at every start-up.
 */
public final class AdminBootstrapping {

	private AdminBootstrapping() {
	}

	/**
	 * @return the bootstrap's outcome name ({@code CREATED}, {@code REACTIVATED}, {@code ACTIVE_ADMIN_EXISTS}, ...)
	 */
	public static String startWith(ApplicationContext context, String email, String mobile, String initialPassword) {
		return context.getBean(AdminBootstrap.class)
			.bootstrap(new AdminProperties(email, mobile, initialPassword))
			.name();
	}

	/**
	 * Like {@link #startWith}, then marks the account as having already chosen its own password (CAR-21), for tests
	 * of sign-in itself that must not be sent to "Choose a new password" first.
	 * @return the bootstrap's outcome name
	 */
	public static String startWithChosenPassword(ApplicationContext context, String email, String mobile,
			String initialPassword) {
		String outcome = startWith(context, email, mobile, initialPassword);
		context.getBean(JdbcTemplate.class).update("update users set must_change_password = false");
		return outcome;
	}

}
