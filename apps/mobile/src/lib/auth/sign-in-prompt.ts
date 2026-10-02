import { AuthRequest, ResponseType } from "expo-auth-session";

import { CLIENT_ID } from "./config";
import { describeError } from "./errors";
import type { AuthEndpoints } from "./oauth";

export type PromptOutcome =
  /** The authorization server redirected back with a code (state already checked by `expo-auth-session`). */
  | { kind: "code"; code: string; codeVerifier: string; redirectUri: string }
  /** The person closed or dismissed the sign-in page. Back to the welcome screen with no error. */
  | { kind: "cancelled" }
  /** The authorization server answered with an error, or the state didn't match. */
  | { kind: "failed" };

export type SignInPrompt = () => Promise<PromptOutcome>;

/**
 * Opens the authorization server's sign-in page in the in-app browser (Authorization Code + PKCE). A new
 * `AuthRequest` per attempt gives a fresh PKCE verifier and state every time. `preferEphemeralSession` (iOS) keeps
 * the page from sharing cookies with Safari.
 */
export function createSignInPrompt(redirectUri: string, resolveEndpoints: () => Promise<AuthEndpoints>): SignInPrompt {
  return async () => {
    const { authorizationEndpoint } = await resolveEndpoints();
    const request = new AuthRequest({
      clientId: CLIENT_ID,
      redirectUri,
      responseType: ResponseType.Code,
      scopes: ["openid"],
      usePKCE: true,
    });
    const result = await request.promptAsync({ authorizationEndpoint }, { preferEphemeralSession: true });
    if (result.type === "success") {
      const code = result.params.code;
      if (code && request.codeVerifier) {
        return { kind: "code", code, codeVerifier: request.codeVerifier, redirectUri };
      }
      return { kind: "failed" };
    }
    if (result.type === "error") {
      console.warn(`Sign-in page returned an error (${result.error ? result.error.code : describeError(result)})`);
      return { kind: "failed" };
    }
    // cancel, dismiss, locked (another prompt already open), opened.
    return { kind: "cancelled" };
  };
}
