package com.washbase.api.auth;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The sign-in page ({@code templates/login.html}), server-rendered by the authorization server. Public; the form
 * posts to {@code /login}, handled by Spring Security's form login (see {@link AuthorizationServerConfig}). Not part
 * of the REST contract, so hidden from the OpenAPI spec.
 *
 * <p>{@code /login?error} shows the one generic failure message; {@code /login?paused} shows
 * {@link #pausedMessage} (CAR-19), worded from the configured pause, not the time left, so it reveals nothing about
 * when the failures happened.
 */
@Hidden
@Controller
class LoginController {

	private final SignInLimitProperties limits;

	LoginController(SignInLimitProperties limits) {
		this.limits = limits;
	}

	@GetMapping(AuthorizationServerConfig.LOGIN_PAGE)
	String login(Model model) {
		model.addAttribute("pausedMessage", pausedMessage(limits));
		return "login";
	}

	/** @return e.g. "Too many attempts. Try again in 15 minutes." (CAR-19's exact wording for the default) */
	static String pausedMessage(SignInLimitProperties limits) {
		long minutes = limits.pauseMinutes();
		return "Too many attempts. Try again in " + minutes + (minutes == 1 ? " minute." : " minutes.");
	}

}
