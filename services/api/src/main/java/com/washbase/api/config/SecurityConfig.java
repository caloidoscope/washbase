package com.washbase.api.config;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.washbase.api.auth.AuthProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtAudienceValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Resource server for {@code /api/**} (ADR-001): default deny, JWT validation, roles claim mapped to
 * {@code ROLE_*} authorities for {@code @PreAuthorize}.
 *
 * <p>This chain matches every request not claimed by an earlier chain. The authorization server's own
 * chains in {@code com.washbase.api.auth} (OAuth/OIDC endpoints, {@code /login}) use a lower
 * {@code @Order} and their own matchers; together with {@link #ALWAYS_PUBLIC} and {@link #API_DOCS}
 * they form the public allowlist. Anything else outside {@code /api/**} is denied.
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
@EnableConfigurationProperties(WashbaseSecurityProperties.class)
class SecurityConfig {

	/** Order of {@link #apiSecurityFilterChain}; the authorization server chains must use lower values. */
	static final int API_CHAIN_ORDER = 3;

	/** Public allowlist: always open. */
	static final String[] ALWAYS_PUBLIC = { "/actuator/health", "/actuator/health/**", "/error" };

	/** Public allowlist: open only when {@code washbase.security.public-api-docs=true} (default). */
	static final String[] API_DOCS = { "/v3/api-docs", "/v3/api-docs/**", "/v3/api-docs.yaml", "/swagger-ui.html",
			"/swagger-ui/**" };

	static final String ROLES_CLAIM = "roles";

	@Bean
	@Order(API_CHAIN_ORDER)
	SecurityFilterChain apiSecurityFilterChain(HttpSecurity http, WashbaseSecurityProperties securityProperties)
			throws Exception {
		http.authorizeHttpRequests(auth -> {
			auth.requestMatchers(ALWAYS_PUBLIC).permitAll();
			if (securityProperties.publicApiDocs()) {
				auth.requestMatchers(API_DOCS).permitAll();
			}
			auth.requestMatchers("/api/**").authenticated();
			auth.anyRequest().denyAll();
		})
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			// Bearer tokens only on this chain: no cookies, so no CSRF exposure.
			.csrf(AbstractHttpConfigurer::disable)
			.oauth2ResourceServer(rs -> rs.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));
		return http.build();
	}

	/**
	 * Validates tokens in-process against the authorization server's own keys (no HTTP call to the JWKS
	 * endpoint): signature, {@code exp}/{@code nbf}, {@code typ}, {@code iss} and {@code aud}. Then, only if all of
	 * those pass, that the token is still the current access token of a non-revoked authorization
	 * ({@link CurrentAccessTokenValidator}, CAR-18): a token from before sign-out, a renewal or refresh-token reuse
	 * gets {@code 401} at once instead of living out its 15 minutes.
	 */
	@Bean
	JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource, AuthProperties authProperties,
			OAuth2AuthorizationService authorizationService) {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSource(jwkSource).build();
		OAuth2TokenValidator<Jwt> claims = new DelegatingOAuth2TokenValidator<>(
				JwtValidators.createDefaultWithIssuer(authProperties.issuer()),
				new JwtAudienceValidator(authProperties.audience()));
		OAuth2TokenValidator<Jwt> current = new CurrentAccessTokenValidator(authorizationService);
		// Sequential, not DelegatingOAuth2TokenValidator: no database lookup for a token whose claims already fail.
		decoder.setJwtValidator(jwt -> {
			OAuth2TokenValidatorResult result = claims.validate(jwt);
			return result.hasErrors() ? result : current.validate(jwt);
		});
		return decoder;
	}

	private static JwtAuthenticationConverter jwtAuthenticationConverter() {
		JwtGrantedAuthoritiesConverter roles = new JwtGrantedAuthoritiesConverter();
		roles.setAuthoritiesClaimName(ROLES_CLAIM);
		roles.setAuthorityPrefix("ROLE_");
		JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
		converter.setJwtGrantedAuthoritiesConverter(roles);
		return converter;
	}

}
