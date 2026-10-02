package com.washbase.api.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Every operation requires {@value #BEARER_AUTH} by default (ADR-001). A public endpoint opts out with an empty
 * {@code @SecurityRequirements} on the operation, and must also be on the public allowlist in {@link SecurityConfig}.
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

	static final String BEARER_AUTH = "bearerAuth";

	@Bean
	OpenAPI washbaseOpenApi() {
		return new OpenAPI().info(new Info().title("Washbase API").version("v1"))
			.components(new Components().addSecuritySchemes(BEARER_AUTH,
					new SecurityScheme().type(SecurityScheme.Type.HTTP)
						.scheme("bearer")
						.bearerFormat("JWT")
						.description("Access token (JWT) from the Washbase authorization server.")))
			.addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH));
	}

}
