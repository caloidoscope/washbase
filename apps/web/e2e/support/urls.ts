import fs from "node:fs";
import path from "node:path";

// The API's URL for this checkout, worked out exactly as playwright.config.ts does (`.washbase-instance`, else 8080).
const instanceFile = path.resolve(__dirname, "../../../../.washbase-instance");
const instance: { apiPort?: number } = fs.existsSync(instanceFile)
  ? JSON.parse(fs.readFileSync(instanceFile, "utf8"))
  : {};

export const apiURL = process.env.API_BASE_URL ?? `http://localhost:${instance.apiPort ?? 8080}`;

/** The API's own sign-in page (the authorization server's `/login`, with or without `?error`). */
export function isApiSignInPage(url: URL): boolean {
  return url.origin === new URL(apiURL).origin && url.pathname === "/login";
}
