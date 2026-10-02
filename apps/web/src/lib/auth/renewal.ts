import "server-only";
import { createHash } from "node:crypto";
import { AuthUnavailableError, SignInFailedError, renewTokens } from "./oidc";
import type { SessionData } from "./session";

// Session renewal (CAR-18), called only from `src/proxy.ts`. Never logs a token (not even a hash of one): outcomes
// are logged by kind only.
//
// Why single-flight: the API rotates the refresh token on every renewal and, if a replaced refresh token is ever
// presented again, ends the whole sign-in (reuse detection, ADR-001 Amendment 1). A page load can send several
// requests carrying the same cookie at once (document, RSC payloads, a second tab), and a request can still carry
// the old cookie for a moment after another one renewed it. So:
//   1. Concurrent calls with the same refresh token share ONE request to the token endpoint.
//   2. After it settles, a "renewed" or "refused" result stays cached for RESULT_TTL_MS, keyed by the presented
//      token's SHA-256, so a late request with the old cookie gets the same new session (and sets the same cookie)
//      instead of replaying the old token.
//   3. An "unavailable" result is NOT cached: the next request retries (the old token wasn't consumed).
//
// The cache is per Node.js process (proxy runs on the Node.js runtime in Next 16; module state persists between
// requests in `next start` and `next dev`). Several web instances need sticky sessions: tracked in CAR-38.

/** How long a settled renewal stays cached for requests still carrying the old cookie. */
export const RESULT_TTL_MS = 30_000;

export type RenewalOutcome =
  /** New tokens. The proxy re-seals the cookie (full Max-Age) and forwards it to the current request. */
  | { kind: "renewed"; session: SessionData }
  /** The API refused the refresh token (`invalid_grant` and other 4xx OAuth errors: expired after 30 days without
   *  use, revoked by sign-out, or the sign-in was ended by reuse detection). The proxy deletes the cookie and
   *  redirects to `/auth/login`. */
  | { kind: "refused" }
  /** Network failure, timeout or 5xx. The proxy keeps the cookie and lets the request through; the home page shows
   *  "Can't reach Washbase right now. Try again." (`getCurrentUser` sees a still-expired access token). */
  | { kind: "unavailable" };

/**
 * Renews `session` through `renewTokens` (oidc.ts), at most once per refresh token per process (see above).
 *
 * The renewed session keeps `signedInAt`, takes the new `accessToken`, `expiresAt` and `refreshToken`, and the new
 * `idToken` (the old one only if the response has none). Never rejects: every failure is an outcome.
 */
export async function renewSession(session: SessionData): Promise<RenewalOutcome> {
  const now = Date.now();
  for (const [key, entry] of settled) {
    if (now - entry.settledAt >= RESULT_TTL_MS) settled.delete(key);
  }

  const key = tokenKey(session.refreshToken);
  const cached = settled.get(key);
  if (cached) return cached.outcome;
  const pending = inFlight.get(key);
  if (pending) return pending;

  const renewal = renewOnce(session).then((outcome) => {
    inFlight.delete(key);
    // "unavailable" isn't cached: the presented token wasn't consumed, so the next request may retry it.
    if (outcome.kind !== "unavailable") settled.set(key, { outcome, settledAt: Date.now() });
    return outcome;
  });
  inFlight.set(key, renewal);
  return renewal;
}

/** Renewals in progress, by `tokenKey` of the presented refresh token. */
const inFlight = new Map<string, Promise<RenewalOutcome>>();
/** Settled "renewed"/"refused" outcomes, by `tokenKey` of the presented refresh token, for RESULT_TTL_MS. */
const settled = new Map<string, { outcome: RenewalOutcome; settledAt: number }>();

/** The map key for a refresh token: its SHA-256, hex. The token itself is never kept here. */
function tokenKey(refreshToken: string): string {
  return createHash("sha256").update(refreshToken).digest("hex");
}

/** One request to the token endpoint, mapped to an outcome. Never rejects. */
async function renewOnce(session: SessionData): Promise<RenewalOutcome> {
  try {
    const tokens = await renewTokens(session.refreshToken);
    return {
      kind: "renewed",
      session: {
        accessToken: tokens.accessToken,
        expiresAt: tokens.expiresAt,
        refreshToken: tokens.refreshToken,
        idToken: tokens.idToken ?? session.idToken,
        signedInAt: session.signedInAt,
      },
    };
  } catch (error) {
    if (error instanceof SignInFailedError) {
      // The message holds only the openid-client error's name, code and OAuth error (e.g. invalid_grant).
      console.warn(`Session renewal refused: ${error.message}`);
      return { kind: "refused" };
    }
    if (error instanceof AuthUnavailableError) {
      console.warn("Session renewal couldn't reach the authorization server");
    } else {
      console.error(`Session renewal failed (${error instanceof Error ? error.name : typeof error})`);
    }
    return { kind: "unavailable" };
  }
}

/** Test-only: forgets every in-flight and cached renewal, so Vitest tests are independent. */
export function resetRenewalsForTests(): void {
  inFlight.clear();
  settled.clear();
}
