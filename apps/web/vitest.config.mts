import { fileURLToPath } from "node:url";
import { defineConfig } from "vitest/config";

const src = fileURLToPath(new URL("./src/", import.meta.url));

// Unit tests for server-side code (route handlers, auth helpers). Playwright owns e2e/; Next.js never imports
// *.test.ts or src/test/, so neither ends up in the app build.
export default defineConfig({
  resolve: {
    alias: [
      // tsconfig.json's "@/*" path.
      { find: /^@\//, replacement: src },
      // The real package throws outside a React Server Components build.
      { find: /^server-only$/, replacement: fileURLToPath(new URL("./src/test/server-only.ts", import.meta.url)) },
    ],
  },
  test: {
    environment: "node",
    include: ["src/**/*.test.ts"],
    restoreMocks: true,
    unstubEnvs: true,
    unstubGlobals: true,
  },
});
