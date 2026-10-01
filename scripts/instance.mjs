// Per-checkout ports and database, so two copies of the repo (e.g. the owner's folder and the
// agents' git worktree) can run their API, web app and tests at the same time without clashing.
//
// A checkout uses the defaults unless it has a `.washbase-instance` file (git-ignored) at its root:
//   { "apiPort": 18080, "webPort": 13000, "database": "washbase_agents" }
// Postgres itself is shared (docker compose, host port 5433); each instance uses its own database.
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

export const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

const DEFAULTS = { apiPort: 8080, webPort: 3000, database: "washbase" };

export function instance() {
  const file = path.join(root, ".washbase-instance");
  const custom = fs.existsSync(file) ? JSON.parse(fs.readFileSync(file, "utf8")) : {};
  const settings = { ...DEFAULTS, ...custom };
  return {
    ...settings,
    apiUrl: `http://localhost:${settings.apiPort}`,
    webUrl: `http://localhost:${settings.webPort}`,
    databaseUrl: `jdbc:postgresql://localhost:5433/${settings.database}`,
  };
}
