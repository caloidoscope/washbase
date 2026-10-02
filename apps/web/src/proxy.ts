import { NextResponse, type NextRequest } from "next/server";
import { SESSION_COOKIE } from "@/lib/auth/cookies";

/**
 * Optimistic check only (no decryption, no API call): a visitor without a session cookie is sent to sign-in.
 * The real check is the home page's `getCurrentUser()` (expired or rejected sessions go back to sign-in there).
 */
export function proxy(request: NextRequest) {
  if (!request.cookies.has(SESSION_COOKIE)) {
    return NextResponse.redirect(new URL("/auth/login", request.url));
  }
  return NextResponse.next();
}

export const config = {
  // Everything except the auth routes (they run the sign-in itself), Next.js internals and files with an extension
  // (favicon.ico, public/ assets).
  // `\\.` is a literal dot in the regex. A single `\.` in a JS string is just `.`, which would exclude every path
  // longer than one character.
  matcher: ["/((?!auth/|_next/static|_next/image|.*\\.[^/]+$).*)"],
};
