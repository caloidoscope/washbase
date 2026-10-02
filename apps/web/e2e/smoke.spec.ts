import { expect, test } from "@playwright/test";
import { signInAsAdmin } from "./support/sign-in";

test("home page renders", async ({ page }) => {
  await signInAsAdmin(page);

  await expect(page).toHaveTitle("Washbase");
});
