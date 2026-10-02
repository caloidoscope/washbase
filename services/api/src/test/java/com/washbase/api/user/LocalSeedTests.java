package com.washbase.api.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.washbase.api.TestcontainersConfiguration;
import org.springframework.test.context.ActiveProfiles;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;

class LocalSeedTests {

	@Test
	@DisplayName("Local seed: creates the account that must choose a new password, once")
	void createsOnceAndIsIdempotent() {
		UserAccountRepository users = mock(UserAccountRepository.class);
		PasswordEncoder encoder = mock(PasswordEncoder.class);
		when(encoder.encode("Washbase-Local-1")).thenReturn("hash");
		when(users.findByEmail("newowner@example.com")).thenReturn(Optional.empty());

		new LocalSeed(users, encoder).run(null);

		verify(users).save(any(UserAccount.class));

		UserAccountRepository seeded = mock(UserAccountRepository.class);
		when(seeded.findByEmail("newowner@example.com")).thenReturn(Optional.of(mock(UserAccount.class)));
		new LocalSeed(seeded, encoder).run(null);
		verify(seeded, never()).save(any());
	}

	@Import(TestcontainersConfiguration.class)
	@SpringBootTest
	@ActiveProfiles("local")
	@Nested
	class WithLocalProfile {

		@Autowired
		ApplicationContext context;

		@Autowired
		UserAccountRepository users;

		@Test
		@DisplayName("Local seed: the application starts with the local profile and seeds the must-change account")
		void startsAndSeeds() {
			assertThat(context.getBeansOfType(LocalSeed.class)).hasSize(1);
			UserAccount owner = users.findByEmail("newowner@example.com").orElseThrow();
			assertThat(owner.getRole()).isEqualTo(Role.OWNER);
			assertThat(owner.isMustChangePassword()).isTrue();
		}

	}

	@Import(TestcontainersConfiguration.class)
	@SpringBootTest
	@Nested
	class WithoutLocalProfile {

		@Autowired
		ApplicationContext context;

		@Test
		@DisplayName("Local seed: is not loaded without the local profile")
		void notLoaded() {
			assertThat(context.getBeansOfType(LocalSeed.class)).isEmpty();
		}

	}

}
