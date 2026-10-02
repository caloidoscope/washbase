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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Drives the web app's sign-in through the embedded authorization server with MockMvc, the way the Next.js
 * backend-for-frontend will: {@code /oauth2/authorize} (PKCE S256) → {@code /login} (form, CSRF) → {@code code} on
 * the redirect URI → {@code /oauth2/token} ({@code client_secret_basic}) → access token. Also renewal
 * ({@link #refresh}), sign-out ({@link #revoke}, {@link #logout}) as the web app's server does them (CAR-18). One
 * instance is one browser: it keeps its own session cookie and PKCE verifier.
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
		return signInForTokens(emailOrMobile, password).accessToken();
	}

	/**
	 * The whole flow for someone who signs in successfully.
	 * @return the access, refresh and ID tokens from the token response
	 */
	public Tokens signInForTokens(String emailOrMobile, String password) throws Exception {
		MockHttpServletResponse authorize = authorize();
		assertThat(authorize.getStatus()).as("unauthenticated /oauth2/authorize").isEqualTo(HttpStatus.FOUND.value());
		assertThat(authorize.getRedirectedUrl()).as("sent to the sign-in page").endsWith("/login");

		MockHttpServletResponse login = submitSignIn(emailOrMobile, password);
		assertThat(login.getRedirectedUrl()).as("signed in and sent back to /oauth2/authorize")
			.contains("/oauth2/authorize");

		MockHttpServletResponse resumed = perform(get(URI.create(login.getRedirectedUrl())).accept(MediaType.TEXT_HTML));
		String code = codeFrom(resumed);
		MockHttpServletResponse token = tokenRequest(code, codeVerifier, CLIENT_SECRET);
		assertThat(token.getStatus()).as("token response: %s", token.getContentAsString()).isEqualTo(200);
		return Tokens.from(token);
	}

	/** {@code GET /oauth2/authorize} with PKCE S256 for {@code washbase-web}, as a browser (HTML). */
	public MockHttpServletResponse authorize() throws Exception {
		return perform(get(authorizeUri(REDIRECT_URI, true)).accept(MediaType.TEXT_HTML));
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
		return perform(post("/login").param("username", emailOrMobile).param("password", password).with(csrf()));
	}

	/**
	 * One request from this browser. A request may give the browser a new session (sign-in, or a new one after the
	 * old one ended); keep following it like a cookie jar would.
	 */
	private MockHttpServletResponse perform(MockHttpServletRequestBuilder request) throws Exception {
		MvcResult result = mockMvc.perform(request.session(session)).andReturn();
		if (result.getRequest().getSession(false) instanceof MockHttpSession current) {
			session = current;
		}
		return result.getResponse();
	}

	/** The sign-in page as the browser sees it after a redirect to {@code location} (e.g. {@code /login?error}). */
	public String page(String location) throws Exception {
		return perform(get(URI.create(absolute(location))).accept(MediaType.TEXT_HTML))
			.getContentAsString(StandardCharsets.UTF_8);
	}

	/** Whether this browser's session holds a signed-in user. */
	public boolean isSignedIn() {
		return !session.isInvalid()
				&& session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY) != null;
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
		return codeFrom(perform(get(URI.create(login.getRedirectedUrl())).accept(MediaType.TEXT_HTML)));
	}

	/** {@code POST /oauth2/token} ({@code refresh_token}, {@code client_secret_basic}), as the web app's server does. */
	public MockHttpServletResponse refresh(String refreshToken) throws Exception {
		return mockMvc
			.perform(post("/oauth2/token").with(httpBasic(CLIENT_ID, CLIENT_SECRET))
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("grant_type", "refresh_token")
				.param("refresh_token", refreshToken))
			.andReturn()
			.getResponse();
	}

	/** {@code POST /oauth2/revoke} ({@code token_type_hint=refresh_token}), as the web app's sign-out does. */
	public MockHttpServletResponse revoke(String refreshToken) throws Exception {
		return mockMvc
			.perform(post("/oauth2/revoke").with(httpBasic(CLIENT_ID, CLIENT_SECRET))
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("token", refreshToken)
				.param("token_type_hint", "refresh_token"))
			.andReturn()
			.getResponse();
	}

	/** {@code GET /connect/logout} (OIDC RP-initiated logout) from this browser, as the web app's sign-out does. */
	public MockHttpServletResponse logout(String idToken, String postLogoutRedirectUri) throws Exception {
		// In the query string, as a browser redirect sends them (the endpoint reads GET parameters from there).
		URI uri = URI.create("http://localhost" + UriComponentsBuilder.fromPath("/connect/logout")
			.queryParam("id_token_hint", idToken)
			.queryParam("post_logout_redirect_uri", postLogoutRedirectUri)
			.encode()
			.build()
			.toUriString());
		return perform(get(uri).accept(MediaType.TEXT_HTML));
	}

	/** The tokens from a successful token response. */
	public record Tokens(String accessToken, String refreshToken, String idToken) {

		public static Tokens from(MockHttpServletResponse tokenResponse) throws Exception {
			String body = tokenResponse.getContentAsString();
			return new Tokens(JsonPath.read(body, "$.access_token"), JsonPath.read(body, "$.refresh_token"),
					JsonPath.read(body, "$.id_token"));
		}

		@Override
		public String toString() {
			return "Tokens[******]";
		}

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
