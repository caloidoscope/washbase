// One command for local manual testing: Postgres (docker compose), the API and the web app.
// Ctrl+C stops everything. Mobile runs in its own terminal: `pnpm dev:mobile`.
//
// By default the API and web app listen on this PC only (127.0.0.1): the local test accounts
// have a publicly known password. `pnpm dev:all --lan` opens them to the local network, which
// is needed only when testing on a phone.
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import path from "node:path";
import concurrently from "concurrently";
import { localApiEnv, printAccounts } from "./local-env.mjs";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const lan = process.argv.includes("--lan");
const bindAddress = lan ? "0.0.0.0" : "127.0.0.1";

console.log("[dev:all] starting Postgres…");
const db = spawnSync("docker", ["compose", "up", "-d", "--wait", "postgres"], { cwd: root, stdio: "inherit" });
if (db.status !== 0) {
  console.error("[dev:all] Postgres didn't start. Is Docker Desktop running?");
  process.exit(db.status ?? 1);
}

printAccounts();
console.log("Web: http://localhost:3000   API: http://localhost:8080 (Swagger UI: /swagger-ui.html)");
if (lan) {
  console.log("LAN mode: the API and web app are reachable from your local network, and the test");
  console.log("accounts' password is public. Use this only on a network you trust.");
  console.log("Mobile: run `pnpm dev:mobile` in a second terminal.\n");
} else {
  console.log("Listening on this PC only. To test on a phone, restart with `pnpm dev:all --lan`.\n");
}

const { result } = concurrently(
  [
    {
      name: "api",
      command: "node scripts/api.mjs serve",
      env: { ...localApiEnv, SERVER_ADDRESS: bindAddress },
      prefixColor: "green",
    },
    {
      name: "web",
      command: `pnpm --filter @washbase/web dev --hostname ${bindAddress}`,
      env: { API_BASE_URL: "http://127.0.0.1:8080" },
      prefixColor: "blue",
    },
  ],
  { cwd: root, killOthersOn: ["failure", "success"], restartTries: 0 },
);
result.then(
  () => process.exit(0),
  () => process.exit(1),
);
