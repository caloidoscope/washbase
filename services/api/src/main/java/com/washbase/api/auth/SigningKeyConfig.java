package com.washbase.api.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The RS256 key that signs access tokens; the same {@link JWKSource} backs the JWKS endpoint and the resource
 * server's in-process {@code JwtDecoder}.
 *
 * <ul>
 * <li>{@link AuthProperties#hasSigningKey()}: the configured PKCS#8 PEM (RSA, at least {@value #MIN_KEY_BITS} bits)
 * is used. The {@code kid} is the key's RFC 7638 thumbprint, so it stays the same across restarts and tokens stay
 * valid. A malformed key fails start-up with a message that contains no key material.</li>
 * <li>No key, {@link AuthProperties#allowGeneratedSigningKey()}: a key is generated at start-up (local runs, tests,
 * CI) and one WARN is logged, without key material. Every restart signs everyone out.</li>
 * <li>No key, generation not allowed (every real deployment): start-up fails naming
 * {@code WASHBASE_AUTH_SIGNING_KEY}.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthProperties.class)
class SigningKeyConfig {

	static final int MIN_KEY_BITS = 2048;

	static final String GENERATED_KEY_WARNING = "No signing key configured (WASHBASE_AUTH_SIGNING_KEY); using a key "
			+ "generated at start-up. Restarting signs everyone out.";

	static final String MISSING_KEY_MESSAGE = "No signing key configured: set WASHBASE_AUTH_SIGNING_KEY to an RSA "
			+ "private key (PKCS#8 PEM, at least " + MIN_KEY_BITS + " bits). Generating a key at start-up is only "
			+ "allowed for local runs and tests (WASHBASE_AUTH_ALLOW_GENERATED_SIGNING_KEY=true).";

	static final String INVALID_KEY_MESSAGE = "WASHBASE_AUTH_SIGNING_KEY is not a usable RSA private key: expected "
			+ "PKCS#8 PEM (-----BEGIN PRIVATE KEY-----) of at least " + MIN_KEY_BITS + " bits.";

	private static final Logger log = LoggerFactory.getLogger(SigningKeyConfig.class);

	private static final String PEM_BEGIN = "-----BEGIN PRIVATE KEY-----";

	private static final String PEM_END = "-----END PRIVATE KEY-----";

	@Bean
	JWKSource<SecurityContext> jwkSource(AuthProperties authProperties) {
		return new ImmutableJWKSet<>(new JWKSet(signingKey(authProperties)));
	}

	/** The signing key for these settings (see the class comment for the three cases). */
	static RSAKey signingKey(AuthProperties authProperties) {
		if (authProperties.hasSigningKey()) {
			return toJwk(parsePkcs8Pem(authProperties.signingKey()));
		}
		if (!authProperties.allowGeneratedSigningKey()) {
			throw new IllegalStateException(MISSING_KEY_MESSAGE);
		}
		log.warn(GENERATED_KEY_WARNING);
		return toJwk(generateRsaKey());
	}

	/**
	 * Parses a PKCS#8 PEM RSA private key and derives its public key from the CRT parameters. Every failure has the
	 * same message and no cause attached, so no part of the key can reach a log.
	 */
	static KeyPair parsePkcs8Pem(String pem) {
		// Env files often can't hold line breaks: accept a literal "\n" as one.
		String text = pem.replace("\\n", "\n").strip();
		if (!text.startsWith(PEM_BEGIN) || !text.endsWith(PEM_END)) {
			throw new IllegalStateException(INVALID_KEY_MESSAGE);
		}
		RSAPrivateCrtKey privateKey;
		RSAPublicKey publicKey;
		try {
			String base64 = text.substring(PEM_BEGIN.length(), text.length() - PEM_END.length())
				.replaceAll("\\s", "");
			KeyFactory keyFactory = KeyFactory.getInstance("RSA");
			PrivateKey key = keyFactory.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
			if (!(key instanceof RSAPrivateCrtKey crtKey)) {
				throw new IllegalStateException(INVALID_KEY_MESSAGE);
			}
			privateKey = crtKey;
			publicKey = (RSAPublicKey) keyFactory
				.generatePublic(new RSAPublicKeySpec(crtKey.getModulus(), crtKey.getPublicExponent()));
		}
		catch (GeneralSecurityException | IllegalArgumentException | ClassCastException ex) {
			// Deliberately without the cause: its message could quote the input.
			throw new IllegalStateException(INVALID_KEY_MESSAGE);
		}
		if (privateKey.getModulus().bitLength() < MIN_KEY_BITS) {
			throw new IllegalStateException(INVALID_KEY_MESSAGE);
		}
		return new KeyPair(publicKey, privateKey);
	}

	private static RSAKey toJwk(KeyPair keyPair) {
		try {
			return new RSAKey.Builder((RSAPublicKey) keyPair.getPublic()).privateKey(keyPair.getPrivate())
				.keyUse(KeyUse.SIGNATURE)
				.algorithm(JWSAlgorithm.RS256)
				// RFC 7638 thumbprint: the same key always gets the same kid, across restarts and instances.
				.keyIDFromThumbprint()
				.build();
		}
		catch (JOSEException ex) {
			throw new IllegalStateException("Could not compute the signing key's ID");
		}
	}

	private static KeyPair generateRsaKey() {
		try {
			KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
			generator.initialize(MIN_KEY_BITS);
			return generator.generateKeyPair();
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("Could not generate a signing key", ex);
		}
	}

}
