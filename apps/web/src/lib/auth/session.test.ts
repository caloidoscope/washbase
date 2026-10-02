import { sealData } from "iron-session";
import { NextResponse } from "next/server";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { SESSION_COOKIE } from "@/lib/auth/cookies";
import type { SessionData } from "@/lib/auth/session";
import { SESSION_SECRET, THIRTY_DAYS_SECONDS, captureLogs, cookieJar, stubAuthEnv } from "@/test/auth-test-env";
import { fakeIdToken, fakeJwt, ISSUER } from "@/test/fake-authorization-server";

const jar = vi.hoisted(() => ({ current: undefined as ReturnType<typeof cookieJar> | undefined }));
vi.mock("next/headers", () => ({ cookies: async () => jar.current!.store }));

const SESSION: SessionData = {
  accessToken: "test-access-token-a1",
  expiresAt: Date.parse("2026-10-01T09:15:00Z"),
  refreshToken: "test-refresh-token-r0",
  idToken: "test-id-token-i0",
  signedInAt: Date.parse("2026-10-01T09:00:00Z"),
};

let logs: ReturnType<typeof captureLogs>;

beforeEach(() => {
  stubAuthEnv();
  jar.current = cookieJar();
  logs = captureLogs();
});

afterEach(() => {
  logs.expectNoSecretLogged([SESSION.accessToken, SESSION.refreshToken, SESSION.idToken]);
  vi.useRealTimers();
});

const session = () => import("@/lib/auth/session");

describe("session cookie", () => {
  it("seals and unseals the five session fields, and nothing else", async () => {
    const { sealSession, unsealSession } = await session();

    const sealed = await sealSession({ ...SESSION, extra: "not kept" } as SessionData);

    expect(sealed).not.toContain(SESSION.accessToken);
    expect(await unsealSession(sealed)).toEqual(SESSION);
  });

  it("keeps a session whose access token has expired (that means renew, not sign in again)", async () => {
    const { sealSession, unsealSession } = await session();
    const expired = { ...SESSION, expiresAt: Date.now() - 60_000 };

    expect(await unsealSession(await sealSession(expired))).toEqual(expired);
  });

  it("is no session when missing, unreadable, or a pre-CAR-18 cookie (no refresh or ID token)", async () => {
    const { unsealSession } = await session();
    const preCar18 = await sealData(
      { accessToken: SESSION.accessToken, expiresAt: SESSION.expiresAt, signedInAt: SESSION.signedInAt },
      { password: SESSION_SECRET, ttl: 15 * 60 },
    );

    expect(await unsealSession(undefined)).toBeUndefined();
    expect(await unsealSession("")).toBeUndefined();
    expect(await unsealSession("not-a-sealed-value")).toBeUndefined();
    expect(await unsealSession(preCar18)).toBeUndefined();
    expect(logs.output()).toContain(`Ignored an unreadable ${SESSION_COOKIE} cookie`);
  });

  it("is no session when sealed with another secret", async () => {
    const { unsealSession } = await session();
    const foreign = await sealData(SESSION, { password: "another-secret-that-is-at-least-32-chars", ttl: 60 });

    expect(await unsealSession(foreign)).toBeUndefined();
  });

  it("has the attributes HttpOnly; Secure; SameSite=Lax; Path=/ and Max-Age = SESSION_MAX_AGE_DAYS", async () => {
    const { sessionCookieOptions } = await session();
    expect(sessionCookieOptions()).toEqual({
      httpOnly: true,
      secure: true,
      sameSite: "lax",
      path: "/",
      maxAge: THIRTY_DAYS_SECONDS,
    });

    vi.stubEnv("SESSION_MAX_AGE_DAYS", "7");
    expect(sessionCookieOptions().maxAge).toBe(7 * 24 * 60 * 60);
  });

  it("is written by saveSession (sign-in) with the same attributes and format as the proxy's renewal", async () => {
    const { saveSession, sessionCookieOptions, unsealSession } = await session();

    await saveSession(SESSION);

    expect(jar.current!.sets).toHaveLength(1);
    const [write] = jar.current!.sets;
    expect(write.name).toBe(SESSION_COOKIE);
    expect(write.options).toEqual(sessionCookieOptions());
    expect(await unsealSession(write.value)).toEqual(SESSION);
  });

  it("is deleted by clearSession with Path=/", async () => {
    const { clearSession } = await session();
    jar.current!.values.set(SESSION_COOKIE, "anything");

    await clearSession();

    expect(jar.current!.deletes).toEqual([{ name: SESSION_COOKIE, path: "/" }]);
    expect(jar.current!.values.has(SESSION_COOKIE)).toBe(false);
  });

  it("getSession reads the cookie through unsealSession", async () => {
    const { getSession, sealSession } = await session();
    expect(await getSession()).toBeUndefined();

    jar.current!.values.set(SESSION_COOKIE, await sealSession(SESSION));
    expect(await getSession()).toEqual(SESSION);
  });

  it("stays under 4096 bytes (Set-Cookie included) with realistic token sizes", async () => {
    const { sealSession, sessionCookieOptions } = await session();
    const now = Math.floor(Date.now() / 1000);
    // The API's access token: an RS256 JWT with iss, sub, aud, exp, iat, nbf, jti, roles, scope: about 1 KB.
    const accessToken = fakeJwt(
      { iss: ISSUER, sub: "0b9f6f9e-1f7a-4a39-9c39-5b0c1d0f3e2a", aud: "washbase-web", roles: ["ADMIN"], scope: ["openid"], iat: now, exp: now + 900 },
      1100,
    );
    const idToken = fakeIdToken("0b9f6f9e-1f7a-4a39-9c39-5b0c1d0f3e2a", 1100);
    // Spring Authorization Server's opaque refresh token: 96 random bytes, base64url.
    const refreshToken = Buffer.alloc(96, 7).toString("base64url");
    expect(accessToken.length).toBeGreaterThanOrEqual(1050);
    expect(idToken.length).toBeGreaterThanOrEqual(1050);

    const sealed = await sealSession({
      accessToken,
      idToken,
      refreshToken,
      expiresAt: Date.now() + 900_000,
      signedInAt: Date.now(),
    });
    const response = new NextResponse();
    response.cookies.set(SESSION_COOKIE, sealed, sessionCookieOptions());
    const setCookie = response.headers.get("set-cookie")!;

    expect(Buffer.byteLength(setCookie)).toBeLessThan(4096);
  });
});
