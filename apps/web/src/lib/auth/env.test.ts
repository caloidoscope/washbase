import { beforeEach, describe, expect, it, vi } from "vitest";
import { authEnv } from "@/lib/auth/env";
import { stubAuthEnv } from "@/test/auth-test-env";

beforeEach(() => {
  stubAuthEnv();
});

describe("authEnv: SESSION_MAX_AGE_DAYS", () => {
  it("defaults to 30 days when unset or blank", () => {
    expect(authEnv().sessionMaxAgeSeconds).toBe(30 * 24 * 60 * 60);
    vi.stubEnv("SESSION_MAX_AGE_DAYS", "   ");
    expect(authEnv().sessionMaxAgeSeconds).toBe(30 * 24 * 60 * 60);
  });

  it.each([
    ["1", 1],
    ["7", 7],
    ["400", 400],
  ])("accepts %s", (value, days) => {
    vi.stubEnv("SESSION_MAX_AGE_DAYS", value);
    expect(authEnv().sessionMaxAgeSeconds).toBe(days * 24 * 60 * 60);
  });

  it.each(["0", "401", "1.5", "abc", "-1", "30d", "1e2"])("rejects %s, naming the variable but not the value", (value) => {
    vi.stubEnv("SESSION_MAX_AGE_DAYS", value);
    // The exact message: it names the variable and never echoes the value.
    expect(() => authEnv()).toThrowError(/^SESSION_MAX_AGE_DAYS must be a whole number of days from 1 to 400$/);
  });
});

describe("authEnv: derived sign-out settings", () => {
  it("derives the post-logout redirect URI and the app origin from AUTH_REDIRECT_URI", () => {
    vi.stubEnv("AUTH_REDIRECT_URI", "https://app.example.com/auth/callback");
    const env = authEnv();
    expect(env.postLogoutRedirectUri.href).toBe("https://app.example.com/");
    expect(env.appOrigin).toBe("https://app.example.com");
  });
});
