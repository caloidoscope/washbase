import { sealData } from "iron-session";
import { NextRequest } from "next/server";
import { afterEach, beforeEach, describe, expect, it, vi, type MockInstance } from "vitest";
import { TRANSACTION_COOKIE } from "@/lib/auth/cookies";
import type { AuthTransaction } from "@/lib/auth/session";
import {
  ISSUER,
  REDIRECT_URI,
  TOKEN_ENDPOINT,
  fakeAuthorizationServer,
  networkError,
  timeoutError,
  type TokenEndpoint,
} from "@/test/fake-authorization-server";

// openid-client and iron-session are real. What's faked: the API (a stubbed global fetch), the request's cookies
// (next/headers) and redirect() (next/navigation), which here throws the destination instead of Next's signal.

const SESSION_SECRET = "test-session-secret-at-least-32-characters-1f0c";
const CLIENT_SECRET = "test-client-secret-77c4";
const CODE = "test-authorization-code-5b1f";
const TRANSACTION: AuthTransaction = {
  codeVerifier: "test-code-verifier-0e7a0e7a0e7a0e7a0e7a0e7a0e7a0e7a0e7a0e7a",
  state: "test-state-9d2c",
  nonce: "test-nonce-41b8",
};
const ACCESS_TOKEN = "test-access-token-eyJ-31aa";
const REFRESH_TOKEN = "test-refresh-token-c0de";
const ID_TOKEN = "test-id-token-eyJ-8e21";
const ERROR_DESCRIPTION = `The code ${CODE} was already used`;

/** The request's cookies, as `cookies()` from next/headers returns them. */
const cookieJar = new Map<string, string>();

vi.mock("next/headers", () => ({
  cookies: async () => ({
    get: (name: string) => (cookieJar.has(name) ? { name, value: cookieJar.get(name) } : undefined),
    set: (name: string, value: string) => {
      cookieJar.set(name, value);
    },
    delete: (name: string) => {
      cookieJar.delete(name);
    },
  }),
}));

class RedirectSignal extends Error {
  constructor(readonly destination: string) {
    super(`redirect(${destination})`);
  }
}

vi.mock("next/navigation", () => ({
  redirect: (destination: string) => {
    throw new RedirectSignal(destination);
  },
}));

let logged: MockInstance[];

beforeEach(() => {
  // oidc.ts caches the discovered configuration per module: a fresh module graph per test keeps tests independent.
  vi.resetModules();
  cookieJar.clear();
  vi.stubEnv("SESSION_SECRET", SESSION_SECRET);
  vi.stubEnv("AUTH_CLIENT_SECRET", CLIENT_SECRET);
  vi.stubEnv("AUTH_ISSUER", ISSUER);
  vi.stubEnv("AUTH_REDIRECT_URI", REDIRECT_URI);
  logged = (["log", "info", "warn", "error", "debug", "trace"] as const).map((level) =>
    vi.spyOn(console, level).mockImplementation(() => {}),
  );
});

afterEach(() => {
  expectNoSecretLogged();
});

/** Puts a valid, encrypted sign-in transaction cookie in the jar, as /auth/login leaves it. */
async function givenTransactionCookie(transaction: AuthTransaction = TRANSACTION) {
  cookieJar.set(TRANSACTION_COOKIE, await sealData(transaction, { password: SESSION_SECRET, ttl: 10 * 60 }));
}

/** Serves the API (discovery and token endpoint) through the global fetch. */
function givenTokenEndpoint(tokenEndpoint: TokenEndpoint) {
  const fetch = fakeAuthorizationServer(tokenEndpoint);
  vi.stubGlobal("fetch", fetch);
  return fetch;
}

/** Calls GET /auth/callback with this query and returns where it redirected. */
async function callback(query: string): Promise<string> {
  const { GET } = await import("./route");
  const request = new NextRequest(`${REDIRECT_URI}?${query}`);
  try {
    await GET(request);
  } catch (signal) {
    if (signal instanceof RedirectSignal) return signal.destination;
    throw signal;
  }
  throw new Error("GET /auth/callback returned without redirecting");
}

/** Every console call so far, as one string, including error objects' messages, causes and stacks. */
function allLogOutput(): string {
  return logged
    .flatMap((spy) => spy.mock.calls.flat())
    .map((arg) => (arg instanceof Error ? `${arg.stack} ${String(arg.cause)}` : typeof arg === "string" ? arg : JSON.stringify(arg)))
    .join("\n");
}

function expectNoSecretLogged() {
  const output = allLogOutput();
  for (const secret of [
    CODE,
    TRANSACTION.codeVerifier,
    TRANSACTION.nonce,
    ACCESS_TOKEN,
    REFRESH_TOKEN,
    ID_TOKEN,
    ERROR_DESCRIPTION,
    CLIENT_SECRET,
    SESSION_SECRET,
  ]) {
    expect(output).not.toContain(secret);
  }
}

