import { NextRequest, NextResponse } from "next/server";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { SESSION_COOKIE } from "@/lib/auth/cookies";
import type { SessionData } from "@/lib/auth/session";
import { APP_ORIGIN, THIRTY_DAYS_SECONDS, captureLogs, cookieJar, stubAuthEnv } from "@/test/auth-test-env";
import {
  fakeAuthorizationServer,
  fakeIdToken,
  networkError,
  oauthError,
  tokenResponse,
  type Endpoint,
} from "@/test/fake-authorization-server";

// The proxy with real session sealing (iron-session), real renewal and real openid-client; the API is a stubbed
// global fetch. next/headers is only mocked so saveSession (sign-in) can be compared with the proxy's write.

const jar = vi.hoisted(() => ({ current: undefined as ReturnType<typeof cookieJar> | undefined }));
vi.mock("next/headers", () => ({ cookies: async () => jar.current!.store }));

const SIGNED_IN = Date.parse("2026-10-01T09:00:00Z");
const DAY_MS = 24 * 60 * 60 * 1000;

let logs: ReturnType<typeof captureLogs>;
/** Every token a test used or the fake API issued, for the no-secret-in-logs check. */
let secrets: string[];
/** Token requests the fake API received. */
let tokenRequests: number;

beforeEach(() => {
  vi.resetModules();
  stubAuthEnv();
  jar.current = cookieJar();
  logs = captureLogs();
  secrets = [];
  tokenRequests = 0;
});

afterEach(() => {
  logs.expectNoSecretLogged(secrets);
  vi.useRealTimers();
});

/** Fixes `Date.now()` (only Date: fetch and promises keep their real timers). */
function at(time: number) {
  vi.useFakeTimers({ toFake: ["Date"] });
  vi.setSystemTime(time);
}

/** A session signed in at `signedInAt`, whose access token expires 15 minutes later. */
function sessionData(signedInAt = SIGNED_IN, suffix = "0"): SessionData {
  const data = {
    accessToken: `test-access-token-a${suffix}`,
    expiresAt: signedInAt + 15 * 60 * 1000,
    refreshToken: `test-refresh-token-r${suffix}`,
    idToken: `test-id-token-i${suffix}`,
    signedInAt,
  };
  secrets.push(data.accessToken, data.refreshToken, data.idToken);
  return data;
}

/** The fake API's token endpoint. `renewing` issues new tokens (and remembers them as secrets). */
function givenTokenEndpoint(endpoint: Endpoint) {
  vi.stubGlobal(
    "fetch",
    fakeAuthorizationServer({
      token: async (request) => {
        tokenRequests += 1;
        return endpoint(request);
      },
    }),
  );
}

const renewing: Endpoint = () => {
  const n = tokenRequests;
  const tokens = {
    accessToken: `test-renewed-access-token-${n}`,
    refreshToken: `test-renewed-refresh-token-${n}`,
    idToken: fakeIdToken(),
  };
  secrets.push(tokens.accessToken, tokens.refreshToken, tokens.idToken);
  return tokenResponse(tokens);
};

async function seal(data: SessionData): Promise<string> {
  const { sealSession } = await import("@/lib/auth/session");
  return sealSession(data);
}

/** Runs the proxy for `GET /` with this session cookie (none when undefined). */
async function proxyWith(cookie: string | undefined): Promise<NextResponse> {
  const { proxy } = await import("./proxy");
  const headers = new Headers();
  if (cookie !== undefined) headers.set("cookie", `${SESSION_COOKIE}=${cookie}`);
  return (await proxy(new NextRequest(`${APP_ORIGIN}/`, { headers }))) as NextResponse;
}

/** The response's Set-Cookie header for the session cookie, if any. */
function sessionSetCookie(response: Response): string | undefined {
  return response.headers.getSetCookie().find((line) => line.startsWith(`${SESSION_COOKIE}=`));
}

/** The session cookie value the proxy forwarded to this request's Server Components, if it changed it. */
function forwardedSessionCookie(response: Response): string | undefined {
  const cookieHeader = response.headers.get("x-middleware-request-cookie");
  if (!cookieHeader) return undefined;
  const pair = cookieHeader.split(/;\s*/).find((part) => part.startsWith(`${SESSION_COOKIE}=`));
  return pair ? decodeURIComponent(pair.slice(SESSION_COOKIE.length + 1)) : undefined;
}

function attributes(setCookie: string): string[] {
  return setCookie
    .split(/;\s*/)
    .slice(1)
    .map((attribute) => attribute.toLowerCase());
}

