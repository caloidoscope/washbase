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
    const now = Date.now();
    const lifetimeSeconds = tokens.expiresIn() ?? DEFAULT_TOKEN_LIFETIME_SECONDS;
    return { accessToken: tokens.access_token, expiresAt: now + lifetimeSeconds * 1000, signedInAt: now };
  } catch (error) {
    throw toAuthError(error);
  }
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
