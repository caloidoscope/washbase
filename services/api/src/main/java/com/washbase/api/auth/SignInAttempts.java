package com.washbase.api.auth;

import com.washbase.api.user.EmailAddresses;
import com.washbase.api.user.PhoneNumbers;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Failed sign-ins and pauses per identifier (CAR-19), in {@code sign_in_failure} and {@code sign_in_pause}
 * ({@code V4__sign_in_pause.sql}). The database, not memory, so pauses survive a restart and hold across instances.
 *
 * <p>Rules (scenario names from CAR-19):
 * <ul>
 * <li>Key: {@link #identifierKey}. Unknown identifiers are counted and paused exactly like known ones ("Unknown
 * accounts are paused the same way"); {@code 09…}/{@code +639…} and email case variants share one key ("The same
 * pause applies whichever form of the identifier is used").</li>
 * <li>{@link #recordFailure}: store a failure at {@code clock.instant()}; if the failures with
 * {@code failed_at > now - window} now number {@code maxFailures} or more, upsert {@code sign_in_pause} with
 * {@code paused_until = now + pause} and delete that key's failures (so old failures can't re-pause right after the
 * pause ends). Also delete that key's failures older than the window. One transaction ("Wrong passwords spread over
 * more than 15 minutes don't pause sign-in").</li>
 * <li>{@link #isPaused}: {@code paused_until > clock.instant()} ("The sixth attempt after five wrong passwords is
 * paused", "Sign-in works again after the pause").</li>
 * <li>{@link #recordSuccess}: delete that key's failures ("A successful sign-in resets the count").</li>
 * </ul>
 * Parameterized SQL only. Never log the identifier, its key or its hash.
 */
@Service
class SignInAttempts {

	private final JdbcOperations jdbc;

	private final Clock clock;

	private final SignInLimitProperties limits;

	SignInAttempts(JdbcOperations jdbc, Clock clock, SignInLimitProperties limits) {
		this.jdbc = jdbc;
		this.clock = clock;
		this.limits = limits;
	}

	/**
	 * The identifier as it is counted, normalized like {@link UserAccountDetailsService} looks people up, so every
	 * form of one identifier shares a count.
	 *
	 * @param typed the "Email or mobile number" field as typed
	 * @return {@code email:<normalized>} if it contains {@code @}; {@code mobile:+639…} if it is a Philippine mobile
	 * number; otherwise {@code other:<trimmed, lower-cased>}; empty if {@code null} or blank (nothing to count)
	 */
	static Optional<String> identifierKey(String typed) {
		if (typed == null || typed.isBlank()) {
			return Optional.empty();
		}
		if (typed.contains("@")) {
			return Optional.of("email:" + EmailAddresses.normalize(typed));
		}
		return Optional.of(PhoneNumbers.normalize(typed)
			.map(mobile -> "mobile:" + mobile)
			.orElseGet(() -> "other:" + typed.strip().toLowerCase(Locale.ROOT)));
	}

	/** @return whether sign-in for this key is paused right now */
	@Transactional(readOnly = true)
	boolean isPaused(String identifierKey) {
		Boolean paused = jdbc.queryForObject(
				"select exists (select 1 from sign_in_pause where identifier_hash = ? and paused_until > ?)",
				Boolean.class, identifierHash(identifierKey), timestamp(clock.instant()));
		return Boolean.TRUE.equals(paused);
	}

	/** Records one failed sign-in for this key and starts a pause when it reaches the limit. */
	@Transactional
	void recordFailure(String identifierKey) {
		String hash = identifierHash(identifierKey);
		Instant now = clock.instant();
		OffsetDateTime windowStart = timestamp(now.minus(limits.window()));
		jdbc.update("insert into sign_in_failure (identifier_hash, failed_at) values (?, ?)", hash, timestamp(now));
		// Failures outside the sliding window no longer count: forget them.
		jdbc.update("delete from sign_in_failure where identifier_hash = ? and failed_at <= ?", hash, windowStart);
		Integer recent = jdbc.queryForObject(
				"select count(*) from sign_in_failure where identifier_hash = ? and failed_at > ?", Integer.class, hash,
				windowStart);
		if (recent != null && recent >= limits.maxFailures()) {
			jdbc.update("""
					insert into sign_in_pause (identifier_hash, paused_until) values (?, ?)
					on conflict (identifier_hash) do update set paused_until = excluded.paused_until""", hash,
					timestamp(now.plus(limits.pause())));
			// So these failures can't pause sign-in again right after the pause ends.
			jdbc.update("delete from sign_in_failure where identifier_hash = ?", hash);
		}
	}

	/** Forgets this key's failures after a successful sign-in. */
	@Transactional
	void recordSuccess(String identifierKey) {
		jdbc.update("delete from sign_in_failure where identifier_hash = ?", identifierHash(identifierKey));
	}

	/** @return the lowercase hex SHA-256 of the key: the only form of it that is stored */
	static String identifierHash(String identifierKey) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
				.digest(identifierKey.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is not available", ex);
		}
	}

	private static OffsetDateTime timestamp(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}

}
