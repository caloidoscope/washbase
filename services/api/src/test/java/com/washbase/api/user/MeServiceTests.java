package com.washbase.api.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.BDDMockito.given;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class MeServiceTests {

	@Mock
	private UserAccountRepository users;

	@InjectMocks
	private MeService meService;

	@Test
	@DisplayName("Maps the token's user to MeResponse")
	void mapsUser() {
		UUID id = UUID.randomUUID();
		UserAccount admin = new UserAccount("Admin", "admin@example.com", "+639171234567", "{bcrypt}x", Role.ADMIN,
				true);
		ReflectionTestUtils.setField(admin, "id", id);
		given(users.findById(id)).willReturn(Optional.of(admin));

		assertThat(meService.getMe(id.toString()))
			.isEqualTo(new MeResponse(id, "Admin", "admin@example.com", "+639171234567", Role.ADMIN));
	}

	@Test
	@DisplayName("401 when the token's user doesn't exist")
	void missingUser() {
		UUID id = UUID.randomUUID();
		given(users.findById(id)).willReturn(Optional.empty());

		assertUnauthorized(id.toString());
	}

	@Test
	@DisplayName("401 when the token's user is inactive")
	void inactiveUser() {
		UUID id = UUID.randomUUID();
		UserAccount user = new UserAccount("Staff", "staff@example.com", null, "{bcrypt}x", Role.STAFF, false);
		ReflectionTestUtils.setField(user, "id", id);
		ReflectionTestUtils.setField(user, "active", false);
		given(users.findById(id)).willReturn(Optional.of(user));

		assertUnauthorized(id.toString());
	}

	@ParameterizedTest(name = "401 when the token''s sub \"{0}\" isn''t a user ID")
	@NullAndEmptySource
	@ValueSource(strings = { "admin@example.com", "not-a-uuid" })
	void subjectNotUuid(String subject) {
		assertUnauthorized(subject);
	}

	private void assertUnauthorized(String subject) {
		assertThatExceptionOfType(ResponseStatusException.class).isThrownBy(() -> meService.getMe(subject))
			.satisfies(ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
	}

}
