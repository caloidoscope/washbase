import { AuthRequest } from "expo-auth-session";

import { CLIENT_ID } from "../config";
import { createSignInPrompt } from "../sign-in-prompt";
import { ENDPOINTS } from "./fakes";

const mockPromptAsync = jest.fn();

jest.mock("expo-auth-session", () => ({
  ResponseType: { Code: "code" },
  AuthRequest: jest.fn().mockImplementation(() => ({ promptAsync: mockPromptAsync, codeVerifier: "the-verifier" })),
}));

const REDIRECT_URI = "exp://192.168.1.10:8081/--/auth/callback";

let warn: jest.SpyInstance;
beforeEach(() => {
  warn = jest.spyOn(console, "warn").mockImplementation(() => undefined);
  mockPromptAsync.mockReset();
  jest.mocked(AuthRequest).mockClear();
});
afterEach(() => {
  warn.mockRestore();
});

function prompt() {
  return createSignInPrompt(REDIRECT_URI, async () => ENDPOINTS)();
}

describe("sign-in prompt", () => {
  it("opens a fresh PKCE request each time, in an ephemeral session, at the authorization endpoint", async () => {
    mockPromptAsync.mockResolvedValue({ type: "dismiss" });

    await prompt();
    await prompt();

    expect(AuthRequest).toHaveBeenCalledTimes(2);
    expect(AuthRequest).toHaveBeenCalledWith({
      clientId: CLIENT_ID,
      redirectUri: REDIRECT_URI,
      responseType: "code",
      scopes: ["openid"],
      usePKCE: true,
    });
    expect(mockPromptAsync).toHaveBeenCalledWith(
      { authorizationEndpoint: ENDPOINTS.authorizationEndpoint },
      { preferEphemeralSession: true },
    );
  });

  it("returns the code with its verifier and redirect URI", async () => {
    mockPromptAsync.mockResolvedValue({ type: "success", params: { code: "the-code", state: "s" } });

    expect(await prompt()).toEqual({
      kind: "code",
      code: "the-code",
      codeVerifier: "the-verifier",
      redirectUri: REDIRECT_URI,
    });
  });

  it.each(["cancel", "dismiss", "locked"])(
    "Scenario: Closing the sign-in page without signing in (%s)",
    async (type) => {
      mockPromptAsync.mockResolvedValue({ type });

      expect(await prompt()).toEqual({ kind: "cancelled" });
    },
  );

  it.each([
    ["a state mismatch", { type: "error", error: { code: "state_mismatch" }, params: { code: "stolen" } }],
    ["an authorization error", { type: "error", error: { code: "access_denied" }, params: {} }],
    ["a success without a code", { type: "success", params: {} }],
  ])("fails on %s, never returning a code", async (_, result) => {
    mockPromptAsync.mockResolvedValue(result);

    expect(await prompt()).toEqual({ kind: "failed" });
  });
});
