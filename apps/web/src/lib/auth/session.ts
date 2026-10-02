import "server-only";
import { cookies } from "next/headers";
import { getIronSession, sealData, unsealData, type SessionOptions, type UnsealErrorReason } from "iron-session";
import { SESSION_COOKIE, TRANSACTION_COOKIE } from "./cookies";
import { authEnv } from "./env";

/**
 * What the encrypted session cookie (`SESSION_COOKIE`) holds (CAR-18). Server-only: never pass this object, or a
 * token in it, to a Client Component, into rendered output or into a log line.
 *
 * Renewal (`src/proxy.ts` → `renewSession`) replaces `accessToken`, `expiresAt`, `refreshToken` and `idToken`
 * (the latest ID token, so the logout's `id_token_hint` always matches the authorization's current one; kept
 * unchanged only if the token response has no `id_token`). `signedInAt` is never changed by a renewal.
 * A cookie from before CAR-18 (no `refreshToken`/`idToken`) is not a session: the person signs in again.
 */
export interface SessionData {
  /** The API access token (JWT, 15 minutes). */
  accessToken: string;
  /** When the access token expires, epoch milliseconds (from the token response's `expires_in`). An expired access
   *  token doesn't end the session: it means "renew" (done by `src/proxy.ts`). */
  expiresAt: number;
  /** The opaque refresh token. Rotated on every renewal; the API ends the whole sign-in if a replaced one is ever
   *  presented again, so renew at most once per refresh token (`renewSession` is single-flight). */
  refreshToken: string;
  /** The latest ID token: `id_token_hint` for RP-initiated logout (`/connect/logout`). */
  idToken: string;
  /** When this sign-in happened, epoch milliseconds. Used by the home page's redirect-loop guard. */
  signedInAt: number;
}

/** Renew the access token when it expires within this many milliseconds (or has already expired). */
export const RENEW_BEFORE_EXPIRY_MS = 60_000;

/** Whether `session`'s access token is expired or expires within `RENEW_BEFORE_EXPIRY_MS`. */
export function needsRenewal(session: SessionData, now: number = Date.now()): boolean {
  return session.expiresAt - now <= RENEW_BEFORE_EXPIRY_MS;
}

/** The session cookie's attributes, the same for every write (sign-in, renewal in the proxy): `HttpOnly; Secure;
 *  SameSite=Lax; Path=/` and `Max-Age` = `authEnv().sessionMaxAgeSeconds` (so each write restarts the 30 days). */
export interface SessionCookieOptions {
  httpOnly: true;
  secure: true;
  sameSite: "lax";
  path: "/";
  /** Seconds. */
  maxAge: number;
}

export function sessionCookieOptions(): SessionCookieOptions {
  return { httpOnly: true, secure: true, sameSite: "lax", path: "/", maxAge: authEnv().sessionMaxAgeSeconds };
}

/** Encrypts `data` into the session cookie's value (iron-session `sealData`, password `authEnv().sessionSecret`,
 *  `ttl` = `authEnv().sessionMaxAgeSeconds`). For `src/proxy.ts`, which sets cookies on `NextResponse`, not
 *  through `next/headers`. */
export async function sealSession(data: SessionData): Promise<string> {
  const { accessToken, expiresAt, refreshToken, idToken, signedInAt } = data;
  const env = authEnv();
  return sealData(
    { accessToken, expiresAt, refreshToken, idToken, signedInAt } satisfies SessionData,
    { password: env.sessionSecret, ttl: env.sessionMaxAgeSeconds },
  );
}

/** Decrypts and validates a session cookie value. `undefined` when it is missing, can't be decrypted, its seal has
 *  expired (no use for `SESSION_MAX_AGE_DAYS`), or it lacks any SessionData field (e.g. a pre-CAR-18 cookie).
 *  Does NOT treat an expired access token as "no session". Logs the reason only (never the value). */
