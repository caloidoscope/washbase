// POST /auth/logout: "Sign out" (CAR-18, ADR-001 Amendment 1). Posted by the header's <form method="post">; there is
// no GET handler, so GET /auth/logout answers 405 and a link or image can't sign anyone out.
//
// Contract, in this order:
// 1. CSRF: the `Origin` header must equal `authEnv().appOrigin`. If `Origin` is absent, accept only
//    `Sec-Fetch-Site: same-origin`. Otherwise answer 403 and change nothing (the cookie stays: a foreign site can't
//    sign anyone out either). This runs before reading the session.
// 2. No usable session (no cookie, unreadable, pre-CAR-18 shape) → clear the cookie anyway, 303 to `/`.
// 3. Revoke the refresh token at the API (`revokeRefreshToken`, `token_type_hint=refresh_token`; this also ends the
//    access token). Then ALWAYS clear the session cookie, whatever happened next.
//    - Revoke unreachable (AuthUnavailableError) → 303 to `/auth/error?reason=unavailable`.
//    - Revoke refused (SignInFailedError, e.g. invalid_client) → log the name only, continue with step 4.
// 4. Build the end-session URL (`endSessionUrl(session.idToken)`: `id_token_hint`, `post_logout_redirect_uri` =
//    `authEnv().postLogoutRedirectUri`, `client_id`, in the query) and check it (`isEndSessionAccepted`):
//    - accepted → 303 to it. The API redirects the browser to `/`, which leads to the sign-in page.
//    - not accepted (the authorization is already gone, e.g. after reuse detection, or a stale ID token: the API
//      would answer 400 and strand the person on its error page) → 303 to `/`.
//    - unreachable → 303 to `/auth/error?reason=unavailable`.
// Always 303 (See Other) so the browser follows with GET; never redirect to a URL taken from the request.
// Log outcomes by kind only: never the cookie, a token, or the end-session URL (it holds the ID token).
//
// Route Handler, not a Server Action: it must answer with a cross-origin 303 and clear the cookie in the same
// response. The deletion goes through `next/headers` (`clearSession`): Next merges those cookie writes into the
// Response a Route Handler returns (app-route module, `appendMutableCookies`), so the 303 carries the Set-Cookie.
import { NextResponse, type NextRequest } from "next/server";
import { authEnv } from "@/lib/auth/env";
import { AuthUnavailableError, endSessionUrl, isEndSessionAccepted, revokeRefreshToken } from "@/lib/auth/oidc";
import { clearSession, getSession } from "@/lib/auth/session";

const UNAVAILABLE = "/auth/error?reason=unavailable";

export async function POST(request: NextRequest): Promise<Response> {
  if (!isSameOrigin(request)) {
    console.warn("Sign-out refused: not a same-origin request");
    return new NextResponse(null, { status: 403 });
  }

  const session = await getSession();
  if (!session) {
    await clearSession();
    return seeOther("/");
  }

  try {
    await revokeRefreshToken(session.refreshToken);
  } catch (error) {
    if (error instanceof AuthUnavailableError) {
      await clearSession();
      // ADR-001 Amendment 1, accepted risk: the browser is signed out, the server-side authorization is not revoked.
      console.warn("Sign-out couldn't revoke the session: the authorization server can't be reached");
      return seeOther(UNAVAILABLE);
    }
    console.warn(`Sign-out: revoking the session was refused (${describe(error)})`);
  }
  await clearSession();

  let destination: URL;
  try {
    destination = await endSessionUrl(session.idToken);
    if (!(await isEndSessionAccepted(destination))) return seeOther("/");
  } catch (error) {
    if (error instanceof AuthUnavailableError) {
      console.warn("Sign-out couldn't end the session at the authorization server: it can't be reached");
      return seeOther(UNAVAILABLE);
    }
    console.warn(`Sign-out couldn't build the end-session URL (${describe(error)})`);
    return seeOther("/");
  }
  return seeOther(destination.href);
}

/** CSRF check: `Origin` equals the app's origin; without `Origin`, only `Sec-Fetch-Site: same-origin`.
 *  The app's origin is `AUTH_REDIRECT_URI`'s, the only host the session cookie is ever set on (by `/auth/callback`),
 *  so the "Sign out" button only renders there. Opening the app on another host (e.g. 127.0.0.1 instead of localhost)
 *  has no session to sign out of, which is why an exact match is enough. */
function isSameOrigin(request: NextRequest): boolean {
  const origin = request.headers.get("origin");
  if (origin) return origin === authEnv().appOrigin;
  return request.headers.get("sec-fetch-site") === "same-origin";
}

/** A 303 to `location` (a fixed path or the end-session URL, never anything from the request). */
function seeOther(location: string): Response {
  return new NextResponse(null, { status: 303, headers: { Location: location } });
}

/** An error's name only (a SignInFailedError's message holds only names and codes). Never its message otherwise. */
function describe(error: unknown): string {
  if (!(error instanceof Error)) return typeof error;
  return error.name === "SignInFailedError" ? error.message : error.name;
}
