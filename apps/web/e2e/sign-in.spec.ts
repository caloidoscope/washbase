import { expect, test } from "@playwright/test";
import { LOCAL_PASSWORD } from "../../../scripts/local-env.mjs";
import { signIn } from "./support/sign-in";
import { isApiSignInPage, apiURL } from "./support/urls";

// CAR-17. The Admin (admin@example.com, mobile 09171234567) comes from the API's bootstrap, configured in
// scripts/local-env.mjs (`testApiEnv`). Each test starts from a fresh browser context, so none depends on another.
const ERROR_MESSAGE = "Incorrect email, mobile number or password.";

test.describe("CAR-17 The Admin signs in on the web app after the deployment is set up", () => {
  test("Scenario: A visitor who isn't signed in is sent to the sign-in page", async ({ page }) => {
    await page.goto("/");

    await expect(page).toHaveURL(isApiSignInPage);
    await expect(page.getByLabel("Email or mobile number")).toBeVisible();
    await expect(page.getByLabel("Password")).toBeVisible();
  });

  test("Scenario: The Admin signs in with their email address", async ({ page }) => {
    await signIn(page, "admin@example.com", LOCAL_PASSWORD);

    await expect(page.getByText("Signed in as Admin (Admin)")).toBeVisible();
  });

  // Row (+639171234567, 09171234567) needs the API started with a differently configured Admin: it is covered by the
  // API's tests only.
  for (const { configured, typed } of [
    { configured: "09171234567", typed: "09171234567" },
    { configured: "09171234567", typed: "+639171234567" },
  ]) {
    test(`Scenario Outline: The Admin signs in with their mobile number in either common format (${configured}, ${typed})`, async ({
      page,
    }) => {
      await signIn(page, typed, LOCAL_PASSWORD);

      await expect(page.getByText("Signed in as Admin (Admin)")).toBeVisible();
    });
  }

  test("Scenario: Signing in with a wrong password is refused", async ({ page }) => {
    await page.goto("/");
    await page.getByLabel("Email or mobile number").fill("admin@example.com");
    await page.getByLabel("Password").fill("wrong-password");
    await page.getByRole("button", { name: "Sign in" }).click();

    await expect(page).toHaveURL(isApiSignInPage);
    await expect(page.getByRole("alert")).toHaveText(ERROR_MESSAGE);

    // Not signed in: the web app still sends them to sign-in.
    await page.goto("/");
    await expect(page).toHaveURL(isApiSignInPage);
    await expect(page.getByLabel("Email or mobile number")).toBeVisible();
    await expect(page.getByText("Signed in as")).toHaveCount(0);
  });

  test("Scenario: Signing in with an email that has no account shows the same message", async ({ page }) => {
    await page.goto("/");
    await page.getByLabel("Email or mobile number").fill("nobody@example.com");
    await page.getByLabel("Password").fill(LOCAL_PASSWORD);
    await page.getByRole("button", { name: "Sign in" }).click();

    await expect(page).toHaveURL(isApiSignInPage);
    await expect(page.getByRole("alert")).toHaveText(ERROR_MESSAGE);
  });

  test("Scenario: The API refuses a request from someone who isn't signed in", async ({ playwright }) => {
    const api = await playwright.request.newContext({ baseURL: apiURL });
    try {
      const response = await api.get("/api/v1/me");
      expect(response.status()).toBe(401);
    } finally {
      await api.dispose();
    }
  });

  // Regression: the proxy's matcher once only ran on `/`, so any other page skipped the sign-in check.
  test("a visitor who isn't signed in is sent to sign-in from any page, not just the home page", async ({ page }) => {
    await page.goto("/some/page");

    await expect(page).toHaveURL(isApiSignInPage);
    await expect(page.getByLabel("Email or mobile number")).toBeVisible();
  });
});
