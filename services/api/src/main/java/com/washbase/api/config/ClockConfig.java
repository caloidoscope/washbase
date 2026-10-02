package com.washbase.api.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The application's clock (UTC). Inject it wherever "now" matters (e.g. sign-in pauses, CAR-19) instead of calling
 * {@code Instant.now()}, so tests can replace it with a clock they control (a {@code @Primary} test bean).
 */
@Configuration(proxyBeanMethods = false)
class ClockConfig {

	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

}
