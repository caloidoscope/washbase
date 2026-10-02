package com.washbase.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** {@link PausingAuthenticationProvider} (CAR-19): when it checks the password, and what it records. */
@ExtendWith(MockitoExtension.class)
class PausingAuthenticationProviderTests {

	private static final String KEY = "email:admin@example.com";

	@Mock
	private AuthenticationProvider delegate;

	@Mock
	private SignInAttempts attempts;

	private PausingAuthenticationProvider provider;

	@BeforeEach
	void setUp() {
		provider = new PausingAuthenticationProvider(delegate, attempts);
	}

	@Test
	@DisplayName("While paused, the password isn't checked and nothing is recorded")
	void pausedSkipsPasswordCheck() {
		given(attempts.isPaused(KEY)).willReturn(true);

		assertThatExceptionOfType(SignInPausedException.class)
			.isThrownBy(() -> provider.authenticate(attempt(" Admin@Example.com ", "Start-Here-2026")))
			.satisfies(ex -> assertThat(ex.getMessage()).doesNotContain("admin").doesNotContain("Start-Here"));
		then(delegate).shouldHaveNoInteractions();
		then(attempts).should().isPaused(KEY);
		then(attempts).shouldHaveNoMoreInteractions();
	}

	@Test
	@DisplayName("A wrong password is recorded as a failure and the same exception is rethrown")
	void wrongPasswordRecordedAndRethrown() {
		BadCredentialsException wrong = new BadCredentialsException("Bad credentials");
		given(delegate.authenticate(any())).willThrow(wrong);

		assertThatExceptionOfType(BadCredentialsException.class)
			.isThrownBy(() -> provider.authenticate(attempt("admin@example.com", "wrong")))
			.isSameAs(wrong);
		then(attempts).should().recordFailure(KEY);
		then(attempts).should(org.mockito.Mockito.never()).recordSuccess(any());
	}

	@Test
	@DisplayName("A successful sign-in clears the count and returns the delegate's result")
	void successClearsCount() {
		Authentication signedIn = UsernamePasswordAuthenticationToken.authenticated("user-id", null,
				List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
		given(delegate.authenticate(any())).willReturn(signedIn);

		assertThat(provider.authenticate(attempt("admin@example.com", "Start-Here-2026"))).isSameAs(signedIn);
		then(attempts).should().recordSuccess(KEY);
		then(attempts).should(org.mockito.Mockito.never()).recordFailure(any());
	}

	@Test
	@DisplayName("Other failures (e.g. the database is down) are rethrown and not recorded")
	void otherExceptionsNotRecorded() {
		InternalAuthenticationServiceException down = new InternalAuthenticationServiceException("down");
		given(delegate.authenticate(any())).willThrow(down);

		assertThatExceptionOfType(InternalAuthenticationServiceException.class)
			.isThrownBy(() -> provider.authenticate(attempt("admin@example.com", "Start-Here-2026")))
			.isSameAs(down);
		then(attempts).should().isPaused(KEY);
		then(attempts).shouldHaveNoMoreInteractions();
	}

	@Test
	@DisplayName("If the pause can't be checked (database down), sign-in fails without checking the password")
	void pauseCheckFailureFailsClosed() {
		DataAccessResourceFailureException down = new DataAccessResourceFailureException("down");
		given(attempts.isPaused(KEY)).willThrow(down);

		assertThatExceptionOfType(DataAccessResourceFailureException.class)
			.isThrownBy(() -> provider.authenticate(attempt("admin@example.com", "Start-Here-2026")))
			.isSameAs(down);
		then(delegate).shouldHaveNoInteractions();
		then(attempts).shouldHaveNoMoreInteractions();
	}

	@ParameterizedTest(name = "A blank identifier ({0}) is passed to the delegate without counting")
	@NullAndEmptySource
	@ValueSource(strings = { "   " })
	void blankIdentifierPassedThrough(String identifier) {
		BadCredentialsException wrong = new BadCredentialsException("Bad credentials");
		given(delegate.authenticate(any())).willThrow(wrong);

		assertThatExceptionOfType(BadCredentialsException.class)
			.isThrownBy(() -> provider.authenticate(attempt(identifier, "x")))
			.isSameAs(wrong);
		then(attempts).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("Supports only username/password sign-ins")
	void supportsUsernamePassword() {
		assertThat(provider.supports(UsernamePasswordAuthenticationToken.class)).isTrue();
		assertThat(provider.supports(TestingAuthenticationToken.class)).isFalse();
	}

	private static Authentication attempt(String identifier, String password) {
		return UsernamePasswordAuthenticationToken.unauthenticated(identifier, password);
	}

}
