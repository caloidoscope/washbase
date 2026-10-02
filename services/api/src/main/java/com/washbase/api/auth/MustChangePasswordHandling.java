package com.washbase.api.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Sends people who must choose a new password to the change-password page (CAR-21): after sign-in
 * ({@link #afterSignIn}), and whenever an authorization request arrives from such a person
 * ({@link #authorizationRequestFilter}), so they can't reach any app without choosing one. The interrupted
 * authorization request stays saved in the session and is resumed after the change.
 */
final class MustChangePasswordHandling {

	private MustChangePasswordHandling() {
	}

	/** The normal success handler (resume the saved request, else {@code defaultUrl}), after the change page if due. */
	static AuthenticationSuccessHandler afterSignIn(ChangePasswordService service, String defaultUrl) {
		SavedRequestAwareAuthenticationSuccessHandler normal = new SavedRequestAwareAuthenticationSuccessHandler();
		normal.setDefaultTargetUrl(defaultUrl);
		return (request, response, authentication) -> {
			if (service.mustChangePassword(authentication.getName())) {
				response.sendRedirect(request.getContextPath() + AuthorizationServerConfig.CHANGE_PASSWORD_PAGE);
			}
			else {
				normal.onAuthenticationSuccess(request, response, authentication);
			}
		};
	}

	static OncePerRequestFilter authorizationRequestFilter(ChangePasswordService service) {
		RequestMatcher authorize = PathPatternRequestMatcher.withDefaults().matcher("/oauth2/authorize");
		RequestCache requestCache = new HttpSessionRequestCache();
		return new OncePerRequestFilter() {
			@Override
			protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
					FilterChain chain) throws ServletException, IOException {
				Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
				if (authorize.matches(request) && authentication != null && authentication.isAuthenticated()
						&& !(authentication instanceof AnonymousAuthenticationToken)
						&& service.mustChangePassword(authentication.getName())) {
					requestCache.saveRequest(request, response);
					response.sendRedirect(request.getContextPath() + AuthorizationServerConfig.CHANGE_PASSWORD_PAGE);
					return;
				}
				chain.doFilter(request, response);
			}
		};
	}

}
