package com.washbase.api.auth;

import org.springframework.security.core.AuthenticationException;

/**
 * Sign-in for the typed identifier is paused (CAR-19). Thrown by {@link PausingAuthenticationProvider} before the
 * password is checked; the login chain's failure handler sends the browser to
 * {@link AuthorizationServerConfig#LOGIN_PAUSED_URL}. Same for known and unknown identifiers. The message is fixed:
 * never include the typed identifier.
 */
class SignInPausedException extends AuthenticationException {

	SignInPausedException() {
		super("Sign-in is paused for this identifier");
	}

}
