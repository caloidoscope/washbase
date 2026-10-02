import { CLIENT_ID } from "../config";
import { ADMIN, ENDPOINTS, fakeAuthServer, fakeMe, fakeStore, makeSession, tokenResponse, type Events } from "./fakes";

let warn: jest.SpyInstance;
beforeEach(() => {
  warn = jest.spyOn(console, "warn").mockImplementation(() => undefined);
});
afterEach(() => {
  warn.mockRestore();
});

describe("start-up and renewal", () => {
  it("Scenario: The Admin is still signed in after reopening the app", async () => {
    // Signed in at 09:00, reopened at 09:20: the stored refresh token is renewed and the home screen shows.
    const store = fakeStore("rt-0900");
    const server = fakeAuthServer(tokenResponse("at-0920", "rt-0920"));
    const me = fakeMe(ADMIN);
    const { session } = makeSession({ store, server, me });

    await session.start();

    expect(session.getState()).toEqual({ status: "signed-in", user: { name: "Admin", roleLabel: "Admin" } });
    expect(server.requests).toEqual([
      {
        url: ENDPOINTS.tokenEndpoint,
        form: { grant_type: "refresh_token", refresh_token: "rt-0900", client_id: CLIENT_ID },
      },
    ]);
    expect(store.value).toBe("rt-0920");
    expect(me.usedTokens).toEqual(["at-0920"]);
  });

  it("shows the welcome screen when nothing is stored, without calling the server", async () => {
    const server = fakeAuthServer();
    const { session } = makeSession({ store: fakeStore(null), server });

    await session.start();

    expect(session.getState()).toEqual({ status: "signed-out" });
    expect(server.fetchImpl).not.toHaveBeenCalled();
  });

  it("Scenario: The mobile session ends after 30 days without use", async () => {
    // Signed in on 1 October, opened on 1 November: the refresh token has expired and the server says invalid_grant.
    const store = fakeStore("rt-1-october");
    const server = fakeAuthServer({ status: 400, body: { error: "invalid_grant" } });
    const { session, me } = makeSession({ store, server });

    await session.start();

    expect(session.getState()).toEqual({ status: "signed-out" });
    expect(store.value).toBeNull();
    expect(store.clear).toHaveBeenCalled();
    expect(me.loadMe).not.toHaveBeenCalled();
  });

  it.each([
    ["a network error", new TypeError("Network request failed")],
    ["a 503", { status: 503, body: { error: "Service Unavailable" } }],
    ["a 500 with a JSON error body", { status: 500, body: { error: "Internal Server Error", status: 500 } }],
  ])("keeps the stored token and shows unreachable on %s", async (_, answer) => {
    const store = fakeStore("rt-1");
    const server = fakeAuthServer(answer);
    const { session } = makeSession({ store, server });

    await session.start();

    expect(session.getState()).toEqual({ status: "unreachable" });
    expect(store.value).toBe("rt-1");
    expect(store.clear).not.toHaveBeenCalled();
  });

  it("Try again after unreachable renews with the kept token and shows home", async () => {
    const store = fakeStore("rt-1");
    const server = fakeAuthServer(new TypeError("Network request failed"), tokenResponse("at-2", "rt-2"));
    const { session } = makeSession({ store, server });

    await session.start();
    expect(session.getState()).toEqual({ status: "unreachable" });

    await session.retry();

    expect(session.getState()).toMatchObject({ status: "signed-in" });
    expect(server.requests.map((r) => r.form.refresh_token)).toEqual(["rt-1", "rt-1"]);
    expect(store.value).toBe("rt-2");
  });

  it("shows unreachable when GET /api/v1/me can't be reached, keeping the token", async () => {
    const store = fakeStore("rt-1");
    const server = fakeAuthServer(tokenResponse("at-2", "rt-2"));
    const { session } = makeSession({ store, server, me: fakeMe({ kind: "unreachable" }) });

    await session.start();

    expect(session.getState()).toEqual({ status: "unreachable" });
    expect(store.value).toBe("rt-2");
  });

  it("signs out when GET /api/v1/me answers 401 right after a fresh renewal", async () => {
    const store = fakeStore("rt-1");
    const server = fakeAuthServer(tokenResponse("at-2", "rt-2"));
    const { session } = makeSession({ store, server, me: fakeMe({ kind: "unauthorized" }) });

    await session.start();

    expect(session.getState()).toEqual({ status: "signed-out" });
    expect(store.value).toBeNull();
  });

  it("stores the new refresh token before its access token is used", async () => {
    const events: Events = [];
    const store = fakeStore("rt-1", events);
    const server = fakeAuthServer(tokenResponse("at-2", "rt-2"));
    const { session } = makeSession({ store, server, me: fakeMe(ADMIN, events) });

    await session.start();

    expect(events).toEqual(["store:rt-2", "use:at-2"]);
  });

  it("doesn't use the new access token if the refresh token can't be stored", async () => {
    const store = fakeStore("rt-1");
    store.write.mockRejectedValueOnce(new Error("keychain unavailable"));
    const server = fakeAuthServer(tokenResponse("at-2", "rt-2"));
    const { session, me } = makeSession({ store, server });

    await session.start();

    expect(session.getState()).toEqual({ status: "signed-out" });
    expect(me.loadMe).not.toHaveBeenCalled();
    expect(await session.getAccessToken()).toBeUndefined();
  });

  it("single-flight: two concurrent callers share one token request", async () => {
    let time = 1_000_000;
    const store = fakeStore("rt-1");
    const server = fakeAuthServer(tokenResponse("at-1", "rt-2", 900), tokenResponse("at-2", "rt-3"));
    const { session } = makeSession({ store, server, now: () => time });
    await session.start();
    expect(server.requests).toHaveLength(1);
    // 14 min 10 s later the access token expires within the 60 s renewal margin.
    time += 850_000;

    const [a, b] = await Promise.all([session.getAccessToken(), session.getAccessToken()]);

    expect(a).toBe("at-2");
    expect(b).toBe("at-2");
    expect(server.requests).toHaveLength(2);
    expect(server.requests[1].form.refresh_token).toBe("rt-2");
    expect(store.value).toBe("rt-3");
  });

  it("uses the in-memory access token without renewing while it has more than 60 s left", async () => {
    const server = fakeAuthServer(tokenResponse("at-1", "rt-2", 900));
    const { session } = makeSession({ store: fakeStore("rt-1"), server });
    await session.start();

    expect(await session.getAccessToken()).toBe("at-1");
    expect(server.requests).toHaveLength(1);
  });

  it("renews when the app returns to the foreground", async () => {
    const store = fakeStore("rt-1");
    const server = fakeAuthServer(tokenResponse("at-1", "rt-2"), tokenResponse("at-2", "rt-3"));
    const { session } = makeSession({ store, server });
    await session.start();

    await session.onForeground();

    expect(server.requests).toHaveLength(2);
    expect(store.value).toBe("rt-3");
    expect(session.getState()).toMatchObject({ status: "signed-in" });
  });

  it("returning to the foreground after 30 days without use shows the welcome screen", async () => {
    const store = fakeStore("rt-1");
    const server = fakeAuthServer(tokenResponse("at-1", "rt-2"), { status: 400, body: { error: "invalid_grant" } });
    const { session } = makeSession({ store, server });
    await session.start();

    await session.onForeground();

    expect(session.getState()).toEqual({ status: "signed-out" });
    expect(store.value).toBeNull();
  });
});

