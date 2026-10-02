package com.washbase.api.user;

import org.springframework.context.ApplicationContext;

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

}