describe("GET /auth/callback failure paths", () => {
  it("goes to /auth/error when there is no sign-in transaction cookie", async () => {
    const fetch = givenTokenEndpoint(() => Response.json({}));

    expect(await callback(`code=${CODE}&state=${TRANSACTION.state}`)).toBe("/auth/error");
    expect(fetch).not.toHaveBeenCalled();
    expect(allLogOutput()).toContain("Sign-in callback without a valid sign-in transaction");
  });

  it("goes to /auth/error when the transaction cookie can't be decrypted", async () => {
    givenTokenEndpoint(() => Response.json({}));
    cookieJar.set(TRANSACTION_COOKIE, "not-a-sealed-value");

    expect(await callback(`code=${CODE}&state=${TRANSACTION.state}`)).toBe("/auth/error");
  });

  it("goes to /auth/error on a state mismatch, without calling the token endpoint", async () => {
    await givenTransactionCookie();
    const fetch = givenTokenEndpoint(() => Response.json({}));

    expect(await callback(`code=${CODE}&state=someone-elses-state`)).toBe("/auth/error");
    expect(fetch.mock.calls.map(([input, init]) => new Request(input, init).url)).not.toContain(TOKEN_ENDPOINT);
    expect(allLogOutput()).toContain("Sign-in didn't complete (ClientError, OAUTH_INVALID_RESPONSE)");
  });

  it("goes to /auth/error when the API answers error=access_denied", async () => {
    await givenTransactionCookie();
    givenTokenEndpoint(() => Response.json({}));

    const query = `error=access_denied&error_description=${encodeURIComponent(ERROR_DESCRIPTION)}&state=${TRANSACTION.state}`;
    expect(await callback(query)).toBe("/auth/error");
    expect(allLogOutput()).toContain("access_denied");
  });

  it("goes to /auth/error when the token endpoint refuses the code (400 invalid_grant)", async () => {
    await givenTransactionCookie();
    givenTokenEndpoint(() => Response.json({ error: "invalid_grant", error_description: ERROR_DESCRIPTION }, { status: 400 }));

    expect(await callback(`code=${CODE}&state=${TRANSACTION.state}`)).toBe("/auth/error");
  });

  it("goes to /auth/error?reason=unavailable when the API is down during the code exchange (fetch failed)", async () => {
    await givenTransactionCookie();
    givenTokenEndpoint(() => {
      throw networkError();
    });

    expect(await callback(`code=${CODE}&state=${TRANSACTION.state}`)).toBe("/auth/error?reason=unavailable");
  });

  it("goes to /auth/error?reason=unavailable when the code exchange times out", async () => {
    await givenTransactionCookie();
    givenTokenEndpoint(() => {
      throw timeoutError();
    });

    expect(await callback(`code=${CODE}&state=${TRANSACTION.state}`)).toBe("/auth/error?reason=unavailable");
  });

  it("goes to /auth/error?reason=unavailable when the token endpoint answers 5xx", async () => {
    await givenTransactionCookie();
    givenTokenEndpoint(
      () =>
        new Response(JSON.stringify({ access_token: ACCESS_TOKEN, refresh_token: REFRESH_TOKEN, id_token: ID_TOKEN }), {
          status: 503,
          headers: { "content-type": "application/json" },
        }),
    );

    expect(await callback(`code=${CODE}&state=${TRANSACTION.state}`)).toBe("/auth/error?reason=unavailable");
  });

  it("goes to /auth/error?reason=unavailable when discovery can't reach the API", async () => {
    await givenTransactionCookie();
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => {
        throw networkError();
      }),
    );

    expect(await callback(`code=${CODE}&state=${TRANSACTION.state}`)).toBe("/auth/error?reason=unavailable");
  });

  it("never redirects a failure to /auth/login, and always uses up the transaction cookie", async () => {
    const failures: Array<{ query: string; tokenEndpoint: TokenEndpoint }> = [
      { query: `code=${CODE}&state=someone-elses-state`, tokenEndpoint: () => Response.json({}) },
      { query: `error=access_denied&state=${TRANSACTION.state}`, tokenEndpoint: () => Response.json({}) },
      {
        query: `code=${CODE}&state=${TRANSACTION.state}`,
        tokenEndpoint: () => Response.json({ error: "invalid_grant" }, { status: 400 }),
      },
      {
        query: `code=${CODE}&state=${TRANSACTION.state}`,
        tokenEndpoint: () => {
          throw networkError();
        },
      },
    ];

    for (const { query, tokenEndpoint } of failures) {
      vi.resetModules();
      await givenTransactionCookie();
      givenTokenEndpoint(tokenEndpoint);

      const destination = await callback(query);

      expect(destination).toMatch(/^\/auth\/error(\?|$)/);
      expect(destination).not.toContain("/auth/login");
      expect(cookieJar.get(TRANSACTION_COOKIE) ?? "").toBe("");
    }
  });
});
