// Names of the web app's auth cookies (ADR-001). Deliberately NOT `server-only`, and holds no secrets:
// `src/proxy.ts` imports it for its optimistic "is there a session cookie?" check.

/** Encrypted session (iron-session): `HttpOnly; Secure; SameSite=Lax; Path=/`, expires with the access token. */
export const SESSION_COOKIE = "washbase_session";

/** Encrypted sign-in transaction (PKCE verifier, state, nonce): `HttpOnly; Secure; SameSite=Lax; Path=/auth`, 10 minutes. */
export const TRANSACTION_COOKIE = "washbase_auth_tx";