function expectPassThrough(response: Response) {
  expect(response.headers.get("x-middleware-next")).toBe("1");
  expect(response.headers.get("location")).toBeNull();
}

function expectSignInRedirect(response: Response) {
  expect(response.status).toBe(307);
  expect(response.headers.get("location")).toBe(`${APP_ORIGIN}/auth/login`);
}

function expectCookieDeleted(response: Response) {
  const setCookie = sessionSetCookie(response);
  expect(setCookie).toBeDefined();
  expect(setCookie).toMatch(new RegExp(`^${SESSION_COOKIE}=;`));
  expect(attributes(setCookie!)).toContain("path=/");
  expect(setCookie).toMatch(/Expires=Thu, 01 Jan 1970 00:00:00 GMT/);
}

/** The renewed cookie's value, after checking it is set with HttpOnly; Secure; SameSite=Lax; Path=/ and full Max-Age. */
function expectFullSessionCookie(response: Response): string {
  const setCookie = sessionSetCookie(response);
  expect(setCookie).toBeDefined();
  const attrs = attributes(setCookie!);
  expect(attrs).toEqual(
    expect.arrayContaining(["path=/", `max-age=${THIRTY_DAYS_SECONDS}`, "secure", "httponly", "samesite=lax"]),
  );
  return decodeURIComponent(setCookie!.split(";")[0].slice(SESSION_COOKIE.length + 1));
}

describe("proxy", () => {
  it("redirects to /auth/login when there is no session cookie, without touching cookies", async () => {
    givenTokenEndpoint(renewing);

    const response = await proxyWith(undefined);

    expectSignInRedirect(response);
    expect(sessionSetCookie(response)).toBeUndefined();
    expect(tokenRequests).toBe(0);
  });

  it("deletes an unreadable cookie (Path=/) and redirects to /auth/login", async () => {
    givenTokenEndpoint(renewing);

    const response = await proxyWith("not-a-sealed-value");

    expectSignInRedirect(response);
    expectCookieDeleted(response);
    expect(tokenRequests).toBe(0);
  });

  it("passes through, without writing the cookie, while the access token is valid for more than 60 s", async () => {
    at(SIGNED_IN + 5 * 60 * 1000);
    givenTokenEndpoint(renewing);

    const response = await proxyWith(await seal(sessionData()));

    expectPassThrough(response);
    expect(sessionSetCookie(response)).toBeUndefined();
    expect(forwardedSessionCookie(response)).toBeUndefined();
    expect(tokenRequests).toBe(0);
  });

  it("renews when the access token expires within 60 s: sets the full cookie and forwards it to this request", async () => {
    at(SIGNED_IN + 14 * 60 * 1000 + 30_000);
    givenTokenEndpoint(renewing);
    const { unsealSession } = await import("@/lib/auth/session");

    const response = await proxyWith(await seal(sessionData()));

    expectPassThrough(response);
    expect(tokenRequests).toBe(1);
    const written = expectFullSessionCookie(response);
    expect(forwardedSessionCookie(response)).toBe(written);
    const renewed = await unsealSession(written);
    expect(renewed?.accessToken).toBe("test-renewed-access-token-1");
    expect(renewed?.refreshToken).toBe("test-renewed-refresh-token-1");
    expect(renewed?.signedInAt).toBe(SIGNED_IN);
  });

  it("deletes the cookie (Path=/) and redirects to /auth/login when the renewal is refused (invalid_grant)", async () => {
    at(SIGNED_IN + 20 * 60 * 1000);
    givenTokenEndpoint(() => oauthError(400, "invalid_grant"));

    const response = await proxyWith(await seal(sessionData()));

    expectSignInRedirect(response);
    expectCookieDeleted(response);
  });

  it("keeps the cookie and passes through when the API can't be reached", async () => {
    at(SIGNED_IN + 20 * 60 * 1000);
    givenTokenEndpoint(() => {
      throw networkError();
    });

    const response = await proxyWith(await seal(sessionData()));

    expectPassThrough(response);
    expect(sessionSetCookie(response)).toBeUndefined();
    expect(forwardedSessionCookie(response)).toBeUndefined();
  });

  it("gives a request still carrying the old cookie the same renewed cookie, without a second token request", async () => {
    at(SIGNED_IN + 20 * 60 * 1000);
    givenTokenEndpoint(renewing);
    const oldCookie = await seal(sessionData());

    const [first, second] = await Promise.all([proxyWith(oldCookie), proxyWith(oldCookie)]);
    vi.setSystemTime(Date.now() + 10_000);
    const late = await proxyWith(oldCookie);

    expect(tokenRequests).toBe(1);
    const { unsealSession } = await import("@/lib/auth/session");
    const sessions = await Promise.all(
      [first, second, late].map((response) => unsealSession(expectFullSessionCookie(response))),
    );
    for (const renewed of sessions) expect(renewed?.refreshToken).toBe("test-renewed-refresh-token-1");
  });

  it("writes the same cookie attributes on renewal as sign-in does (saveSession)", async () => {
    at(SIGNED_IN + 20 * 60 * 1000);
    givenTokenEndpoint(renewing);
    const { saveSession } = await import("@/lib/auth/session");

    const renewal = await proxyWith(await seal(sessionData()));
    await saveSession(sessionData(Date.now(), "1"));
    const signIn = new NextResponse();
    const [write] = jar.current!.sets;
    signIn.cookies.set(write.name, write.value, write.options);

    expect(attributes(sessionSetCookie(renewal)!)).toEqual(attributes(sessionSetCookie(signIn)!));
  });

  it("only runs on pages: not on /auth/*, Next.js internals or files", async () => {
    const { config } = await import("./proxy");
    const matcher = new RegExp(`^${config.matcher[0]}$`);

    expect(matcher.test("/")).toBe(true);
    expect(matcher.test("/settings")).toBe(true);
    expect(matcher.test("/auth/login")).toBe(false);
    expect(matcher.test("/auth/logout")).toBe(false);
    expect(matcher.test("/_next/static/chunk.js")).toBe(false);
    expect(matcher.test("/favicon.ico")).toBe(false);
  });
});

