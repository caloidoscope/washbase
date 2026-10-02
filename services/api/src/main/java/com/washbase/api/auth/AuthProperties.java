package com.washbase.api.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
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
 * @param refreshTokenTtl how long a refresh token lives (ISO-8601, e.g. {@code P30D}, the default). Refresh tokens
 * are rotated on every use and each new one gets the full lifetime, so a sign-in lasts until sign-out or this long
 * without use (CAR-18). Must be longer than the access token's 15 minutes. Env:
 * {@code WASHBASE_AUTH_REFRESH_TOKEN_TTL}.
 * @param mobileClient the mobile app's registered client ({@code washbase-mobile}, CAR-20).
 */
@Validated
@ConfigurationProperties("washbase.auth")
public record AuthProperties(@NotBlank @DefaultValue("http://localhost:8080") String issuer,
		@NotBlank @DefaultValue("washbase-api") String audience, String signingKey,
		@DefaultValue("false") boolean allowGeneratedSigningKey, @Valid @DefaultValue WebClient webClient,
		@NotNull @DefaultValue("P30D") Duration refreshTokenTtl, @Valid @DefaultValue MobileClient mobileClient) {

	/** @return whether {@link #refreshTokenTtl} outlives an access token (anything shorter would end every session) */
	@AssertTrue(message = "washbase.auth.refresh-token-ttl must be longer than the access token lifetime (15 minutes)")
	boolean isRefreshTokenTtlLongerThanAccessToken() {
		return refreshTokenTtl == null || refreshTokenTtl.compareTo(RegisteredClients.ACCESS_TOKEN_TIME_TO_LIVE) > 0;
	}

	/** @return whether a signing key is configured */
	public boolean hasSigningKey() {
		return StringUtils.hasText(signingKey);
	}

	/** Masks the signing key so it can never reach a log line, an exception message or a debugger view. */
	@Override
	public String toString() {
		return "AuthProperties[issuer=" + issuer + ", audience=" + audience + ", signingKey="
				+ (hasSigningKey() ? "******" : "<unset>") + ", allowGeneratedSigningKey=" + allowGeneratedSigningKey
				+ ", webClient=" + webClient + ", refreshTokenTtl=" + refreshTokenTtl + ", mobileClient=" + mobileClient
				+ "]";
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

	/**
	 * The mobile app (Expo): a <em>public</em> client using Authorization Code + PKCE, with no secret (RFC 8252;
	 * ADR-001 Amendment 2, CAR-20). Always registered: it has nothing secret to configure.
	 *
	 * <p>Redirect URIs are matched exactly (Spring Authorization Server compares the full string for non-loopback
	 * hosts), so no pattern or wildcard is ever accepted and the list can't become an open redirect. Two kinds:
	 * <ul>
	 * <li>the app's own scheme, {@code washbase://auth/callback} (development and production builds; the scheme is
	 * {@code app.json}'s {@code scheme});</li>
	 * <li>Expo Go's form {@code exp://<LAN-IP>:8081/--/auth/callback}, only for testing on a phone with Expo Go.
	 * Allowed only while {@link #allowExpoGoRedirectUris} is {@code true}, which only the {@code local} profile sets;
	 * anywhere else such a URI stops start-up ({@link #isExpoGoRedirectUriAllowed()}).</li>
	 * </ul>
	 *
	 * @param clientId the OAuth {@code client_id}.
	 * @param redirectUris the allowed {@code redirect_uri}s, each matched exactly: absolute, no fragment. Env:
	 * {@code WASHBASE_MOBILE_REDIRECT_URIS} (comma-separated; default {@code washbase://auth/callback}).
	 * {@code pnpm dev:all --lan} adds the Expo Go URI for this PC's LAN address.
	 * @param allowExpoGoRedirectUris whether {@code exp://} / {@code exps://} redirect URIs may be registered.
	 * Default {@code false}; {@code true} only in {@code application-local.properties} (profile {@code local}, i.e.
	 * {@code pnpm dev:all}) and in tests that need it. Deliberately has no {@code ${...}} placeholder in
	 * {@code application.properties}, so no documented setting turns it on. (Spring's relaxed binding would still
	 * accept {@code WASHBASE_AUTH_MOBILECLIENT_ALLOWEXPOGOREDIRECTURIS}, but someone would have to set that and an
	 * {@code exp://} redirect URI on purpose.)
	 */
	public record MobileClient(@NotBlank @DefaultValue("washbase-mobile") String clientId,
			@NotEmpty @DefaultValue("washbase://auth/callback") List<@NotBlank String> redirectUris,
			@DefaultValue("false") boolean allowExpoGoRedirectUris) {

		/** Expo Go's URI schemes: {@code exp://} (and {@code exps://} over TLS). */
		static final List<String> EXPO_GO_SCHEMES = List.of("exp", "exps");

		/** The production defaults: {@code washbase-mobile}, {@code washbase://auth/callback}, no Expo Go. */
		static MobileClient defaults() {
			return new MobileClient("washbase-mobile", List.of("washbase://auth/callback"), false);
		}

		/** @return whether every redirect URI is absolute and has no fragment (RFC 6749 section 3.1.2) */
		@AssertTrue(message = "washbase.auth.mobile-client.redirect-uris must be absolute URIs without a fragment")
		boolean isEveryRedirectUriWellFormed() {
			return redirectUris == null || redirectUris.stream().allMatch(MobileClient::isWellFormed);
		}

		/** @return whether there is no Expo Go redirect URI, or {@link #allowExpoGoRedirectUris} allows them */
		@AssertTrue(message = "Expo Go redirect URIs (exp://, exps://) are for local testing only: "
				+ "washbase.auth.mobile-client.allow-expo-go-redirect-uris is false")
		boolean isExpoGoRedirectUriAllowed() {
			return allowExpoGoRedirectUris || redirectUris == null
					|| redirectUris.stream().noneMatch(MobileClient::isExpoGoUri);
		}

		private static boolean isWellFormed(String uri) {
			if (uri == null) {
				return true; // reported by @NotBlank
			}
			try {
				URI parsed = new URI(uri);
				return parsed.isAbsolute() && parsed.getRawFragment() == null;
			}
			catch (URISyntaxException ex) {
				return false;
			}
		}

		static boolean isExpoGoUri(String uri) {
			int colon = uri == null ? -1 : uri.indexOf(':');
			return colon > 0 && EXPO_GO_SCHEMES.contains(uri.substring(0, colon).toLowerCase(Locale.ROOT));
		}

	}

}
