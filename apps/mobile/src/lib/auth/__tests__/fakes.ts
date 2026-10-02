import type { MeLoader, MeResult } from "../me";
import { createTokenClient, type AuthEndpoints } from "../oauth";
import { AuthSession } from "../session";
import type { PromptOutcome } from "../sign-in-prompt";
import type { RefreshTokenStore } from "../token-store";

export const ENDPOINTS: AuthEndpoints = {
  authorizationEndpoint: "http://192.168.1.10:8080/oauth2/authorize",
  tokenEndpoint: "http://192.168.1.10:8080/oauth2/token",
  revocationEndpoint: "http://192.168.1.10:8080/oauth2/revoke",
};

/** Records every step so tests can check ordering ("stored before used"). */
export type Events = string[];

export function fakeStore(initial: string | null, events: Events = []) {
  let value = initial;
  const store = {
    get value() {
      return value;
    },
    read: jest.fn(async () => value),
    write: jest.fn(async (token: string) => {
      events.push(`store:${token}`);
      value = token;
    }),
    clear: jest.fn(async () => {
      events.push("store:cleared");
      value = null;
    }),
  } satisfies RefreshTokenStore & { value: string | null };
  return store;
}

export interface FakeResponse {
  status: number;
  body?: unknown;
}

export interface RecordedRequest {
  url: string;
  form: Record<string, string>;
}

/** A fake `fetch` for the authorization server: answers from a queue (a response, or an Error to throw). */
export function fakeAuthServer(...answers: (FakeResponse | Error)[]) {
  const requests: RecordedRequest[] = [];
  const queue = [...answers];
  const fetchImpl = jest.fn(async (url: string, init: RequestInit) => {
    const form = Object.fromEntries(
      String(init?.body ?? "")
        .split("&")
        .filter(Boolean)
        .map((pair) => pair.split("=").map(decodeURIComponent) as [string, string]),
    );
    requests.push({ url: String(url), form });
    const answer = queue.shift();
    if (!answer) throw new Error("unexpected request");
    if (answer instanceof Error) throw answer;
    return {
      ok: answer.status >= 200 && answer.status < 300,
      status: answer.status,
      json: async () => {
        if (answer.body === undefined) throw new SyntaxError("no body");
        return answer.body;
      },
    } as unknown as Response;
  });
  return { fetchImpl, requests };
}

export function tokenResponse(accessToken: string, refreshToken: string, expiresIn = 900): FakeResponse {
  return { status: 200, body: { access_token: accessToken, refresh_token: refreshToken, token_type: "Bearer", expires_in: expiresIn } };
}

export const ADMIN: MeResult = { kind: "ok", user: { name: "Admin", roleLabel: "Admin" } };

/** A fake `GET /api/v1/me`: asks the session for its access token like the API client's middleware does. */
export function fakeMe(result: MeResult = ADMIN, events: Events = []) {
  const usedTokens: (string | undefined)[] = [];
  const loadMe: MeLoader = jest.fn(async (getAccessToken): Promise<MeResult> => {
    const token = await getAccessToken();
    events.push(`use:${token}`);
    usedTokens.push(token);
    return token ? result : { kind: "unauthorized" };
  });
  return { loadMe, usedTokens };
}

export function makeSession({
  store = fakeStore(null),
  server = fakeAuthServer(),
  me = fakeMe(),
  prompt = jest.fn(async (): Promise<PromptOutcome> => ({ kind: "cancelled" })),
  now = () => 1_000_000,
}: {
  store?: ReturnType<typeof fakeStore>;
  server?: ReturnType<typeof fakeAuthServer>;
  me?: ReturnType<typeof fakeMe>;
  prompt?: jest.Mock<Promise<PromptOutcome>, []>;
  now?: () => number;
} = {}) {
  const tokens = createTokenClient(async () => ENDPOINTS, { fetchImpl: server.fetchImpl, now });
  const session = new AuthSession({ store, tokens, loadMe: me.loadMe, prompt, now });
  return { session, store, server, me, prompt };
}
