// The home page. A Server Component: the user's details come from `getCurrentUser()`, which calls the API with the
// session's token on the server. Only display strings reach the HTML; no token ever does.
import { redirect } from "next/navigation";
import { getCurrentUser } from "@/lib/auth/current-user";

export default async function Home() {
  const user = await getCurrentUser();

  if (user.status === "signed-out") redirect("/auth/login");
  // The API rejected a session that was created moments ago: sending the browser to sign-in again would loop.
  if (user.status === "sign-in-failed") redirect("/auth/error");

  return (
    <main className="flex flex-1 items-center justify-center bg-zinc-50 px-6 py-16">
      <section
        aria-labelledby="home-heading"
        className="w-full max-w-sm rounded-lg border border-zinc-200 bg-white p-8 shadow-sm"
      >
        <h1 id="home-heading" className="text-2xl font-semibold text-zinc-900">
          Washbase
        </h1>
        {user.status === "signed-in" ? (
          <p className="mt-4 text-zinc-700">
            {`Signed in as ${user.name} (${user.roleLabel})`}
          </p>
        ) : (
          <>
            <p role="alert" className="mt-4 text-zinc-700">
              Can&apos;t reach Washbase right now. Try again.
            </p>
            {/* A full page load on purpose: the retry may redirect through /auth/login to the API's sign-in page,
                which a client-side <Link> navigation can't follow. */}
            {/* eslint-disable-next-line @next/next/no-html-link-for-pages */}
            <a
              href="/"
              className="mt-6 inline-flex h-10 items-center justify-center rounded-md bg-zinc-900 px-4 font-medium text-white hover:bg-zinc-700 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-zinc-900"
            >
              Try again
            </a>
          </>
        )}
      </section>
    </main>
  );
}
