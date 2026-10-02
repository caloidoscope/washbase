package com.washbase.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenContext;

/**
 * {@link AuthorizationServerConfig#bindToDpopProof}: what Spring Authorization Server's default JWT customizer does
 * for DPoP, kept now that the token generator is Washbase's own (CAR-20).
 */
class DpopBindingTests {

	@Test
	@DisplayName("An access token requested with a DPoP proof is bound to the proof's key (cnf.jkt)")
	void accessTokenBound() throws Exception {
		RSAKey key = new RSAKeyGenerator(2048).generate();
		JwtEncodingContext context = context(OAuth2TokenType.ACCESS_TOKEN,
				proof(key.toPublicJWK().toJSONObject()));

		AuthorizationServerConfig.bindToDpopProof(context);

		assertThat(context.getClaims().build().getClaims()).containsEntry("cnf",
				Map.of("jkt", key.computeThumbprint().toString()));
	}

	@Test
	@DisplayName("Without a DPoP proof, and for ID tokens, nothing is added")
	void nothingWithoutProof() throws Exception {
		JwtEncodingContext noProof = context(OAuth2TokenType.ACCESS_TOKEN, null);
		JwtEncodingContext idToken = context(new OAuth2TokenType("id_token"),
				proof(new RSAKeyGenerator(2048).generate().toPublicJWK().toJSONObject()));

		AuthorizationServerConfig.bindToDpopProof(noProof);
		AuthorizationServerConfig.bindToDpopProof(idToken);

		assertThat(noProof.getClaims().build().getClaims()).doesNotContainKey("cnf");
		assertThat(idToken.getClaims().build().getClaims()).doesNotContainKey("cnf");
	}

	@Test
	@DisplayName("A DPoP proof without a usable jwk header is invalid_dpop_proof")
	void invalidJwk() {
		JwtEncodingContext context = context(OAuth2TokenType.ACCESS_TOKEN, proof(Map.of("kty", "nonsense")));

		assertThatExceptionOfType(OAuth2AuthenticationException.class)
			.isThrownBy(() -> AuthorizationServerConfig.bindToDpopProof(context))
			.satisfies(ex -> assertThat(ex.getError().getErrorCode()).isEqualTo("invalid_dpop_proof"));
	}

	private static Jwt proof(Map<String, Object> jwk) {
		return Jwt.withTokenValue("a.dpop.proof")
			.header("typ", "dpop+jwt")
			.header("alg", "RS256")
			.header("jwk", jwk)
			.claim("htm", "POST")
			.issuedAt(Instant.now())
			.build();
	}

	private static JwtEncodingContext context(OAuth2TokenType tokenType, Jwt proof) {
		JwtEncodingContext.Builder builder = JwtEncodingContext
			.with(JwsHeader.with(SignatureAlgorithm.RS256), JwtClaimsSet.builder().subject("user"))
			.tokenType(tokenType);
		if (proof != null) {
			builder.put(OAuth2TokenContext.DPOP_PROOF_KEY, proof);
		}
		return builder.build();
	}

}
