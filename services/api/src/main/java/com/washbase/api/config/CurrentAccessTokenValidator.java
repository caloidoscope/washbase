package com.washbase.api.config;

import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;

/**
 * Accepts an access token only while it is the current access token of an authorization that hasn't been revoked
 * (CAR-18, ADR-001 amendment). So an access token stops working at once when:
 * <ul>
 * <li>its refresh token is revoked (sign-out): Spring Authorization Server invalidates the access token too;</li>
 * <li>it is replaced by a renewal (the authorization now holds the new access token);</li>
 * <li>its authorization is removed (refresh-token reuse detection).</li>
 * </ul>
 * One indexed lookup per request ({@code oauth2_authorization.access_token_value}, hash index from
 * {@code V3__refresh_token_rotation.sql}). Failure: {@code invalid_token}, so the resource server answers
 * {@code 401}. The token value is never logged.
 */
final class CurrentAccessTokenValidator implements OAuth2TokenValidator<Jwt> {

	static final String NOT_CURRENT = "The access token is no longer active";

	private static final OAuth2TokenValidatorResult FAILURE = OAuth2TokenValidatorResult
		.failure(new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, NOT_CURRENT, null));

	private final OAuth2AuthorizationService authorizationService;

	CurrentAccessTokenValidator(OAuth2AuthorizationService authorizationService) {
		this.authorizationService = authorizationService;
	}

	@Override
	public OAuth2TokenValidatorResult validate(Jwt jwt) {
		OAuth2Authorization authorization = authorizationService.findByToken(jwt.getTokenValue(),
				OAuth2TokenType.ACCESS_TOKEN);
		if (authorization == null) {
			return FAILURE;
		}
		OAuth2Authorization.Token<OAuth2AccessToken> accessToken = authorization.getAccessToken();
		if (accessToken == null || accessToken.isInvalidated()
				|| !jwt.getTokenValue().equals(accessToken.getToken().getTokenValue())) {
			return FAILURE;
		}
		return OAuth2TokenValidatorResult.success();
	}

}
