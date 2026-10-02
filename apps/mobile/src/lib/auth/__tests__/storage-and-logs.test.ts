import * as fs from "node:fs";
import * as path from "node:path";

import * as SecureStore from "expo-secure-store";

import { REFRESH_TOKEN_KEY, secureRefreshTokenStore } from "../token-store";
import { fakeAuthServer, fakeMe, fakeStore, makeSession, tokenResponse } from "./fakes";

jest.mock("expo-secure-store", () => ({
  AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY: 4,
  getItemAsync: jest.fn(async () => null),
  setItemAsync: jest.fn(async () => undefined),
  deleteItemAsync: jest.fn(async () => undefined),
}));

const appRoot = path.resolve(__dirname, "../../../..");

function sourceFiles(dir: string): string[] {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) return entry.name === "__tests__" ? [] : sourceFiles(full);
    return /\.(ts|tsx|js)$/.test(entry.name) ? [full] : [];
  });
}

describe("secure storage only", () => {
  it("keeps the refresh token in expo-secure-store under washbase.refreshToken, this device only", async () => {
    await secureRefreshTokenStore.write("rt-1");
    await secureRefreshTokenStore.read();
    await secureRefreshTokenStore.clear();

    const options = { keychainAccessible: SecureStore.AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY };
    expect(REFRESH_TOKEN_KEY).toBe("washbase.refreshToken");
    expect(SecureStore.setItemAsync).toHaveBeenCalledWith("washbase.refreshToken", "rt-1", options);
    expect(SecureStore.getItemAsync).toHaveBeenCalledWith("washbase.refreshToken", options);
    expect(SecureStore.deleteItemAsync).toHaveBeenCalledWith("washbase.refreshToken", options);
  });

  it("writes nothing to AsyncStorage: no AsyncStorage dependency and no use in the app's code", () => {
    const pkg = JSON.parse(fs.readFileSync(path.join(appRoot, "package.json"), "utf8"));
    const deps = { ...pkg.dependencies, ...pkg.devDependencies };
    expect(Object.keys(deps).filter((name) => /async-storage/i.test(name))).toEqual([]);

    const offenders = sourceFiles(path.join(appRoot, "src")).filter((file) =>
      /@react-native-async-storage|\bAsyncStorage\.\w|\blocalStorage\.\w/.test(fs.readFileSync(file, "utf8")),
    );
    expect(offenders).toEqual([]);
  });
});

describe("no tokens in logs", () => {
  it("never logs a token, through sign-in, renewal failures and sign-out", async () => {
    const spies = (["log", "info", "warn", "error", "debug"] as const).map((method) =>
      jest.spyOn(console, method).mockImplementation(() => undefined),
    );
    try {
      // Unreachable renewal, then a successful one, a refused one, sign-in, and a failed revoke.
      const store = fakeStore("secret-rt-1");
      const server = fakeAuthServer(
        new TypeError("Network request failed for secret-rt-1"),
        { status: 503 },
        tokenResponse("secret-at-2", "secret-rt-2"),
        new TypeError("Network request failed"),
      );
      const { session } = makeSession({ store, server, me: fakeMe() });
      await session.start();
      await session.retry();
      await session.retry();
      await session.signOut();

      const refused = makeSession({
        store: fakeStore("secret-rt-3"),
        server: fakeAuthServer({ status: 400, body: { error: "invalid_grant", error_description: "secret-rt-3" } }),
      });
      await refused.session.start();

      const signIn = makeSession({
        server: fakeAuthServer({ status: 400, body: { error: "invalid_grant" } }),
        prompt: jest.fn(async () => ({
          kind: "code" as const,
          code: "secret-code",
          codeVerifier: "secret-verifier",
          redirectUri: "r",
        })),
      });
      await signIn.session.signIn();

      const logged = spies.flatMap((spy) => spy.mock.calls.flat().map((arg) => String(arg)));
      expect(logged.length).toBeGreaterThan(0);
      for (const line of logged) expect(line).not.toMatch(/secret-/);
    } finally {
      spies.forEach((spy) => spy.mockRestore());
    }
  });
});
