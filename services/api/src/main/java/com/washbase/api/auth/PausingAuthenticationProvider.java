package com.washbase.api.auth;

import java.util.Optional;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;

/**
 * The sign-in form's only authentication provider (CAR-19): wraps the username/password provider
 * ({@code DaoAuthenticationProvider} with {@link UserAccountDetailsService} and the {@code PasswordEncoder}) and
 * pauses sign-in per identifier ({@link SignInAttempts}).
 *
 * <p>For {@code authentication.getName()} (the typed identifier), with {@code key = SignInAttempts.identifierKey}:
 * <ol>
 * <li>No key (blank): delegate unchanged.</li>
 * <li>{@code attempts.isPaused(key)}: throw {@link SignInPausedException} without calling the delegate (no password
 * check, nothing recorded, the pause isn't extended).</li>
 * <li>Delegate. On success: {@code recordSuccess(key)}, return the result. On {@link BadCredentialsException} (wrong
 * password, unknown, inactive or password-less account: the delegate already makes these look the same):
 * {@code recordFailure(key)} and rethrow the <em>same</em> exception, so the attempt that reaches the limit still
 * shows the usual "Incorrect ..." message and only the next one shows the pause. Other exceptions: rethrow,
 * nothing recorded.</li>
 * </ol>
 *
 * <p>Wired in {@link AuthorizationServerConfig#loginSecurityFilterChain} as the login chain's whole
 * {@code AuthenticationManager} ({@code new ProviderManager(this)}), not added next to the global one: a
 * {@code ProviderManager} retries a failed attempt on its parent, which would check the password twice and bypass
 * the pause.
 */
class PausingAuthenticationProvider implements AuthenticationProvider {

	private final AuthenticationProvider delegate;

	private final SignInAttempts attempts;

	PausingAuthenticationProvider(AuthenticationProvider delegate, SignInAttempts attempts) {
		this.delegate = delegate;
		this.attempts = attempts;
	}

	@Override
	public Authentication authenticate(Authentication authentication) throws AuthenticationException {
		Optional<String> key = SignInAttempts.identifierKey(authentication.getName());
		if (key.isEmpty()) {
			return delegate.authenticate(authentication);
		}
		if (attempts.isPaused(key.get())) {
			throw new SignInPausedException();
		}
		Authentication result;
		try {
			result = delegate.authenticate(authentication);
		}
		catch (BadCredentialsException ex) {
			attempts.recordFailure(key.get());
			throw ex;
		}
		if (result != null && result.isAuthenticated()) {
			attempts.recordSuccess(key.get());
		}
		return result;
	}

	@Override
	public boolean supports(Class<?> authentication) {
		return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
	}

}
