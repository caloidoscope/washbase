import "server-only";
import { cookies } from "next/headers";
import { getIronSession, type SessionOptions, type UnsealErrorReason } from "iron-session";
import { SESSION_COOKIE, TRANSACTION_COOKIE } from "./cookies";
import { authEnv } from "./env";

/**
 * What the encrypted session cookie (`SESSION_COOKIE`) holds. Server-only: never pass this object, or the token
 * in it, to a Client Component or into rendered output. Refresh/ID tokens are not stored (sign-out and staying
 * signed in are CAR-18).
 */
export interface SessionData {
  /** The API access token (JWT, 15 minutes). */
  accessToken: string;
  /** When the access token expires, epoch milliseconds (from the token response's `expires_in`). */
  expiresAt: number;
  /** When this session was created, epoch milliseconds. Used by the home page's redirect-loop guard. */
  signedInAt: number;
}

/** What the encrypted transaction cookie (`TRANSACTION_COOKIE`) holds between `/auth/login` and `/auth/callback`. */
export interface AuthTransaction {
  codeVerifier: string;
  state: string;
  nonce: string;
}

/** Session lifetime, seconds: the access token's 15 minutes. iron-session expires the cookie 60 s earlier. */
const SESSION_TTL_SECONDS = 15 * 60;
/** Sign-in transaction lifetime, seconds. */
const TRANSACTION_TTL_SECONDS = 10 * 60;

/** Logs why a cookie was rejected: the reason only, never the cookie or the error (which could quote it). */
function logUnsealError(cookieName: string) {
  return (reason: UnsealErrorReason) => {
    if (reason !== "expired") console.warn(`Ignored an unreadable ${cookieName} cookie (${reason})`);
  };
}

function sessionOptions(): SessionOptions {
  return {
    cookieName: SESSION_COOKIE,
    password: authEnv().sessionSecret,
    ttl: SESSION_TTL_SECONDS,
    cookieOptions: { httpOnly: true, secure: true, sameSite: "lax", path: "/" },
    onUnsealError: logUnsealError(SESSION_COOKIE),
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

/** The current session, or `undefined` when there is none, it can't be decrypted, or its token has expired.
 *  Usable in Server Components and Route Handlers. */
export async function getSession(): Promise<SessionData | undefined> {
  const session = await getIronSession<SessionData>(await cookies(), sessionOptions());
  const { accessToken, expiresAt, signedInAt } = session;
  if (typeof accessToken !== "string" || !accessToken) return undefined;
  if (typeof expiresAt !== "number" || typeof signedInAt !== "number") return undefined;
  if (expiresAt <= Date.now()) return undefined;
  return { accessToken, expiresAt, signedInAt };
}

/** Writes the session cookie. Route Handlers only (cookies can't be set while a Server Component renders). */
export async function saveSession(data: SessionData): Promise<void> {
  const session = await getIronSession<SessionData>(await cookies(), sessionOptions());
  session.accessToken = data.accessToken;
  session.expiresAt = data.expiresAt;
  session.signedInAt = data.signedInAt;
  await session.save();
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
