import { randomInt, randomUUID } from "node:crypto";
import { expect, test, type Page } from "@playwright/test";
import { LOCAL_PASSWORD } from "../../../scripts/local-env.mjs";
import { signInAsAdmin } from "./support/sign-in";
import { isApiSignInPage } from "./support/urls";

// CAR-19. Pauses are stored in the API's database and last 15 minutes, so these specs never use the Admin's
// identifiers (a paused Admin would break every other spec): each test makes up its own unknown identifiers, new for
// every run. Random values are generated inside the tests, not in the titles, because each Playwright worker loads
// this file separately and test titles must be the same in all of them.
// The scenarios that depend on time or need a known account other than the Admin are covered by the API's
// SignInPauseTests only: E2E can't control the API's clock, and no endpoint creates accounts yet.
const WRONG_PASSWORD_MESSAGE = "Incorrect email, mobile number or password.";
const PAUSED_MESSAGE = "Too many attempts. Try again in 15 minutes.";
const MAX_FAILURES = 5;

/** A made-up email address that no account uses, different on every call. */
function unknownEmailToken(): string {
  return randomUUID().slice(0, 8);
}

/** 9 random digits for an unknown mobile number. Starts with "00" so it can never be the Admin's (09171234567). */
function unknownMobileDigits(): string {
  return `00${String(randomInt(0, 10_000_000)).padStart(7, "0")}`;
}

/** Submits the API's sign-in page (reached through the web app) once. */
async function attemptSignIn(page: Page, identifier: string, password: string) {
  await page.goto("/");
  await expect(page).toHaveURL(isApiSignInPage);
  await page.getByLabel("Email or mobile number").fill(identifier);
  await page.getByLabel("Password").fill(password);
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).toHaveURL(isApiSignInPage);
}

/** Five wrong passwords: each one shows the usual message, including the fifth, which starts the pause. */
async function enterWrongPasswords(page: Page, identifier: string) {
  for (let attempt = 1; attempt <= MAX_FAILURES; attempt++) {
    await attemptSignIn(page, identifier, `wrong-password-${attempt}`);
    await expect(page.getByRole("alert")).toHaveText(WRONG_PASSWORD_MESSAGE);
  }
}

async function expectPausedAndNotSignedIn(page: Page) {
  await expect(page).toHaveURL(isApiSignInPage);
  await expect(page.getByRole("alert")).toHaveText(PAUSED_MESSAGE);

  // Not signed in: the web app still sends them to sign-in.
  await page.goto("/");
  await expect(page).toHaveURL(isApiSignInPage);
  await expect(page.getByText("Signed in as")).toHaveCount(0);
}

test.describe("CAR-19 Sign-in is paused after repeated wrong passwords", () => {
  test("Scenario: Unknown accounts are paused the same way", async ({ page }) => {
    // Stands in for "nobody@example.com", made unique for this run.
    const nobody = `nobody-${unknownEmailToken()}@example.com`;

    await enterWrongPasswords(page, nobody);
    await attemptSignIn(page, nobody, LOCAL_PASSWORD);

    await expectPausedAndNotSignedIn(page);
  });

  // The Feature's rows use the Admin's identifiers (09171234567 → +639171234567, Admin@Example.com →
  // admin@example.com); here each row uses the same forms of a made-up identifier. The Admin's own rows are in the
  // API's SignInPauseTests.
  const rows: { title: string; make: () => { first: string; second: string } }[] = [
    {
      title: "09<random>, +639<random>",
      make: () => {
        const digits = unknownMobileDigits();
        return { first: `09${digits}`, second: `+639${digits}` };
      },
    },
    {
      title: "Nobody-<random>@Example.com, nobody-<random>@example.com",
      make: () => {
        const token = unknownEmailToken();
        return { first: `Nobody-${token}@Example.com`, second: `nobody-${token}@example.com` };
      },
    },
  ];
  for (const { title, make } of rows) {
    test(`Scenario Outline: The same pause applies whichever form of the identifier is used (${title})`, async ({
      page,
    }) => {
      const { first, second } = make();

      await enterWrongPasswords(page, first);
      await attemptSignIn(page, second, LOCAL_PASSWORD);

      await expectPausedAndNotSignedIn(page);
    });
  }

  // Not a scenario on its own (the API's "Pausing one account doesn't affect another" covers it with a second
  // account): a pause on one identifier leaves the Admin able to sign in.
  test("the Admin still signs in while a different identifier is paused", async ({ page, browser }) => {
    const other = `nobody-${unknownEmailToken()}@example.com`;
    await enterWrongPasswords(page, other);
    await attemptSignIn(page, other, LOCAL_PASSWORD);
    await expect(page.getByRole("alert")).toHaveText(PAUSED_MESSAGE);

    const adminContext = await browser.newContext();
    try {
      const adminPage = await adminContext.newPage();
      await signInAsAdmin(adminPage);
      await expect(adminPage.getByText("Signed in as Admin (Admin)")).toBeVisible();
    } finally {
      await adminContext.close();
    }
  });
});
