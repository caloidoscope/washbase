# washbase

Laundry shop operations for one business per deployment: clients book drop-off, pickup or delivery and track their laundry; staff take in orders, move them through processing and record payments; owners manage accounts, pricing and branding. Product vision: [docs/products/vision.md](docs/products/vision.md).

| Path | Stack |
|---|---|
| `services/api` | Java 21 · Spring Boot · Postgres 17 · Flyway · Springdoc OpenAPI |
| `apps/web` | Next.js (App Router) · Tailwind · Playwright |
| `apps/mobile` | Expo · Expo Router · EAS |
| `packages/api-client` | TypeScript client generated from the API's OpenAPI spec |

## Getting started

Prerequisites: JDK 21, Node 22+, pnpm, Docker Desktop (running).

```bash
pnpm install
pnpm dev:all            # Postgres + API (http://localhost:8080) + web (http://localhost:3000)
```

`pnpm dev:all` starts everything in one terminal and **Ctrl+C** stops it all. By default it listens on this PC only. API docs: http://localhost:8080/swagger-ui.html.

### On a phone (Expo Go)

```bash
pnpm dev:all --lan      # terminal 1: also reachable from your Wi-Fi
pnpm dev:mobile         # terminal 2: scan the QR code with Expo Go
```

The phone and PC must be on the same Wi-Fi. If the app can't reach the API, rerun `pnpm dev:mobile` with `LAN_IP=<your PC's Wi-Fi address>`; on Windows, also allow Java through the firewall for private networks. Use `--lan` only on a network you trust (see test accounts below).

## Testing changes before they're merged

Every Feature arrives as a pull request with a **"How to test this PR"** section (steps, which account to use, a checklist to tick).

**One pull request:**

```bash
gh pr checkout <number>
pnpm install
pnpm dev:all
git switch main         # when done
```

**All open pull requests at once:**

```bash
pnpm try:open-prs       # local branch preview/all-open-prs = main + every open PR combined
pnpm install
pnpm dev:all
git switch main         # when done
```

`try:open-prs` rebuilds the combined branch fresh on every run and never pushes it. It skips (and lists) PRs that conflict with the others and PRs from forks, which you'd test on their own with `gh pr checkout` after reviewing their code.

### Local test accounts

Local only, password `Washbase-Local-1`:

| Role | Sign in with |
|---|---|
| Admin | `admin@example.com` |
| Owner | `owner@example.com` |
| Staff | `staff@example.com` |
| Client | `client@example.com` |

Each account exists once the Feature that adds it is built (the Admin arrives with the sign-in foundation, CAR-17). The password is public, which is why `dev:all` listens on this PC only unless you pass `--lan`.

### After testing PRs that change the database

Switching between PRs can leave tables from one PR's migrations in your local database. That's tolerated, but if a PR *changed* a migration you already ran, the API refuses to start; run `pnpm db:reset` (deletes all local data in your local database) and start again.

## Other commands

| Command | What it does |
|---|---|
| `pnpm lint` / `pnpm typecheck` | Lint and typecheck web, mobile and the API client |
| `pnpm test:e2e` | Playwright end-to-end tests (starts the API and web app itself) |
| `pnpm api:client` | Regenerate `packages/api-client` from the API's OpenAPI spec |
| `pnpm api:serve` | Run only the API |
| `pnpm db:reset` | Empty your local database (local test data only) |
| `(cd services/api && ./mvnw verify)` | API unit and integration tests (needs Docker) |

## Deploying

A real deployment (anything not started with `pnpm dev:all`) must set two secrets, never committed to the repo: `WASHBASE_AUTH_SIGNING_KEY` (an RSA private key, e.g. from `openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048`; without it the API refuses to start) and `WASHBASE_WEB_CLIENT_SECRET` (without it nobody can sign in on the web app). The web app needs the same client secret as `AUTH_CLIENT_SECRET`, plus `SESSION_SECRET` (at least 32 random characters, e.g. `openssl rand -base64 32`; it encrypts the session cookie), `AUTH_ISSUER` (the API's public URL, equal to its `WASHBASE_AUTH_ISSUER`), `AUTH_REDIRECT_URI` (the web app's public URL + `/auth/callback`, equal to the API's `WASHBASE_WEB_REDIRECT_URI`) and `API_BASE_URL`. Details: [ADR-001](docs/architecture/adr-001-authentication.md).

## How work happens

The AI agents work in their own copy of the repo (`../washbase-agents`, a git worktree) with their own ports and database, so your folder stays on `main` and your `pnpm dev:all` never clashes with them.

Ideas become Epics and Features in Linear, which the owner approves (`Backlog` → `Todo`) before AI agents build them as pull requests. The owner tests and merges each one. Details: [CLAUDE.md](CLAUDE.md) (workflow, conventions, git/PR process), [`.claude/agents/`](.claude/agents/) (the agent roles), and [docs/architecture/](docs/architecture/) (design decisions).
