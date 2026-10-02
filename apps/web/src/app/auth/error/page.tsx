// /auth/error: where a sign-in that couldn't finish ends up. A page, never a redirect, so there is no loop.
//   ?reason=unavailable → "Can't reach Washbase right now. Try again."
//   anything else       → "Sign-in didn't complete. Try again."
// "Try again" links to /auth/login.
import type { Metadata } from "next";

export const metadata: Metadata = { title: "Sign-in · Washbase" };

export default async function SignInErrorPage({ searchParams }: PageProps<"/auth/error">) {
  const { reason } = await searchParams;
  const message =
    reason === "unavailable" ? "Can't reach Washbase right now. Try again." : "Sign-in didn't complete. Try again.";

  return (
    <main className="flex flex-1 items-center justify-center bg-zinc-50 px-6 py-16">
      <section
        aria-labelledby="sign-in-error-heading"
        className="w-full max-w-sm rounded-lg border border-zinc-200 bg-white p-8 shadow-sm"
      >
        <h1 id="sign-in-error-heading" className="text-2xl font-semibold text-zinc-900">
          Washbase
        </h1>
        <p role="alert" className="mt-4 text-zinc-700">
          {message}
        </p>
        <a
          href="/auth/login"
          className="mt-6 inline-flex h-10 items-center justify-center rounded-md bg-zinc-900 px-4 font-medium text-white hover:bg-zinc-700 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-zinc-900"
        >
          Try again
        </a>
      </section>
    </main>
  );
}
