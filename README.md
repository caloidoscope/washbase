# washbase

Monorepo for the Washbase API, web app, and mobile app.

| Path | Stack |
|---|---|
| `services/api` | Java 21 · Spring Boot · Postgres 17 · Flyway · Springdoc OpenAPI |
| `apps/web` | Next.js (App Router) · Tailwind · Playwright |
| `apps/mobile` | Expo · Expo Router · EAS |
| `packages/api-client` | TypeScript client generated from the API's OpenAPI spec |

## Getting started

Prerequisites: JDK 21, Node 20+, pnpm, Docker.

```bash
docker compose up -d                           # Postgres (host port 5433)
pnpm install
(cd services/api && ./mvnw spring-boot:run)    # API on :8080, docs at /swagger-ui.html
pnpm dev:web                                   # web on :3000
pnpm dev:mobile                                # Expo dev server
```

See [CLAUDE.md](CLAUDE.md) for conventions, the contract-first workflow, and the git/PR process.
