// Test helper (Vitest only, never imported by the app): the web app's auth settings for tests, a console capture
// that proves no secret is logged, and a stand-in for `cookies()` from next/headers.
import { expect, vi, type MockInstance } from "vitest";
import { ISSUER, REDIRECT_URI } from "./fake-authorization-server";

export const SESSION_SECRET = "test-session-secret-at-least-32-characters-1f0c";
export const CLIENT_SECRET = "test-client-secret-77c4";
/** The web app's origin, from `REDIRECT_URI`. */
export const APP_ORIGIN = new URL(REDIRECT_URI).origin;
/** 30 days, the default `SESSION_MAX_AGE_DAYS`, in seconds. */
export const THIRTY_DAYS_SECONDS = 30 * 24 * 60 * 60;

/** Sets the variables `authEnv()` reads (SESSION_MAX_AGE_DAYS left unset: the default, 30). */
export function stubAuthEnv() {
  vi.stubEnv("SESSION_SECRET", SESSION_SECRET);
  vi.stubEnv("AUTH_CLIENT_SECRET", CLIENT_SECRET);
  vi.stubEnv("AUTH_ISSUER", ISSUER);
  vi.stubEnv("AUTH_REDIRECT_URI", REDIRECT_URI);
  vi.stubEnv("SESSION_MAX_AGE_DAYS", "");
}

/** Silences and records every console call. */
export function captureLogs() {
  const spies: MockInstance[] = (["log", "info", "warn", "error", "debug", "trace"] as const).map((level) =>
    vi.spyOn(console, level).mockImplementation(() => {}),
  );

  /** Every console call so far, as one string, including error objects' messages, causes and stacks. */
  function output(): string {
    return spies
      .flatMap((spy) => spy.mock.calls.flat())
      .map((arg) =>
        arg instanceof Error ? `${arg.stack} ${String(arg.cause)}` : typeof arg === "string" ? arg : JSON.stringify(arg),
      )
      .join("\n");
  }

  /** No log line holds any of `secrets`, the client secret or the session secret. */
  function expectNoSecretLogged(secrets: Iterable<string>) {
    const logged = output();
    for (const secret of [...secrets, CLIENT_SECRET, SESSION_SECRET]) {
      expect(logged).not.toContain(secret);
    }
  }

  return { output, expectNoSecretLogged };
}

export interface RecordedCookieWrite {
  name: string;
  value: string;
  options: Record<string, unknown>;
}

/**
 * The request's cookies as `cookies()` from next/headers returns them in a Route Handler, recording every write.
 * Use with `vi.mock("next/headers", () => ({ cookies: async () => jar.store }))`.
 */
export function cookieJar() {
  const values = new Map<string, string>();
  const sets: RecordedCookieWrite[] = [];
  const deletes: Array<{ name: string; path?: string }> = [];
  const store = {
    get: (name: string) => (values.has(name) ? { name, value: values.get(name) } : undefined),
    set: (name: string, value: string, options: Record<string, unknown> = {}) => {
      values.set(name, value);
      sets.push({ name, value, options });
    },
    delete: (arg: string | { name: string; path?: string }) => {
      const target = typeof arg === "string" ? { name: arg } : arg;
      values.delete(target.name);
      deletes.push(target);
    },
  };
  return {
    values,
    sets,
    deletes,
    store,
    reset() {
      values.clear();
      sets.length = 0;
      deletes.length = 0;
    },
  };
}