describe("sign-in", () => {
  it("Scenario: The Admin signs in on the mobile app with their email address (code exchange and home screen)", async () => {
    const store = fakeStore(null);
    const server = fakeAuthServer(tokenResponse("at-1", "rt-1"));
    const prompt = jest.fn(async () => ({
      kind: "code" as const,
      code: "the-code",
      codeVerifier: "the-verifier",
      redirectUri: "exp://192.168.1.10:8081/--/auth/callback",
    }));
    const { session } = makeSession({ store, server, prompt });
    await session.start();

    await session.signIn();

    expect(session.getState()).toEqual({ status: "signed-in", user: { name: "Admin", roleLabel: "Admin" } });
    expect(server.requests).toEqual([
      {
        url: ENDPOINTS.tokenEndpoint,
        form: {
          grant_type: "authorization_code",
          code: "the-code",
          code_verifier: "the-verifier",
          redirect_uri: "exp://192.168.1.10:8081/--/auth/callback",
          client_id: CLIENT_ID,
        },
      },
    ]);
    expect(store.value).toBe("rt-1");
  });

  it("Scenario: Closing the sign-in page without signing in", async () => {
    const server = fakeAuthServer();
    const { session, prompt } = makeSession({ server });
    await session.start();

    await session.signIn();

    expect(prompt).toHaveBeenCalledTimes(1);
    expect(session.getState()).toEqual({ status: "signed-out" });
    expect(server.fetchImpl).not.toHaveBeenCalled();
  });

  it("shows the sign-in-failed notice when the code exchange is refused", async () => {
    const store = fakeStore(null);
    const server = fakeAuthServer({ status: 400, body: { error: "invalid_grant" } });
    const prompt = jest.fn(async () => ({ kind: "code" as const, code: "c", codeVerifier: "v", redirectUri: "r" }));
    const { session } = makeSession({ store, server, prompt });

    await session.signIn();

    expect(session.getState()).toEqual({ status: "signed-out", notice: "sign-in-failed" });
    expect(store.value).toBeNull();
  });
});

