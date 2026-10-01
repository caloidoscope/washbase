package com.washbase.api.user;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Philippine mobile numbers (ADR-001): accepted as {@code 09XXXXXXXXX} or {@code +639XXXXXXXXX} and stored as
 * {@code +639XXXXXXXXX}, so both forms are the same number.
 */
public final class PhoneNumbers {

	private static final Pattern LOCAL = Pattern.compile("09([0-9]{9})");

	private static final Pattern INTERNATIONAL = Pattern.compile("\\+639[0-9]{9}");

	private PhoneNumbers() {
	}

	/**
	 * @param input a mobile number as typed; surrounding whitespace is ignored
	 * @return the number as {@code +639XXXXXXXXX}, or empty if it isn't a Philippine mobile number in either
	 * accepted form
	 */
	public static Optional<String> normalize(String input) {
		if (input == null) {
			return Optional.empty();
		}
		String value = input.strip();
		Matcher local = LOCAL.matcher(value);
		if (local.matches()) {
			return Optional.of("+639" + local.group(1));
		}
		if (INTERNATIONAL.matcher(value).matches()) {
			return Optional.of(value);
		}
		return Optional.empty();
	}

}
