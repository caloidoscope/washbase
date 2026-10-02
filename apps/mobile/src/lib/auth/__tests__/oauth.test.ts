import type { DiscoveryDocument } from "expo-auth-session";

import { CLIENT_ID, REQUEST_TIMEOUT_MS } from "../config";
import { OAuthError, UnreachableError } from "../errors";
import { createEndpointResolver, createTokenClient, endpointsFromIssuer, type Fetch } from "../oauth";
import { ENDPOINTS, fakeAuthServer, tokenResponse } from "./fakes";

const ISSUER = "http://192.168.1.10:8080";

let warn: jest.SpyInstance;
beforeEach(() => {
  warn = jest.spyOn(console, "warn").mockImplementation(() => undefined);
});
afterEach(() => {
  warn.mockRestore();
  jest.useRealTimers();
});

function client(server: ReturnType<typeof fakeAuthServer>) {
  return createTokenClient(async () => ENDPOINTS, { fetchImpl: server.fetchImpl, now: () => 1_000_000 });
}

describe("token client", () => {
  it("sends client_id only (no secret), form-encoded", async () => {
    const server = fakeAuthServer(tokenResponse("at", "rt"));

    await client(server).exchangeCode("c&d=e", "v+/ =", "exp://192.168.1.10:8081/--/auth/callback");

    const init = server.fetchImpl.mock.calls[0][1];
    expect(init.method).toBe("POST");
    expect(init.headers).toMatchObject({ "Content-Type": "application/x-www-form-urlencoded" });
    expect(String(init.body)).toBe(
      "grant_type=authorization_code&code=c%26d%3De&code_verifier=v%2B%2F%20%3D" +
        "&redirect_uri=exp%3A%2F%2F192.168.1.10%3A8081%2F--%2Fauth%2Fcallback&client_id=washbase-mobile",
    );
    expect(String(init.body)).not.toMatch(/client_secret/);
    expect(init.headers).not.toHaveProperty("Authorization");
    expect(server.requests[0].form.client_id).toBe(CLIENT_ID);
  });

  it("returns the tokens with the expiry in epoch milliseconds", async () => {
    const tokens = await client(fakeAuthServer(tokenResponse("at", "rt", 900))).refresh("rt-0");

    expect(tokens).toEqual({ accessToken: "at", refreshToken: "rt", expiresAt: 1_000_000 + 900_000 });
  });

  it.each([
    ["a network error", new TypeError("Network request failed")],
    ["a 500", { status: 500, body: { error: "server_error" } }],
    ["a 503 without a body", { status: 503 }],
    ["a 408", { status: 408 }],
    ["a 429", { status: 429, body: { error: "slow_down" } }],
    ["an unreadable 200", { status: 200 }],
  ])("treats %s as unreachable", async (_, answer) => {
    await expect(client(fakeAuthServer(answer)).refresh("rt")).rejects.toBeInstanceOf(UnreachableError);
  });

  it.each([
    ["invalid_grant", { status: 400, body: { error: "invalid_grant" } }, "invalid_grant", 400],
    ["invalid_client", { status: 401, body: { error: "invalid_client" } }, "invalid_client", 401],
    ["a 4xx without JSON", { status: 403 }, "invalid_request", 403],
  ])("treats %s as an OAuth error", async (_, answer, code, status) => {
    const error = await client(fakeAuthServer(answer))
      .refresh("rt")
      .catch((e: unknown) => e);

    expect(error).toBeInstanceOf(OAuthError);
    expect(error).toMatchObject({ code, status });
  });

  it("treats a request that takes too long as unreachable", async () => {
    jest.useFakeTimers();
    const fetchImpl: Fetch = (_url, init) =>
      new Promise((_, reject) => init.signal?.addEventListener("abort", () => reject(new Error("aborted"))));
    const tokens = createTokenClient(async () => ENDPOINTS, { fetchImpl });

    const result = tokens.refresh("rt").catch((e: unknown) => e);
    await jest.advanceTimersByTimeAsync(REQUEST_TIMEOUT_MS);

    expect(await result).toEqual(new UnreachableError("timeout"));
  });

  it.each([
    ["no refresh token", { access_token: "at", token_type: "Bearer", expires_in: 900 }, "incomplete_token_response"],
    ["no access token", { refresh_token: "rt", token_type: "Bearer", expires_in: 900 }, "incomplete_token_response"],
    ["no token type", { access_token: "at", refresh_token: "rt", expires_in: 900 }, "unsupported_token_type"],
    ["a DPoP token", { access_token: "at", refresh_token: "rt", token_type: "DPoP" }, "unsupported_token_type"],
  ])("refuses a token response with %s", async (_, body, code) => {
    const error = await client(fakeAuthServer({ status: 200, body }))
      .refresh("rt")
      .catch((e: unknown) => e);

    expect(error).toBeInstanceOf(OAuthError);
    expect(error).toMatchObject({ code });
  });

  it("accepts token_type in any case and defaults a missing or invalid expires_in", async () => {
    const tokens = await client(
      fakeAuthServer({ status: 200, body: { access_token: "at", refresh_token: "rt", token_type: "bearer", expires_in: -5 } }),
    ).refresh("rt-0");

    expect(tokens.expiresAt).toBe(1_000_000 + 300_000);
  });

  it("revokes the refresh token with the refresh_token hint and client_id only", async () => {
    const server = fakeAuthServer({ status: 200 });

    await client(server).revoke("rt-1");

    expect(server.requests).toEqual([
      { url: ENDPOINTS.revocationEndpoint, form: { token: "rt-1", token_type_hint: "refresh_token", client_id: CLIENT_ID } },
    ]);
  });
});

