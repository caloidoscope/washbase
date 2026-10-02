// GET /auth/callback: the API redirects here with `code` and `state` (or `error`). Its URL is the registered
// redirect URI (AUTH_REDIRECT_URI = the API's WASHBASE_WEB_REDIRECT_URI).
//
// Exchanges the code (client secret, PKCE verifier, state and nonce checked), saves the session, then goes to `/`.
// Any failure goes to /auth/error, never back to /auth/login, so a failure can't loop.
import type { NextRequest } from "next/server";
import { redirect } from "next/navigation";
import { AuthUnavailableError, SignInFailedError, completeSignIn } from "@/lib/auth/oidc";
import { saveSession, takeTransaction } from "@/lib/auth/session";

export async function GET(request: NextRequest): Promise<Response> {
  let destination: string;
  try {
    const transaction = await takeTransaction();
    if (!transaction) {
      console.warn("Sign-in callback without a valid sign-in transaction");
      destination = "/auth/error";
    } else {
      const session = await completeSignIn(request.nextUrl.searchParams, transaction);
      await saveSession(session);
      destination = "/";
    }
  } catch (error) {
    if (error instanceof AuthUnavailableError) {
      console.warn("Sign-in couldn't complete: the authorization server can't be reached");
      destination = "/auth/error?reason=unavailable";
    } else {
      // SignInFailedError messages hold only an error name/code; anything else is reported by name only.
      console.warn(error instanceof SignInFailedError ? error.message : `Sign-in couldn't complete (${describe(error)})`);
      destination = "/auth/error";
    }
  }
  redirect(destination);
}

function describe(error: unknown): string {
  if (!(error instanceof Error)) return typeof error;
  return error.name === "Error" ? error.message : error.name;
}
