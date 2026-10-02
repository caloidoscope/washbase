package com.washbase.api.user;

import java.util.Locale;

/** Email addresses are matched case-insensitively: they are stored and looked up trimmed and lower-cased. */
public final class EmailAddresses {

	private EmailAddresses() {
	}

	/**
	 * @param input an email address as typed
	 * @return the address trimmed and lower-cased, or {@code null} if the input is {@code null} or blank
	 */
	public static String normalize(String input) {
		if (input == null || input.isBlank()) {
			return null;
		}
		return input.strip().toLowerCase(Locale.ROOT);
	}

}
