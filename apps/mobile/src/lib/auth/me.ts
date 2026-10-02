import { createApiClient, type AccessTokenSource, type components } from "@washbase/api-client";

import { REQUEST_TIMEOUT_MS } from "./config";
import { describeError } from "./errors";

export type Role = components["schemas"]["MeResponse"]["role"];

/** Only display values: no token. */
export interface SignedInUser {
  name: string;
  roleLabel: string;
}

export type MeResult =
  | { kind: "ok"; user: SignedInUser }
  /** 401/403: the token isn't accepted (revoked, user deactivated). */
  | { kind: "unauthorized" }
  /** Network error, timeout or any other status. */
  | { kind: "unreachable" };

export type MeLoader = (getAccessToken: AccessTokenSource) => Promise<MeResult>;

const ROLE_LABELS: Record<Role, string> = {
  ADMIN: "Admin",
  OWNER: "Owner",
  STAFF: "Staff",
  CLIENT: "Client",
};

/** "Admin", "Owner", "Staff", "Client" (same labels as the web app). */
export function roleLabel(role: Role): string {
  return ROLE_LABELS[role];
}

/** `GET /api/v1/me` through `@washbase/api-client` only (no hand-written fetch to `/api`). */
export function createMeLoader(apiBaseUrl: string): MeLoader {
  return async (getAccessToken) => {
    const api = createApiClient(apiBaseUrl, { getAccessToken });
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
    try {
      const { data, response } = await api.GET("/api/v1/me", { signal: controller.signal });
      if (data) return { kind: "ok", user: { name: data.name, roleLabel: roleLabel(data.role) } };
      if (response.status === 401 || response.status === 403) return { kind: "unauthorized" };
      console.warn(`GET /api/v1/me answered ${response.status}`);
      return { kind: "unreachable" };
    } catch (error) {
      console.warn(`GET /api/v1/me failed (${describeError(error)})`);
      return { kind: "unreachable" };
    } finally {
      clearTimeout(timer);
    }
  };
}
