package com.washbase.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Drives the web app's sign-in through the embedded authorization server with MockMvc, the way the Next.js
 * backend-for-frontend will: {@code /oauth2/authorize} (PKCE S256) → {@code /login} (form, CSRF) → {@code code} on
 * the redirect URI → {@code /oauth2/token} ({@code client_secret_basic}) → access token. One instance is one
 * browser: it keeps its own session cookie and PKCE verifier.
 *
 * <p>Uses the client registered from {@code src/test/resources/config/application.properties}.
 */
public final class SignInFlow {

	public static final String CLIENT_ID = "washbase-web";

	public static final String CLIENT_SECRET = "test-web-client-secret";

	public static final String REDIRECT_URI = "http://localhost:3000/auth/callback";

	/** Exactly what the sign-in page shows for every failure. */
	public static final String SIGN_IN_ERROR = "Incorrect email, mobile number or password.";

	private static final SecureRandom RANDOM = new SecureRandom();

	private final MockMvc mockMvc;

	private final String codeVerifier = randomUrlSafe(32);

	private final String state = randomUrlSafe(16);

	private MockHttpSession session = new MockHttpSession();

	public SignInFlow(MockMvc mockMvc) {
		this.mockMvc = mockMvc;
	}

	/**
	 * The whole flow for someone who signs in successfully.
	 * @return the access token
	 */
	public String signIn(String emailOrMobile, String password) throws Exception {
		MockHttpServletResponse authorize = authorize();
		assertThat(authorize.getStatus()).as("unauthenticated /oauth2/authorize").isEqualTo(HttpStatus.FOUND.value());
		assertThat(authorize.getRedirectedUrl()).as("sent to the sign-in page").endsWith("/login");

		MockHttpServletResponse login = submitSignIn(emailOrMobile, password);
		assertThat(login.getRedirectedUrl()).as("signed in and sent back to /oauth2/authorize")
			.contains("/oauth2/authorize");

		MockHttpServletResponse resumed = mockMvc.perform(get(URI.create(login.getRedirectedUrl())).session(session)
			.accept(MediaType.TEXT_HTML)).andReturn().getResponse();
		String code = codeFrom(resumed);
		return exchange(code, codeVerifier, CLIENT_SECRET);
	}

	/** {@code GET /oauth2/authorize} with PKCE S256 for {@code washbase-web}, as a browser (HTML). */
	public MockHttpServletResponse authorize() throws Exception {
		return mockMvc.perform(get(authorizeUri(REDIRECT_URI, true)).session(session).accept(MediaType.TEXT_HTML))
			.andReturn()
			.getResponse();
	}

	/** The authorization request the web app sends, optionally without PKCE or with another redirect URI. */
	public URI authorizeUri(String redirectUri, boolean withPkce) {
		UriComponentsBuilder uri = UriComponentsBuilder.fromPath("/oauth2/authorize")
			.queryParam("response_type", "code")
			.queryParam("client_id", CLIENT_ID)
			.queryParam("scope", "openid")
			.queryParam("redirect_uri", redirectUri)
			.queryParam("state", state);
		if (withPkce) {
			uri.queryParam("code_challenge", s256(codeVerifier)).queryParam("code_challenge_method", "S256");
		}
		return URI.create("http://localhost" + uri.encode().build().toUriString());
	}

	/** {@code POST /login} with a valid CSRF token, as the sign-in page's form does. */
	public MockHttpServletResponse submitSignIn(String emailOrMobile, String password) throws Exception {
		MvcResult result = mockMvc
			.perform(post("/login").session(session)
				.param("username", emailOrMobile)
				.param("password", password)
				.with(csrf()))
			.andReturn();
		// Signing in may give the browser a new session; keep following it like a cookie jar would.
		if (result.getRequest().getSession(false) instanceof MockHttpSession current) {
			session = current;
		}
		return result.getResponse();
	}

	/** The sign-in page as the browser sees it after a redirect to {@code location} (e.g. {@code /login?error}). */
	public String page(String location) throws Exception {
		return mockMvc.perform(get(URI.create(absolute(location))).session(session).accept(MediaType.TEXT_HTML))
			.andReturn()
			.getResponse()
			.getContentAsString(StandardCharsets.UTF_8);
	}

	/** Whether this browser's session holds a signed-in user. */
	public boolean isSignedIn() {
		return session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY) != null;
	}

	/** {@code POST /oauth2/token} for a code, as the web app's server does. */
	public String exchange(String code, String verifier, String clientSecret) throws Exception {
		MockHttpServletResponse token = tokenRequest(code, verifier, clientSecret);
		assertThat(token.getStatus()).as("token response: %s", token.getContentAsString()).isEqualTo(200);
		return JsonPath.read(token.getContentAsString(), "$.access_token");
	}

	/** {@code POST /oauth2/token} ({@code authorization_code}); {@code verifier} may be {@code null}. */
	public MockHttpServletResponse tokenRequest(String code, String verifier, String clientSecret) throws Exception {
		var request = post("/oauth2/token").with(httpBasic(CLIENT_ID, clientSecret))
			.contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.param("grant_type", "authorization_code")
			.param("code", code)
			.param("redirect_uri", REDIRECT_URI);
		if (verifier != null) {
			request.param("code_verifier", verifier);
		}
		return mockMvc.perform(request).andReturn().getResponse();
	}

	/** Signs in and returns the {@code code} on the redirect URI, without exchanging it. */
	public String authorizationCode(String emailOrMobile, String password) throws Exception {
		authorize();
		MockHttpServletResponse login = submitSignIn(emailOrMobile, password);
		return codeFrom(mockMvc
			.perform(get(URI.create(login.getRedirectedUrl())).session(session).accept(MediaType.TEXT_HTML))
			.andReturn()
			.getResponse());
	}

	public String codeVerifier() {
		return codeVerifier;
	}

	private String codeFrom(MockHttpServletResponse response) {
		assertThat(response.getStatus()).as("authorization response").isEqualTo(HttpStatus.FOUND.value());
		String location = response.getHeader(HttpHeaders.LOCATION);
		assertThat(location).as("redirect to the web app's callback").startsWith(REDIRECT_URI + "?");
		UriComponents callback = UriComponentsBuilder.fromUriString(location).build();
		assertThat(callback.getQueryParams().getFirst("state")).isEqualTo(state);
		String code = callback.getQueryParams().getFirst("code");
		assertThat(code).as("authorization code").isNotBlank();
		return code;
	}

	private static String absolute(String location) {
		return location.startsWith("http") ? location : "http://localhost" + location;
	}

	static String s256(String verifier) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
			return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static String randomUrlSafe(int bytes) {
		byte[] value = new byte[bytes];
		RANDOM.nextBytes(value);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
	}

}
