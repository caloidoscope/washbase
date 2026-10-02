package com.washbase.api.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;

/**
 * Settings shared by the embedded authorization server and the resource server (ADR-001).
 *
 * @param issuer the issuer URL ({@code iss} claim, OIDC discovery). Configuration so the authorization server can
 * move to its own service later without changing the apps. Env: {@code WASHBASE_AUTH_ISSUER}.
 * @param audience the {@code aud} claim of access tokens for this API.
 * @param signingKey the RSA private key that signs tokens, PKCS#8 PEM ({@code -----BEGIN PRIVATE KEY-----}). A
 * deployment secret, never in the repo and never logged (see {@link #toString()}). Env:
 * {@code WASHBASE_AUTH_SIGNING_KEY}.
 * @param allowGeneratedSigningKey whether a missing {@link #signingKey} may be replaced by a key generated at
 * start-up (every restart then signs everyone out). Default {@code false}, so a real deployment without a key fails
 * fast. {@code true} only for local runs, tests and CI ({@code scripts/api.mjs}, profile {@code local}, test
 * config). Env: {@code WASHBASE_AUTH_ALLOW_GENERATED_SIGNING_KEY}.
 * @param webClient the web app's registered client ({@code washbase-web}).
 */
@Validated
@ConfigurationProperties("washbase.auth")
public record AuthProperties(@NotBlank @DefaultValue("http://localhost:8080") String issuer,
		@NotBlank @DefaultValue("washbase-api") String audience, String signingKey,
		@DefaultValue("false") boolean allowGeneratedSigningKey, @Valid @DefaultValue WebClient webClient) {

	/** @return whether a signing key is configured */
	public boolean hasSigningKey() {
		return StringUtils.hasText(signingKey);
	}

	/** Masks the signing key so it can never reach a log line, an exception message or a debugger view. */
	@Override
	public String toString() {
		return "AuthProperties[issuer=" + issuer + ", audience=" + audience + ", signingKey="
				+ (hasSigningKey() ? "******" : "<unset>") + ", allowGeneratedSigningKey=" + allowGeneratedSigningKey
				+ ", webClient=" + webClient + "]";
	}

	/**
	 * The web app (Next.js backend-for-frontend): a confidential client using Authorization Code + PKCE.
	 *
	 * @param clientId the OAuth {@code client_id}.
	 * @param secret the client secret, sent with {@code client_secret_basic}. A deployment secret with no default:
	 * when unset the client isn't registered and a warning is logged. Never logged (see {@link #toString()}). Env:
	 * {@code WASHBASE_WEB_CLIENT_SECRET}.
	 * @param redirectUri the only allowed {@code redirect_uri}, matched exactly. Env:
	 * {@code WASHBASE_WEB_REDIRECT_URI}.
	 */
	public record WebClient(@NotBlank @DefaultValue("washbase-web") String clientId, String secret,
			@NotBlank @DefaultValue("http://localhost:3000/auth/callback") String redirectUri) {

		/** @return whether the client can be registered (a secret is configured) */
		public boolean isConfigured() {
			return StringUtils.hasText(secret);
		}

		/** Masks the secret so it can never reach a log line, an exception message or a debugger view. */
		@Override
		public String toString() {
			return "WebClient[clientId=" + clientId + ", secret=" + (isConfigured() ? "******" : "<unset>")
					+ ", redirectUri=" + redirectUri + "]";
		}

	}

}