describe("endpoint discovery", () => {
  function discovery(overrides: Partial<DiscoveryDocument> = {}, issuer = ISSUER): DiscoveryDocument {
    return {
      discoveryDocument: { issuer } as DiscoveryDocument["discoveryDocument"],
      authorizationEndpoint: `${ISSUER}/oauth2/authorize`,
      tokenEndpoint: `${ISSUER}/oauth2/token`,
      revocationEndpoint: `${ISSUER}/oauth2/revoke`,
      ...overrides,
    };
  }

  it("uses and caches a discovery document for the configured issuer", async () => {
    const discover = jest.fn(async () => discovery());
    const resolve = createEndpointResolver(ISSUER, discover);

    expect(await resolve()).toEqual(endpointsFromIssuer(ISSUER));
    await resolve();

    expect(discover).toHaveBeenCalledTimes(1);
    expect(discover).toHaveBeenCalledWith(ISSUER);
  });

  it.each([
    ["another issuer", discovery({}, "http://evil.example")],
    ["an endpoint on another host", discovery({ tokenEndpoint: "http://evil.example/oauth2/token" })],
    ["an endpoint on a look-alike host", discovery({ revocationEndpoint: `${ISSUER}.evil.example/oauth2/revoke` })],
    ["a missing endpoint", discovery({ revocationEndpoint: undefined })],
  ])("ignores a discovery document with %s and uses the configured issuer's paths", async (_, doc) => {
    const discover = jest.fn(async () => doc);
    const resolve = createEndpointResolver(ISSUER, discover);

    expect(await resolve()).toEqual({
      authorizationEndpoint: `${ISSUER}/oauth2/authorize`,
      tokenEndpoint: `${ISSUER}/oauth2/token`,
      revocationEndpoint: `${ISSUER}/oauth2/revoke`,
    });
    await resolve();
    expect(discover).toHaveBeenCalledTimes(2);
  });

  it("falls back to the configured issuer's paths when discovery fails, and tries again next time", async () => {
    const discover = jest
      .fn<Promise<DiscoveryDocument>, [string]>()
      .mockRejectedValueOnce(new TypeError("Network request failed"))
      .mockResolvedValueOnce(discovery());
    const resolve = createEndpointResolver(ISSUER, discover);

    expect(await resolve()).toEqual(endpointsFromIssuer(ISSUER));
    expect(await resolve()).toEqual(endpointsFromIssuer(ISSUER));
    expect(discover).toHaveBeenCalledTimes(2);
  });
});
