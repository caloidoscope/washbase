# ADR-001: Authentication and authorization (OAuth 2.1 / OIDC with JWT)

- **Status:** Accepted (2026-10-02)
- **Implemented by:** the account Epics (CAR-5 and its split-offs). Their first Features build this foundation.

## Context

The owner wants every API to use OAuth/JWT authorization. Constraints from `docs/products/vision.md`:

- **One laundry business per deployment.** Each new client gets its own forked deployment, so the auth setup is repeated per client and must stay cheap and self-contained.
- **Four roles:** Client, Staff, Owner, and Admin. Admin is the deployment's operator, who manages Owner accounts; Owners manage Staff and Clients but not other Owners. Every shop role uses both the web app (Next.js) and the mobile app (Expo).
- **Sign-in with email or mobile number.** Clients can register themselves, Staff and Owners create accounts, and multiple Owners are allowed. Walk-in clients are records, not users, and never sign in. New email addresses and mobile numbers are confirmed with a one-time code (email or SMS).
- **The business's logo**, which the Owner uploads at runtime, must appear on every screen, including sign-in.

## Decision

### Components
| Role | What | Where |
|---|---|---|
| **Authorization server** (issues tokens, hosts the login page) | Spring Authorization Server (Spring Boot starter `oauth2-authorization-server`) | Embedded in `services/api`, package `com.washbase.api.auth`, with its own `SecurityFilterChain` |
| **Resource server** (protects `/api/**`) | Spring Security OAuth2 Resource Server (`oauth2-resource-server`), JWT validation | `services/api`, separate `SecurityFilterChain` |
| **User store** | Our own `users` table in Postgres (Flyway): email and/or mobile, password hash, role, active flag | `services/api` |

Embedded means one deployable per client deployment. The issuer URL is configuration, so the authorization server can move to its own `services/auth` later without changing the apps.

**Not chosen:**
- **Keycloak**, which would add a second server per client deployment, and branding plus phone-or-email login would fight its themes.
- **Hosted identity providers** (Auth0, Cognito, Firebase Auth), which have a per-user cost for every forked client and live outside our data model.

### Flows (OAuth 2.1)
- **Authorization Code + PKCE only.** The password and implicit grants are not used.
- **Web (Next.js):** a *confidential* client using the backend-for-frontend pattern. The Next.js server exchanges the code and keeps tokens server-side or in `HttpOnly; Secure; SameSite=Lax` cookies. Browser JavaScript never sees a token.
- **Mobile (Expo):** a *public* client with PKCE via `expo-auth-session`. Tokens are stored in `expo-secure-store`, never in AsyncStorage.
- **Login, registration, forgot-password and verification pages** are served by the authorization server and show the business's logo and name.

### Tokens
- **Access token:** a JWT signed with an asymmetric key (RS256), valid for **15 minutes**. Claims: `iss`, `sub` (user ID), `aud`, `exp`, `iat`, `roles` (`CLIENT` / `STAFF` / `OWNER` / `ADMIN`), and `scope`.
- **Refresh token:** opaque, **rotated on every use**, revoked on sign-out, password change and deactivation. Lifetime 30 days (configurable).
- **Signing keys:** supplied by environment or secret, never in the repo, and published via the JWKS endpoint so they can be rotated. Local development generates a key at startup.
- **Deactivation:** refresh tokens are revoked immediately, and access tokens expire within 15 minutes. If immediate lock-out is ever required, add a per-request "user active" check.

### Protecting the API
- **Default deny:** every `/api/**` endpoint requires a valid JWT. Signature, `iss`, `aud` and `exp` are all checked.
- **Public endpoints are an explicit allowlist** in one place: `/actuator/health`, the OAuth/OIDC endpoints, the login, registration and password-reset pages, and `/v3/api-docs` and Swagger UI. The docs endpoints are controlled by one property, `washbase.security.public-api-docs`, which defaults to **`true`** and is set to `false` only in production configuration. Local runs, `pnpm api:client` (`scripts/api.mjs`) and the CI `contract` job fetch `/v3/api-docs` without a token and must keep working. A Feature that needs another public endpoint must say so in its Technical Notes.
- **Role checks:** a `roles` claim maps to authorities, checked with `@PreAuthorize("hasRole('STAFF')")` (or similar) on the controller method.
- **Ownership checks**, such as a Client seeing only their own orders, happen in the service layer using `sub`, never a user ID taken from the request.
- **Bootstrap:** the first `ADMIN` account is created on a deployment's first start from deployment configuration (environment or secrets, never the repo), and only if no Admin exists yet. No Owner or Admin can be created by self-registration.
- **Passwords** are hashed with Spring Security's `DelegatingPasswordEncoder` (bcrypt or argon2), and attempts are rate-limited on the login and reset endpoints.

### Contract and clients
- **OpenAPI:** declares a global `bearerAuth` (JWT) security scheme, and public endpoints opt out. The committed `packages/api-client/openapi.json` is generated with these security settings applied, so the contract shows which endpoints need a token. The required role for each endpoint is written in its `@Operation` description.
- **`@washbase/api-client`** gets an `openapi-fetch` middleware that adds `Authorization: Bearer <token>`. Each app supplies its own token source: the server-side session on web, secure storage on mobile.

### Testing
- **Every protected endpoint has tests** for: no token → `401`, wrong role → `403`, allowed role → success. Use Spring Security's `jwt()` MockMvc post-processor.
- **E2E:** a test helper creates a user through the API and signs in programmatically, so specs don't click through the login page except in the sign-in Feature's own tests.

## Consequences
- **CAR-5 comes first.** Its first Features set up the users table, both security filter chains, the login page and the token flows. Every later Feature builds on them.
- **No anonymous APIs.** Every Feature with an API must state the role(s) allowed per endpoint in its Technical Notes.
- **Forgot password and email/phone verification** need a sending provider (email and/or SMS). That decision is tracked in the forgotten-password Epic.
- **Running both servers in one app** keeps deployments simple, but means the API process holds the signing keys. If a client deployment ever needs to separate them, split it out to `services/auth`.
