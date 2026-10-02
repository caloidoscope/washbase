package com.washbase.api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * {@code washbase.auth.sign-in-limit.*} (CAR-19): defaults 5 failures / 15 minutes / 15 minutes; zero or negative
 * values stop start-up; and the sign-in page's pause message ({@link LoginController#pausedMessage}).
 */
class SignInLimitPropertiesTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withUserConfiguration(SignInLimitPropertiesOnly.class);

	@Test
	@DisplayName("Sign-in limit defaults: 5 failures within 15 minutes pause sign-in for 15 minutes")
	void defaults() {
		runner.run(context -> assertThat(context.getBean(SignInLimitProperties.class))
			.isEqualTo(new SignInLimitProperties(5, Duration.ofMinutes(15), Duration.ofMinutes(15))));
	}

	@Test
	@DisplayName("Configured sign-in limits are used (ISO-8601 durations)")
	void configuredValuesUsed() {
		runner
			.withPropertyValues("washbase.auth.sign-in-limit.max-failures=3", "washbase.auth.sign-in-limit.window=PT5M",
					"washbase.auth.sign-in-limit.pause=PT1M")
			.run(context -> assertThat(context.getBean(SignInLimitProperties.class))
				.isEqualTo(new SignInLimitProperties(3, Duration.ofMinutes(5), Duration.ofMinutes(1))));
	}

	@ParameterizedTest(name = "Sign-in limit setting {0} fails start-up")
	@ValueSource(strings = { "washbase.auth.sign-in-limit.max-failures=0", "washbase.auth.sign-in-limit.max-failures=-1",
			"washbase.auth.sign-in-limit.window=PT0S", "washbase.auth.sign-in-limit.window=-PT15M",
			"washbase.auth.sign-in-limit.pause=PT0S", "washbase.auth.sign-in-limit.pause=-PT1M" })
	void nonPositiveFailsStartup(String setting) {
		runner.withPropertyValues(setting)
			.run(context -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessageContaining("washbase.auth.sign-in-limit"));
	}

	@ParameterizedTest(name = "A pause of {0} shows \"{1}\"")
	@CsvSource(delimiter = '|', value = { "PT15M | Too many attempts. Try again in 15 minutes.",
			"PT1M | Too many attempts. Try again in 1 minute.", "PT30S | Too many attempts. Try again in 1 minute.",
			"PT90S | Too many attempts. Try again in 2 minutes." })
	void pausedMessage(String pause, String message) {
		SignInLimitProperties limits = new SignInLimitProperties(5, Duration.ofMinutes(15), Duration.parse(pause));

		assertThat(LoginController.pausedMessage(limits)).isEqualTo(message);
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(SignInLimitProperties.class)
	static class SignInLimitPropertiesOnly {

	}

}
