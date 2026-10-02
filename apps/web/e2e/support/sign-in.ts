import { expect, type Page } from "@playwright/test";
import { LOCAL_PASSWORD } from "../../../../scripts/local-env.mjs";
import { isApiSignInPage } from "./urls";

/**
 * Signs in through the real browser: the web app sends it to the API's sign-in page, the form is submitted, and the
 * browser follows the OAuth redirects back to the web app's home page. It drives the browser on purpose: the session
 * cookie is `Secure` and `HttpOnly`, and the browser is the client that has to accept it.
 * Later Features extend this (e.g. creating users through the API first).
 */
export async function signIn(page: Page, identifier: string, password: string) {
  await page.goto("/");
  await expect(page).toHaveURL(isApiSignInPage);
  await page.getByLabel("Email or mobile number").fill(identifier);
  await page.getByLabel("Password").fill(password);
  await page.getByRole("button", { name: "Sign in" }).click();
  // The web app's home page (relative URLs resolve against the web app's baseURL).
  await page.waitForURL("/");
}

/** Signs in as the bootstrapped Admin (admin@example.com, the local/test password from scripts/local-env.mjs). */
export async function signInAsAdmin(page: Page) {
  await signIn(page, "admin@example.com", LOCAL_PASSWORD);
}
