package com.washbase.api.auth;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

/** Signing keys generated for one test run, as PKCS#8 PEM (what {@code WASHBASE_AUTH_SIGNING_KEY} holds). */
final class TestSigningKeys {

	private TestSigningKeys() {
	}

	static KeyPair rsa(int bits) {
		return generate("RSA", bits);
	}

	static KeyPair generate(String algorithm, int size) {
		try {
			KeyPairGenerator generator = KeyPairGenerator.getInstance(algorithm);
			generator.initialize(size);
			return generator.generateKeyPair();
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException(ex);
		}
	}

	/** The key's body, base64 with line breaks every 64 characters, as {@code openssl genpkey} writes it. */
	static String base64Body(KeyPair keyPair) {
		return Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(keyPair.getPrivate().getEncoded());
	}

	static String pkcs8Pem(KeyPair keyPair) {
		return "-----BEGIN PRIVATE KEY-----\n" + base64Body(keyPair) + "\n-----END PRIVATE KEY-----\n";
	}

	static AuthProperties properties(String signingKey, boolean allowGenerated) {
		return new AuthProperties("http://localhost:8080", "washbase-api", signingKey, allowGenerated,
				new AuthProperties.WebClient("washbase-web", null, "http://localhost:3000/auth/callback"));
	}

}
