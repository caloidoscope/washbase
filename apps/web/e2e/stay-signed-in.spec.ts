import { expect, test, type BrowserContext, type Page } from "@playwright/test";
import { LOCAL_WEB_CLIENT_SECRET } from "../../../scripts/local-env.mjs";
import {
  DAY_MS,
  SESSION_MAX_AGE_SECONDS,
  agedSignIn,
  expiredAccessToken,
  readSession,
  sessionCookie,
  updateSession,
  writeSession,
} from "./support/session-cookie";
import { signInAsAdmin } from "./support/sign-in";
import { apiURL, isApiSignInPage } from "./support/urls";

// CAR-18. Every test signs in the bootstrapped Admin in its own browser context, so each has its own authorization
// at the API and none depends on another. Time is simulated through the encrypted session cookie
// (support/session-cookie.ts): an access token "expires" by rewriting `expiresAt`, and a cookie "from N days ago" is
// re-sealed with its seal back-dated. The token renewals and sign-outs themselves are real calls to the API.
const SIGNED_IN = "Signed in as Admin (Admin)";

async function expectSignInPage(page: Page) {
  await expect(page).toHaveURL(isApiSignInPage);
  await expect(page.getByLabel("Email or mobile number")).toBeVisible();
  await expect(page.getByLabel("Password")).toBeVisible();
  await expect(page.getByText("Signed in as")).toHaveCount(0);
}

async function signOut(page: Page) {
  await page.getByRole("banner").getByRole("button", { name: "Sign out" }).click();
  await expectSignInPage(page);
}

/** The `washbase_session` cookie's expiry, in milliseconds from now. */
async function cookieLifetimeMs(context: BrowserContext): Promise<number> {
  const cookie = await sessionCookie(context);
  if (!cookie) throw new Error("no session cookie");
  return cookie.expires * 1000 - Date.now();
}

