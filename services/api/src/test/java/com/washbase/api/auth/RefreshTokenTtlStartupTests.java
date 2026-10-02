package com.washbase.api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** {@code washbase.auth.refresh-token-ttl} (CAR-18): default 30 days; start-up fails unless it outlives 15 minutes. */
class RefreshTokenTtlStartupTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withUserConfiguration(AuthPropertiesOnly.class);

	@Test
	@DisplayName("The refresh token lifetime defaults to 30 days")
	void defaultsTo30Days() {
		runner.run(context -> assertThat(context.getBean(AuthProperties.class).refreshTokenTtl())
			.isEqualTo(Duration.ofDays(30)));
	}

	@Test
	@DisplayName("A configured refresh token lifetime is used (ISO-8601)")
	void configuredValueUsed() {
		runner.withPropertyValues("washbase.auth.refresh-token-ttl=P7D")
			.run(context -> assertThat(context.getBean(AuthProperties.class).refreshTokenTtl())
				.isEqualTo(Duration.ofDays(7)));
	}

	@ParameterizedTest(name = "A refresh token lifetime of {0} fails start-up")
	@ValueSource(strings = { "PT15M", "PT1M", "PT0S" })
	void tooShortFailsStartup(String ttl) {
		runner.withPropertyValues("washbase.auth.refresh-token-ttl=" + ttl)
			.run(context -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessageContaining("refresh-token-ttl must be longer than the access token lifetime"));
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(AuthProperties.class)
	static class AuthPropertiesOnly {

	}

}
