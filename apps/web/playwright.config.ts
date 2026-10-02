import fs from "node:fs";
import path from "node:path";
import { defineConfig, devices } from "@playwright/test";
import { localWebEnv, testApiEnv } from "../../scripts/local-env.mjs";

// Ports come from this checkout's `.washbase-instance` file, so the owner's folder and the agents'
// worktree can run E2E at the same time. Keep the defaults in sync with scripts/instance.mjs.
const instanceFile = path.resolve(__dirname, "../../.washbase-instance");
const instance = fs.existsSync(instanceFile) ? JSON.parse(fs.readFileSync(instanceFile, "utf8")) : {};
const PORT = instance.webPort ?? 3000;
const baseURL = process.env.PLAYWRIGHT_BASE_URL ?? `http://localhost:${PORT}`;
const apiURL = process.env.API_BASE_URL ?? `http://localhost:${instance.apiPort ?? 8080}`;

export default defineConfig({
  testDir: "./e2e",
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  workers: process.env.CI ? 1 : undefined,
  reporter: process.env.CI ? [["github"], ["html", { open: "never" }]] : "list",
  use: {
    baseURL,
    trace: "on-first-retry",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
  // Starts the API (needs Postgres: `docker compose up -d` locally, a service container in CI)
  // and the web app. Locally, already-running servers are reused (e.g. this checkout's `pnpm dev:all`,
  // which uses the same settings from scripts/local-env.mjs).
  // CI runs the web app from a production build; locally it uses `pnpm dev`.
  // Every secret here is a publicly known local/test value (scripts/local-env.mjs): CI needs no GitHub secrets.
  webServer: process.env.PLAYWRIGHT_BASE_URL
    ? undefined
    : [
        {
          command: "node scripts/api.mjs serve",
          cwd: "../..",
          url: `${apiURL}/actuator/health`,
          // The Admin bootstrap (admin@example.com / 09171234567) and the web app's OAuth client.
          env: testApiEnv,
          reuseExistingServer: !process.env.CI,
          timeout: 180_000,
        },
        {
          command: process.env.CI ? `pnpm start --port ${PORT}` : `pnpm dev --port ${PORT}`,
          // Not `/`: it redirects to sign-in. The favicon is outside the proxy's matcher.
          url: `${baseURL}/favicon.ico`,
          env: localWebEnv({ apiUrl: apiURL, webUrl: `http://localhost:${PORT}` }),
          reuseExistingServer: !process.env.CI,
          timeout: 120_000,
        },
      ],
});
