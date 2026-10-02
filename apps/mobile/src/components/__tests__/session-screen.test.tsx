import { fireEvent, render, screen, waitFor } from "@testing-library/react-native";

import { SessionScreen, SIGN_IN_FAILED_MESSAGE, UNREACHABLE_MESSAGE } from "@/components/session-screen";
import { AuthProvider } from "@/lib/auth/auth-provider";
import { fakeAuthServer, fakeStore, makeSession, tokenResponse } from "@/lib/auth/__tests__/fakes";

jest.mock("@/lib/auth/create-session", () => ({
  createAuthSession: () => {
    throw new Error("tests pass their own session");
  },
}));

let warn: jest.SpyInstance;
beforeEach(() => {
  warn = jest.spyOn(console, "warn").mockImplementation(() => undefined);
});
afterEach(() => {
  warn.mockRestore();
});

async function renderWith(made: ReturnType<typeof makeSession>) {
  await render(
    <AuthProvider session={made.session}>
      <SessionScreen />
    </AuthProvider>,
  );
  // Let start-up (storage read, renewal, GET /me) settle.
  await waitFor(() => expect(made.session.getState().status).not.toBe("loading"));
}

describe("SessionScreen", () => {
  it("shows the welcome screen with Sign in when signed out", async () => {
    await renderWith(makeSession());

    expect(screen.getByRole("button", { name: "Sign in" })).toBeOnTheScreen();
    expect(screen.queryByText(SIGN_IN_FAILED_MESSAGE)).toBeNull();
  });

  it("Scenario: Closing the sign-in page without signing in", async () => {
    const made = makeSession();
    await renderWith(made);

    await fireEvent.press(screen.getByRole("button", { name: "Sign in" }));

    expect(made.prompt).toHaveBeenCalledTimes(1);
    expect(screen.getByRole("button", { name: "Sign in" })).toBeOnTheScreen();
    expect(screen.queryByText(SIGN_IN_FAILED_MESSAGE)).toBeNull();
    expect(screen.queryByText(UNREACHABLE_MESSAGE)).toBeNull();
  });

  it("Scenario: The Admin signs in on the mobile app with their email address (home screen text)", async () => {
    const made = makeSession({
      server: fakeAuthServer(tokenResponse("at-1", "rt-1")),
      prompt: jest.fn(async () => ({ kind: "code" as const, code: "c", codeVerifier: "v", redirectUri: "r" })),
    });
    await renderWith(made);

    await fireEvent.press(screen.getByRole("button", { name: "Sign in" }));

    expect(screen.getByText("Signed in as Admin (Admin)")).toBeOnTheScreen();
    expect(screen.getByRole("button", { name: "Sign out" })).toBeOnTheScreen();
  });

  it("Scenario: The Admin is still signed in after reopening the app", async () => {
    await renderWith(makeSession({ store: fakeStore("rt-1"), server: fakeAuthServer(tokenResponse("at-2", "rt-2")) }));

    expect(screen.getByText("Signed in as Admin (Admin)")).toBeOnTheScreen();
    expect(screen.queryByRole("button", { name: "Sign in" })).toBeNull();
  });

  it("Scenario: The Admin signs out on the mobile app", async () => {
    const made = makeSession({
      store: fakeStore("rt-1"),
      server: fakeAuthServer(tokenResponse("at-2", "rt-2"), { status: 200 }),
    });
    await renderWith(made);

    await fireEvent.press(screen.getByRole("button", { name: "Sign out" }));

    expect(screen.getByRole("button", { name: "Sign in" })).toBeOnTheScreen();
    expect(made.store.value).toBeNull();
  });

  it("Scenario: The mobile session ends after 30 days without use", async () => {
    await renderWith(
      makeSession({
        store: fakeStore("rt-1-october"),
        server: fakeAuthServer({ status: 400, body: { error: "invalid_grant" } }),
      }),
    );

    expect(screen.getByRole("button", { name: "Sign in" })).toBeOnTheScreen();
  });

  it("shows Can't reach Washbase with Try again on a network error, and recovers", async () => {
    const made = makeSession({
      store: fakeStore("rt-1"),
      server: fakeAuthServer(new TypeError("Network request failed"), tokenResponse("at-2", "rt-2")),
    });
    await renderWith(made);

    expect(screen.getByText(UNREACHABLE_MESSAGE)).toBeOnTheScreen();
    expect(made.store.value).toBe("rt-1");

    await fireEvent.press(screen.getByRole("button", { name: "Try again" }));

    expect(screen.getByText("Signed in as Admin (Admin)")).toBeOnTheScreen();
  });
});