describe("Scenario: The session ends after 30 days without use", () => {
  it("Scenario: The session ends after 30 days without use", async () => {
    // Given the Admin signed in on 1 October 2026 and did not open the web app again
    at(SIGNED_IN);
    const cookie = await seal(sessionData());
    givenTokenEndpoint(renewing);

    // When the Admin opens the web app on 1 November 2026
    at(Date.parse("2026-11-01T09:00:00Z"));
    const { unsealSession } = await import("@/lib/auth/session");
    const response = await proxyWith(cookie);

    // Then the Admin sees the sign-in page (the cookie's seal has expired: no session, no renewal attempt)
    expect(await unsealSession(cookie)).toBeUndefined();
    expectSignInRedirect(response);
    expectCookieDeleted(response);
    expect(tokenRequests).toBe(0);
  });

  it("Scenario: The session ends after 30 days without use (the API refuses the expired refresh token)", async () => {
    // A cookie still readable but whose refresh token the API has expired: the renewal is refused.
    at(SIGNED_IN);
    const cookie = await seal(sessionData());
    at(SIGNED_IN + 29 * DAY_MS);
    givenTokenEndpoint(() => oauthError(400, "invalid_grant"));

    const response = await proxyWith(cookie);

    expect(tokenRequests).toBe(1);
    expectSignInRedirect(response);
    expectCookieDeleted(response);
  });
});

describe("Scenario: Using the app keeps the session going", () => {
  it("Scenario: Using the app keeps the session going", async () => {
    givenTokenEndpoint(renewing);
    const { unsealSession } = await import("@/lib/auth/session");

    // Given the Admin signed in on 1 October 2026
    at(SIGNED_IN);
    const signInCookie = await seal(sessionData());

    // And used the web app on 25 October 2026: the renewal re-seals the cookie with the full 30 days from then
    const day25 = Date.parse("2026-10-25T09:00:00Z");
    at(day25);
    const use = await proxyWith(signInCookie);
    expectPassThrough(use);
    const day25Cookie = expectFullSessionCookie(use);
    const expires = Date.parse(/Expires=([^;]+)/i.exec(sessionSetCookie(use)!)![1]);
    expect(Math.abs(expires - (day25 + 30 * DAY_MS))).toBeLessThan(2_000);

    // When the Admin opens the web app on 20 November 2026 (50 days after signing in)
    at(Date.parse("2026-11-20T09:00:00Z"));
    expect(await unsealSession(signInCookie)).toBeUndefined();
    const open = await proxyWith(day25Cookie);

    // Then the Admin sees the home page without signing in again
    expectPassThrough(open);
    const renewed = await unsealSession(expectFullSessionCookie(open));
    expect(renewed?.signedInAt).toBe(SIGNED_IN);
    expect(tokenRequests).toBe(2);
  });
});
