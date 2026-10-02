import type { BrowserContext, Cookie } from "@playwright/test";
import { sealData, unsealData } from "iron-session";
import { LOCAL_SESSION_SECRET } from "../../../../scripts/local-env.mjs";

// Reads and rewrites the web app's encrypted session cookie (`washbase_session`, CAR-18) from a test, with the
// publicly known local key the E2E web server runs with (scripts/local-env.mjs → `localWebEnv`). This is how the
// specs make an access token "expire", read the refresh token, or make a sign-in look older, without waiting.
// Test-only: the real cookie is HttpOnly, so browser JavaScript can't do any of this.

export const SESSION_COOKIE = "washbase_session";
/** `SESSION_MAX_AGE_DAYS` in `localWebEnv` (30), in seconds: the seal's ttl and the cookie's Max-Age. */
export const SESSION_MAX_AGE_SECONDS = 30 * 24 * 60 * 60;
export const DAY_MS = 24 * 60 * 60 * 1000;

/** Same shape as `SessionData` in src/lib/auth/session.ts (not imported: that module is `server-only`). */
export interface SessionData {
  accessToken: string;
  expiresAt: number;
  refreshToken: string;
  idToken: string;
  signedInAt: number;
}

/** The context's session cookie, or `undefined` when there is none. */
export async function sessionCookie(context: BrowserContext): Promise<Cookie | undefined> {
  return (await context.cookies()).find((c) => c.name === SESSION_COOKIE);
}

/** Decrypts the context's session cookie. Throws when there is none or it can't be read. */
export async function readSession(context: BrowserContext): Promise<SessionData> {
  const cookie = await sessionCookie(context);
  if (!cookie) throw new Error(`no ${SESSION_COOKIE} cookie`);
  const data = await unsealData<SessionData>(cookie.value, {
    password: LOCAL_SESSION_SECRET,
    ttl: SESSION_MAX_AGE_SECONDS,
  });
  if (!data.refreshToken) throw new Error(`${SESSION_COOKIE} could not be read`);
  return data;
}

/**
 * Seals `data` as the web app does. `sealedAgoMs` back-dates the seal, as if it had been written that long ago: its
 * expiry (sealed time + 30 days) is what the app checks, so this simulates a cookie last renewed in the past.
 */
export async function sealSession(data: SessionData, { sealedAgoMs = 0 } = {}): Promise<string> {
  const realNow = Date.now;
  const sealedAt = realNow() - sealedAgoMs;
  Date.now = () => sealedAt;
  try {
    return await sealData(data, { password: LOCAL_SESSION_SECRET, ttl: SESSION_MAX_AGE_SECONDS });
  } finally {
    Date.now = realNow;
  }
}

/**
 * Replaces the session cookie in `context` (same name, domain, path and flags as the one the app set) with `data`.
 * The browser cookie's own expiry follows the seal (`sealedAgoMs`), as it would have in reality, unless `expires`
 * (seconds since the epoch) overrides it, e.g. to keep a cookie whose seal has expired so the app has to reject it.
 */
export async function writeSession(
  context: BrowserContext,
  data: SessionData,
  { sealedAgoMs = 0, expires }: { sealedAgoMs?: number; expires?: number } = {},
): Promise<void> {
  const base = await sessionCookie(context);
  if (!base) throw new Error(`no ${SESSION_COOKIE} cookie to replace`);
  const cookieExpires = expires ?? Math.floor((Date.now() - sealedAgoMs) / 1000) + SESSION_MAX_AGE_SECONDS;
  await context.addCookies([
    { ...base, value: await sealSession(data, { sealedAgoMs }), expires: cookieExpires },
  ]);
}

/** Rewrites the session cookie with `change` applied (e.g. an access token that has expired). */
export async function updateSession(
  context: BrowserContext,
  change: (session: SessionData) => Partial<SessionData>,
  options: { sealedAgoMs?: number } = {},
): Promise<SessionData> {
  const session = await readSession(context);
  const updated = { ...session, ...change(session) };
  await writeSession(context, updated, options);
  return updated;
}

/** The access token expired a minute ago: the next page load makes the web app renew the session. */
export function expiredAccessToken(): Partial<SessionData> {
  return { expiresAt: Date.now() - 60_000 };
}

/** The sign-in happened 10 minutes ago, past the home page's 30-second redirect-loop guard (CAR-17), so an API 401
 *  sends the browser to sign-in instead of "Sign-in didn't complete". */
export function agedSignIn(): Partial<SessionData> {
  return { signedInAt: Date.now() - 10 * 60_000 };
}
