package com.washbase.api;

import java.time.Instant;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Replaces the application's {@code Clock} ({@code com.washbase.api.config.ClockConfig}) with a {@link MutableClock}
 * the test controls, e.g. to sign in "at 09:05" (CAR-19). {@code @Import} it next to
 * {@link TestcontainersConfiguration}; it starts at the real current time.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestClockConfiguration {

	@Bean
	@Primary
	MutableClock testClock() {
		return new MutableClock(Instant.now());
	}

}
