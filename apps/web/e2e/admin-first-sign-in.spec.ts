import { expect, test, type Page } from "@playwright/test";
import { INITIAL_PASSWORD, createAdminWithInitialPassword, deleteAccount, submitSignIn } from "./support/first-sign-in";
import { isApiSignInPage } from "./support/urls";

// CAR-21. The page is served by the API's authorization server. Every test creates its own Admin with the initial
// password (support/first-sign-in.ts) and deletes it afterwards. Only one Admin can be active, so creating one
// deactivates the shared admin@example.com until the delete reactivates it: this spec runs serially, in its own
// Playwright project that starts after all other specs (apps/web/playwright.config.ts).
// The `/e2e` hooks exist only under the `e2e` profile that `pnpm test:e2e` starts the API with, so this spec fails
// against an API already running from `dev:all`.
// Not automated here: "The Admin chooses a new password on the mobile app" (manual, Expo Go) and "Restoring the Admin
// with the bootstrap settings..." (needs an API restart; covered by the API's tests).
const NEW_PASSWORD = "Blue-Basket-77";
const SIGNED_IN = "Signed in as Admin (Admin)";

async function expectChoosePasswordPage(page: Page) {
  await expect(page.getByRole("heading", { name: "Choose a new password" })).toBeVisible();
  await expect(page.getByLabel("New password", { exact: true })).toBeVisible();
  await expect(page.getByLabel("Confirm new password")).toBeVisible();
}

let createdEmail: string | undefined;

async function signInToChoosePassword(page: Page): Promise<string> {
  const { email } = await createAdminWithInitialPassword();
  createdEmail = email;
  await submitSignIn(page, email, INITIAL_PASSWORD);
  await expectChoosePasswordPage(page);
  return email;
}

async function choosePassword(page: Page, password: string, confirmation = password) {
  await page.getByLabel("New password", { exact: true }).fill(password);
  await page.getByLabel("Confirm new password").fill(confirmation);
  await page.getByRole("button", { name: "Save" }).click();
}

test.describe("CAR-21 The Admin replaces the initial password at first sign-in", () => {
  test.describe.configure({ mode: "serial" });

  test.afterEach(async () => {
    if (createdEmail) await deleteAccount(createdEmail);
    createdEmail = undefined;
  });

  test("Scenario: The Admin is asked to choose a new password at first sign-in", async ({ page }) => {
    await signInToChoosePassword(page);

    await expect(page.getByText(SIGNED_IN)).toHaveCount(0);
  });

  test("Scenario: The Admin chooses a new password and reaches the home page", async ({ page }) => {
    await signInToChoosePassword(page);
    await choosePassword(page, NEW_PASSWORD);

    await page.waitForURL("/");
    await expect(page.getByText(SIGNED_IN)).toBeVisible();
  });

  test("Scenario: The initial password stops working after it is replaced", async ({ page, context }) => {
    const email = await signInToChoosePassword(page);
    await choosePassword(page, NEW_PASSWORD);
    await page.waitForURL("/");
    await context.clearCookies();

    await submitSignIn(page, email, INITIAL_PASSWORD);

    await expect(page).toHaveURL(isApiSignInPage);
    await expect(page.getByRole("alert")).toHaveText("Incorrect email, mobile number or password.");
  });

  test("Scenario: The next sign-in goes straight to the home page", async ({ page }) => {
    const email = await signInToChoosePassword(page);
    await choosePassword(page, NEW_PASSWORD);
    await page.waitForURL("/");
    await page.getByRole("banner").getByRole("button", { name: "Sign out" }).click();
    await expect(page).toHaveURL(isApiSignInPage);

    await submitSignIn(page, email, NEW_PASSWORD);

    await page.waitForURL("/");
    await expect(page.getByText(SIGNED_IN)).toBeVisible();
    await expect(page.getByRole("heading", { name: "Choose a new password" })).toHaveCount(0);
  });

  for (const { newPassword, confirmation, message } of [
    { newPassword: "Short-1", confirmation: "Short-1", message: "Use at least 8 characters." },
    { newPassword: "Blue-Basket-77", confirmation: "Blue-Basket-78", message: "The passwords don't match." },
    {
      newPassword: "Start-Here-2026",
      confirmation: "Start-Here-2026",
      message: "Choose a password different from the one you were given.",
    },
  ]) {
    test(`Scenario Outline: An unacceptable new password is refused (${newPassword}, ${confirmation}, ${message})`, async ({
      page,
    }) => {
      await signInToChoosePassword(page);

      await choosePassword(page, newPassword, confirmation);

      await expect(page.getByText(message)).toBeVisible();
      await expectChoosePasswordPage(page);
      await expect(page.getByText(SIGNED_IN)).toHaveCount(0);
    });
  }

  test("Scenario: The Admin can't skip choosing a new password", async ({ page }) => {
    await signInToChoosePassword(page);

    await page.goto("/");

    await expectChoosePasswordPage(page);
    await expect(page.getByText(SIGNED_IN)).toHaveCount(0);
  });
});
