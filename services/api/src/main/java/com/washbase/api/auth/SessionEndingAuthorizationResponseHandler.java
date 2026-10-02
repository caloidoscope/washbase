package com.washbase.api.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

/**
 * The authorization endpoint's success response (CAR-18): ends the authorization server's own browser session, then
 * redirects to the client's redirect URI with {@code code} and {@code state}, exactly as Spring Authorization
 * Server's default handler does.
 *
 * <p>The web app keeps its own session (an encrypted cookie with the tokens), so the API's session is only needed
 * for the sign-in round trip. Ending it here means nothing can silently sign someone back in after they signed out
 * or after refresh-token reuse ended their authorization: the next {@code /oauth2/authorize} asks for the password
 * again. Error responses are unchanged (the default error handler).
 */
final class SessionEndingAuthorizationResponseHandler implements AuthenticationSuccessHandler {

	private final RedirectStrategy redirectStrategy = new DefaultRedirectStrategy();

	private final SecurityContextLogoutHandler sessionEnder = new SecurityContextLogoutHandler();

	@Override
	public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
			Authentication authentication) throws IOException {
		OAuth2AuthorizationCodeRequestAuthenticationToken codeRequest = (OAuth2AuthorizationCodeRequestAuthenticationToken) authentication;
		String redirectUri = codeRequest.getRedirectUri();
		Assert.notNull(redirectUri, "redirectUri cannot be null");
		OAuth2AuthorizationCode code = codeRequest.getAuthorizationCode();
		Assert.notNull(code, "authorizationCode cannot be null");

		// Invalidates the HttpSession and clears the security context.
		sessionEnder.logout(request, response, null);

		UriComponentsBuilder uri = UriComponentsBuilder.fromUriString(redirectUri)
			.queryParam(OAuth2ParameterNames.CODE, code.getTokenValue());
		if (StringUtils.hasText(codeRequest.getState())) {
			uri.queryParam(OAuth2ParameterNames.STATE, UriUtils.encode(codeRequest.getState(), StandardCharsets.UTF_8));
		}
		// build(true): the components are already encoded.
		redirectStrategy.sendRedirect(request, response, uri.build(true).toUriString());
	}

}
