import { randomUUID } from "node:crypto";
import { expect, request, type Page } from "@playwright/test";
import { isApiSignInPage, apiURL } from "./urls";

export const INITIAL_PASSWORD = "Start-Here-2026";

/**
 * Creates a brand-new Admin whose "must change password" flag is set, with the initial password "Start-Here-2026".
 * The shared bootstrapped Admin can't be used: replacing its password would break every other spec.
 *
 * Uses `POST /e2e/accounts` (201, 409 on a duplicate), which exists only under the `e2e` profile that `pnpm test:e2e`
 * starts the API with. Creating an Admin deactivates admin@example.com until `deleteAccount` removes it again.
 */
export async function createAdminWithInitialPassword(): Promise<{ email: string }> {
  const email = `admin-${randomUUID()}@example.com`;
  const api = await request.newContext({ baseURL: apiURL });
  try {
    const response = await api.post("/e2e/accounts", {
      data: { email, password: INITIAL_PASSWORD, name: "Admin", role: "ADMIN", mustChangePassword: true },
    });
    expect(response.status(), "the e2e account hook must create the Admin").toBe(201);
  } finally {
    await api.dispose();
  }
  return { email };
}

/** Removes the account and reactivates admin@example.com (only one Admin can be active at a time). */
export async function deleteAccount(email: string) {
  const api = await request.newContext({ baseURL: apiURL });
  try {
    const response = await api.delete("/e2e/accounts", { params: { email } });
    expect(response.status(), "the e2e account hook must delete the Admin").toBeLessThan(300);
  } finally {
    await api.dispose();
  }
}

/** Signs in on the web app's sign-in page (no wait for the home page: the next page depends on the account). */
export async function submitSignIn(page: Page, identifier: string, password: string) {
  await page.goto("/");
  await expect(page).toHaveURL(isApiSignInPage);
  await page.getByLabel("Email or mobile number").fill(identifier);
  await page.getByLabel("Password").fill(password);
  await page.getByRole("button", { name: "Sign in" }).click();
}
