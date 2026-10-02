// GET /auth/login: starts the Authorization Code + PKCE flow (ADR-001). Saves the PKCE verifier, state and nonce
// in the encrypted `washbase_auth_tx` cookie, then redirects to the API's /oauth2/authorize (which shows its
// /login page). Never redirects to a URL taken from the request (no open redirect).
import { redirect } from "next/navigation";
import { AuthUnavailableError, startSignIn } from "@/lib/auth/oidc";
import { saveTransaction } from "@/lib/auth/session";

export async function GET(): Promise<Response> {
  let destination: string;
  try {
    const { authorizationUrl, transaction } = await startSignIn();
    await saveTransaction(transaction);
    destination = authorizationUrl.href;
  } catch (error) {
    if (error instanceof AuthUnavailableError) {
      console.warn("Sign-in couldn't start: the authorization server can't be reached");
      destination = "/auth/error?reason=unavailable";
    } else {
      // Configuration errors name the variable, never its value; other errors are reported by name only.
      console.error(`Sign-in couldn't start: ${describe(error)}`);
      destination = "/auth/error";
    }
  }
  redirect(destination);
}

function describe(error: unknown): string {
  if (!(error instanceof Error)) return typeof error;
  return error.name === "Error" ? error.message : error.name;
}
