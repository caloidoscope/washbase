import { NextRequest } from "next/server";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { SESSION_COOKIE } from "@/lib/auth/cookies";
import type { SessionData } from "@/lib/auth/session";
import { APP_ORIGIN, CLIENT_SECRET, captureLogs, cookieJar, stubAuthEnv } from "@/test/auth-test-env";
import {
  CLIENT_ID,
  END_SESSION_ENDPOINT,
  fakeAuthorizationServer,
  networkError,
  oauthError,
  requestedUrls,
  timeoutError,
  type Endpoint,
} from "@/test/fake-authorization-server";

// POST /auth/logout with real iron-session and openid-client; the API is a stubbed global fetch and the request's
// cookies are the next/headers stand-in, which records every write.

const jar = vi.hoisted(() => ({ current: undefined as ReturnType<typeof cookieJar> | undefined }));
vi.mock("next/headers", () => ({ cookies: async () => jar.current!.store }));

const SESSION: SessionData = {
  accessToken: "test-access-token-a1",
  expiresAt: Date.now() + 10 * 60 * 1000,
  refreshToken: "test-refresh-token-r1",
  idToken: "test-id-token-i1",
  signedInAt: Date.now() - 60 * 60 * 1000,
};
const POST_LOGOUT_REDIRECT_URI = `${APP_ORIGIN}/`;
const UNAVAILABLE = "/auth/error?reason=unavailable";

let logs: ReturnType<typeof captureLogs>;

beforeEach(() => {
  vi.resetModules();
  stubAuthEnv();
  jar.current = cookieJar();
  logs = captureLogs();
});

afterEach(() => {
  logs.expectNoSecretLogged([SESSION.accessToken, SESSION.refreshToken, SESSION.idToken]);
});

async function givenSessionCookie(data: SessionData = SESSION) {
  const { sealSession } = await import("@/lib/auth/session");
  jar.current!.values.set(SESSION_COOKIE, await sealSession(data));
}

/** The API's /connect/logout when it accepts the ID token: a 302 to the registered post-logout redirect URI. */
const endSessionAccepting: Endpoint = () =>
  new Response(null, { status: 302, headers: { Location: POST_LOGOUT_REDIRECT_URI } });

/** Serves revocation and end-session; records the revocation requests. */
function givenApi({ revocation = () => new Response(null, { status: 200 }), endSession = endSessionAccepting }: {
  revocation?: Endpoint;
  endSession?: Endpoint;
} = {}) {
  const revocations: Request[] = [];
  const fetch = fakeAuthorizationServer({
    revocation: async (request) => {
      revocations.push(request.clone());
      return revocation(request);
    },
    endSession,
  });
  vi.stubGlobal("fetch", fetch);
  return { fetch, revocations };
}

async function logout(headers: Record<string, string> = { origin: APP_ORIGIN }): Promise<Response> {
  const { POST } = await import("./route");
  return POST(new NextRequest(`${APP_ORIGIN}/auth/logout`, { method: "POST", headers }));
}

function expectSeeOther(response: Response, location: string) {
  expect(response.status).toBe(303);
  expect(response.headers.get("location")).toBe(location);
}

function expectCookieCleared() {
  expect(jar.current!.deletes).toContainEqual({ name: SESSION_COOKIE, path: "/" });
  expect(jar.current!.values.has(SESSION_COOKIE)).toBe(false);
}

describe("POST /auth/logout: CSRF check", () => {
  it("answers 403 to a foreign Origin and leaves the cookie and the API alone", async () => {
    await givenSessionCookie();
    const { fetch } = givenApi();

    const response = await logout({ origin: "https://evil.example" });

    expect(response.status).toBe(403);
    expect(jar.current!.deletes).toEqual([]);
    expect(jar.current!.values.has(SESSION_COOKIE)).toBe(true);
    expect(fetch).not.toHaveBeenCalled();
  });

  it("answers 403 to Origin: null", async () => {
    await givenSessionCookie();
    givenApi();

    expect((await logout({ origin: "null" })).status).toBe(403);
    expect(jar.current!.deletes).toEqual([]);
  });

  it("answers 403 without Origin when Sec-Fetch-Site is cross-site", async () => {
    await givenSessionCookie();
    const { fetch } = givenApi();

    expect((await logout({ "sec-fetch-site": "cross-site" })).status).toBe(403);
    expect(jar.current!.deletes).toEqual([]);
    expect(fetch).not.toHaveBeenCalled();
  });

  it("answers 403 without Origin or Sec-Fetch-Site", async () => {
    await givenSessionCookie();
    givenApi();

    expect((await logout({})).status).toBe(403);
    expect(jar.current!.deletes).toEqual([]);
  });

  it("accepts a request without Origin when Sec-Fetch-Site is same-origin", async () => {
    await givenSessionCookie();
    givenApi();

    const response = await logout({ "sec-fetch-site": "same-origin" });

    expect(response.status).toBe(303);
    expectCookieCleared();
  });

  it("has no GET handler (Next answers 405), so a link or image can't sign anyone out", async () => {
    const route = await import("./route");
    expect("GET" in route).toBe(false);
  });
});

