import "server-only";
import { cache } from "react";
import { createApiClient, type components } from "@washbase/api-client";
import { authEnv } from "./env";
import { getSession } from "./session";

export type Role = components["schemas"]["MeResponse"]["role"];

/** What the home page may render. Only display values: no token, no session. */
export type CurrentUser =
  /** `GET /api/v1/me` answered 200. */
  | { status: "signed-in"; name: string; roleLabel: string }
  /** No session, an expired one, or the API answered 401/403 for a session older than 30 seconds: sign in again. */
  | { status: "signed-out" }
  /** The API answered 401/403 for a session created less than 30 seconds ago: a configuration fault. Show the
   *  "Sign-in didn't complete" state instead of redirecting, so the browser can't loop through sign-in. */
  | { status: "sign-in-failed" }
  /** Network error, timeout or 5xx: show "Can't reach Washbase right now. Try again." */
  | { status: "unavailable" };

/** A session this young that the API still rejects means sign-in itself is broken: don't send the user round again. */
const LOOP_GUARD_MS = 30_000;
/** How long the home page waits for the API. */
const API_TIMEOUT_MS = 10_000;

const ROLE_LABELS: Record<Role, string> = {
  ADMIN: "Admin",
  OWNER: "Owner",
  STAFF: "Staff",
  CLIENT: "Client",
};

/** "Admin", "Owner", "Staff", "Client". */
export function roleLabel(role: Role): string {
  return ROLE_LABELS[role];
}

/**
 * The signed-in user, via `@washbase/api-client` only: `createApiClient(authEnv().apiBaseUrl, { getAccessToken })`
 * with the token from `getSession()`, then `GET /api/v1/me`. Wrapped in React `cache` so one render calls the API
 * once.
 */
export const getCurrentUser = cache(async (): Promise<CurrentUser> => {
  const session = await getSession();
  if (!session) return { status: "signed-out" };

  const api = createApiClient(authEnv().apiBaseUrl, { getAccessToken: () => session.accessToken });
  let result;
  try {
    result = await api.GET("/api/v1/me", { signal: AbortSignal.timeout(API_TIMEOUT_MS) });
  } catch (error) {
    // Network failure or timeout. Log the error's name only: nothing that could carry the request's headers.
    console.warn(`GET /api/v1/me failed (${error instanceof Error ? error.name : typeof error})`);
    return { status: "unavailable" };
  }

  const { data, response } = result;
  if (data) return { status: "signed-in", name: data.name, roleLabel: roleLabel(data.role) };
  if (response.status === 401 || response.status === 403) {
    return Date.now() - session.signedInAt < LOOP_GUARD_MS ? { status: "sign-in-failed" } : { status: "signed-out" };
  }
  console.warn(`GET /api/v1/me answered ${response.status}`);
  return { status: "unavailable" };
});
