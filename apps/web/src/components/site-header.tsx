// The page header with the "Sign out" control (CAR-18). A Server Component: a plain HTML form, so signing out works
// without JavaScript and no token or session data reaches the browser.
//
// Contract (E2E and accessibility rely on it):
// - A `<header>` landmark (role "banner") with the app name.
// - A `<form method="post" action="/auth/logout">` with one `<button type="submit">Sign out</button>`: the
//   accessible name is exactly "Sign out" (E2E: `getByRole("button", { name: "Sign out" })`), reachable by
//   keyboard, with a visible focus style.
// - Rendered only when there is a session: by the home page for "signed-in" and "unavailable" (so a person can
//   still sign out of a shared computer while the API is down). Never on `/auth/*` pages.
export function SiteHeader() {
  return (
    <header className="flex items-center justify-between gap-4 border-b border-zinc-200 bg-white px-4 py-3 sm:px-6">
      <span className="text-lg font-semibold text-zinc-900">Washbase</span>
      <form method="post" action="/auth/logout">
        <button
          type="submit"
          className="inline-flex h-10 items-center justify-center rounded-md border border-zinc-300 bg-white px-4 font-medium text-zinc-900 hover:bg-zinc-100 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-zinc-900"
        >
          Sign out
        </button>
      </form>
    </header>
  );
}
