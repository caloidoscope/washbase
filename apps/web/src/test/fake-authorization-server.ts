// Test helper (Vitest only, never imported by the app): a stand-in for the API's authorization server, served
// through a stubbed `fetch`. openid-client stays real, so the errors under test are the ones it really throws.
import { vi } from "vitest";

export const ISSUER = "http://localhost:18080";
export const REDIRECT_URI = "http://localhost:13000/auth/callback";
export const TOKEN_ENDPOINT = `${ISSUER}/oauth2/token`;

/** The discovery document (`/.well-known/openid-configuration`) for `ISSUER`. */
export const serverMetadata = {
  issuer: ISSUER,
  authorization_endpoint: `${ISSUER}/oauth2/authorize`,
  token_endpoint: TOKEN_ENDPOINT,
  jwks_uri: `${ISSUER}/oauth2/jwks`,
  response_types_supported: ["code"],
  subject_types_supported: ["public"],
  id_token_signing_alg_values_supported: ["RS256"],
  code_challenge_methods_supported: ["S256"],
  token_endpoint_auth_methods_supported: ["client_secret_basic"],
};

/** What the token endpoint does: answer with a Response, or throw (as `fetch` does when the API is down). */
export type TokenEndpoint = (request: Request) => Response | Promise<Response>;

/** A `fetch` that serves discovery and hands token requests to `tokenEndpoint`. Every other URL is a test bug. */
export function fakeAuthorizationServer(tokenEndpoint: TokenEndpoint) {
  return vi.fn(async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const request = new Request(input, init);
    if (request.url === `${ISSUER}/.well-known/openid-configuration`) return Response.json(serverMetadata);
    if (request.url === TOKEN_ENDPOINT) return tokenEndpoint(request);
    throw new Error(`Unexpected request in test: ${request.method} ${request.url}`);
  });
}

/** `fetch()`'s own failure when nothing answers (connection refused, DNS, reset). */
export function networkError(): TypeError {
  return new TypeError("fetch failed");
}

/** What `AbortSignal.timeout()` makes `fetch()` reject with. */
export function timeoutError(): DOMException {
  return new DOMException("The operation was aborted due to timeout", "TimeoutError");
}
