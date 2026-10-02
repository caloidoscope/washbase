package com.washbase.api.auth;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.net.URI;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2Token;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.DelegatingOAuth2TokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.JwtGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.ExceptionMappingAuthenticationFailureHandler;
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
 * Public. Repeated failed sign-ins for one identifier pause sign-in for it ({@link PausingAuthenticationProvider},
 * CAR-19).</li>
 * </ol>
 * Only Authorization Code + PKCE (plus the refresh token grant it leads to) is possible: the registered clients
 * allow no other grant (see {@link RegisteredClients}). The public mobile client ({@code washbase-mobile}, CAR-20)
 * proves nothing but its {@code client_id} when it renews or revokes: {@link PublicClientTokenAuthenticationConverter}
 * and {@link PublicClientTokenAuthenticationProvider} allow exactly that, only for {@code grant_type=refresh_token}
 * at the token endpoint and for the revocation endpoint, and only for clients registered with method {@code none};
 * {@link #tokenGenerator} issues refresh tokens to it, which Spring Authorization Server doesn't by default. The
 * code exchange still needs the PKCE {@code code_verifier}, and {@code washbase-web} still needs its secret
 * everywhere. Refresh tokens are rotated on every use, and presenting a
 * replaced one ends the whole authorization ({@link RefreshTokenReuseDetectingAuthorizationService}). Signing out
 * revokes the refresh token, which also invalidates its access token; the resource server refuses an access token
 * that is no longer current at once ({@code com.washbase.api.config.SecurityConfig}).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ AuthProperties.class, SignInLimitProperties.class })
class AuthorizationServerConfig {

	static final int AUTHORIZATION_SERVER_CHAIN_ORDER = 1;

	static final int LOGIN_CHAIN_ORDER = 2;

	static final String LOGIN_PAGE = "/login";

	/** Where the sign-in page sends the browser after any failure; the page then shows the one generic message. */
	static final String LOGIN_FAILURE_URL = LOGIN_PAGE + "?error";

	/**
	 * Where the sign-in page sends the browser when sign-in for the typed identifier is paused
	 * ({@link SignInPausedException}, CAR-19); the page then shows "Too many attempts. Try again in 15 minutes.".
	 */
	static final String LOGIN_PAUSED_URL = LOGIN_PAGE + "?paused";

