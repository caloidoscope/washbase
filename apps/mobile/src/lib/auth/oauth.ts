import { fetchDiscoveryAsync } from "expo-auth-session";

import { CLIENT_ID, REQUEST_TIMEOUT_MS } from "./config";
import { describeError, OAuthError, UnreachableError } from "./errors";

/** The authorization server's endpoints the app uses. */
export interface AuthEndpoints {
  authorizationEndpoint: string;
  tokenEndpoint: string;
  revocationEndpoint: string;
}

/** Tokens from the token endpoint. `expiresAt` is in epoch milliseconds. */
export interface Tokens {
  accessToken: string;
  refreshToken: string;
  expiresAt: number;
}

/** What the token endpoint client can do; injected into the session so tests can fake it. */
export interface TokenClient {
  exchangeCode(code: string, codeVerifier: string, redirectUri: string): Promise<Tokens>;
  refresh(refreshToken: string): Promise<Tokens>;
  revoke(refreshToken: string): Promise<void>;
}

/** Spring Authorization Server's standard endpoint paths, used when discovery isn't available. */
export function endpointsFromIssuer(issuer: string): AuthEndpoints {
  return {
    authorizationEndpoint: `${issuer}/oauth2/authorize`,
    tokenEndpoint: `${issuer}/oauth2/token`,
    revocationEndpoint: `${issuer}/oauth2/revoke`,
  };
}

function withTimeout<T>(promise: Promise<T>, ms: number): Promise<T> {
  let timer: ReturnType<typeof setTimeout> | undefined;
  const timeout = new Promise<never>((_, reject) => {
    timer = setTimeout(() => reject(new UnreachableError("timeout")), ms);
  });
  return Promise.race([promise, timeout]).finally(() => clearTimeout(timer));
}

/** True when `url` is on the configured issuer (same scheme, host and port, below its path). */
function isUnderIssuer(url: string | undefined, issuer: string): url is string {
  return typeof url === "string" && url.startsWith(`${issuer}/`);
}

/**
 * Resolves the endpoints through OIDC discovery (`fetchDiscoveryAsync`; `expo-auth-session` itself accepts an `http`
 * issuer, which local phone testing uses). The document is only trusted when its `issuer` equals the configured one
 * (OpenID Connect Discovery §4.3; `fetchDiscoveryAsync` doesn't check it) and every endpoint is on that issuer, so a
 * tampered or misconfigured answer can never send the code, verifier or refresh token elsewhere. Otherwise, or if
 * discovery fails, falls back to the issuer's standard paths, which come only from the configuration, so sign-in can
 * still open (the sign-in page then shows its own error if the server is down). Only a trusted discovery is cached.
 */
export function createEndpointResolver(issuer: string, discover = fetchDiscoveryAsync) {
  let cached: AuthEndpoints | undefined;
  return async function resolveEndpoints(): Promise<AuthEndpoints> {
    if (cached) return cached;
    try {
      const doc = await withTimeout(discover(issuer), REQUEST_TIMEOUT_MS);
      if (
        doc.discoveryDocument?.issuer === issuer &&
        isUnderIssuer(doc.authorizationEndpoint, issuer) &&
        isUnderIssuer(doc.tokenEndpoint, issuer) &&
        isUnderIssuer(doc.revocationEndpoint, issuer)
      ) {
        cached = {
          authorizationEndpoint: doc.authorizationEndpoint,
          tokenEndpoint: doc.tokenEndpoint,
          revocationEndpoint: doc.revocationEndpoint,
        };
        return cached;
      }
      console.warn("Sign-in discovery was incomplete or not for the configured issuer; using its standard endpoints");
    } catch (error) {
      console.warn(`Sign-in discovery failed (${describeError(error)}); using the issuer's standard endpoints`);
    }
    return endpointsFromIssuer(issuer);
  };
}

/** The subset of `fetch` the token client uses (injectable for tests). */
export type Fetch = (url: string, init: RequestInit) => Promise<Response>;

