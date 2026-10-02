package com.washbase.api.auth;

import java.net.URI;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The embedded authorization server (ADR-001): Spring Authorization Server with OIDC, and the sign-in page.
 *
 * <p>Filter chains, in order (the resource server chain for {@code /api/**} in
 * {@code com.washbase.api.config.SecurityConfig} is order 3 and takes everything else):
 * <ol>
 * <li>{@link #authorizationServerSecurityFilterChain}: only the OAuth/OIDC endpoints ({@code /oauth2/authorize},
 * {@code /oauth2/token} (authorization code and refresh token grants), {@code /oauth2/revoke} (token revocation),
 * {@code /connect/logout} (OIDC RP-initiated logout), {@code /oauth2/jwks}, {@code /.well-known/openid-configuration},
 * {@code /.well-known/oauth-authorization-server}, {@code /userinfo}, ...). A browser that isn't signed in is sent
 * to {@code /login}. Once the authorization code is issued, the browser's session here ends
 * ({@link SessionEndingAuthorizationResponseHandler}): the web app keeps its own session, and nothing can sign
 * someone back in without their password after sign-out or refresh-token reuse.</li>
 * <li>{@link #loginSecurityFilterChain}: {@code GET /login} (page) and {@code POST /login} (form, CSRF protected).
 * Public.</li>
 * </ol>
 * Only Authorization Code + PKCE (plus the refresh token grant it leads to) is possible: the registered clients
 * allow no other grant (see {@link RegisteredClients}). Refresh tokens are rotated on every use, and presenting a
 * replaced one ends the whole authorization ({@link RefreshTokenReuseDetectingAuthorizationService}). Signing out
 * revokes the refresh token, which also invalidates its access token; the resource server refuses an access token
 * that is no longer current at once ({@code com.washbase.api.config.SecurityConfig}).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthProperties.class)
class AuthorizationServerConfig {

	static final int AUTHORIZATION_SERVER_CHAIN_ORDER = 1;

	static final int LOGIN_CHAIN_ORDER = 2;

	static final String LOGIN_PAGE = "/login";

	/** Where the sign-in page sends the browser after any failure; the page then shows the one generic message. */
	static final String LOGIN_FAILURE_URL = LOGIN_PAGE + "?error";

	@Bean
	@Order(AUTHORIZATION_SERVER_CHAIN_ORDER)
	SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http) throws Exception {
		http.oauth2AuthorizationServer(authorizationServer -> {
			http.securityMatcher(authorizationServer.getEndpointsMatcher());
			authorizationServer
				// Ends the API's own sign-in session as soon as the code is issued (CAR-18).
				.authorizationEndpoint(endpoint -> endpoint
					.authorizationResponseHandler(new SessionEndingAuthorizationResponseHandler()))
				// Defaults include RP-initiated logout (/connect/logout) for any session still left.
				.oidc(Customizer.withDefaults());
		})
			.authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
			// Browsers (HTML) that aren't signed in go to the sign-in page; other callers get 401.
			.exceptionHandling(exceptions -> exceptions.defaultAuthenticationEntryPointFor(
					new LoginUrlAuthenticationEntryPoint(LOGIN_PAGE), new MediaTypeRequestMatcher(MediaType.TEXT_HTML)))
			// OIDC UserInfo accepts the access token.
			.oauth2ResourceServer(resourceServer -> resourceServer.jwt(Customizer.withDefaults()));
		return http.build();
	}

	@Bean
	@Order(LOGIN_CHAIN_ORDER)
	SecurityFilterChain loginSecurityFilterChain(HttpSecurity http, AuthProperties authProperties) throws Exception {
		http.securityMatcher(LOGIN_PAGE)
			.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
			// CSRF stays on (default): the template includes the token via th:action.
			.formLogin(form -> form.loginPage(LOGIN_PAGE)
				.failureUrl(LOGIN_FAILURE_URL)
				// Normally the saved /oauth2/authorize request is resumed. Someone who opened /login directly is
				// sent to the web app, which signs them in through the normal flow without asking again.
				.defaultSuccessUrl(webAppHome(authProperties), false)
				.permitAll());
		return http.build();
	}

	@Bean
	AuthorizationServerSettings authorizationServerSettings(AuthProperties authProperties) {
		return AuthorizationServerSettings.builder().issuer(authProperties.issuer()).build();
	}

	/** Clients come from configuration, not the database (see {@link RegisteredClients}). */
	@Bean
	RegisteredClientRepository registeredClientRepository(AuthProperties authProperties,
			PasswordEncoder passwordEncoder) {
		return RegisteredClients.fromConfiguration(authProperties, passwordEncoder);
	}

	/**
	 * Authorizations (codes, tokens) in {@code oauth2_authorization}, Flyway {@code V2__oauth2_authorization.sql},
	 * with refresh-token reuse detection ({@code oauth2_replaced_refresh_token}, {@code V3__refresh_token_rotation.sql}).
	 */
	@Bean
	OAuth2AuthorizationService authorizationService(JdbcOperations jdbcOperations,
			RegisteredClientRepository registeredClientRepository, PlatformTransactionManager transactionManager) {
		return new RefreshTokenReuseDetectingAuthorizationService(
				new JdbcOAuth2AuthorizationService(jdbcOperations, registeredClientRepository), jdbcOperations,
				new TransactionTemplate(transactionManager));
	}

	/** Unused while first-party clients skip consent; kept so the JDBC tables match Spring Authorization Server. */
	@Bean
	OAuth2AuthorizationConsentService authorizationConsentService(JdbcOperations jdbcOperations,
			RegisteredClientRepository registeredClientRepository) {
		return new JdbcOAuth2AuthorizationConsentService(jdbcOperations, registeredClientRepository);
	}

	/** The web app's origin ({@code http://localhost:3000/} for the default redirect URI). */
	static String webAppHome(AuthProperties authProperties) {
		URI redirectUri = URI.create(authProperties.webClient().redirectUri());
		return redirectUri.resolve("/").toString();
	}

}
