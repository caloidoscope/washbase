package com.washbase.api.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.web.authentication.AuthenticationConverter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;

/**
 * Recognises a public client's renewal or revocation request (CAR-20, ADR-001 Amendment 2), which Spring
 * Authorization Server 7.1.1 can't authenticate: its {@code PublicClientAuthenticationConverter} only matches the PKCE
 * code exchange ({@code grant_type=authorization_code} with {@code code_verifier}).
 *
 * <p>Contract. Returns an {@code OAuth2ClientAuthenticationToken(clientId, ClientAuthenticationMethod.NONE, null,
 * additionalParameters)} only when <em>all</em> of these hold, otherwise {@code null} (so the other converters,
 * e.g. {@code client_secret_basic} for {@code washbase-web}, still apply):
 * <ul>
 * <li>{@code POST}, form-encoded;</li>
 * <li>either the token endpoint with {@code grant_type=refresh_token}, or the revocation endpoint (paths from
 * {@code AuthorizationServerSettings}, not hard-coded);</li>
 * <li>no {@code Authorization} header, no {@code client_secret}, no {@code client_assertion};</li>
 * <li>{@code client_id} present.</li>
 * </ul>
 * A {@code client_id} that is repeated or blank is {@code invalid_request} (as Spring's own converters). The
 * additional parameters are the form parameters without {@code client_id}, plus a marker saying which endpoint it
 * was ({@link #ENDPOINT_PARAMETER} = an {@link Endpoint}), for {@link PublicClientTokenAuthenticationProvider}. The
 * marker is an enum constant, never a string, so a form parameter of the same name (which is always a string) passed
 * through another converter can't pose as it.
 *
 * <p>Registered first in the client authentication converters
 * ({@code clientAuthentication(c -> c.authenticationConverters(list -> list.addFirst(...)))}).
 */
final class PublicClientTokenAuthenticationConverter implements AuthenticationConverter {

	/** The additional parameter holding the {@link Endpoint} the request was for. */
	static final String ENDPOINT_PARAMETER = PublicClientTokenAuthenticationConverter.class.getName() + ".ENDPOINT";

	/** Which endpoint a public client authenticated by {@code client_id} alone is calling. */
	enum Endpoint {

		/** {@code /oauth2/token} with {@code grant_type=refresh_token}. */
		TOKEN,

		/** {@code /oauth2/revoke}. */
		REVOCATION

	}

	private static final String CLIENT_ASSERTION = "client_assertion";

	private final RequestMatcher tokenEndpoint;

	private final RequestMatcher revocationEndpoint;

	PublicClientTokenAuthenticationConverter(AuthorizationServerSettings settings) {
		this.tokenEndpoint = PathPatternRequestMatcher.pathPattern(HttpMethod.POST, settings.getTokenEndpoint());
		this.revocationEndpoint = PathPatternRequestMatcher.pathPattern(HttpMethod.POST,
				settings.getTokenRevocationEndpoint());
	}

	@Override
	public @Nullable Authentication convert(HttpServletRequest request) {
		if (!isFormPost(request) || request.getHeader(HttpHeaders.AUTHORIZATION) != null) {
			return null;
		}
		MultiValueMap<String, String> parameters = formParameters(request);
		Endpoint endpoint = endpoint(request, parameters);
		if (endpoint == null || parameters.containsKey(OAuth2ParameterNames.CLIENT_SECRET)
				|| parameters.containsKey(CLIENT_ASSERTION)) {
			return null;
		}
		List<String> clientIds = parameters.get(OAuth2ParameterNames.CLIENT_ID);
		if (clientIds == null) {
			return null;
		}
		if (clientIds.size() != 1 || !StringUtils.hasText(clientIds.getFirst())) {
			throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_REQUEST);
		}
		String clientId = clientIds.getFirst();
		parameters.remove(OAuth2ParameterNames.CLIENT_ID);

		Map<String, Object> additionalParameters = new HashMap<>();
		parameters.forEach((name, values) -> additionalParameters.put(name,
				(values.size() == 1) ? values.getFirst() : values.toArray(new String[0])));
		additionalParameters.put(ENDPOINT_PARAMETER, endpoint);
		return new OAuth2ClientAuthenticationToken(clientId, ClientAuthenticationMethod.NONE, null,
				additionalParameters);
	}

	private @Nullable Endpoint endpoint(HttpServletRequest request, MultiValueMap<String, String> parameters) {
		if (tokenEndpoint.matches(request)) {
			List<String> grantTypes = parameters.get(OAuth2ParameterNames.GRANT_TYPE);
			boolean refresh = grantTypes != null && grantTypes.size() == 1
					&& AuthorizationGrantType.REFRESH_TOKEN.getValue().equals(grantTypes.getFirst());
			return refresh ? Endpoint.TOKEN : null;
		}
		return revocationEndpoint.matches(request) ? Endpoint.REVOCATION : null;
	}

	private static boolean isFormPost(HttpServletRequest request) {
		if (!HttpMethod.POST.matches(request.getMethod()) || request.getContentType() == null) {
			return false;
		}
		try {
			return MediaType.APPLICATION_FORM_URLENCODED.includes(MediaType.parseMediaType(request.getContentType()));
		}
		catch (InvalidMediaTypeException ex) {
			return false;
		}
	}

	/** The request's form parameters only, not those in the query string (as Spring's own converters). */
	private static MultiValueMap<String, String> formParameters(HttpServletRequest request) {
		String queryString = StringUtils.hasText(request.getQueryString()) ? request.getQueryString() : "";
		MultiValueMap<String, String> parameters = new LinkedMultiValueMap<>();
		request.getParameterMap().forEach((name, values) -> {
			if (!queryString.contains(name)) {
				for (String value : values) {
					parameters.add(name, value);
				}
			}
		});
		return parameters;
	}

}
