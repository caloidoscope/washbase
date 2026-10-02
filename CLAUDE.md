# Washbase

Monorepo: Spring Boot API, Next.js web app, Expo mobile app, and a TypeScript API client generated from the API's OpenAPI spec. Work is planned in Linear and built by a team of subagents in `.claude/agents/`, behind human approval gates: see **Workflow** at the end of this file. It is the single source of truth for the process.

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

# API
pnpm api:serve                            # build + run the API on :8080 (Swagger UI at /swagger-ui.html); blocks
(cd services/api && ./mvnw verify)        # unit + integration tests (Testcontainers needs Docker)
(cd services/api && ./mvnw spring-boot:test-run)  # run against a throwaway Testcontainers Postgres

# JS (from repo root)
pnpm dev:web | pnpm dev:mobile            # block; run in the background when automating
pnpm lint && pnpm typecheck               # run before declaring any JS task done
pnpm test:e2e                             # Playwright; starts the API and web app itself (reuses running ones)
pnpm api:client                           # build + start the API, regenerate the client, stop the API
```

## Contract-first workflow

The OpenAPI spec produced by `services/api` is the contract between backend and frontends.

1. Senior Dev defines/changes the endpoint (controller + DTOs with Springdoc annotations) first.
2. Run `pnpm api:client` (it starts and stops the API itself via `scripts/api.mjs`), and commit `packages/api-client/openapi.json` + `src/schema.d.ts` **in the same PR** as the API change. CI (`api-ci.yml` → `contract`) fails if they drift.
3. Web and mobile consume only `@washbase/api-client` (`createApiClient(baseUrl)`) — no hand-written fetch calls or duplicated DTO types.

Never edit `src/schema.d.ts` by hand.

## Security (all apps)

Authentication and authorization follow **`docs/architecture/adr-001-authentication.md`** (OAuth 2.1 / OIDC, JWT). In short:
- `services/api` embeds the authorization server (Spring Authorization Server, `com.washbase.api.auth`) and is a JWT resource server for `/api/**`.
- **Default deny:** every `/api/**` endpoint needs a valid JWT. Public endpoints come from one explicit allowlist. Roles (`CLIENT` / `STAFF` / `OWNER` / `ADMIN`; only `ADMIN` manages Owners) are checked with `@PreAuthorize`; "own data only" rules are checked in services using the token's `sub`.
- Flows: Authorization Code + PKCE only. Web = confidential client via the Next.js server (tokens never reach browser JS); mobile = public client with PKCE, tokens in `expo-secure-store`.
- Every protected endpoint has `401` (no token), `403` (wrong role) and success tests.
- Never put signing keys, client secrets or tokens in the repo or in logs.

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
- Every user-facing flow in a PBI gets a Playwright spec in `apps/web/e2e/` (written by `build-qa`). Prefer role/label locators over CSS selectors. Specs run against the real API, so seed data through the API.
- Read the API base URL from `API_BASE_URL` (default `http://localhost:8080`).
- `pnpm typecheck` runs `next typegen` first (generates route types like `LayoutProps`).

## Mobile (Expo)

- Add native deps with `npx expo install <pkg>` (SDK-compatible versions), never `pnpm add`.
- Never create/edit `ios/` or `android/` — configure via `app.json` and config plugins.
- Bundle IDs: `com.washbase.mobile`. Build profiles live in `eas.json` (`development`, `preview`, `production`).
- EAS project: `@caloidoscope-io/washbase` (owner and project ID in `app.json`).
- `eas build --local` only runs on macOS/Linux (use WSL2 on Windows); iOS builds need macOS, so use EAS cloud builds for iOS. Day-to-day testing on a phone: Expo Go.
- The API base URL comes from `EXPO_PUBLIC_API_BASE_URL`; a phone can't reach `localhost` on the dev machine, so use its LAN IP.

## Manual testing (local)

```bash
gh pr checkout <n>        # one PR, or: pnpm try:open-prs  (all open PRs combined on a throwaway local branch)
pnpm install
pnpm dev:all              # Postgres + API (:8080) + web (:3000), on this PC only; Ctrl+C stops all
pnpm dev:all --lan        # same, but reachable from your Wi-Fi: needed only for testing on a phone
pnpm dev:mobile           # second terminal: Expo for Expo Go, API pointed at this PC's LAN address
git switch main           # when done
```

- Local test accounts (password `Washbase-Local-1`): `admin@example.com`, `owner@example.com`, `staff@example.com`, `client@example.com`, defined in `scripts/local-env.mjs`. The Admin comes from the bootstrap; the others come from a **local-only seed** (Spring profile `local`, which `dev:all` activates). When a Feature introduces a role or data needed to try it by hand, it adds that to the seed. The seed must never run outside the `local` profile.
- Every Feature PR has a **"How to test this PR"** section: commands, which account to use for each scenario, and the manual checklist.
- `pnpm try:open-prs` makes `preview/all-open-prs` (main + every open PR, merged locally). Never push it (a plain `git push` fails on it). PRs that conflict are skipped and listed.
- `pnpm db:reset` empties this checkout's local database (e.g. after testing a PR whose migration later changed). The `local` profile tolerates migrations from other branches; a *changed* migration needs a reset.

## Agent workspace

The owner's folder is for the owner: they keep it on `main` and run `pnpm dev:all` there. **Delivery agents never work in it.** They work in a separate git worktree next to it, `../washbase-agents` (create with `git worktree add ../washbase-agents <branch>` if missing, then `pnpm install`), which has a git-ignored `.washbase-instance` file:

```json
{ "apiPort": 18080, "webPort": 13000, "database": "washbase_agents" }
```

`scripts/instance.mjs` reads it, so `pnpm api:client`, `pnpm test:e2e`, `pnpm dev:all` and Playwright in the worktree use ports 18080/13000 and their own database in the shared compose Postgres (created on demand). Both checkouts can run at the same time without clashing. Never stop processes on the owner's ports (8080, 3000, 8081): they belong to the owner. The read-only Postgres MCP points at the owner's `washbase` database, not `washbase_agents`.

## Git workflow

- `main` is protected by convention: all work lands through PRs.
- Branch per PBI: `feat/<Linear ID>-<short-title>` (e.g. `feat/CAR-5-order-intake`) (or `fix/…`, `chore/…`).
- Conventional Commits (`feat(api): …`, `fix(web): …`, `chore(ci): …`); scope = `api`, `web`, `mobile`, `api-client`, `ci`, `infra`.
- Before opening a PR (`gh pr create`): `./mvnw verify` (if API touched), `pnpm lint && pnpm typecheck`, `pnpm test:e2e` (if web or API touched), `docker build services/api` (if API/Dockerfile touched), regenerated api-client (if contract touched). Link the Linear issue in the PR body.
- **Verify once:** dev agents run only the tests they added or changed; senior-dev reviews the diff without re-running the full suite; `build-qa` runs the full checklist above once; CI repeats it as the final word.

## Workflow

Linear team "Carlo Licup", project "Washbase". Every Epic and PBI moves `Backlog` → `Todo` → `In Progress` → `Done`. **Only the human moves anything from `Backlog` to `Todo`, and only the human merges PRs.** Nothing runs automatically: work starts when the human asks (e.g. "check Linear").

### Agents (`.claude/agents/`)

| Agent | Does | Never |
|---|---|---|
| `product-manager` | Turns the human's idea into Epics (label `Epic`) in `Backlog`, grounded in `docs/products/vision.md` | Approves, breaks down, touches code |
| `product-owner` | Breaks an Epic in `Todo` into Features (PBIs; sub-issues, labels `Feature` + `api`/`web`/`mobile`) in `Backlog`, in the vision's Feature Format with Given/When/Then scenarios; moves the Epic to `In Progress` | Approves Features, touches code |
| `senior-dev` | Plan mode: branch, PBI → `In Progress`, API contract + regenerated client, plan comment. Review mode: reviews the diff | Pushes, opens PRs, merges |
| `backend-dev` | Implements `services/api` logic and tests | Changes the contract, commits |
| `web-dev` / `mobile-dev` | Build UI in `apps/web` / `apps/mobile` on the generated client | Edit other folders, commit |
| `build-qa` | E2E tests from acceptance criteria, all checks, commit, push, PR, watches CI | Fixes product code, merges |

Subagents can't start other subagents, so the main session (Lead Architect) runs them and passes each the Linear ID.

### Planning (two gates)

1. The human describes an idea → run `product-manager` → Epic(s) in `Backlog` with open questions.
2. **Gate 1:** the human moves an Epic to `Todo`.
3. Run `product-owner` on it → PBIs in `Backlog`, Epic → `In Progress`.
4. **Gate 2:** the human moves the PBIs they approve to `Todo`.

### Building a PBI in `Todo` (one at a time, in `blockedBy` order)

**Keep it lean** (token cost):
- **Models:** `senior-dev` runs on Opus (plans, contracts, reviews); the other agents run on Sonnet (set in each agent's frontmatter).
- **Splitting:** split a Feature into stacked PRs only when it's clearly large (more than ~1,500 changed lines) or mixes unrelated risk areas.
- **Light path:** a Feature that touches a single area with **no API contract change** skips the separate `senior-dev` plan step. The dev agent works from the Feature directly, then `senior-dev` reviews.
- **One review:** `senior-dev` reviews every PR. The CI Claude reviewer (`claude-review.yml`) runs only on security-sensitive paths: auth, security config, migrations, `docs/architecture`.
- **Handoffs:** agent reports stay under ~200 words; plan comments are concise. The main session's prompts point to the plan comment instead of restating it, and it starts a **fresh session per Feature** (state lives in Linear, this file and memory).

**Stacking:** a Feature doesn't wait for its prerequisites to be *merged*. If a `blockedBy` Feature has an open PR (not yet merged), the next Feature branches from that PR's branch and its PR targets that branch, so several Features can be in progress for the human to test together (`pnpm try:open-prs`). The human merges stacked PRs bottom-up. After a lower PR is squash-merged, `build-qa` rebases the next PR onto `main` (`git rebase --onto origin/main <old base> <branch>`, `git push --force-with-lease`, `gh pr edit <n> --base main`) and re-runs its checks.

1. `senior-dev` (plan) → 2. `backend-dev` (if `api`) → 3. `web-dev` and/or `mobile-dev` (if `web`/`mobile`; may run in parallel) → 4. `senior-dev` (review; on `CHANGES REQUESTED`, send findings to the named agent and review again) → 5. `build-qa` (on a failed check it names the agent to fix it; loop) → 6. the human tests (see Manual testing) and merges → move the PBI to `Done`, and the Epic to `Done` when all its PBIs are; restack any PR that was based on the merged one.

### "Check Linear"

1. Epics in `Todo` → run `product-owner` on each; report the drafted PBIs.
2. PBIs in `Todo` → build them as above.
3. Report what's waiting on the human: Epics/PBIs in `Backlog`, PRs awaiting merge, open questions in Linear comments.

"Feature" (the vision's term) and "PBI" mean the same thing. Formats for Epics and Features are defined in `docs/products/vision.md` section 5; every Given/When/Then scenario gets a test named after it (API and web); the mobile side of every scenario (for Features that target mobile) is checked manually in Expo Go, as unticked PR checkboxes the human ticks before merging, until a mobile E2E harness exists. Scenario Outline rows are named `Scenario Outline: <name> (<row values>)`.

Fall back to `.backlog/*.md` if Linear MCP is unavailable.
