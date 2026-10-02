# ADR-001: Authentication and authorization (OAuth 2.1 / OIDC with JWT)

- **Status:** Accepted (2026-10-02); amended 2026-10-02 by CAR-18 (see **Amendment 1**) and 2026-10-03 by CAR-20 (see **Amendment 2**)
- **Implemented by:** the account Epics (CAR-5 and its split-offs). Their first Features build this foundation.

## Context

The owner wants every API to use OAuth/JWT authorization. Constraints from `docs/products/vision.md`:

- **One laundry business per deployment.** Each new client gets its own forked deployment, so the auth setup is repeated per client and must stay cheap and self-contained.
- **Four roles:** Client, Staff, Owner, and Admin. Admin is the deployment's operator, who manages Owner accounts; Owners manage Staff and Clients but not other Owners. There is exactly **one Admin per deployment**, who can also do everything an Owner can (for support). Every role, Admin included, uses both the web app (Next.js) and the mobile app (Expo).
- **Sign-in with email or mobile number.** Clients can register themselves. Staff and Owners create Client accounts, Owners create Staff accounts, and only the Admin creates Owner accounts (there can be several Owners). Walk-in clients are records, not users, and never sign in. New email addresses and mobile numbers are confirmed with a one-time code (email or SMS).
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
- **Refresh token:** opaque, **rotated on every use**, revoked on sign-out, password change and deactivation. Lifetime 30 days (configurable, `washbase.auth.refresh-token-ttl`); each renewal issues a new one with the full lifetime, so a sign-in lasts until sign-out or 30 days without use. **Reuse detection:** presenting an already-replaced refresh token ends the whole authorization (see Amendment 1).
- **Signing keys:** supplied by environment or secret, never in the repo, and published via the JWKS endpoint so they can be rotated. The key comes from `WASHBASE_AUTH_SIGNING_KEY` (RSA private key, PKCS#8 PEM, at least 2048 bits). A key generated at start-up is allowed only when `washbase.auth.allow-generated-signing-key=true`, which is set only for local runs (the `local` profile), tests and `scripts/api.mjs`. Without it, a missing `WASHBASE_AUTH_SIGNING_KEY` stops start-up. Real deployments must set `WASHBASE_AUTH_SIGNING_KEY` and `WASHBASE_WEB_CLIENT_SECRET` (without the secret, the web app's client isn't registered and nobody can sign in on the web app).
- **Deactivation:** refresh tokens are revoked immediately, and access tokens stop working at once too (Amendment 1: an access token is accepted only while its authorization is active).

### Protecting the API
- **Default deny:** every `/api/**` endpoint requires a valid JWT. Signature, `iss`, `aud` and `exp` are all checked, and the token must be the current access token of an authorization that hasn't been revoked (Amendment 1).
- **Public endpoints are an explicit allowlist** in one place: `/actuator/health`, the OAuth/OIDC endpoints, the login, registration and password-reset pages, and `/v3/api-docs` and Swagger UI. The docs endpoints are controlled by one property, `washbase.security.public-api-docs`, which defaults to **`true`** and is set to `false` only in production configuration. Local runs, `pnpm api:client` (`scripts/api.mjs`) and the CI `contract` job fetch `/v3/api-docs` without a token and must keep working. A Feature that needs another public endpoint must say so in its Technical Notes.
- **Role checks:** a `roles` claim maps to authorities, checked with `@PreAuthorize` on the controller method. **Roles are independent, not a hierarchy:** each user has exactly one role (the token's `roles` claim is an array for standards compatibility but always holds one value; don't build multi-role handling), and `OWNER` does not imply `STAFF`. An endpoint lists every role it allows, e.g. `@PreAuthorize("hasAnyRole('STAFF','OWNER','ADMIN')")`. **Rule:** every endpoint that allows `OWNER` also lists `ADMIN` (the Admin can do everything an Owner can); Admin-only endpoints (managing Owners, the Admin action log) list only `ADMIN`. This keeps the rule visible in code and testable, so each Feature states exactly who may call it and the `403` tests cover every other role.
- **Ownership checks**, such as a Client seeing only their own orders, happen in the service layer using `sub`, never a user ID taken from the request.
- **Admin bootstrap:** on start-up, if no *active* `ADMIN` exists, one is created from `WASHBASE_ADMIN_EMAIL` (or `WASHBASE_ADMIN_MOBILE`) and `WASHBASE_ADMIN_INITIAL_PASSWORD`, supplied as deployment secrets, never in the repo. The initial password is never logged, and the first sign-in forces a password change. If no active Admin exists and the variables are missing, the app logs a warning (without secrets) and starts anyway. No Owner or Admin can be created by self-registration.
- **Single Admin:** no endpoint creates a second Admin, and the Admin can't deactivate themselves. If access is lost anyway (e.g. forgotten password and no reset channel yet), the operator deactivates the Admin in the database and restarts with the bootstrap variables set. If an inactive Admin with the same email or mobile already exists, bootstrap **reactivates that account and resets its password** to the initial one (first sign-in again forces a change) instead of creating a duplicate. **At least one active Owner must remain:** deactivating the last active Owner is refused.
- **Audit log:** every action taken by the Admin (and every change to Owner accounts) is recorded with who, what, target and when, append-only, and viewable by the Admin.
- **Passwords** are hashed with Spring Security's `DelegatingPasswordEncoder` (bcrypt or argon2), and attempts are rate-limited on the login and reset endpoints.
  - **Sign-in (`POST /login`, CAR-19):** **5 failed sign-ins** for the same identifier within a **15-minute** sliding window pause sign-in for that identifier for **15 minutes** (`washbase.auth.sign-in-limit.max-failures`, `.window`, `.pause`). The key is only the normalized identifier (lower-cased email; `09…` and `+639…` are the same mobile number), stored only as a SHA-256 hash in `sign_in_failure` / `sign_in_pause` and never logged. Unknown, inactive and existing accounts are counted and paused the same way, so a pause never reveals whether an account exists. While paused, the attempt is refused **before the password is checked**, isn't counted and doesn't extend the pause; the page says "Too many attempts. Try again in 15 minutes." The attempt that reaches the limit still gets the usual "Incorrect …" message. A successful sign-in clears the identifier's failures, and so does the start of a pause. State is in the database (survives restarts, shared by instances); time comes from an injected `Clock`.
  - Accepted risks: anyone who knows a person's email or mobile number can keep their sign-in paused (there's no limit by IP or device: out of scope for CAR-19, and limits by IP would need `server.forward-headers-strategy` behind a proxy, CAR-38); a burst of simultaneous requests can get a few more than 5 guesses in before the pause is recorded. Cleanup of expired rows: CAR-38.