describe("sign-out", () => {
  async function signedIn(revokeAnswer: Parameters<typeof fakeAuthServer>[0]) {
    const store = fakeStore("rt-1");
    const server = fakeAuthServer(tokenResponse("at-1", "rt-2"), revokeAnswer);
    const made = makeSession({ store, server });
    await made.session.start();
    expect(made.session.getState()).toMatchObject({ status: "signed-in" });
    return made;
  }

  it("Scenario: The Admin signs out on the mobile app", async () => {
    const { session, store, server } = await signedIn({ status: 200 });

    await session.signOut();

    expect(session.getState()).toEqual({ status: "signed-out" });
    expect(server.requests[1]).toEqual({
      url: ENDPOINTS.revocationEndpoint,
      form: { token: "rt-2", token_type_hint: "refresh_token", client_id: CLIENT_ID },
    });
    expect(store.value).toBeNull();
    expect(await session.getAccessToken()).toBeUndefined();

    // Reopening the app shows the welcome screen.
    const reopened = makeSession({ store, server: fakeAuthServer() });
    await reopened.session.start();
    expect(reopened.session.getState()).toEqual({ status: "signed-out" });
    expect(reopened.server.fetchImpl).not.toHaveBeenCalled();
  });

  it.each([
    ["a network error", new TypeError("Network request failed")],
    ["a 503", { status: 503 }],
  ])("Scenario: The Admin signs out on the mobile app (revoking fails with %s)", async (_, answer) => {
    const { session, store } = await signedIn(answer);

    await session.signOut();

    expect(session.getState()).toEqual({ status: "signed-out" });
    expect(store.value).toBeNull();
  });

  it("clears storage before revoking, so a killed app can't come back signed in", async () => {
    const events: Events = [];
    const store = fakeStore("rt-1", events);
    const server = fakeAuthServer(tokenResponse("at-1", "rt-2"), { status: 200 });
    const { session } = makeSession({ store, server });
    await session.start();
    const answer = server.fetchImpl.getMockImplementation()!;
    server.fetchImpl.mockImplementation(async (url, init) => {
      events.push("revoke");
      return answer(url, init);
    });

    await session.signOut();

    expect(events.slice(-2)).toEqual(["store:cleared", "revoke"]);
  });
});
