package com.washbase.api.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Password hashing (ADR-001): {@code DelegatingPasswordEncoder}, bcrypt by default. Hashes carry an {@code {id}}
 * prefix so the algorithm can change later without invalidating stored hashes.
 */
@Configuration(proxyBeanMethods = false)
class PasswordEncoderConfig {

	@Bean
	PasswordEncoder passwordEncoder() {
		return PasswordEncoderFactories.createDelegatingPasswordEncoder();
	}

}
