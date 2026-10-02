package com.washbase.api.auth;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;

/**
 * The OAuth clients, built from configuration at start-up (read-only: {@code save} is unsupported). May be empty:
 * a deployment without {@code WASHBASE_WEB_CLIENT_SECRET} still starts, and nobody can sign in through the web app.
 *
 * <p>{@code washbase-web} (when {@link AuthProperties.WebClient#isConfigured()}):
 * <ul>
 * <li>{@code id} = {@code client_id} = {@link AuthProperties.WebClient#clientId()} (stable, because stored
 * authorizations reference it), client name {@value #WEB_CLIENT_NAME}</li>
 * <li>client secret {@code passwordEncoder.encode(secret)}; authentication method {@code client_secret_basic}
 * only</li>
 * <li>grant types {@code authorization_code} and {@code refresh_token} (CAR-18); no other grant</li>
 * <li>redirect URI {@link AuthProperties.WebClient#redirectUri()} only; scope {@code openid}</li>
 * <li>post-logout redirect URI: the web app's home ({@link AuthorizationServerConfig#webAppHome}, e.g.
 * {@code http://localhost:3000/}) only, for OIDC RP-initiated logout ({@code /connect/logout})</li>
 * <li>client settings: {@code requireProofKey(true)}, {@code requireAuthorizationConsent(false)}</li>
 * <li>token settings: access token {@link #ACCESS_TOKEN_TIME_TO_LIVE}, {@code SELF_CONTAINED} (JWT), RS256; refresh
 * token opaque, lifetime {@link AuthProperties#refreshTokenTtl()}, {@code reuseRefreshTokens(false)} (a new refresh
 * token on every use; presenting a replaced one ends the whole authorization, see
 * {@code RefreshTokenReuseDetectingAuthorizationService})</li>
 * <li>sign-out: the client revokes its refresh token at {@code /oauth2/revoke} ({@code client_secret_basic}; this
 * also invalidates the access token), then sends the browser to {@code /connect/logout} with {@code id_token_hint}
 * and the post-logout redirect URI</li>
 * </ul>
 * When the secret is unset, one WARN ({@value #WEB_CLIENT_MISSING_WARNING}) is logged. The secret is never logged.
 */
final class RegisteredClients implements RegisteredClientRepository {

	static final Duration ACCESS_TOKEN_TIME_TO_LIVE = Duration.ofMinutes(15);

	static final String WEB_CLIENT_NAME = "Washbase web app";

	static final String WEB_CLIENT_MISSING_WARNING = "No web app client is configured (WASHBASE_WEB_CLIENT_SECRET); "
			+ "sign-in on the web app is unavailable.";

	private static final Logger log = LoggerFactory.getLogger(RegisteredClients.class);

	private final List<RegisteredClient> clients;

	private RegisteredClients(List<RegisteredClient> clients) {
		this.clients = List.copyOf(clients);
	}

	static RegisteredClients fromConfiguration(AuthProperties authProperties, PasswordEncoder passwordEncoder) {
		List<RegisteredClient> clients = new ArrayList<>();
		AuthProperties.WebClient web = authProperties.webClient();
		if (web.isConfigured()) {
			clients.add(webClient(authProperties, passwordEncoder));
		}
		else {
			log.warn(WEB_CLIENT_MISSING_WARNING);
		}
		return new RegisteredClients(clients);
	}

	private static RegisteredClient webClient(AuthProperties authProperties, PasswordEncoder passwordEncoder) {
		AuthProperties.WebClient web = authProperties.webClient();
		return RegisteredClient.withId(web.clientId())
			.clientId(web.clientId())
			.clientName(WEB_CLIENT_NAME)
			.clientSecret(passwordEncoder.encode(web.secret()))
			.clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
			.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
			.authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
			.redirectUri(web.redirectUri())
			.postLogoutRedirectUri(AuthorizationServerConfig.webAppHome(authProperties))
			.scope(OidcScopes.OPENID)
			.clientSettings(ClientSettings.builder().requireProofKey(true).requireAuthorizationConsent(false).build())
			.tokenSettings(TokenSettings.builder()
				.accessTokenTimeToLive(ACCESS_TOKEN_TIME_TO_LIVE)
				.accessTokenFormat(OAuth2TokenFormat.SELF_CONTAINED)
				.idTokenSignatureAlgorithm(SignatureAlgorithm.RS256)
				.refreshTokenTimeToLive(authProperties.refreshTokenTtl())
				.reuseRefreshTokens(false)
				.build())
			.build();
	}

	@Override
	public void save(RegisteredClient registeredClient) {
		throw new UnsupportedOperationException("Registered clients come from configuration");
	}

	@Override
	public RegisteredClient findById(String id) {
		return clients.stream().filter(c -> c.getId().equals(id)).findFirst().orElse(null);
	}

	@Override
	public RegisteredClient findByClientId(String clientId) {
		return clients.stream().filter(c -> c.getClientId().equals(clientId)).findFirst().orElse(null);
	}

}
