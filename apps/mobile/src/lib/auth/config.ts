/** The mobile app's OAuth client (public, PKCE, no secret; ADR-001 Amendment 2). */
export const CLIENT_ID = "washbase-mobile";

/** The app's scheme (`app.json` → `scheme`) and the path the authorization server redirects back to. */
export const REDIRECT_SCHEME = "washbase";
export const REDIRECT_PATH = "auth/callback";

/** Renew the access token when it expires within this many milliseconds. */
export const RENEW_MARGIN_MS = 60_000;

/** How long a request to the authorization server or the API may take before it counts as "can't reach". */
export const REQUEST_TIMEOUT_MS = 15_000;

export interface AuthConfig {
  /** Base URL of the API (`EXPO_PUBLIC_API_BASE_URL`). A phone can't reach `localhost` on the PC: use its LAN IP. */
  apiBaseUrl: string;
  /** The authorization server's issuer (`EXPO_PUBLIC_AUTH_ISSUER`), defaulting to the API base URL (it's embedded). */
  issuer: string;
}

const DEFAULT_API_BASE_URL = "http://localhost:8080";

function withoutTrailingSlash(url: string): string {
  return url.replace(/\/+$/, "");
}

/**
 * Reads the configuration. `EXPO_PUBLIC_*` variables are inlined at bundle time, so they must be read as literal
 * `process.env.EXPO_PUBLIC_…` expressions (no destructuring or dynamic keys).
 */
export function authConfig(): AuthConfig {
  const apiBaseUrl = withoutTrailingSlash(process.env.EXPO_PUBLIC_API_BASE_URL || DEFAULT_API_BASE_URL);
  const issuer = withoutTrailingSlash(process.env.EXPO_PUBLIC_AUTH_ISSUER || apiBaseUrl);
  return { apiBaseUrl, issuer };
}
