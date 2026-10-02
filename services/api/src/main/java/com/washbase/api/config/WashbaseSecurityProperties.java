package com.washbase.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param publicApiDocs whether {@code /v3/api-docs} and Swagger UI are public. Default {@code true}: local runs,
 * {@code pnpm api:client} and the CI {@code contract} job fetch the spec without a token. Set to {@code false}
 * only in production ({@code WASHBASE_PUBLIC_API_DOCS=false}).
 */
@ConfigurationProperties("washbase.security")
record WashbaseSecurityProperties(@DefaultValue("true") boolean publicApiDocs) {
}