function formBody(params: Record<string, string>): string {
  return Object.entries(params)
    .map(([key, value]) => `${encodeURIComponent(key)}=${encodeURIComponent(value)}`)
    .join("&");
}

/**
 * POSTs a form to the authorization server and classifies failures by HTTP status. `expo-auth-session`'s own token
 * helpers drop the status (a 5xx JSON body with an `error` field would look like `invalid_grant` and sign the person
 * out) and have no timeout, so this small client is used for the token and revocation endpoints instead.
 *
 * Throws `UnreachableError` on a network error, timeout, 5xx, 408 or 429; `OAuthError` on any other non-2xx.
 */
async function postForm(fetchImpl: Fetch, url: string, params: Record<string, string>): Promise<Response> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
  let response: Response;
  try {
    response = await fetchImpl(url, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded", Accept: "application/json" },
      body: formBody(params),
      signal: controller.signal,
    });
  } catch (error) {
    throw new UnreachableError(controller.signal.aborted ? "timeout" : describeError(error));
  } finally {
    clearTimeout(timer);
  }
  if (response.ok) return response;
  if (response.status >= 500 || response.status === 408 || response.status === 429) {
    throw new UnreachableError(`HTTP ${response.status}`);
  }
  let code = "invalid_request";
  try {
    const body = (await response.json()) as { error?: unknown };
    if (typeof body.error === "string") code = body.error;
  } catch {
    // Not JSON: keep the generic code.
  }
  throw new OAuthError(code, response.status);
}

async function readTokens(response: Response, now: () => number): Promise<Tokens> {
  let body: { access_token?: unknown; refresh_token?: unknown; token_type?: unknown; expires_in?: unknown };
  try {
    body = await response.json();
  } catch {
    throw new UnreachableError("unreadable token response");
  }
  if (
    typeof body.access_token !== "string" ||
    !body.access_token ||
    typeof body.refresh_token !== "string" ||
    !body.refresh_token
  ) {
    // A rotated refresh token is required: without it the stored one is already replaced and can't be used again.
    throw new OAuthError("incomplete_token_response", response.status);
  }
  if (typeof body.token_type !== "string" || body.token_type.toLowerCase() !== "bearer") {
    // The API only accepts Bearer tokens (RFC 6749 §5.1: token_type is required, case-insensitive).
    throw new OAuthError("unsupported_token_type", response.status);
  }
  const expiresIn =
    typeof body.expires_in === "number" && Number.isFinite(body.expires_in) && body.expires_in > 0
      ? body.expires_in
      : 300;
  return { accessToken: body.access_token, refreshToken: body.refresh_token, expiresAt: now() + expiresIn * 1000 };
}

/** The token endpoint client for the public `washbase-mobile` client: `client_id` only, never a secret. */
export function createTokenClient(
  resolveEndpoints: () => Promise<AuthEndpoints>,
  { fetchImpl = (url, init) => fetch(url, init), now = Date.now }: { fetchImpl?: Fetch; now?: () => number } = {},
): TokenClient {
  return {
    async exchangeCode(code, codeVerifier, redirectUri) {
      const { tokenEndpoint } = await resolveEndpoints();
      const response = await postForm(fetchImpl, tokenEndpoint, {
        grant_type: "authorization_code",
        code,
        code_verifier: codeVerifier,
        redirect_uri: redirectUri,
        client_id: CLIENT_ID,
      });
      return readTokens(response, now);
    },
    async refresh(refreshToken) {
      const { tokenEndpoint } = await resolveEndpoints();
      const response = await postForm(fetchImpl, tokenEndpoint, {
        grant_type: "refresh_token",
        refresh_token: refreshToken,
        client_id: CLIENT_ID,
      });
      return readTokens(response, now);
    },
    async revoke(refreshToken) {
      const { revocationEndpoint } = await resolveEndpoints();
      await postForm(fetchImpl, revocationEndpoint, {
        token: refreshToken,
        token_type_hint: "refresh_token",
        client_id: CLIENT_ID,
      });
    },
  };
}
