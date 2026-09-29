// Build and run services/api for tooling that needs a live API.
//
//   node scripts/api.mjs serve          build, then run the API in the foreground (Playwright webServer, manual use)
//   node scripts/api.mjs run -- <cmd>   build, start the API in the background, run <cmd>, stop the API
//
// `run` reuses an API that is already healthy on API_URL instead of starting a second one.
// DATABASE_URL / DATABASE_USERNAME / DATABASE_PASSWORD pass through (defaults: compose Postgres on 5433).
import { spawn, spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import path from "node:path";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const apiDir = path.join(root, "services", "api");
const apiUrl = process.env.API_URL ?? "http://localhost:8080";
const healthUrl = `${apiUrl}/actuator/health`;

const [mode, ...rest] = process.argv.slice(2);
const command = rest[0] === "--" ? rest.slice(1) : rest;

async function isHealthy() {
  try {
    const res = await fetch(healthUrl);
    return res.ok;
  } catch {
    return false;
  }
}

function build() {
  // Absolute path: cmd.exe doesn't reliably resolve bare names from the working directory.
  const mvnw = process.platform === "win32" ? `"${path.join(apiDir, "mvnw.cmd")}"` : "./mvnw";
  console.log("[api] building services/api (package -DskipTests)…");
  const result = spawnSync(mvnw, ["-q", "-B", "package", "-DskipTests"], {
    cwd: apiDir,
    stdio: "inherit",
    shell: process.platform === "win32",
  });
  if (result.status !== 0) {
    console.error("[api] build failed");
    process.exit(result.status ?? 1);
  }
}

function start(stdio) {
  // Spawn java directly (no shell) so killing the child stops the API on every OS.
  return spawn("java", ["-jar", path.join("target", "api.jar")], { cwd: apiDir, stdio });
}

async function waitUntilHealthy(child, timeoutMs = 120_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (child.exitCode !== null) throw new Error(`API exited early with code ${child.exitCode}`);
    if (await isHealthy()) return;
    await new Promise((r) => setTimeout(r, 1000));
  }
  throw new Error(`API not healthy at ${healthUrl} after ${timeoutMs / 1000}s`);
}

if (mode === "serve") {
  build();
  const child = start("inherit");
  for (const signal of ["SIGINT", "SIGTERM"]) process.on(signal, () => child.kill());
  child.on("exit", (code) => process.exit(code ?? 0));
} else if (mode === "run" && command.length > 0) {
  let child;
  if (await isHealthy()) {
    console.log(`[api] reusing the API already running at ${apiUrl} — make sure it is up to date`);
  } else {
    build();
    child = start(["ignore", "inherit", "inherit"]);
    try {
      await waitUntilHealthy(child);
    } catch (err) {
      console.error(`[api] ${err.message}`);
      child.kill();
      process.exit(1);
    }
    console.log(`[api] healthy at ${apiUrl}`);
  }
  const result = spawnSync(command[0], command.slice(1), {
    cwd: root,
    stdio: "inherit",
    shell: process.platform === "win32",
  });
  child?.kill();
  process.exit(result.status ?? 1);
} else {
  console.error("usage: node scripts/api.mjs serve | run -- <command…>");
  process.exit(2);
}
