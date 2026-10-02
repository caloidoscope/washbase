package com.washbase.api.auth;

import com.washbase.api.auth.PublicClientTokenAuthenticationConverter.Endpoint;
import org.jspecify.annotations.Nullable;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

/**
 * Authenticates a public client by its {@code client_id} alone for a refresh-token renewal or a revocation (CAR-20,
 * ADR-001 Amendment 2), the requests marked by {@link PublicClientTokenAuthenticationConverter}. Spring's
 * {@code PublicClientAuthenticationProvider} would demand a {@code code_verifier}, which only the code exchange has.
 *
 * <p>Why this is safe: a public client has no secret to prove anyway (RFC 6749 section 2.1; RFC 7009 section 2.1
 * allows public clients to revoke). What protects the refresh token is rotation with reuse detection, and Spring's
 * refresh and revocation providers still check that the token's authorization belongs to <em>this</em> client, so a
 * {@code washbase-web} refresh token presented as {@code washbase-mobile} is refused ({@code invalid_grant}) and
 * revoking another client's token does nothing.
 *
 * <p>Contract:
 * <ul>
 * <li>returns {@code null} (not mine) unless the token's method is {@code none} and it carries the converter's
 * marker;</li>
 * <li>unknown {@code client_id} → {@code invalid_client};</li>
 * <li>the registered client must have authentication method {@code none} (so {@code washbase-web} can never be
 * authenticated this way: {@code invalid_client}), and, for the token endpoint, the {@code refresh_token} grant;</li>
 * <li>success: {@code new OAuth2ClientAuthenticationToken(registeredClient, NONE, null)}.</li>
 * </ul>
 * Registered first in the client authentication providers. Never logs a token.
 */
final class PublicClientTokenAuthenticationProvider implements AuthenticationProvider {

	private static final String ERROR_URI = "https://datatracker.ietf.org/doc/html/rfc6749#section-3.2.1";

	private final RegisteredClientRepository registeredClients;

	PublicClientTokenAuthenticationProvider(RegisteredClientRepository registeredClients) {
		this.registeredClients = registeredClients;
	}

	@Override
	public @Nullable Authentication authenticate(Authentication authentication) throws AuthenticationException {
		OAuth2ClientAuthenticationToken clientAuthentication = (OAuth2ClientAuthenticationToken) authentication;
		if (!ClientAuthenticationMethod.NONE.equals(clientAuthentication.getClientAuthenticationMethod())
				|| !(clientAuthentication.getAdditionalParameters()
					.get(PublicClientTokenAuthenticationConverter.ENDPOINT_PARAMETER) instanceof Endpoint endpoint)) {
			return null;
		}
		String clientId = String.valueOf(clientAuthentication.getPrincipal());
		RegisteredClient client = registeredClients.findByClientId(clientId);
		if (client == null) {
			throw invalidClient("client_id");
		}
		if (!client.getClientAuthenticationMethods().contains(ClientAuthenticationMethod.NONE)) {
			throw invalidClient("authentication_method");
		}
		if (endpoint == Endpoint.TOKEN
				&& !client.getAuthorizationGrantTypes().contains(AuthorizationGrantType.REFRESH_TOKEN)) {
			throw invalidClient("grant_type");
		}
		return new OAuth2ClientAuthenticationToken(client, ClientAuthenticationMethod.NONE, null);
	}

	@Override
	public boolean supports(Class<?> authentication) {
		return OAuth2ClientAuthenticationToken.class.isAssignableFrom(authentication);
	}

	/** As Spring's own client authentication providers word it ({@code what} is never a secret or token). */
	private static OAuth2AuthenticationException invalidClient(String what) {
		return new OAuth2AuthenticationException(
				new OAuth2Error(OAuth2ErrorCodes.INVALID_CLIENT, "Client authentication failed: " + what, ERROR_URI));
	}

}
