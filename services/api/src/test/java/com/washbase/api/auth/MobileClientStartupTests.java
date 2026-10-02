package com.washbase.api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * {@code washbase.auth.mobile-client.*} (CAR-20): the mobile client's redirect URIs are validated at start-up, and
 * Expo Go's {@code exp://} URIs are allowed only with {@code allow-expo-go-redirect-uris}, which only the
 * {@code local} profile sets.
 */
class MobileClientStartupTests {

	private static final String EXPO_GO_URI = "exp://192.168.1.20:8081/--/auth/callback";

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withUserConfiguration(AuthPropertiesOnly.class);

	/** With the real {@code application.properties} (and {@code application-local.properties} for {@code local}). */
	private final ApplicationContextRunner withApplicationProperties = runner
		.withInitializer(new ConfigDataApplicationContextInitializer());

	@Test
	@DisplayName("The mobile client defaults to washbase-mobile with washbase://auth/callback and no Expo Go")
	void defaults() {
		withApplicationProperties.run(context -> {
			assertThat(context).hasNotFailed();
			AuthProperties.MobileClient mobile = context.getBean(AuthProperties.class).mobileClient();
			assertThat(mobile.clientId()).isEqualTo("washbase-mobile");
			assertThat(mobile.redirectUris()).containsExactly("washbase://auth/callback");
			assertThat(mobile.allowExpoGoRedirectUris()).isFalse();
		});
	}

	@Test
	@DisplayName("WASHBASE_MOBILE_REDIRECT_URIS is a comma-separated list")
	void commaSeparatedEnvironmentVariable() {
		withApplicationProperties.withPropertyValues("washbase.auth.mobile-client.allow-expo-go-redirect-uris=true")
			.withInitializer(context -> context.getEnvironment()
				.getPropertySources()
				.replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
						new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
								Map.of("WASHBASE_MOBILE_REDIRECT_URIS", "washbase://auth/callback," + EXPO_GO_URI))))
			.run(context -> assertThat(context.getBean(AuthProperties.class).mobileClient().redirectUris())
				.containsExactly("washbase://auth/callback", EXPO_GO_URI));
	}

	@Test
	@DisplayName("An Expo Go redirect URI fails start-up unless allow-expo-go-redirect-uris is set")
	void expoGoUriNeedsTheFlag() {
		runner.withPropertyValues("washbase.auth.mobile-client.redirect-uris=washbase://auth/callback," + EXPO_GO_URI)
			.run(context -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessageContaining("Expo Go redirect URIs (exp://, exps://) are for local testing only"));
		runner.withPropertyValues("washbase.auth.mobile-client.redirect-uris=EXPS://192.168.1.20:8081/--/auth/callback")
			.run(context -> assertThat(context).hasFailed());
	}

	@Test
	@DisplayName("With allow-expo-go-redirect-uris an Expo Go redirect URI starts")
	void expoGoUriWithTheFlag() {
		runner
			.withPropertyValues("washbase.auth.mobile-client.redirect-uris=washbase://auth/callback," + EXPO_GO_URI,
					"washbase.auth.mobile-client.allow-expo-go-redirect-uris=true")
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context.getBean(AuthProperties.class).mobileClient().redirectUris()).contains(EXPO_GO_URI);
			});
	}

	@Test
	@DisplayName("Only the local profile allows Expo Go redirect URIs")
	void onlyLocalProfileAllowsExpoGo() {
		withApplicationProperties.withPropertyValues("spring.profiles.active=local")
			.run(context -> assertThat(
					context.getBean(AuthProperties.class).mobileClient().allowExpoGoRedirectUris())
				.isTrue());
		withApplicationProperties.withPropertyValues("spring.profiles.active=prod")
			.run(context -> assertThat(
					context.getBean(AuthProperties.class).mobileClient().allowExpoGoRedirectUris())
				.isFalse());
	}

	@ParameterizedTest(name = "A malformed mobile redirect URI fails start-up ({0})")
	@ValueSource(strings = { "washbase://auth/callback#fragment", "/auth/callback", "auth/callback",
			"washbase://auth/call back" })
	void malformedUriFailsStartup(String uri) {
		runner
			.withPropertyValues("washbase.auth.mobile-client.redirect-uris=" + uri,
					"washbase.auth.mobile-client.allow-expo-go-redirect-uris=true")
			.run(context -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessageContaining("redirect-uris must be absolute URIs without a fragment"));
	}

	@Test
	@DisplayName("An empty list of mobile redirect URIs fails start-up")
	void emptyListFailsStartup() {
		runner.withPropertyValues("washbase.auth.mobile-client.redirect-uris=")
			.run(context -> assertThat(context).hasFailed());
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(AuthProperties.class)
	static class AuthPropertiesOnly {

	}

}
