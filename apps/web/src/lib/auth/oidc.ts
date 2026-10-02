import "server-only";
import * as client from "openid-client";
import { authEnv } from "./env";
import type { AuthTransaction, SessionData } from "./session";

// Never log tokens, codes, the client secret or the PKCE verifier: errors are reported by their name/code only.

/** Seconds to wait for the authorization server (discovery, then every request made with the Configuration). */
const REQUEST_TIMEOUT_SECONDS = 10;
/** Used when the token response has no `expires_in`: the API's access tokens live 15 minutes (ADR-001). */
const DEFAULT_TOKEN_LIFETIME_SECONDS = 15 * 60;

let configuration: Promise<client.Configuration> | undefined;

/** The discovered `washbase-web` client configuration, cached for the process. A failed discovery is not cached,
 *  so an API that was down is retried on the next sign-in. */
function oidcConfiguration(): Promise<client.Configuration> {
  if (!configuration) {
    const env = authEnv();
    const pending = client
      .discovery(env.issuer, env.clientId, undefined, client.ClientSecretBasic(env.clientSecret), {
        // Plain http is only for local runs and CI, where the issuer is http://localhost:<port>.
        execute: env.issuer.protocol === "http:" ? [client.allowInsecureRequests] : [],
        timeout: REQUEST_TIMEOUT_SECONDS,
      })
      .catch((error: unknown) => {
        configuration = undefined;
        throw toAuthError(error);
      });
    configuration = pending;
  }
  return configuration;
}

/**
 * Starts a sign-in: a fresh PKCE verifier (S256 challenge), state and nonce, and the authorization URL
 * (`response_type=code`, `scope=openid`, `redirect_uri` = `authEnv().redirectUri`).
 * Rejects with `AuthUnavailableError` when the issuer can't be reached.
 */
export async function startSignIn(): Promise<{ authorizationUrl: URL; transaction: AuthTransaction }> {
  const config = await oidcConfiguration();
  const codeVerifier = client.randomPKCECodeVerifier();
  const transaction: AuthTransaction = {
    codeVerifier,
    state: client.randomState(),
    nonce: client.randomNonce(),
  };
  const authorizationUrl = client.buildAuthorizationUrl(config, {
    response_type: "code",
    scope: "openid",
    redirect_uri: authEnv().redirectUri.href,
    code_challenge: await client.calculatePKCECodeChallenge(codeVerifier),
    code_challenge_method: "S256",
    state: transaction.state,
    nonce: transaction.nonce,
  });
  return { authorizationUrl, transaction };
}

/**
 * Finishes a sign-in from the callback's query string: `authorizationCodeGrant` with the transaction's
 * `pkceCodeVerifier`, `expectedState` and `expectedNonce`. The `currentUrl` passed to openid-client is
 * `authEnv().redirectUri` with `callbackSearch` as its query (not the incoming request URL, whose host can differ
 * from the registered redirect URI, e.g. 127.0.0.1 vs localhost or behind a proxy).
 * Rejects on any failure (`error=` in the callback, state/nonce mismatch, invalid code, API down): with
 * `AuthUnavailableError` when the API can't be reached, otherwise with `SignInFailedError`.
 */
export async function completeSignIn(callbackSearch: URLSearchParams, transaction: AuthTransaction): Promise<SessionData> {
  const config = await oidcConfiguration();
  const currentUrl = new URL(authEnv().redirectUri.href);
  currentUrl.search = callbackSearch.toString();
  try {
    const tokens = await client.authorizationCodeGrant(config, currentUrl, {
      pkceCodeVerifier: transaction.codeVerifier,
      expectedState: transaction.state,
      expectedNonce: transaction.nonce,
      idTokenExpected: true,
    });
    // CAR-18: the session keeps the refresh token (staying signed in) and the ID token (sign-out's id_token_hint).
    if (!tokens.refresh_token || !tokens.id_token) {
      throw new SignInFailedError("token response without refresh_token or id_token");
    }
    const now = Date.now();
    const lifetimeSeconds = tokens.expiresIn() ?? DEFAULT_TOKEN_LIFETIME_SECONDS;
    return {
      accessToken: tokens.access_token,
      expiresAt: now + lifetimeSeconds * 1000,
      refreshToken: tokens.refresh_token,
      idToken: tokens.id_token,
      signedInAt: now,
    };
  } catch (error) {
    throw toAuthError(error);
  }
}

/** What a renewal returns. `idToken` is absent only if the token response had none (then keep the stored one). */
export interface RenewedTokens {
  accessToken: string;
  /** Epoch milliseconds, from `expires_in` (default 15 minutes). */
  expiresAt: number;
  /** The rotated refresh token. The presented one is now "replaced": presenting it again ends the sign-in. */
  refreshToken: string;
  idToken?: string;
}

/**
 * One renewal at the API: `POST /oauth2/token` with `grant_type=refresh_token` (openid-client `refreshTokenGrant`,
 * client_secret_basic). Never call this directly from request code: go through `renewSession` (renewal.ts), which
 * makes it single-flight per refresh token.
 *
 * Rejects with `AuthUnavailableError` (network, timeout, 5xx: keep the session) or `SignInFailedError` (an OAuth
 * error such as `invalid_grant`: expired, revoked or reused, so the session is over). Neither carries a token.
 */
export async function renewTokens(refreshToken: string): Promise<RenewedTokens> {
  const config = await oidcConfiguration();
  try {
    const tokens = await client.refreshTokenGrant(config, refreshToken);
    // The API always rotates: a response without a new refresh token can't keep the session going.
    if (!tokens.refresh_token) throw new SignInFailedError("token response without refresh_token");
    const lifetimeSeconds = tokens.expiresIn() ?? DEFAULT_TOKEN_LIFETIME_SECONDS;
    return {
      accessToken: tokens.access_token,
      expiresAt: Date.now() + lifetimeSeconds * 1000,
      refreshToken: tokens.refresh_token,
      ...(tokens.id_token ? { idToken: tokens.id_token } : {}),
    };
  } catch (error) {
    throw toAuthError(error);
  }
}

