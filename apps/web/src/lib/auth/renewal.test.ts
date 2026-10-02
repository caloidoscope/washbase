import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { SessionData } from "@/lib/auth/session";
import { captureLogs, stubAuthEnv } from "@/test/auth-test-env";
import {
  fakeAuthorizationServer,
  fakeIdToken,
  networkError,
  oauthError,
  timeoutError,
  tokenResponse,
  type Endpoint,
} from "@/test/fake-authorization-server";

// openid-client is real; the API is a stubbed global fetch. Each test gets fresh modules, so the discovery cache and
// the renewal state start empty.

const SIGNED_IN_AT = Date.parse("2026-10-01T09:00:00Z");
const OLD_ACCESS_TOKEN = "test-old-access-token-a1";
const OLD_REFRESH_TOKEN = "test-old-refresh-token-r0";
const OLD_ID_TOKEN = "test-old-id-token-i0";
const NEW_ACCESS_TOKEN = "test-new-access-token-a2";
const NEW_REFRESH_TOKEN = "test-new-refresh-token-r1";

let logs: ReturnType<typeof captureLogs>;
/** Every token value a test used, for the no-secret-in-logs check. */
let secrets: string[];

beforeEach(() => {
  vi.resetModules();
  stubAuthEnv();
  logs = captureLogs();
  secrets = [OLD_ACCESS_TOKEN, OLD_REFRESH_TOKEN, OLD_ID_TOKEN, NEW_ACCESS_TOKEN, NEW_REFRESH_TOKEN];
});

afterEach(() => {
  logs.expectNoSecretLogged(secrets);
  vi.useRealTimers();
});

function oldSession(): SessionData {
  return {
    accessToken: OLD_ACCESS_TOKEN,
    expiresAt: Date.now() - 1_000,
    refreshToken: OLD_REFRESH_TOKEN,
    idToken: OLD_ID_TOKEN,
    signedInAt: SIGNED_IN_AT,
  };
}

/** Serves the token endpoint and counts its requests (discovery isn't counted). */
function givenTokenEndpoint(endpoint: Endpoint) {
  const requests: Request[] = [];
  vi.stubGlobal(
    "fetch",
    fakeAuthorizationServer({
      token: async (request) => {
        requests.push(request.clone());
        return endpoint(request);
      },
    }),
  );
  return requests;
}

/** A token endpoint that renews successfully, with a new ID token unless `withIdToken` is false. */
function renewing({ withIdToken = true } = {}): Endpoint {
  return () => {
    const idToken = withIdToken ? fakeIdToken() : undefined;
    if (idToken) secrets.push(idToken);
    return tokenResponse({ accessToken: NEW_ACCESS_TOKEN, refreshToken: NEW_REFRESH_TOKEN, idToken });
  };
}

async function renewal() {
  return import("@/lib/auth/renewal");
}

