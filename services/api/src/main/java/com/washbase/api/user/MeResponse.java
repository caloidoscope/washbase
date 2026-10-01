package com.washbase.api.user;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.UUID;

/** The signed-in user, as returned by {@code GET /api/v1/me}. */
@Schema(description = "The signed-in user.")
public record MeResponse(
		@Schema(requiredMode = RequiredMode.REQUIRED, description = "User ID (the access token's `sub`).") UUID id,
		@Schema(requiredMode = RequiredMode.REQUIRED, description = "Display name.", example = "Admin") String name,
		@Schema(requiredMode = RequiredMode.REQUIRED, types = { "string", "null" }, format = "email",
				description = "Email address (lower-case), or null if the user signs in with a mobile number only.",
				example = "admin@example.com") String email,
		@Schema(requiredMode = RequiredMode.REQUIRED, types = { "string", "null" },
				description = "Philippine mobile number in +639XXXXXXXXX form, or null if none.",
				example = "+639171234567") String mobile,
		@Schema(requiredMode = RequiredMode.REQUIRED) Role role) {
}
