package com.washbase.api.user;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for the Admin bootstrap (ADR-001), supplied as deployment secrets. Email and mobile may both be set;
 * at least one of them plus the initial password is needed to create the Admin.
 *
 * @param email {@code WASHBASE_ADMIN_EMAIL}
 * @param mobile {@code WASHBASE_ADMIN_MOBILE}, {@code 09XXXXXXXXX} or {@code +639XXXXXXXXX}
 * @param initialPassword {@code WASHBASE_ADMIN_INITIAL_PASSWORD}; never logged (see {@link #toString()})
 */
@ConfigurationProperties("washbase.admin")
record AdminProperties(String email, String mobile, String initialPassword) {

	/** Masks the initial password so it can never reach a log line, an exception message or a debugger view. */
	@Override
	public String toString() {
		return "AdminProperties[email=" + email + ", mobile=" + mobile + ", initialPassword="
				+ ((initialPassword == null || initialPassword.isEmpty()) ? "<unset>" : "******") + "]";
	}

}
