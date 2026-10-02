import "server-only";

/** The web app's auth settings (ADR-001: `washbase-web` is a confidential OAuth client). */
export interface AuthEnv {
  /** `API_BASE_URL`, default `http://localhost:8080`. Where server-side `@washbase/api-client` calls go. */
  apiBaseUrl: string;
  /** `AUTH_ISSUER`, default `http://localhost:8080`. Must equal the API's `WASHBASE_AUTH_ISSUER` exactly. */
  issuer: URL;
  /** `AUTH_CLIENT_ID`, default `washbase-web`. */
  clientId: string;
  /** `AUTH_CLIENT_SECRET`, required. Must match the API's `WASHBASE_WEB_CLIENT_SECRET`. */
  clientSecret: string;
  /** `AUTH_REDIRECT_URI`, default `http://localhost:3000/auth/callback`. Must match the API's `WASHBASE_WEB_REDIRECT_URI`. */
  redirectUri: URL;
  /** `SESSION_SECRET`, required, at least 32 characters. Encrypts the session and transaction cookies. */
  sessionSecret: string;
}

const MIN_SESSION_SECRET_LENGTH = 32;

/** The variable's value, or `fallback` when unset or blank. */
function optional(name: string, fallback: string): string {
  const value = process.env[name]?.trim();
  return value ? value : fallback;
}

/** The variable's value. Throws, naming the variable (never its value), when it is unset or blank. */
function required(name: string): string {
  const value = process.env[name]?.trim();
  if (!value) throw new Error(`${name} must be set`);
  return value;
}

/** An absolute http(s) URL. Throws, naming the variable (never its value), when it is malformed. */
function httpUrl(name: string, value: string): URL {
  let url: URL;
  try {
    url = new URL(value);
  } catch {
    throw new Error(`${name} must be an absolute http(s) URL`);
  }
  if (url.protocol !== "http:" && url.protocol !== "https:") {
    throw new Error(`${name} must be an absolute http(s) URL`);
  }
  return url;
}

/**
 * Reads and validates the settings from `process.env` on each call, at request time, never at module load:
 * `next build` (CI) runs without these variables.
 *
 * A missing required variable, a SESSION_SECRET shorter than 32 characters, or a malformed URL throws an Error
 * that names the variable and never includes its value.
 */
export function authEnv(): AuthEnv {
  const apiBaseUrl = optional("API_BASE_URL", "http://localhost:8080");
  httpUrl("API_BASE_URL", apiBaseUrl);

  const sessionSecret = required("SESSION_SECRET");
  if (sessionSecret.length < MIN_SESSION_SECRET_LENGTH) {
    throw new Error(`SESSION_SECRET must be at least ${MIN_SESSION_SECRET_LENGTH} characters`);
  }

  return {
    apiBaseUrl,
    // Kept exactly as configured: openid-client compares it with the discovery document's `issuer`.
    issuer: httpUrl("AUTH_ISSUER", optional("AUTH_ISSUER", "http://localhost:8080")),
    clientId: optional("AUTH_CLIENT_ID", "washbase-web"),
    clientSecret: required("AUTH_CLIENT_SECRET"),
    redirectUri: httpUrl("AUTH_REDIRECT_URI", optional("AUTH_REDIRECT_URI", "http://localhost:3000/auth/callback")),
    sessionSecret,
  };
}
