package com.washbase.api.auth;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.stereotype.Component;

/**
 * Adds the Washbase claims to access tokens (ADR-001). Spring Authorization Server picks this bean up by type.
 *
 * <p>Only for access tokens (ID tokens keep {@code aud} = client ID):
 * <ul>
 * <li>{@code roles}: a list with the principal's single role, taken from its {@code ROLE_*} authority without the
 * prefix (e.g. {@code ["ADMIN"]}).</li>
 * <li>{@code aud}: {@code [AuthProperties.audience()]} ({@code ["washbase-api"]}), replacing the default client ID,
 * so the resource server's audience check passes.</li>
 * </ul>
 * {@code iss}, {@code sub} (user ID), {@code exp}, {@code iat} and {@code scope} are set by Spring Authorization
 * Server.
 */
@Component
class AccessTokenCustomizer implements OAuth2TokenCustomizer<JwtEncodingContext> {

	static final String ROLES_CLAIM = "roles";

	private static final String ROLE_PREFIX = "ROLE_";

	private final AuthProperties authProperties;

	AccessTokenCustomizer(AuthProperties authProperties) {
		this.authProperties = authProperties;
	}

	@Override
	public void customize(JwtEncodingContext context) {
		if (!OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
			return;
		}
		// Mutable lists on purpose: the claims are stored as JSON in oauth2_authorization, and the immutable
		// List.of/toList() types are not on Spring Security's Jackson allowlist, so the stored authorization
		// couldn't be read back (code reuse check, UserInfo).
		List<String> roles = context.getPrincipal()
			.getAuthorities()
			.stream()
			.map(GrantedAuthority::getAuthority)
			.filter(authority -> authority != null && authority.startsWith(ROLE_PREFIX))
			.map(authority -> authority.substring(ROLE_PREFIX.length()))
			.collect(Collectors.toCollection(ArrayList::new));
		context.getClaims()
			.audience(new ArrayList<>(List.of(authProperties.audience())))
			.claim(ROLES_CLAIM, roles);
	}

}
