import { NextResponse, type NextRequest } from "next/server";
import { SESSION_COOKIE } from "@/lib/auth/cookies";
import { renewSession } from "@/lib/auth/renewal";
import { needsRenewal, sealSession, sessionCookieOptions, unsealSession } from "@/lib/auth/session";

/**
 * Keeps people signed in (CAR-18). Runs before every page (not `/auth/*`, see `config.matcher`), on the Node.js
 * runtime:
 *
 * - No session cookie → redirect to `/auth/login`.
 * - A cookie that can't be used (unreadable, seal expired after `SESSION_MAX_AGE_DAYS` without use, pre-CAR-18
 *   shape) → delete it, redirect to `/auth/login`.
 * - Access token valid for more than `RENEW_BEFORE_EXPIRY_MS` → continue unchanged (no cookie write).
 * - Otherwise renew, single-flight per refresh token (`renewSession`):
 *   - "renewed" → re-seal; set the cookie on the response (full Max-Age, so each use extends the 30 days) and on
 *     the forwarded request, so this request's Server Components already use the new access token.
 *   - "refused" (invalid_grant: expired, signed out, reuse detected) → delete the cookie, redirect to `/auth/login`.
 *   - "unavailable" (API down) → continue with the cookie unchanged; the home page shows "Can't reach Washbase".
 *
 * Never logs a cookie or token. The real authorization check is still the API (`getCurrentUser`). The redirect for
 * "refused" can't loop: `/auth/login` is outside the matcher. Every branch is covered in `src/proxy.test.ts`.
 */
export async function proxy(request: NextRequest) {
  const cookie = request.cookies.get(SESSION_COOKIE)?.value;
  if (!cookie) return toSignIn(request);

  const session = await unsealSession(cookie);
  if (!session) return toSignIn(request, { clearCookie: true });

  if (!needsRenewal(session)) return NextResponse.next();

  const outcome = await renewSession(session);
  switch (outcome.kind) {
    case "renewed": {
      const sealed = await sealSession(outcome.session);
      request.cookies.set(SESSION_COOKIE, sealed);
      const response = NextResponse.next({ request: { headers: request.headers } });
      response.cookies.set(SESSION_COOKIE, sealed, sessionCookieOptions());
      return response;
    }
    case "refused":
      return toSignIn(request, { clearCookie: true });
    case "unavailable":
      return NextResponse.next();
  }
}

function toSignIn(request: NextRequest, { clearCookie = false } = {}) {
  const response = NextResponse.redirect(new URL("/auth/login", request.url));
  // Path=/ explicitly: without it the browser would only drop a cookie scoped to this request's directory.
  if (clearCookie) response.cookies.delete({ name: SESSION_COOKIE, path: "/" });
  return response;
}

export const config = {
  // Everything except the auth routes (they run the sign-in and sign-out themselves), Next.js internals and files
  // with an extension (favicon.ico, public/ assets).
  // `\\.` is a literal dot in the regex. A single `\.` in a JS string is just `.`, which would exclude every path
  // longer than one character.
  matcher: ["/((?!auth/|_next/static|_next/image|.*\\.[^/]+$).*)"],
};