- **One-time codes** (contact confirmation, password reset, first password for counter-created clients):
  - 6 digits, stored only as a hash, single use, expire after **10 minutes**.
  - At most **5 wrong attempts** per code; after that the code is void and a new one must be requested.
  - Resends are limited to 1 per minute and 5 per hour per contact.
  - Responses never reveal whether an email or number has an account.
- **Sending email and SMS:** through one internal interface (`NotificationSender`, with email and SMS implementations), each provider chosen and configured per deployment. Codes and messages are never logged. Choosing the providers is open (CAR-13). Until then, Features that send codes are blocked, and local development and tests use a fake sender that records messages.

### Contract and clients
- **OpenAPI:** declares a global `bearerAuth` (JWT) security scheme, and public endpoints opt out. The committed `packages/api-client/openapi.json` is generated with these security settings applied, so the contract shows which endpoints need a token. The required role for each endpoint is written in its `@Operation` description.
- **`@washbase/api-client`** gets an `openapi-fetch` middleware that adds `Authorization: Bearer <token>`. Each app supplies its own token source: the server-side session on web, secure storage on mobile.

### Testing
- **Every protected endpoint has tests** for: no token → `401`, wrong role → `403`, allowed role → success. Use Spring Security's `jwt()` MockMvc post-processor.
- **E2E:** a test helper creates a user through the API and signs in programmatically, so specs don't click through the login page except in the sign-in Feature's own tests.

## Amendment 1: Sign-out, renewal and revocation take effect at once (CAR-18, 2026-10-02)

Decided with CAR-18 ("People stay signed in on the web app until they sign out"). Without these, a copied session would keep working for up to 15 minutes after sign-out, and a still-open sign-in session at the authorization server would sign someone straight back in after sign-out or reuse detection.

