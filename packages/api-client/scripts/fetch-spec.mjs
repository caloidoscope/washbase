// Pulls the OpenAPI spec from a running Spring Boot API into openapi.json.
// openapi.json is committed so `pnpm generate` and CI work without a running API.
import { writeFile } from "node:fs/promises";

const url = process.env.API_DOCS_URL ?? "http://localhost:8080/v3/api-docs";

const res = await fetch(url).catch((err) => {
  console.error(`Could not reach ${url} — is services/api running? (${err.cause?.code ?? err.message})`);
  process.exit(1);
});
if (!res.ok) {
  console.error(`GET ${url} -> ${res.status} ${res.statusText}`);
  process.exit(1);
}

const spec = await res.json();
await writeFile(new URL("../openapi.json", import.meta.url), JSON.stringify(spec, null, 2) + "\n");
console.log(`Wrote openapi.json from ${url}`);