test.describe("CAR-18 People stay signed in on the web app until they sign out", () => {
  test("Scenario: The Admin is still signed in after the short-lived sign-in token expires", async ({
    page,
    context,
  }) => {
    await signInAsAdmin(page);
    await expect(page.getByText(SIGNED_IN)).toBeVisible();

    const cookie = await sessionCookie(context);
    // How big the real session cookie is (browsers cap a cookie at 4096 bytes).
    console.log(`washbase_session cookie length after sign-in: ${cookie?.value.length} characters`);
    test.info().annotations.push({ type: "washbase_session length", description: String(cookie?.value.length) });

    // 09:20: the 15-minute access token has run out.
    const before = await updateSession(context, expiredAccessToken);
    await page.reload();

    await expect(page).toHaveURL("/");
    await expect(page.getByText(SIGNED_IN)).toBeVisible();
    const after = await readSession(context);
    expect(after.refreshToken).not.toBe(before.refreshToken);
    expect(after.accessToken).not.toBe(before.accessToken);
    expect(after.expiresAt).toBeGreaterThan(Date.now());
    expect(after.signedInAt).toBe(before.signedInAt);
  });

  test("Scenario: The Admin signs out", async ({ page, context }) => {
    await signInAsAdmin(page);
    await expect(page.getByText(SIGNED_IN)).toBeVisible();

    await signOut(page);
    expect(await sessionCookie(context)).toBeUndefined();

    await page.goto("/");
    await expectSignInPage(page);
  });

  test("Scenario: Signing out asks for the password again next time", async ({ page }) => {
    await signInAsAdmin(page);
    await signOut(page);

    // A fresh visit to the web app: the API's sign-in page is shown instead of signing the Admin straight back in.
    await page.goto("/");
    await expectSignInPage(page);
    await expect(page.getByLabel("Email or mobile number")).toHaveValue("");
    await expect(page.getByLabel("Password")).toHaveValue("");
  });

  test("Scenario: A copied session can't be used after signing out", async ({ page, context, browser }) => {
    await signInAsAdmin(page);
    const copied = await sessionCookie(context);
    if (!copied) throw new Error("no session cookie");

    const thief = await browser.newContext();
    try {
      await thief.addCookies([copied]);
      await signOut(page);

      // Past the 30-second redirect-loop guard, so the API's 401 leads to sign-in. The access token in the copy is
      // still within its 15 minutes: the API refuses it because the sign-in was revoked.
      await updateSession(thief, agedSignIn);
      const thiefPage = await thief.newPage();
      await thiefPage.goto("/");
      await expectSignInPage(thiefPage);

      // Renewing the copy is refused too.
      await thief.addCookies([copied]);
      await updateSession(thief, () => ({ ...agedSignIn(), ...expiredAccessToken() }));
      await thiefPage.goto("/");
      await expectSignInPage(thiefPage);
      expect(await sessionCookie(thief)).toBeUndefined();
    } finally {
      await thief.close();
    }
  });

  // Simulated: the cookie is re-sealed as if written on 1 October and opened on 1 November (31 days later). The
  // API side (refresh token past its 30 days gets invalid_grant) is proven by the API's test of the same name, and
  // the web side's unit test is in src/proxy.test.ts.
  test("Scenario: The session ends after 30 days without use", async ({ page, context }) => {
    await signInAsAdmin(page);
    const session = await readSession(context);
    // 1 November.
    // A browser drops the cookie itself after its 30-day Max-Age: no cookie at all.
    await writeSession(context, session, { sealedAgoMs: 31 * DAY_MS });
    expect(await sessionCookie(context)).toBeUndefined();
    await page.goto("/");
    await expectSignInPage(page);

    // A browser that kept it anyway: the web app rejects the expired seal and deletes the cookie.
    await signInAsAdmin(page);
    await writeSession(context, await readSession(context), {
      sealedAgoMs: 31 * DAY_MS,
      expires: Math.floor(Date.now() / 1000) + 3600,
    });
    expect(await sessionCookie(context)).toBeDefined();
    await page.goto("/");
    await expectSignInPage(page);
    expect(await sessionCookie(context)).toBeUndefined();
  });

  // Simulated: signed in on 1 October; on 25 October (24 days later) the cookie is still the one from sign-in and the
  // access token has expired, so using the app renews it for another 30 days. On 20 November (26 days after that
  // use, 50 after sign-in) the renewed cookie still works. src/proxy.test.ts and the API's test of the same name cover
  // the rest.
  test("Scenario: Using the app keeps the session going", async ({ page, context }) => {
    await signInAsAdmin(page);

    // 25 October.
    await updateSession(context, expiredAccessToken, { sealedAgoMs: 24 * DAY_MS });
    await page.goto("/");
    await expect(page.getByText(SIGNED_IN)).toBeVisible();
    // The renewal re-set the cookie for the full 30 days from now.
    const lifetime = await cookieLifetimeMs(context);
    expect(lifetime).toBeGreaterThan(SESSION_MAX_AGE_SECONDS * 1000 - 5 * 60_000);
    expect(lifetime).toBeLessThanOrEqual(SESSION_MAX_AGE_SECONDS * 1000 + 60_000);

    // 20 November: the cookie from 25 October, with its access token long expired.
    await updateSession(context, expiredAccessToken, { sealedAgoMs: 26 * DAY_MS });
    await page.goto("/");
    await expect(page).toHaveURL("/");
    await expect(page.getByText(SIGNED_IN)).toBeVisible();
  });

  test("Scenario: An already-used session renewal is refused", async ({ page, context, playwright }) => {
    await signInAsAdmin(page);
    // Before 09:20: the refresh token R0.
    const { refreshToken: r0 } = await updateSession(context, expiredAccessToken);

    // 09:20: the web app renews the session (R0 → R1).
    await page.reload();
    await expect(page.getByText(SIGNED_IN)).toBeVisible();
    expect((await readSession(context)).refreshToken).not.toBe(r0);

    // R0 presented again.
    const api = await playwright.request.newContext({ baseURL: apiURL });
    try {
      const response = await api.post("/oauth2/token", {
        headers: {
          Authorization: `Basic ${Buffer.from(`washbase-web:${LOCAL_WEB_CLIENT_SECRET}`).toString("base64")}`,
        },
        form: { grant_type: "refresh_token", refresh_token: r0 },
      });
      expect(response.status()).toBe(400);
      expect((await response.json()).error).toBe("invalid_grant");
    } finally {
      await api.dispose();
    }

    // The whole sign-in has ended: the current session (R1) can't be renewed either.
    await updateSession(context, expiredAccessToken);
    await page.reload();
    await expectSignInPage(page);
    expect(await sessionCookie(context)).toBeUndefined();
  });

  // Not a scenario: a browser can load several pages at once with the same expired cookie. They must share one
  // renewal; if each presented the same refresh token, the API's reuse detection would end the sign-in.
  test("three pages renewing the session at once all stay signed in", async ({ page, context }) => {
    await signInAsAdmin(page);
    await updateSession(context, expiredAccessToken);

    const pages = [page, await context.newPage(), await context.newPage()];
    await Promise.all(pages.map((p) => p.goto("/")));
    for (const p of pages) await expect(p.getByText(SIGNED_IN)).toBeVisible();

    // The sign-in is still alive at the API: the renewed session renews again.
    await updateSession(context, expiredAccessToken);
    await page.reload();
    await expect(page).toHaveURL("/");
    await expect(page.getByText(SIGNED_IN)).toBeVisible();
  });
});