/**
 * Revokes the session's refresh token: `POST /oauth2/revoke` with `token_type_hint=refresh_token` (openid-client
 * `tokenRevocation`, client_secret_basic). The hint matters: with it (or none) the API's reuse detection also
 * applies, so revoking an already-replaced refresh token ends the whole sign-in; with `access_token` it would not.
 * Revoking the refresh token also invalidates its access token. The API answers 200 for an unknown token (RFC 7009).
 *
 * Rejects with `AuthUnavailableError` (network, timeout, 5xx) or `SignInFailedError` (4xx, e.g. `invalid_client`).
 */
export async function revokeRefreshToken(refreshToken: string): Promise<void> {
  const config = await oidcConfiguration();
  try {
    await client.tokenRevocation(config, refreshToken, { token_type_hint: "refresh_token" });
  } catch (error) {
    throw toAuthError(error);
  }
}

/**
 * The RP-initiated logout URL (`end_session_endpoint` from discovery, i.e. `{issuer}/connect/logout`) with
 * `id_token_hint` = `idToken`, `post_logout_redirect_uri` = `authEnv().postLogoutRedirectUri` and `client_id`, all
 * in the query (openid-client `buildEndSessionUrl`). Rejects with `AuthUnavailableError` if discovery fails.
 */
export async function endSessionUrl(idToken: string): Promise<URL> {
  const config = await oidcConfiguration();
  try {
    // buildEndSessionUrl adds client_id itself.
    return client.buildEndSessionUrl(config, {
      id_token_hint: idToken,
      post_logout_redirect_uri: authEnv().postLogoutRedirectUri.href,
    });
  } catch (error) {
    throw toAuthError(error);
  }
}

/**
 * Whether the API would accept this end-session URL, checked server-side before sending the browser there:
 * `GET url` with `redirect: "manual"` and a short timeout. Accepted = a 3xx whose `Location` is exactly
 * `authEnv().postLogoutRedirectUri`.
 *
 * The API answers 400 (an error page the person would be stuck on) when no authorization holds this exact ID token:
 * the sign-in was already ended by reuse detection, or the cookie's ID token is stale. Sign-out then just goes to
 * `/`. Without a browser session at the API (it ends right after sign-in, ADR-001 Amendment 1) this request
 * changes nothing at the API.
 *
 * Resolves `false` for any non-accepted answer; rejects with `AuthUnavailableError` on network failure or timeout.
 * Never logs the URL (it contains the ID token).
 */
export async function isEndSessionAccepted(url: URL): Promise<boolean> {
  let response: Response;
  try {
    response = await fetch(url, {
      method: "GET",
      redirect: "manual",
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_SECONDS * 1000),
    });
  } catch {
    // Network failure, timeout or abort. The error is dropped: its message or cause could quote the URL.
    throw new AuthUnavailableError();
  }
  // Only the status and Location are needed; don't leave the body stream open.
  await response.body?.cancel().catch(() => {});
  if (response.status < 300 || response.status >= 400) return false;
  const location = response.headers.get("location");
  if (!location) return false;
  let target: URL;
  try {
    target = new URL(location, url);
  } catch {
    return false;
  }
  return target.href === authEnv().postLogoutRedirectUri.href;
}

/** The authorization server (the API) couldn't be reached. */
export class AuthUnavailableError extends Error {
  constructor(options?: ErrorOptions) {
    super("The authorization server can't be reached", options);
    this.name = "AuthUnavailableError";
  }
}

/** The sign-in was refused or invalid (OAuth error, state/nonce/PKCE mismatch, bad code, misconfiguration).
 *  The message is safe to log: it holds only the openid-client error's name and code. */
export class SignInFailedError extends Error {
  constructor(detail: string) {
    super(`Sign-in didn't complete (${detail})`);
    this.name = "SignInFailedError";
  }
}

/** Maps any openid-client failure to one of our two errors, without carrying the original (its message or cause
 *  can quote the code, the token response or the request). */
export function toAuthError(error: unknown): AuthUnavailableError | SignInFailedError {
  if (error instanceof AuthUnavailableError || error instanceof SignInFailedError) return error;
  if (isUnreachable(error)) return new AuthUnavailableError();
  const name = error instanceof Error ? error.name : typeof error;
  const code = error instanceof Error && "code" in error && typeof error.code === "string" ? error.code : undefined;
  const oauthError =
    error instanceof client.AuthorizationResponseError || error instanceof client.ResponseBodyError
      ? error.error
      : undefined;
  return new SignInFailedError([name, code, oauthError].filter(Boolean).join(", "));
}

/** A network failure, a timeout, or a 5xx answer from the authorization server. */
function isUnreachable(error: unknown): boolean {
  // fetch() failures are a plain TypeError ("fetch failed"); openid-client's own argument errors carry a `code`.
  if (error instanceof TypeError && !("code" in error)) return true;
  if (error instanceof DOMException && (error.name === "TimeoutError" || error.name === "AbortError")) return true;
  // openid-client rethrows fetch's TimeoutError/AbortError as a ClientError with one of these codes.
  if (error instanceof client.ClientError && (error.code === "OAUTH_TIMEOUT" || error.code === "OAUTH_ABORT")) return true;
  if (error instanceof client.ResponseBodyError) return error.status >= 500;
  if (error instanceof client.ClientError && error.cause instanceof Response) return error.cause.status >= 500;
  return false;
}
