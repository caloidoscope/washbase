package com.washbase.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.washbase.api.user.Role;
import com.washbase.api.user.UserAccount;
import com.washbase.api.user.UserAccountRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class UserAccountDetailsServiceTests {

	private static final UUID ID = UUID.fromString("0b6f6f8e-5a6e-4d0e-9a52-2a3f3f0b7c11");

	@Mock
	private UserAccountRepository users;

	private UserAccountDetailsService service() {
		return new UserAccountDetailsService(users);
	}

	@Test
	@DisplayName("An email finds the account case-insensitively; the username is the user ID")
	void findsByEmail() {
		given(users.findByEmail("admin@example.com"))
			.willReturn(Optional.of(account("admin@example.com", null, "{bcrypt}hash", Role.ADMIN, true)));

		UserDetails details = service().loadUserByUsername("  Admin@Example.COM ");

		assertThat(details).isInstanceOf(User.class);
		assertThat(details.getUsername()).isEqualTo(ID.toString());
		assertThat(details.getPassword()).isEqualTo("{bcrypt}hash");
		assertThat(details.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_ADMIN");
		assertThat(details.isEnabled()).isTrue();
	}

	@ParameterizedTest(name = "A mobile number in either format finds the account ({0})")
	@ValueSource(strings = { "09171234567", "+639171234567", " 09171234567 " })
	void findsByMobile(String typed) {
		given(users.findByMobile("+639171234567"))
			.willReturn(Optional.of(account(null, "+639171234567", "{bcrypt}hash", Role.STAFF, true)));

		UserDetails details = service().loadUserByUsername(typed);

		assertThat(details.getUsername()).isEqualTo(ID.toString());
		assertThat(details.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_STAFF");
	}

	@Test
	@DisplayName("An unknown email fails like every other case, without the email in the message")
	void unknownEmail() {
		given(users.findByEmail("nobody@example.com")).willReturn(Optional.empty());

		assertNotFound("nobody@example.com");
	}

	@ParameterizedTest(name = "Something that is neither an email nor a Philippine mobile number fails without a lookup ({0})")
	@ValueSource(strings = { "admin", "12345", "0917123456", "+15551234567", " " })
	void invalidIdentifier(String typed) {
		assertNotFound(typed);
		verify(users, never()).findByMobile(anyString());
		verify(users, never()).findByEmail(anyString());
	}

	@Test
	@DisplayName("An inactive account fails like an unknown one")
	void inactive() {
		given(users.findByEmail("staff@example.com"))
			.willReturn(Optional.of(account("staff@example.com", null, "{bcrypt}hash", Role.STAFF, false)));

		assertNotFound("staff@example.com");
	}

	@Test
	@DisplayName("An account without a password fails like an unknown one")
	void noPassword() {
		given(users.findByMobile("+639181234567"))
			.willReturn(Optional.of(account(null, "+639181234567", null, Role.CLIENT, true)));

		assertNotFound("09181234567");
	}

	@Test
	@DisplayName("A null identifier fails like an unknown one")
	void nullIdentifier() {
		assertNotFound(null);
	}

	private void assertNotFound(String typed) {
		assertThatThrownBy(() -> service().loadUserByUsername(typed)).isInstanceOf(UsernameNotFoundException.class)
			.hasMessage(UserAccountDetailsService.NOT_FOUND);
	}

	private static UserAccount account(String email, String mobile, String hash, Role role, boolean active) {
		UserAccount account = new UserAccount("Someone", email, mobile, hash, role, false);
		ReflectionTestUtils.setField(account, "id", ID);
		ReflectionTestUtils.setField(account, "active", active);
		return account;
	}

}