describe("POST /auth/logout: signing out", () => {
  it("without a session, clears the cookie and goes to /", async () => {
    const { fetch } = givenApi();

    expectSeeOther(await logout(), "/");
    expectCookieCleared();
    expect(fetch).not.toHaveBeenCalled();
  });

  it("with an unreadable cookie, clears it and goes to /", async () => {
    jar.current!.values.set(SESSION_COOKIE, "not-a-sealed-value");
    const { fetch } = givenApi();

    expectSeeOther(await logout(), "/");
    expectCookieCleared();
    expect(fetch).not.toHaveBeenCalled();
  });

  it("revokes the refresh token (hint refresh_token, Basic auth), clears the cookie and goes through /connect/logout", async () => {
    await givenSessionCookie();
    const { fetch, revocations } = givenApi();

    const response = await logout();

    // Revocation: the refresh token, with token_type_hint=refresh_token and client_secret_basic.
    expect(revocations).toHaveLength(1);
    const body = new URLSearchParams(await revocations[0].text());
    expect(body.get("token")).toBe(SESSION.refreshToken);
    expect(body.get("token_type_hint")).toBe("refresh_token");
    const authorization = revocations[0].headers.get("authorization") ?? "";
    expect(authorization).toMatch(/^Basic /);
    // RFC 6749 section 2.3.1: client ID and secret are form-encoded before Base64.
    const [id, secret] = Buffer.from(authorization.slice("Basic ".length), "base64").toString().split(":");
    expect([decodeURIComponent(id), decodeURIComponent(secret)]).toEqual([CLIENT_ID, CLIENT_SECRET]);

    expectCookieCleared();

    // The end-session redirect, checked server-side first with the same URL.
    expect(response.status).toBe(303);
    const location = new URL(response.headers.get("location")!);
    expect(`${location.origin}${location.pathname}`).toBe(END_SESSION_ENDPOINT);
    expect(location.searchParams.get("id_token_hint")).toBe(SESSION.idToken);
    expect(location.searchParams.get("post_logout_redirect_uri")).toBe(POST_LOGOUT_REDIRECT_URI);
    expect(location.searchParams.get("client_id")).toBe(CLIENT_ID);
    expect(requestedUrls(fetch)).toContain(location.href);
  });

  it("revokes even when the access token has already expired", async () => {
    await givenSessionCookie({ ...SESSION, expiresAt: Date.now() - 60_000 });
    const { revocations } = givenApi();

    expect((await logout()).status).toBe(303);
    expect(revocations).toHaveLength(1);
    expectCookieCleared();
  });

  it("goes to / when the API would refuse the end-session request (400: the authorization is already gone)", async () => {
    await givenSessionCookie();
    givenApi({ endSession: () => new Response("<html>error</html>", { status: 400 }) });

    expectSeeOther(await logout(), "/");
    expectCookieCleared();
  });

  it("goes to / when the end-session request redirects anywhere but the post-logout redirect URI", async () => {
    await givenSessionCookie();
    givenApi({
      endSession: () => new Response(null, { status: 302, headers: { Location: "https://elsewhere.example/" } }),
    });

    expectSeeOther(await logout(), "/");
    expectCookieCleared();
  });

  it("still ends the session when the revocation is refused (4xx), logging the error name only", async () => {
    await givenSessionCookie();
    givenApi({ revocation: () => oauthError(401, "invalid_client") });

    const response = await logout();

    expect(response.status).toBe(303);
    expect(response.headers.get("location")).toMatch(new RegExp(`^${END_SESSION_ENDPOINT}\\?`));
    expectCookieCleared();
    expect(logs.output()).toContain("invalid_client");
  });

  it.each([
    ["fetch failed", (): Response => { throw networkError(); }],
    ["a timeout", (): Response => { throw timeoutError(); }],
    ["a 503", () => oauthError(503, "server_error")],
  ])("clears the cookie and shows \"Can't reach Washbase\" when revoking hits %s", async (_, revocation) => {
    await givenSessionCookie();
    const { fetch } = givenApi({ revocation });

    expectSeeOther(await logout(), UNAVAILABLE);
    expectCookieCleared();
    expect(requestedUrls(fetch).some((url) => url.startsWith(END_SESSION_ENDPOINT))).toBe(false);
  });

  it("clears the cookie and shows \"Can't reach Washbase\" when discovery can't reach the API", async () => {
    await givenSessionCookie();
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => {
        throw networkError();
      }),
    );

    expectSeeOther(await logout(), UNAVAILABLE);
    expectCookieCleared();
  });

  it("clears the cookie and shows \"Can't reach Washbase\" when the end-session check can't reach the API", async () => {
    await givenSessionCookie();
    givenApi({
      endSession: () => {
        throw networkError();
      },
    });

    expectSeeOther(await logout(), UNAVAILABLE);
    expectCookieCleared();
  });

  it("never logs the cookie, a token or the end-session URL", async () => {
    await givenSessionCookie();
    givenApi({ endSession: () => new Response(null, { status: 400 }) });

    await logout();

    expect(logs.output()).not.toContain(END_SESSION_ENDPOINT);
    expect(logs.output()).not.toContain(jar.current!.sets[0]?.value ?? "no-cookie-written");
  });
});
