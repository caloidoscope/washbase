package com.washbase.api.auth;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Settings shared by the embedded authorization server and the resource server (ADR-001).
 *
 * @param issuer the issuer URL ({@code iss} claim, OIDC discovery). Configuration so the authorization server can
 * move to its own service later without changing the apps. Env: {@code WASHBASE_AUTH_ISSUER}.
 * @param audience the {@code aud} claim of access tokens for this API.
 */
@Validated
@ConfigurationProperties("washbase.auth")
public record AuthProperties(@NotBlank @DefaultValue("http://localhost:8080") String issuer,
		@NotBlank @DefaultValue("washbase-api") String audience) {
}
