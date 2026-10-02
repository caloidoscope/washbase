package com.washbase.api.user;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.washbase.api.TestcontainersConfiguration;
import com.washbase.api.auth.AuthProperties;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.MockMvc;

/** {@code GET /api/v1/me} end to end through the resource server chain, against the real database. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class MeControllerTests {

	private static final String ME = "/api/v1/me";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserAccountRepository users;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private JWKSource<SecurityContext> jwkSource;

	@Autowired
	private AuthProperties authProperties;

	private final Map<Role, UserAccount> userByRole = new EnumMap<>(Role.class);

	@BeforeEach
	void givenOneUserPerRole() {
		jdbc.update("delete from users");
		userByRole.put(Role.ADMIN,
				users.save(new UserAccount("Admin", "admin@example.com", "+639171234567", "{noop}x", Role.ADMIN, true)));
		userByRole.put(Role.OWNER,
				users.save(new UserAccount("Olivia Owner", "owner@example.com", null, "{noop}x", Role.OWNER, false)));
		userByRole.put(Role.STAFF,
				users.save(new UserAccount("Sam Staff", null, "+639181234567", "{noop}x", Role.STAFF, false)));
		userByRole.put(Role.CLIENT,
				users.save(new UserAccount("Cora Client", "client@example.com", null, "{noop}x", Role.CLIENT, false)));
	}

	@Test
	@DisplayName("Scenario: The API refuses a request from someone who isn't signed in")
	void refusesWithoutSignIn() throws Exception {
		mockMvc.perform(get(ME))
			.andExpect(status().isUnauthorized())
			.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer")));
	}

	@ParameterizedTest(name = "GET /api/v1/me returns the signed-in {0}")
	@EnumSource(Role.class)
	void returnsSignedInUser(Role role) throws Exception {
		UserAccount user = userByRole.get(role);
		mockMvc.perform(get(ME).with(jwt().jwt(j -> j.subject(user.getId().toString()))
			.authorities(new SimpleGrantedAuthority("ROLE_" + role.name()))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(user.getId().toString()))
			.andExpect(jsonPath("$.name").value(user.getName()))
			.andExpect(jsonPath("$.email").value(user.getEmail()))
			.andExpect(jsonPath("$.mobile").value(user.getMobile()))
			.andExpect(jsonPath("$.role").value(role.name()));
	}

	@Test
	@DisplayName("GET /api/v1/me returns null email for a user who signs in with a mobile number only")
	void nullEmailIsPresent() throws Exception {
		UserAccount staff = userByRole.get(Role.STAFF);
		mockMvc.perform(get(ME).with(jwt().jwt(j -> j.subject(staff.getId().toString()))
			.authorities(new SimpleGrantedAuthority("ROLE_STAFF"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.email").isEmpty())
			.andExpect(jsonPath("$.mobile").value("+639181234567"));
	}

	@Test
	@DisplayName("GET /api/v1/me is forbidden (403) for a token without any allowed role")
	void forbiddenWithoutRole() throws Exception {
		UserAccount admin = userByRole.get(Role.ADMIN);
		mockMvc.perform(get(ME).with(jwt().jwt(j -> j.subject(admin.getId().toString()))
			.authorities(new SimpleGrantedAuthority("SCOPE_openid"))))
			.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("GET /api/v1/me is 401 when the token's user no longer exists")
	void unauthorizedForUnknownUser() throws Exception {
		mockMvc.perform(get(ME).with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString()))
			.authorities(new SimpleGrantedAuthority("ROLE_CLIENT"))))
			.andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("GET /api/v1/me is 401 when the token's user is inactive")
	void unauthorizedForInactiveUser() throws Exception {
		UserAccount client = userByRole.get(Role.CLIENT);
		jdbc.update("update users set active = false where id = ?", client.getId());
		mockMvc.perform(get(ME).with(jwt().jwt(j -> j.subject(client.getId().toString()))
			.authorities(new SimpleGrantedAuthority("ROLE_CLIENT"))))
			.andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("GET /api/v1/me is 401 for a malformed bearer token")
	void unauthorizedForMalformedToken() throws Exception {
		mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("A signed access token's roles claim maps to the role: GET /api/v1/me returns 200")
	void signedTokenWithRolesClaim() throws Exception {
		UserAccount owner = userByRole.get(Role.OWNER);
		String token = token(owner.getId().toString(), authProperties.issuer(), authProperties.audience(),
				List.of("OWNER"), Instant.now().plus(15, ChronoUnit.MINUTES));
		mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.role").value("OWNER"));
	}

	@Test
	@DisplayName("A signed access token without a roles claim is forbidden (403)")
	void signedTokenWithoutRoles() throws Exception {
		String token = token(userByRole.get(Role.OWNER).getId().toString(), authProperties.issuer(),
				authProperties.audience(), List.of(), Instant.now().plus(15, ChronoUnit.MINUTES));
		mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
			.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("A signed access token for another audience, another issuer, or expired is refused (401)")
	void signedTokenRejectedClaims() throws Exception {
		String sub = userByRole.get(Role.OWNER).getId().toString();
		Instant valid = Instant.now().plus(15, ChronoUnit.MINUTES);
		for (String token : List.of(token(sub, authProperties.issuer(), "another-api", List.of("OWNER"), valid),
				token(sub, "https://evil.example.com", authProperties.audience(), List.of("OWNER"), valid),
				token(sub, authProperties.issuer(), authProperties.audience(), List.of("OWNER"),
						Instant.now().minus(5, ChronoUnit.MINUTES)))) {
			mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isUnauthorized());
		}
	}

	private String token(String subject, String issuer, String audience, List<String> roles, Instant expiresAt) {
		Instant issuedAt = expiresAt.minus(15, ChronoUnit.MINUTES);
		JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
			.issuer(issuer)
			.subject(subject)
			.audience(List.of(audience))
			.issuedAt(issuedAt)
			.notBefore(issuedAt)
			.expiresAt(expiresAt);
		if (!roles.isEmpty()) {
			claims.claim("roles", roles);
		}
		JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).type("JWT").build();
		return new NimbusJwtEncoder(jwkSource).encode(JwtEncoderParameters.from(header, claims.build()))
			.getTokenValue();
	}

}
