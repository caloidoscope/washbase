package com.washbase.api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;

class AccessTokenCustomizerTests {

	private final AccessTokenCustomizer customizer = new AccessTokenCustomizer(
			TestSigningKeys.properties(null, true));

	@Test
	@DisplayName("Access tokens get roles [ADMIN] and aud [washbase-api]")
	void accessTokenClaims() {
		JwtClaimsSet.Builder claims = defaultClaims();

		customizer.customize(context(OAuth2TokenType.ACCESS_TOKEN, claims, "ROLE_ADMIN"));

		JwtClaimsSet built = claims.build();
		assertThat(built.getAudience()).containsExactly("washbase-api");
		assertThat(built.<List<String>>getClaim("roles")).containsExactly("ADMIN");
		assertThat(built.getSubject()).isEqualTo("3f1c0e9e-0000-0000-0000-000000000001");
	}

	@Test
	@DisplayName("Only ROLE_ authorities become roles, without the prefix")
	void onlyRoleAuthorities() {
		JwtClaimsSet.Builder claims = defaultClaims();

		customizer.customize(context(OAuth2TokenType.ACCESS_TOKEN, claims, "FACTOR_PASSWORD", "ROLE_STAFF"));

		assertThat(claims.build().<List<String>>getClaim("roles")).containsExactly("STAFF");
	}

	@Test
	@DisplayName("ID tokens keep aud = client ID and get no roles")
	void idTokenUnchanged() {
		JwtClaimsSet.Builder claims = defaultClaims();

		customizer.customize(context(new OAuth2TokenType("id_token"), claims, "ROLE_ADMIN"));

		JwtClaimsSet built = claims.build();
		assertThat(built.getAudience()).containsExactly("washbase-web");
		assertThat(built.hasClaim("roles")).isFalse();
	}

	private static JwtClaimsSet.Builder defaultClaims() {
		return JwtClaimsSet.builder()
			.subject("3f1c0e9e-0000-0000-0000-000000000001")
			.audience(List.of("washbase-web"));
	}

	private static JwtEncodingContext context(OAuth2TokenType type, JwtClaimsSet.Builder claims,
			String... authorities) {
		var principal = UsernamePasswordAuthenticationToken.authenticated("3f1c0e9e-0000-0000-0000-000000000001",
				null, AuthorityUtils.createAuthorityList(authorities));
		return JwtEncodingContext.with(JwsHeader.with(SignatureAlgorithm.RS256), claims)
			.principal(principal)
			.tokenType(type)
			.build();
	}

}
