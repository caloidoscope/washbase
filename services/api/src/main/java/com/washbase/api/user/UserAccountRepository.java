package com.washbase.api.user;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Users. Look-ups by sign-in identifier take values already normalized (lower-cased email, {@code +639XXXXXXXXX}
 * mobile): use {@link EmailAddresses#normalize} and {@link PhoneNumbers#normalize} first.
 */
public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {

	/** @param email normalized with {@link EmailAddresses#normalize} */
	Optional<UserAccount> findByEmail(String email);

	/** @param mobile normalized with {@link PhoneNumbers#normalize} */
	Optional<UserAccount> findByMobile(String mobile);

	boolean existsByRoleAndActiveTrue(Role role);

}
