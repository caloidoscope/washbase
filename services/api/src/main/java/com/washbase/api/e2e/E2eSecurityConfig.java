package com.washbase.api.e2e;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/** Opens {@code /e2e/**} for the E2E specs. Only under Spring profile {@code e2e}; never anywhere else. */
@Profile("e2e")
@Configuration(proxyBeanMethods = false)
class E2eSecurityConfig {

	@Bean
	@Order(0)
	SecurityFilterChain e2eSecurityFilterChain(HttpSecurity http) throws Exception {
		http.securityMatcher("/e2e/**")
			.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.csrf(AbstractHttpConfigurer::disable);
		return http.build();
	}

}
