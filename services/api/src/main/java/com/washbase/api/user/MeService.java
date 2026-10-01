package com.washbase.api.user;

import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The signed-in user, identified only by the access token's {@code sub} (never by an ID from the request). */
@Service
class MeService {

	private final UserAccountRepository users;

	MeService(UserAccountRepository users) {
		this.users = users;
	}

	/**
	 * @param subject the access token's {@code sub} (the user's UUID)
	 * @throws ResponseStatusException {@code 401} if the subject isn't a user ID, or the user doesn't exist or is
	 * inactive
	 */
	@Transactional(readOnly = true)
	MeResponse getMe(String subject) {
		return parseId(subject).flatMap(users::findById)
			.filter(UserAccount::isActive)
			.map(MeService::toResponse)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
	}

	static MeResponse toResponse(UserAccount user) {
		return new MeResponse(user.getId(), user.getName(), user.getEmail(), user.getMobile(), user.getRole());
	}

	private static Optional<UUID> parseId(String subject) {
		if (subject == null) {
			return Optional.empty();
		}
		try {
			return Optional.of(UUID.fromString(subject));
		}
		catch (IllegalArgumentException ex) {
			return Optional.empty();
		}
	}

}
