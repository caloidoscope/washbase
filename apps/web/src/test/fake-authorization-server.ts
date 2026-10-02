// Test helper (Vitest only, never imported by the app): a stand-in for the API's authorization server, served
// through a stubbed `fetch`. openid-client stays real, so the errors under test are the ones it really throws.
import { randomBytes } from "node:crypto";
import { vi } from "vitest";

export const ISSUER = "http://localhost:18080";
export const REDIRECT_URI = "http://localhost:13000/auth/callback";
export const TOKEN_ENDPOINT = `${ISSUER}/oauth2/token`;
export const REVOCATION_ENDPOINT = `${ISSUER}/oauth2/revoke`;
export const END_SESSION_ENDPOINT = `${ISSUER}/connect/logout`;
export const CLIENT_ID = "washbase-web";

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
  revocation_endpoint: REVOCATION_ENDPOINT,
  revocation_endpoint_auth_methods_supported: ["client_secret_basic"],
  end_session_endpoint: END_SESSION_ENDPOINT,
};

/** What an endpoint does: answer with a Response, or throw (as `fetch` does when the API is down). */
export type Endpoint = (request: Request) => Response | Promise<Response>;
/** What the token endpoint does. */
export type TokenEndpoint = Endpoint;

/** The endpoints a test serves, besides discovery. A request to one that isn't given is a test bug. */
export interface FakeEndpoints {
  token?: Endpoint;
  revocation?: Endpoint;
  /** `GET /connect/logout?…` (the request URL carries the query). */
  endSession?: Endpoint;
}

/** A `fetch` that serves discovery and hands requests to the given endpoints (a function alone is the token
 *  endpoint). Every other URL is a test bug. */
export function fakeAuthorizationServer(endpoints: TokenEndpoint | FakeEndpoints) {
  const { token, revocation, endSession }: FakeEndpoints =
    typeof endpoints === "function" ? { token: endpoints } : endpoints;
  return vi.fn(async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const request = new Request(input, init);
    const path = request.url.split("?")[0];
    if (request.url === `${ISSUER}/.well-known/openid-configuration`) return Response.json(serverMetadata);
    if (request.url === TOKEN_ENDPOINT && token) return token(request);
    if (request.url === REVOCATION_ENDPOINT && revocation) return revocation(request);
    if (path === END_SESSION_ENDPOINT && endSession) return endSession(request);
    throw new Error(`Unexpected request in test: ${request.method} ${path}`);
  });
}

/** The request URLs `fetch` was called with (query included). */
export function requestedUrls(fetch: ReturnType<typeof fakeAuthorizationServer>): string[] {
  return fetch.mock.calls.map(([input, init]) => new Request(input, init).url);
}

function base64url(value: string | Buffer): string {
  return Buffer.from(value).toString("base64url");
}

/**
 * A JWT shaped like the API's (RS256 header, base64url payload, a 342-character signature, as a 2048-bit RSA key
 * makes). The signature is random: openid-client doesn't verify ID token signatures from the token endpoint, and the
 * access token is opaque to the web app. `padTo` pads the payload so the token is about that many characters.
 */
export function fakeJwt(claims: Record<string, unknown>, padTo = 0): string {
  const header = base64url(JSON.stringify({ alg: "RS256", typ: "JWT", kid: "test-key-1" }));
  const signature = base64url(randomBytes(256));
  let payload = base64url(JSON.stringify(claims));
  const missing = padTo - (header.length + payload.length + signature.length + 2);
  if (missing > 0) {
    payload = base64url(JSON.stringify({ ...claims, pad: "x".repeat(Math.floor((missing * 3) / 4)) }));
  }
  return `${header}.${payload}.${signature}`;
}

/** An ID token for `CLIENT_ID` issued now (by `Date.now()`, so fake timers apply), valid 15 minutes. */
export function fakeIdToken(subject = "user-1", padTo = 0): string {
  const iat = Math.floor(Date.now() / 1000);
  return fakeJwt({ iss: ISSUER, sub: subject, aud: CLIENT_ID, iat, exp: iat + 15 * 60, auth_time: iat }, padTo);
}

/** A successful token endpoint response. Leave `idToken` out for a response without one. */
export function tokenResponse(tokens: { accessToken: string; refreshToken?: string; idToken?: string; expiresIn?: number }) {
  return Response.json({
    access_token: tokens.accessToken,
    token_type: "Bearer",
    expires_in: tokens.expiresIn ?? 899,
    scope: "openid",
    ...(tokens.refreshToken ? { refresh_token: tokens.refreshToken } : {}),
    ...(tokens.idToken ? { id_token: tokens.idToken } : {}),
  });
}

/** An OAuth error response, e.g. `oauthError(400, "invalid_grant")`. */
export function oauthError(status: number, error: string): Response {
  return Response.json({ error }, { status });
}

/** `fetch()`'s own failure when nothing answers (connection refused, DNS, reset). */
export function networkError(): TypeError {
  return new TypeError("fetch failed");
}

/** What `AbortSignal.timeout()` makes `fetch()` reject with. */
export function timeoutError(): DOMException {
  return new DOMException("The operation was aborted due to timeout", "TimeoutError");
}
