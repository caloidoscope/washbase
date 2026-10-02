package com.washbase.api.auth;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The sign-in page ({@code templates/login.html}), server-rendered by the authorization server. Public; the form
 * posts to {@code /login}, handled by Spring Security's form login (see {@link AuthorizationServerConfig}). Not part
 * of the REST contract, so hidden from the OpenAPI spec.
 */
@Hidden
@Controller
class LoginController {

	@GetMapping(AuthorizationServerConfig.LOGIN_PAGE)
	String login() {
		return "login";
	}

}
