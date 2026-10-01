package com.washbase.api.user;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
@Tag(name = "Me", description = "The signed-in user")
class MeController {

	private final MeService meService;

	MeController(MeService meService) {
		this.meService = meService;
	}

	@GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
	@PreAuthorize("hasAnyRole('CLIENT','STAFF','OWNER','ADMIN')")
	@Operation(operationId = "getMe", summary = "Who am I",
			description = "Returns the signed-in user, identified by the access token's `sub`. "
					+ "Roles: CLIENT, STAFF, OWNER, ADMIN.")
	@ApiResponse(responseCode = "200", description = "The signed-in user.")
	@ApiResponse(responseCode = "401",
			description = "No valid access token (missing, expired, wrong issuer/audience/signature), "
					+ "or the token's user no longer exists or has been deactivated.",
			content = @Content)
	@ApiResponse(responseCode = "403", description = "The token carries none of the allowed roles.",
			content = @Content)
	MeResponse getMe(@AuthenticationPrincipal Jwt jwt) {
		return meService.getMe(jwt.getSubject());
	}

}
