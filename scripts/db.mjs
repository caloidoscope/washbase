// Local database helpers for this checkout's instance database (see scripts/instance.mjs).
//
//   node scripts/db.mjs ensure   create the instance database if it doesn't exist (no-op for the default one)
//   node scripts/db.mjs reset    drop and recreate it: deletes ALL local data in that database
//
// Uses the docker compose Postgres. Never touches any other database.
import { spawnSync } from "node:child_process";
import { instance, root } from "./instance.mjs";

const { database } = instance();
if (!/^[a-z_][a-z0-9_]*$/.test(database)) {
  console.error(`[db] refusing unusual database name "${database}"`);
  process.exit(2);
}

function psql(sql) {
  const res = spawnSync(
    "docker",
    ["compose", "exec", "-T", "postgres", "psql", "-U", "washbase", "-d", "postgres", "-v", "ON_ERROR_STOP=1", "-Atc", sql],
    { cwd: root, encoding: "utf8" },
  );
  if (res.status !== 0) {
    console.error(`[db] ${res.stderr.trim() || "psql failed. Is Postgres running (docker compose up -d)?"}`);
    process.exit(res.status ?? 1);
  }
  return res.stdout.trim();
}

const exists = () => psql(`select 1 from pg_database where datname = '${database}'`) === "1";

const mode = process.argv[2];
if (mode === "ensure") {
  if (!exists()) {
    psql(`create database ${database} owner washbase`);
    console.log(`[db] created database ${database}`);
  }
} else if (mode === "reset") {
  console.log(`[db] resetting database "${database}": all local data in it is deleted…`);
  psql(`drop database if exists ${database} with (force)`);
  psql(`create database ${database} owner washbase`);
  console.log(`[db] database "${database}" is empty again; the API recreates its tables on next start.`);
} else {
  console.error("usage: node scripts/db.mjs ensure | reset");
  process.exit(2);
}
