package com.washbase.api.user;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A user's single role (ADR-001). Roles are independent, not a hierarchy: OWNER does not imply STAFF. Travels in
 * the access token's {@code roles} claim and maps to the {@code ROLE_<name>} authority.
 */
@Schema(description = "The user's role. Each user has exactly one.")
public enum Role {

	CLIENT, STAFF, OWNER, ADMIN

}
