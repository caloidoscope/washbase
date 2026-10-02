/**
 * The authorization server refused the request with an OAuth error (a 4xx such as `invalid_grant`: the refresh token
 * expired after 30 days without use, was revoked, or was already replaced). The session is over: sign in again.
 */
export class OAuthError extends Error {
  constructor(
    readonly code: string,
    readonly status: number,
  ) {
    super(`OAuth error ${code} (HTTP ${status})`);
    this.name = "OAuthError";
  }
}

/** Network failure, timeout, 5xx, 408/429 or an unreadable answer: keep the session and show "Can't reach…". */
export class UnreachableError extends Error {
  constructor(readonly reason: string) {
    super(`Can't reach the server (${reason})`);
    this.name = "UnreachableError";
  }
}

/** The session has ended locally (no stored refresh token, or it was refused and cleared). */
export class SignedOutError extends Error {
  constructor(readonly reason: string) {
    super(`Signed out (${reason})`);
    this.name = "SignedOutError";
  }
}

/** A log-safe description of an error: its class name and, for OAuth errors, the error code. Never the message of an
 *  unknown error, which could carry a URL or body with a token in it. */
export function describeError(error: unknown): string {
  if (error instanceof OAuthError) return `${error.name} ${error.code} ${error.status}`;
  if (error instanceof UnreachableError || error instanceof SignedOutError) return `${error.name} ${error.reason}`;
  if (error instanceof Error) return error.name;
  return typeof error;
}
