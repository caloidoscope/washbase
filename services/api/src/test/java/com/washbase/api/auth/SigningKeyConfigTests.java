package com.washbase.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import java.security.KeyPair;
import java.security.interfaces.RSAPublicKey;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** The three signing-key cases of {@link SigningKeyConfig}, without a Spring context. */
@ExtendWith(OutputCaptureExtension.class)
class SigningKeyConfigTests {

	private static final KeyPair KEY = TestSigningKeys.rsa(2048);

	@Test
	@DisplayName("Signing key: a configured key is used, with its RFC 7638 thumbprint as a key ID stable across restarts")
	void configuredKeyUsed(CapturedOutput output) throws Exception {
		AuthProperties settings = TestSigningKeys.properties(TestSigningKeys.pkcs8Pem(KEY), false);

		RSAKey first = SigningKeyConfig.signingKey(settings);
		RSAKey afterRestart = SigningKeyConfig.signingKey(settings);

		assertThat(first.toRSAPublicKey().getModulus()).isEqualTo(((RSAPublicKey) KEY.getPublic()).getModulus());
		assertThat(first.toRSAPublicKey().getPublicExponent())
			.isEqualTo(((RSAPublicKey) KEY.getPublic()).getPublicExponent());
		assertThat(first.toRSAPrivateKey().getEncoded()).isEqualTo(KEY.getPrivate().getEncoded());
		assertThat(first.getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
		assertThat(first.getKeyUse()).isEqualTo(KeyUse.SIGNATURE);
		assertThat(first.getKeyID()).isEqualTo(first.computeThumbprint().toString())
			.isEqualTo(afterRestart.getKeyID());
		assertThat(output.getAll()).doesNotContain(SigningKeyConfig.GENERATED_KEY_WARNING);
	}

	@Test
	@DisplayName("Signing key: a configured key written on one line with literal \\n (env files) is accepted")
	void configuredKeyOnOneLine() throws Exception {
		String oneLine = TestSigningKeys.pkcs8Pem(KEY).replace("\n", "\\n");

		RSAKey key = SigningKeyConfig.signingKey(TestSigningKeys.properties(oneLine, false));

		assertThat(key.toRSAPrivateKey().getEncoded()).isEqualTo(KEY.getPrivate().getEncoded());
	}

	@Test
	@DisplayName("Signing key: generated at start-up when none is configured and generation is allowed, with one WARN and no key material")
	void generatedWhenAllowed(CapturedOutput output) throws Exception {
		RSAKey key = SigningKeyConfig.signingKey(TestSigningKeys.properties(null, true));

		assertThat(key.isPrivate()).isTrue();
		assertThat(key.size()).isGreaterThanOrEqualTo(2048);
		assertThat(key.getKeyID()).isEqualTo(key.computeThumbprint().toString());
		assertThat(output.getAll()).containsOnlyOnce(SigningKeyConfig.GENERATED_KEY_WARNING)
			.contains("WARN")
			.doesNotContain(key.getModulus().toString())
			.doesNotContain(key.getPrivateExponent().toString())
			.doesNotContain("PRIVATE KEY");
	}

	@Test
	@DisplayName("Signing key: no key and generation not allowed fails with a message naming WASHBASE_AUTH_SIGNING_KEY")
	void refusedWhenNotAllowed() {
		assertThatThrownBy(() -> SigningKeyConfig.signingKey(TestSigningKeys.properties(null, false)))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("WASHBASE_AUTH_SIGNING_KEY")
			.hasMessage(SigningKeyConfig.MISSING_KEY_MESSAGE);
		assertThatThrownBy(() -> SigningKeyConfig.signingKey(TestSigningKeys.properties("  ", false)))
			.hasMessage(SigningKeyConfig.MISSING_KEY_MESSAGE);
	}

	static Stream<Arguments> unusableKeys() {
		KeyPair small = TestSigningKeys.rsa(1024);
		KeyPair ec = TestSigningKeys.generate("EC", 256);
		String body = TestSigningKeys.base64Body(KEY);
		return Stream.of(Arguments.of("not a PEM", "secret-looking-text-" + body.substring(0, 40)),
				Arguments.of("corrupt base64",
						"-----BEGIN PRIVATE KEY-----\n" + body.substring(0, 60) + "!!\n-----END PRIVATE KEY-----"),
				Arguments.of("truncated key",
						"-----BEGIN PRIVATE KEY-----\n" + body.substring(0, 200) + "\n-----END PRIVATE KEY-----"),
				Arguments.of("PKCS#1 instead of PKCS#8",
						"-----BEGIN RSA PRIVATE KEY-----\n" + body + "\n-----END RSA PRIVATE KEY-----"),
				Arguments.of("RSA key shorter than 2048 bits", TestSigningKeys.pkcs8Pem(small)),
				Arguments.of("EC key instead of RSA", TestSigningKeys.pkcs8Pem(ec)));
	}

	@ParameterizedTest(name = "Signing key: an unusable key fails start-up without key material in the message ({0})")
	@MethodSource("unusableKeys")
	void unusableKeyRefused(String description, String pem) {
		AuthProperties settings = TestSigningKeys.properties(pem, true);

		assertThatThrownBy(() -> SigningKeyConfig.signingKey(settings)).isInstanceOf(IllegalStateException.class)
			.hasMessage(SigningKeyConfig.INVALID_KEY_MESSAGE)
			.hasNoCause();
	}

	@Test
	@DisplayName("Signing key: the settings' toString() never shows the key")
	void toStringMasksKey() {
		String pem = TestSigningKeys.pkcs8Pem(KEY);

		assertThat(TestSigningKeys.properties(pem, false).toString()).contains("signingKey=******")
			.doesNotContain(TestSigningKeys.base64Body(KEY).substring(0, 64));
		assertThat(TestSigningKeys.properties(null, true).toString()).contains("signingKey=<unset>");
	}

}