	@Bean
	@Order(AUTHORIZATION_SERVER_CHAIN_ORDER)
	SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http,
			RegisteredClientRepository registeredClients, AuthorizationServerSettings settings,
			OAuth2TokenGenerator<OAuth2Token> tokenGenerator) throws Exception {
		http.oauth2AuthorizationServer(authorizationServer -> {
			http.securityMatcher(authorizationServer.getEndpointsMatcher());
			authorizationServer
				// Ends the API's own sign-in session as soon as the code is issued (CAR-18).
				.authorizationEndpoint(endpoint -> endpoint
					.authorizationResponseHandler(new SessionEndingAuthorizationResponseHandler()))
				// Public clients (washbase-mobile) renew and revoke with client_id alone (CAR-20). First, so they
				// are tried before Spring's own; they decline (null) anything else.
				.clientAuthentication(clients -> clients
					.authenticationConverters(
							converters -> converters.addFirst(new PublicClientTokenAuthenticationConverter(settings)))
					.authenticationProviders(providers -> providers
						.addFirst(new PublicClientTokenAuthenticationProvider(registeredClients))))
				// Refresh tokens for public clients too (CAR-20): see tokenGenerator().
				.tokenGenerator(tokenGenerator)
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
	SecurityFilterChain loginSecurityFilterChain(HttpSecurity http, AuthProperties authProperties,
			UserAccountDetailsService userDetailsService, PasswordEncoder passwordEncoder, SignInAttempts attempts)
			throws Exception {
		// Pause sign-in after repeated wrong passwords (CAR-19). The pausing provider is this chain's whole
		// AuthenticationManager (no parent): falling back to the global one would check the password again and
		// bypass the pause.
		DaoAuthenticationProvider passwords = new DaoAuthenticationProvider(userDetailsService);
		passwords.setPasswordEncoder(passwordEncoder);
		http.authenticationManager(new ProviderManager(new PausingAuthenticationProvider(passwords, attempts)));
		http.securityMatcher(LOGIN_PAGE)
			.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
			// CSRF stays on (default): the template includes the token via th:action.
			.formLogin(form -> form.loginPage(LOGIN_PAGE)
				.failureHandler(loginFailureHandler())
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

	/**
	 * The tokens the authorization server issues (CAR-20). The same as Spring Authorization Server's default except
	 * for refresh tokens, which public clients get too ({@link PublicClientRefreshTokenGenerator}):
	 * <ul>
	 * <li>access tokens and ID tokens: signed JWTs ({@link JwtGenerator} with the signing key), access tokens with
	 * the Washbase claims ({@link AccessTokenCustomizer}) and, as Spring's default, bound to a DPoP proof when the
	 * client sent one ({@link #bindToDpopProof});</li>
	 * <li>refresh tokens: opaque, for every client with the {@code refresh_token} grant.</li>
	 * </ul>
	 * No {@code OAuth2AccessTokenGenerator}: every client's access tokens are self-contained (JWT).
	 */
	@Bean
	OAuth2TokenGenerator<OAuth2Token> tokenGenerator(JWKSource<SecurityContext> jwkSource,
			AccessTokenCustomizer accessTokenCustomizer, Clock clock) {
		JwtGenerator jwtGenerator = new JwtGenerator(new NimbusJwtEncoder(jwkSource));
		jwtGenerator.setJwtCustomizer(context -> {
			bindToDpopProof(context);
			accessTokenCustomizer.customize(context);
		});
		return new DelegatingOAuth2TokenGenerator(jwtGenerator, new PublicClientRefreshTokenGenerator(clock));
	}

	/**
	 * What Spring Authorization Server's default JWT customizer does for DPoP (RFC 9449 section 6.1), kept because
	 * {@link #tokenGenerator} replaces the default generator: an access token requested with a verified DPoP proof
	 * gets {@code cnf.jkt}, the SHA-256 thumbprint of the proof's public key. Washbase's clients send no DPoP proof
	 * today, so this changes nothing for them.
	 */
	static void bindToDpopProof(JwtEncodingContext context) {
		if (!OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
			return;
		}
		Jwt proof = context.get(OAuth2TokenContext.DPOP_PROOF_KEY);
		if (proof == null) {
			return;
		}
		String thumbprint;
		try {
			@SuppressWarnings("unchecked")
			Map<String, Object> jwk = (Map<String, Object>) proof.getHeaders().get("jwk");
			thumbprint = JWK.parse(jwk).computeThumbprint().toString();
		}
		catch (Exception ex) {
			throw new OAuth2AuthenticationException(new OAuth2Error("invalid_dpop_proof",
					"jwk header is missing or invalid.", null), ex);
		}
		// A mutable map, as Spring's: the claims are stored as JSON and read back (see AccessTokenCustomizer).
		Map<String, Object> confirmation = new HashMap<>();
		confirmation.put("jkt", thumbprint);
		context.getClaims().claim("cnf", confirmation);
	}

	/** Unused while first-party clients skip consent; kept so the JDBC tables match Spring Authorization Server. */
	@Bean
	OAuth2AuthorizationConsentService authorizationConsentService(JdbcOperations jdbcOperations,
			RegisteredClientRepository registeredClientRepository) {
		return new JdbcOAuth2AuthorizationConsentService(jdbcOperations, registeredClientRepository);
	}

	/** A paused sign-in goes to {@link #LOGIN_PAUSED_URL}; every other failure to {@link #LOGIN_FAILURE_URL}. */
	static AuthenticationFailureHandler loginFailureHandler() {
		ExceptionMappingAuthenticationFailureHandler handler = new ExceptionMappingAuthenticationFailureHandler();
		handler.setDefaultFailureUrl(LOGIN_FAILURE_URL);
		handler.setExceptionMappings(Map.of(SignInPausedException.class.getName(), LOGIN_PAUSED_URL));
		return handler;
	}

	/** The web app's origin ({@code http://localhost:3000/} for the default redirect URI). */
	static String webAppHome(AuthProperties authProperties) {
		URI redirectUri = URI.create(authProperties.webClient().redirectUri());
		return redirectUri.resolve("/").toString();
	}

}