- **Access tokens are refused immediately** after sign-out (refresh-token revocation), any other revocation, a renewal that replaced them, or refresh-token reuse detection. The resource server (`/api/**` and `/userinfo`) accepts a JWT only if, besides signature and claims, it is the **current** access token of a non-revoked authorization in `oauth2_authorization`. This is one indexed lookup per request (hash index on `access_token_value`); the answer otherwise is `401 invalid_token`. It replaces "access tokens expire within 15 minutes" wherever this ADR said so, and gives deactivation immediate lock-out once deactivation revokes the person's authorizations.
- **Refresh tokens are rotated on every use, with reuse detection.** Every replaced refresh token is remembered only as a SHA-256 hash (`oauth2_replaced_refresh_token`) until its own expiry. Presenting a replaced token again, to the token endpoint or the revocation endpoint (with the `refresh_token` hint or none), removes the whole authorization: the current refresh and access tokens stop working and the person must sign in again. A replaced token presented after its own expiry is simply refused. **Only one renewal per refresh token can win:** saving a renewal locks the authorization's row and is refused (`invalid_grant`) if the stored refresh token is no longer the one presented, so two simultaneous renewals never both succeed. Token values are never stored outside `oauth2_authorization` and never logged.
- **The authorization server's own sign-in session ends right after sign-in.** As soon as the authorization code is issued, the API invalidates its browser session. Clients keep their own session (the web app's encrypted `HttpOnly` cookie; secure storage on mobile), so every new authorization request asks for the password again. RP-initiated logout (`/connect/logout`) stays enabled for any session that is still left.
- **Sign-out** (web): the web app's server revokes the refresh token (`POST /oauth2/revoke`), clears its session, then redirects through `/connect/logout` with `id_token_hint` and the registered `post_logout_redirect_uri` (the web app's home).
- **Consequences:** every `/api` request costs one indexed database read; the cleanup of expired authorization and replaced-token rows is tracked with the production-readiness work (CAR-38). Several web app instances renewing the same refresh token concurrently would get one success and one `invalid_grant` (or trigger reuse detection if they don't overlap), signing the person out, so renewal is single-flight per process (noted for CAR-38). Sign-out while the API is unreachable clears the cookie but can't revoke the server-side authorization: the browser is signed out and shows "Can't reach Washbase right now. Try again.", but a copy of the cookie taken *before* sign-out could still be renewed until its refresh token expires (30 days without use) or is reused. This is an accepted risk (CAR-18 decision, 2026-10-02): refusing to sign out would be worse on a shared computer.

## Amendment 2: The mobile app as a public client (CAR-20, 2026-10-03)

Decided with CAR-20 ("People sign in, stay signed in and sign out on the mobile app").

- **Client `washbase-mobile`:** public (authentication method `none`, no secret), Authorization Code + PKCE, grants `authorization_code` and `refresh_token`, scope `openid`, and the same token settings as `washbase-web` (15-minute JWT access tokens; opaque refresh tokens rotated on every use, with the reuse detection and single-winner renewal of Amendment 1). Always registered: there is nothing secret to configure.
- **Refresh tokens for a public client.** Spring Authorization Server 7.1.1 issues none to public clients and can only authenticate a public client on the PKCE code exchange. Washbase adds both: a refresh-token generator that also serves public clients, and client authentication by `client_id` alone for exactly two requests, `grant_type=refresh_token` at `/oauth2/token` and `/oauth2/revoke`, only for clients registered with method `none`. OAuth 2.1 (section 4.3.1) allows this because the tokens are rotated. Spring's own checks still bind every token to its client, so one client's token can't be renewed or revoked as another client.
- **Redirect URIs** come from configuration (`WASHBASE_MOBILE_REDIRECT_URIS`, default `washbase://auth/callback`, the scheme in `app.json`) and are matched exactly: no patterns, so no open redirect. Expo Go's form (`exp://<LAN-IP>:8081/--/auth/callback`) is refused at start-up unless `washbase.auth.mobile-client.allow-expo-go-redirect-uris=true`, which only the `local` profile sets.
- **One issuer, also for a phone.** The issuer stays one configured URL, validated exactly; no per-request issuer (Spring Authorization Server can derive it from the request's `Host`, but then the resource server would have to trust several issuers chosen by a header). Production: the public `https` URL of the API. Local: `http://localhost:<port>`, and with `pnpm dev:all --lan` this PC's LAN URL (`http://<LAN-IP>:<port>`) for the API, the web app's `AUTH_ISSUER` and the phone's `EXPO_PUBLIC_AUTH_ISSUER` alike.
- **Sign-out on mobile** first clears secure storage and the in-memory access token (so an app killed mid-sign-out can never come back signed in), then revokes the refresh token it read (`/oauth2/revoke`, `token_type_hint=refresh_token`, `client_id`), which also ends its access token. No end-session request is needed: the authorization server's browser session already ended when the code was issued (Amendment 1). Sign-out works the same while the API is unreachable as on the web (local tokens cleared; the accepted risk of Amendment 1 applies).
- **Storage:** only the refresh token is persisted, in `expo-secure-store` (`AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY`, so it isn't restored to another device from a backup); the access token is kept in memory and renewed at start-up, when the app returns to the foreground and shortly before it expires, single-flight within the app. A new refresh token is written to secure storage before its access token is used.

## Consequences
- **CAR-5 comes first.** Its first Features set up the users table, both security filter chains, the login page and the token flows. Every later Feature builds on them.
- **No anonymous APIs.** Every Feature with an API must state the role(s) allowed per endpoint in its Technical Notes.
- **Forgot password and email/phone verification** need a sending provider (email and/or SMS). That decision is tracked in the forgotten-password Epic.
- **Running both servers in one app** keeps deployments simple, but means the API process holds the signing keys. If a client deployment ever needs to separate them, split it out to `services/auth`.