describe("renewSession", () => {
  it("renews: new access, refresh and ID tokens, a new expiry, signedInAt unchanged", async () => {
    const requests = givenTokenEndpoint(renewing());
    const { renewSession } = await renewal();
    const before = Date.now();

    const outcome = await renewSession(oldSession());

    expect(outcome.kind).toBe("renewed");
    if (outcome.kind !== "renewed") return;
    expect(outcome.session.accessToken).toBe(NEW_ACCESS_TOKEN);
    expect(outcome.session.refreshToken).toBe(NEW_REFRESH_TOKEN);
    expect(outcome.session.idToken).not.toBe(OLD_ID_TOKEN);
    expect(outcome.session.idToken).toBe(secrets.at(-1));
    expect(outcome.session.signedInAt).toBe(SIGNED_IN_AT);
    expect(outcome.session.expiresAt).toBeGreaterThanOrEqual(before + 898_000); // expiresIn() counts whole seconds since the response
    expect(Object.keys(outcome.session).sort()).toEqual(
      ["accessToken", "expiresAt", "idToken", "refreshToken", "signedInAt"].sort(),
    );

    // One refresh_token grant, with client_secret_basic.
    expect(requests).toHaveLength(1);
    const body = new URLSearchParams(await requests[0].text());
    expect(body.get("grant_type")).toBe("refresh_token");
    expect(body.get("refresh_token")).toBe(OLD_REFRESH_TOKEN);
    expect(requests[0].headers.get("authorization")).toMatch(/^Basic /);
  });

  it("keeps the stored ID token when the token response has none", async () => {
    givenTokenEndpoint(renewing({ withIdToken: false }));
    const { renewSession } = await renewal();

    const outcome = await renewSession(oldSession());

    expect(outcome.kind).toBe("renewed");
    if (outcome.kind === "renewed") expect(outcome.session.idToken).toBe(OLD_ID_TOKEN);
  });

  it("is refused when the API answers invalid_grant (expired, signed out or reused)", async () => {
    givenTokenEndpoint(() => oauthError(400, "invalid_grant"));
    const { renewSession } = await renewal();

    expect(await renewSession(oldSession())).toEqual({ kind: "refused" });
    expect(logs.output()).toContain("invalid_grant");
  });

  it("is refused when the token response has no new refresh token", async () => {
    givenTokenEndpoint(() => tokenResponse({ accessToken: NEW_ACCESS_TOKEN }));
    const { renewSession } = await renewal();

    expect(await renewSession(oldSession())).toEqual({ kind: "refused" });
  });

  it.each([
    ["the network fails (fetch failed)", (): Response => { throw networkError(); }],
    ["the request times out", (): Response => { throw timeoutError(); }],
    ["the token endpoint answers 503", () => oauthError(503, "server_error")],
    ["the token endpoint answers 500 without a body", () => new Response(null, { status: 500 })],
  ])("is unavailable when %s, and the outcome isn't cached", async (_, endpoint) => {
    const requests = givenTokenEndpoint(endpoint);
    const { renewSession } = await renewal();

    expect(await renewSession(oldSession())).toEqual({ kind: "unavailable" });
    expect(await renewSession(oldSession())).toEqual({ kind: "unavailable" });
    expect(requests).toHaveLength(2);
  });

  it("is unavailable when discovery can't reach the API", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => {
        throw networkError();
      }),
    );
    const { renewSession } = await renewal();

    expect(await renewSession(oldSession())).toEqual({ kind: "unavailable" });
  });

  it("makes one token request for 5 concurrent calls with the same refresh token", async () => {
    let release!: () => void;
    const gate = new Promise<void>((resolve) => (release = resolve));
    const requests = givenTokenEndpoint(async (request) => {
      await gate;
      return renewing()(request);
    });
    const { renewSession } = await renewal();

    const calls = Array.from({ length: 5 }, () => renewSession(oldSession()));
    release();
    const outcomes = await Promise.all(calls);

    expect(requests).toHaveLength(1);
    for (const outcome of outcomes) expect(outcome).toEqual(outcomes[0]);
    expect(outcomes[0].kind).toBe("renewed");
  });

  it("answers a call 10 s after a renewal with the old refresh token with the same session, without a request", async () => {
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(Date.parse("2026-10-01T09:20:00Z"));
    const requests = givenTokenEndpoint(renewing());
    const { renewSession } = await renewal();

    const first = await renewSession(oldSession());
    vi.setSystemTime(Date.now() + 10_000);
    const late = await renewSession(oldSession());

    expect(late).toEqual(first);
    expect(requests).toHaveLength(1);
  });

  it("keeps a refusal for 30 s too", async () => {
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(Date.parse("2026-10-01T09:20:00Z"));
    const requests = givenTokenEndpoint(() => oauthError(400, "invalid_grant"));
    const { renewSession } = await renewal();

    await renewSession(oldSession());
    vi.setSystemTime(Date.now() + 29_000);
    expect(await renewSession(oldSession())).toEqual({ kind: "refused" });
    expect(requests).toHaveLength(1);
  });

  it("forgets a settled renewal after 30 s", async () => {
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(Date.parse("2026-10-01T09:20:00Z"));
    const requests = givenTokenEndpoint(renewing());
    const { RESULT_TTL_MS, renewSession } = await renewal();

    await renewSession(oldSession());
    vi.setSystemTime(Date.now() + RESULT_TTL_MS);
    await renewSession(oldSession());

    expect(requests).toHaveLength(2);
  });

  it("renews separately for different refresh tokens", async () => {
    const requests = givenTokenEndpoint(renewing());
    const { renewSession } = await renewal();

    await Promise.all([
      renewSession(oldSession()),
      renewSession({ ...oldSession(), refreshToken: "test-other-refresh-token-r9" }),
    ]);

    expect(requests).toHaveLength(2);
  });

  it("resetRenewalsForTests forgets cached outcomes", async () => {
    const requests = givenTokenEndpoint(renewing());
    const { renewSession, resetRenewalsForTests } = await renewal();

    await renewSession(oldSession());
    resetRenewalsForTests();
    await renewSession(oldSession());

    expect(requests).toHaveLength(2);
  });
});
