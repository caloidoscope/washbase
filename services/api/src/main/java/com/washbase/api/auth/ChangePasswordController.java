package com.washbase.api.auth;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * "Choose a new password" ({@code templates/change-password.html}, CAR-21): server-rendered inside the sign-in flow,
 * for web and the mobile in-app browser alike. Needs the sign-in session (not a token) and CSRF protection. On
 * success the saved authorization request is resumed, so the app still receives its code. Someone who does not have
 * to change their password is simply sent on: changing it later is another Feature. Hidden from the OpenAPI spec.
 */
@Hidden
@Controller
class ChangePasswordController {

	private final ChangePasswordService service;

	private final String webAppHome;

	private final RequestCache requestCache = new HttpSessionRequestCache();

	ChangePasswordController(ChangePasswordService service, AuthProperties authProperties) {
		this.service = service;
		this.webAppHome = AuthorizationServerConfig.webAppHome(authProperties);
	}

	@GetMapping(AuthorizationServerConfig.CHANGE_PASSWORD_PAGE)
	String page(Authentication authentication, HttpServletRequest request, HttpServletResponse response) {
		return service.mustChangePassword(authentication.getName()) ? "change-password" : resume(request, response);
	}

	@PostMapping(AuthorizationServerConfig.CHANGE_PASSWORD_PAGE)
	String change(@RequestParam(defaultValue = "") String newPassword,
			@RequestParam(defaultValue = "") String confirmPassword, Authentication authentication,
			HttpServletRequest request, HttpServletResponse response, Model model) {
		if (!service.mustChangePassword(authentication.getName())) {
			return resume(request, response);
		}
		Optional<String> error = service.change(authentication.getName(), newPassword, confirmPassword);
		if (error.isEmpty()) {
			return resume(request, response);
		}
		model.addAttribute("error", error.get());
		return "change-password";
	}

	/** Back to the authorization request that was interrupted, or to the web app when there is none. */
	private String resume(HttpServletRequest request, HttpServletResponse response) {
		SavedRequest saved = requestCache.getRequest(request, response);
		if (saved == null) {
			return "redirect:" + webAppHome;
		}
		requestCache.removeRequest(request, response);
		return "redirect:" + saved.getRedirectUrl();
	}

}
