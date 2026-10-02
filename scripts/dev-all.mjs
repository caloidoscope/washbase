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
import { lanAddress, lanAuthEnv, localApiEnv, localWebEnv, printAccounts } from "./local-env.mjs";
import { instance } from "./instance.mjs";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const lan = process.argv.includes("--lan");
const bindAddress = lan ? "0.0.0.0" : "127.0.0.1";
const inst = instance();
// LAN mode (testing on a phone, CAR-20): the issuer is this PC's LAN URL for the API, the web app and the phone, and
// the mobile client also accepts Expo Go's redirect URI for this PC. Otherwise everything stays on localhost.
const lanAuth = lan ? lanAuthEnv({ ip: lanAddress(), apiPort: inst.apiPort }) : undefined;
const apiAuthEnv = lanAuth
  ? { WASHBASE_AUTH_ISSUER: lanAuth.issuer, WASHBASE_MOBILE_REDIRECT_URIS: lanAuth.mobileRedirectUris.join(",") }
  : {};
const webAuthEnv = lanAuth ? { AUTH_ISSUER: lanAuth.issuer } : {};

console.log("[dev:all] starting Postgres…");
const db = spawnSync("docker", ["compose", "up", "-d", "--wait", "postgres"], { cwd: root, stdio: "inherit" });
if (db.status !== 0) {
  console.error("[dev:all] Postgres didn't start. Is Docker Desktop running?");
  process.exit(db.status ?? 1);
}

printAccounts();
console.log(`Web: ${inst.webUrl}   API: ${inst.apiUrl} (Swagger UI: /swagger-ui.html)`);
if (lan) {
  console.log("LAN mode: the API and web app are reachable from your local network, and the test");
  console.log("accounts' password is public. Use this only on a network you trust.");
  console.log(`Sign-in issuer: ${lanAuth.issuer} (the web app on this PC signs in through it too).`);
  console.log("Mobile: run `pnpm dev:mobile` in a second terminal.\n");
} else {
  console.log("Listening on this PC only. To test on a phone, restart with `pnpm dev:all --lan`.\n");
}

const { result } = concurrently(
  [
    {
      name: "api",
      command: "node scripts/api.mjs serve",
      env: { ...localApiEnv, ...apiAuthEnv, SERVER_ADDRESS: bindAddress },
      prefixColor: "green",
    },
    {
      name: "web",
      command: `pnpm --filter @washbase/web dev --hostname ${bindAddress} --port ${inst.webPort}`,
      // AUTH_ISSUER must equal the API's issuer (localhost, or the LAN URL with --lan). Server-side API calls go to
      // 127.0.0.1 either way: tokens are checked against the configured issuer, not the request's host.
      env: { ...localWebEnv(inst), ...webAuthEnv, API_BASE_URL: `http://127.0.0.1:${inst.apiPort}` },
      prefixColor: "blue",
    },
  ],
  { cwd: root, killOthersOn: ["failure", "success"], restartTries: 0 },
);
result.then(
  () => process.exit(0),
  () => process.exit(1),
);
