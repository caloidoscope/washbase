package com.washbase.api.auth;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Pausing sign-in after repeated wrong passwords (CAR-19, ADR-001): {@link #maxFailures} failed sign-ins for the
 * same identifier within {@link #window} pause sign-in for that identifier for {@link #pause}.
 *
 * @param maxFailures how many failures within {@link #window} start a pause (default 5). The attempt after them is
 * the first one refused. Env: {@code WASHBASE_AUTH_SIGN_IN_LIMIT_MAX_FAILURES}.
 * @param window how far back failures count (ISO-8601, default {@code PT15M}): a sliding window ending at the
 * failure being recorded. Env: {@code WASHBASE_AUTH_SIGN_IN_LIMIT_WINDOW}.
 * @param pause how long sign-in stays paused (ISO-8601, default {@code PT15M}), counted from the failure that reached
 * {@link #maxFailures}. Also the "Try again in N minutes." shown on the sign-in page. Env:
 * {@code WASHBASE_AUTH_SIGN_IN_LIMIT_PAUSE}.
 */
@Validated
@ConfigurationProperties("washbase.auth.sign-in-limit")
public record SignInLimitProperties(@Min(1) @DefaultValue("5") int maxFailures,
		@NotNull @DefaultValue("PT15M") Duration window, @NotNull @DefaultValue("PT15M") Duration pause) {

	/** @return whether both durations are positive (a zero window would never pause; a zero pause never refuses) */
	@AssertTrue(message = "washbase.auth.sign-in-limit.window and .pause must be positive")
	boolean isPositive() {
		return window == null || pause == null || (window.isPositive() && pause.isPositive());
	}

	/** @return the pause in whole minutes, rounded up, for the sign-in page's message (at least 1) */
	long pauseMinutes() {
		return Math.max(1, (pause.toSeconds() + 59) / 60);
	}

}
