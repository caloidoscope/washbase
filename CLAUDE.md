# Washbase

Monorepo: Spring Boot API, Next.js web app, Expo mobile app, and a TypeScript API client generated from the API's OpenAPI spec. The multi-agent workflow (roles, gates, phases) is defined in `.claude/commands/init.md` — follow its gates.

## Layout

| Path | What | Stack |
|---|---|---|
| `services/api` | Backend | Java 21, Spring Boot (latest GA), Maven wrapper, Springdoc, JPA + Flyway, Postgres 17 |
| `apps/web` | Web frontend | Next.js (App Router, `src/`), Tailwind v4, Playwright |
| `apps/mobile` | Mobile app | Expo + Expo Router (`src/app/`), EAS |
| `packages/api-client` | Typed client | `openapi-typescript` + `openapi-fetch` |
| `docker-compose.yml` | Local infra | Postgres on **host port 5433**, Postgres MCP (SSE, read-only) on 8000 |

`apps/web` and `apps/mobile` have their own `CLAUDE.md`/`AGENTS.md`. Their frameworks change fast — read the versioned docs they point to before writing code, not memory.

## Commands

```bash
docker compose up -d                      # Postgres + Postgres MCP
pnpm install                              # all JS workspaces (pnpm only — never npm/yarn)

# API (from services/api)
./mvnw spring-boot:run                    # http://localhost:8080, Swagger UI at /swagger-ui.html
./mvnw verify                             # unit + integration tests (Testcontainers needs Docker)
./mvnw spring-boot:test-run               # run against a throwaway Testcontainers Postgres

# JS (from repo root)
pnpm dev:web | pnpm dev:mobile
pnpm lint && pnpm typecheck               # run before declaring any JS task done
pnpm test:e2e                             # Playwright (starts `next dev` if not running)
pnpm api:client                           # fetch spec from running API + regenerate client
```

## Contract-first workflow

The OpenAPI spec produced by `services/api` is the contract between backend and frontends.

1. Senior Dev defines/changes the endpoint (controller + DTOs with Springdoc annotations) first.
2. Start the API, run `pnpm api:client`, and commit `packages/api-client/openapi.json` + `src/schema.d.ts` **in the same PR** as the API change. CI (`api-ci.yml` → `contract`) fails if they drift.
3. Web and mobile consume only `@washbase/api-client` (`createApiClient(baseUrl)`) — no hand-written fetch calls or duplicated DTO types.

Never edit `src/schema.d.ts` by hand.

## Backend (Java 21 / Spring Boot)

- Package by feature under `com.washbase.api.<feature>` (controller, service, repository, DTOs together); cross-cutting config in `com.washbase.api.config`.
- Use Java records for DTOs; never expose JPA entities from controllers. Validate input with Jakarta Validation (`@Valid`, constraints on records).
- Constructor injection only (no field `@Autowired` in main code). Keep classes package-private unless used across packages.
- Schema changes go in Flyway migrations `src/main/resources/db/migration/V<n>__<description>.sql` — never edit an applied migration. `ddl-auto=validate` catches entity/schema mismatch.
- Config via env vars with local defaults in `application.properties` (`DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`). No secrets in the repo.
- Tests: JUnit 5 + Mockito for unit tests (services, mappers); `@SpringBootTest` / `@WebMvcTest` / `@DataJpaTest` with `TestcontainersConfiguration` for integration. Postgres in tests is `postgres:17` to match compose.
- The Postgres MCP server is **read-only** (restricted mode): use it to inspect schema and `EXPLAIN` queries, not to apply changes. Migrations are applied by Flyway on app start.

## Web (Next.js)

- Server Components by default; add `"use client"` only for interactivity. Fetch data server-side where possible via `@washbase/api-client`.
- Tailwind v4 utility classes (CSS-first config in `globals.css`); no CSS-in-JS.
- Every user-facing flow in a PBI gets a Playwright spec in `apps/web/e2e/`. Prefer role/label locators over CSS selectors.
- `pnpm typecheck` runs `next typegen` first (generates route types like `LayoutProps`).

## Mobile (Expo)

- Add native deps with `npx expo install <pkg>` (SDK-compatible versions), never `pnpm add`.
- Never create/edit `ios/` or `android/` — configure via `app.json` and config plugins.
- Bundle IDs: `com.washbase.mobile`. Build profiles live in `eas.json` (`development`, `preview`, `production`).
- `eas build --local` only runs on macOS/Linux (use WSL2 on Windows); iOS builds need macOS. It also requires `eas login` and `eas init` (project ID in `app.json`).

## Git workflow

- `main` is protected by convention: all work lands through PRs.
- Branch per PBI: `feat/PBI-<id>-<short-title>` (or `fix/…`, `chore/…`).
- Conventional Commits (`feat(api): …`, `fix(web): …`, `chore(ci): …`); scope = `api`, `web`, `mobile`, `api-client`, `ci`, `infra`.
- Before opening a PR (`gh pr create`): `./mvnw verify` (if API touched), `pnpm lint && pnpm typecheck`, `pnpm test:e2e` (if web touched), `docker build services/api` (if API/Dockerfile touched), regenerated api-client (if contract touched). Link the Linear issue in the PR body.

## Backlog

PBIs live in Linear (team "Carlo Licup") and move `DRAFT` → `APPROVED` only by the human. Development starts only on `APPROVED` PBIs. Fall back to `.backlog/*.md` if Linear MCP is unavailable.