export async function unsealSession(value: string | undefined): Promise<SessionData | undefined> {
  if (!value) return undefined;
  const env = authEnv();
  const data = await unsealData<Partial<Record<keyof SessionData, unknown>>>(value, {
    password: env.sessionSecret,
    ttl: env.sessionMaxAgeSeconds,
    onUnsealError: logUnsealError(SESSION_COOKIE),
  });
  const { accessToken, expiresAt, refreshToken, idToken, signedInAt } = data;
  if (typeof accessToken !== "string" || !accessToken) return undefined;
  if (typeof refreshToken !== "string" || !refreshToken) return undefined;
  if (typeof idToken !== "string" || !idToken) return undefined;
  if (typeof expiresAt !== "number" || !Number.isFinite(expiresAt)) return undefined;
  if (typeof signedInAt !== "number" || !Number.isFinite(signedInAt)) return undefined;
  return { accessToken, expiresAt, refreshToken, idToken, signedInAt };
}

/** What the encrypted transaction cookie (`TRANSACTION_COOKIE`) holds between `/auth/login` and `/auth/callback`. */
export interface AuthTransaction {
  codeVerifier: string;
  state: string;
  nonce: string;
}

/** Sign-in transaction lifetime, seconds. */
const TRANSACTION_TTL_SECONDS = 10 * 60;

/** Logs why a cookie was rejected: the reason only, never the cookie or the error (which could quote it). */
function logUnsealError(cookieName: string) {
  return (reason: UnsealErrorReason) => {
    if (reason !== "expired") console.warn(`Ignored an unreadable ${cookieName} cookie (${reason})`);
  };
}

function transactionOptions(): SessionOptions {
  return {
    cookieName: TRANSACTION_COOKIE,
    password: authEnv().sessionSecret,
    ttl: TRANSACTION_TTL_SECONDS,
    cookieOptions: { httpOnly: true, secure: true, sameSite: "lax", path: "/auth" },
    onUnsealError: logUnsealError(TRANSACTION_COOKIE),
  };
}

/**
 * The current session, or `undefined` when there is none or it can't be used (see `unsealSession`).
 * Usable in Server Components and Route Handlers. Server Components see the cookie the proxy forwarded, so after a
 * successful renewal this is already the renewed session.
 *
 * The access token may be expired: that is a renewal the proxy couldn't do (API unreachable), which
 * `getCurrentUser` reports as "unavailable".
 */
export async function getSession(): Promise<SessionData | undefined> {
  return unsealSession((await cookies()).get(SESSION_COOKIE)?.value);
}

/**
 * Writes the session cookie. Route Handlers only (cookies can't be set while a Server Component renders).
 * Same value format and attributes (`sessionCookieOptions()`, Max-Age = SESSION_MAX_AGE_DAYS) as the proxy's write
 * on renewal.
 */
export async function saveSession(data: SessionData): Promise<void> {
  (await cookies()).set(SESSION_COOKIE, await sealSession(data), sessionCookieOptions());
}

/** Deletes the session cookie (sign-out). Route Handlers only. Safe to call when there is no session. */
export async function clearSession(): Promise<void> {
  // Path=/ explicitly, matching the cookie's own path: otherwise the browser keeps it.
  (await cookies()).delete({ name: SESSION_COOKIE, path: "/" });
}

/** Writes the transaction cookie (10 minutes). Route Handlers only. */
export async function saveTransaction(transaction: AuthTransaction): Promise<void> {
  const tx = await getIronSession<AuthTransaction>(await cookies(), transactionOptions());
  tx.codeVerifier = transaction.codeVerifier;
  tx.state = transaction.state;
  tx.nonce = transaction.nonce;
  await tx.save();
}

/** Reads the transaction cookie and deletes it (single use). `undefined` if missing, expired or unreadable.
 *  Route Handlers only. */
export async function takeTransaction(): Promise<AuthTransaction | undefined> {
  const tx = await getIronSession<AuthTransaction>(await cookies(), transactionOptions());
  const { codeVerifier, state, nonce } = tx;
  tx.destroy();
  if (!codeVerifier || !state || !nonce) return undefined;
  return { codeVerifier, state, nonce };
}
