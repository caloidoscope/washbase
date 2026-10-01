import createClient, { type Middleware } from "openapi-fetch";
import type { paths } from "./schema";

export type { components, paths } from "./schema";

/**
 * Supplies the current access token, or `undefined` when nobody is signed in (the request is then sent without
 * `Authorization` and protected endpoints answer 401). Each app brings its own source: the server-side session on
 * web (never browser JS), secure storage on mobile.
 */
export type AccessTokenSource = () => string | undefined | Promise<string | undefined>;

export interface ApiClientOptions {
  /** Adds `Authorization: Bearer <token>` to every request when it returns a token. */
  getAccessToken?: AccessTokenSource;
}

/** Middleware adding `Authorization: Bearer <token>` from `getAccessToken` (ADR-001). */
export function bearerAuth(getAccessToken: AccessTokenSource): Middleware {
  return {
    async onRequest({ request }) {
      const token = await getAccessToken();
      if (token) request.headers.set("Authorization", `Bearer ${token}`);
      return request;
    },
  };
}

/** Typed client for the Washbase API. Types are generated from services/api's OpenAPI spec. */
export function createApiClient(baseUrl: string, options: ApiClientOptions = {}) {
  const client = createClient<paths>({ baseUrl });
  if (options.getAccessToken) client.use(bearerAuth(options.getAccessToken));
  return client;
}

export type ApiClient = ReturnType<typeof createApiClient>;
