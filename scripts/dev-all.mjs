// One command for local manual testing: Postgres (docker compose), the API and the web app.
// Ctrl+C stops everything. Mobile runs in its own terminal: `pnpm dev:mobile`.
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import path from "node:path";
import concurrently from "concurrently";
import { localApiEnv, printAccounts } from "./local-env.mjs";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

console.log("[dev:all] starting Postgres…");
const db = spawnSync("docker", ["compose", "up", "-d", "--wait", "postgres"], { cwd: root, stdio: "inherit" });
if (db.status !== 0) {
  console.error("[dev:all] Postgres didn't start. Is Docker Desktop running?");
  process.exit(db.status ?? 1);
}

printAccounts();
console.log("Web: http://localhost:3000   API: http://localhost:8080 (Swagger UI: /swagger-ui.html)");
console.log("Mobile: run `pnpm dev:mobile` in a second terminal.\n");

const { result } = concurrently(
  [
    { name: "api", command: "node scripts/api.mjs serve", env: localApiEnv, prefixColor: "green" },
    {
      name: "web",
      command: "pnpm --filter @washbase/web dev",
      env: { API_BASE_URL: "http://localhost:8080" },
      prefixColor: "blue",
    },
  ],
  { cwd: root, killOthersOn: ["failure", "success"], restartTries: 0 },
);
result.then(
  () => process.exit(0),
  () => process.exit(1),
);
