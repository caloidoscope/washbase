# Washbase mobile app

Expo + Expo Router (`src/app/`). Routes live in `src/app/`; everything else (components, `src/lib/auth/`) lives outside it.

## Run it on a phone (Expo Go)

From the repo root, in two terminals (see the root README, "On a phone"):

```bash
pnpm dev:all --lan   # Postgres + API reachable from your Wi-Fi, with sign-in issued at http://<LAN-IP>:8080
pnpm dev:mobile      # Expo on port 8081, with EXPO_PUBLIC_API_BASE_URL and EXPO_PUBLIC_AUTH_ISSUER set to the LAN URL
```

Scan the QR code with Expo Go. Sign in with `admin@example.com` (or `09171234567`) and `Washbase-Local-1`.

## Configuration

| Variable | Default | What |
|---|---|---|
| `EXPO_PUBLIC_API_BASE_URL` | `http://localhost:8080` | The API. A phone can't reach `localhost` on your PC: use its LAN IP (`pnpm dev:mobile` does). |
| `EXPO_PUBLIC_AUTH_ISSUER` | the API base URL | The authorization server's issuer. It must equal the API's issuer exactly. |

## Sign-in (ADR-001 Amendment 2)

- Authorization Code + PKCE with the public client `washbase-mobile` (`expo-auth-session`), in the in-app browser.
- Redirect URI: `exp://<LAN-IP>:8081/--/auth/callback` in Expo Go, `washbase://auth/callback` in development and
  production builds. The API accepts only registered redirect URIs.
- Only the refresh token is stored, in `expo-secure-store` (`washbase.refreshToken`, this device only). The access
  token stays in memory. Nothing goes to AsyncStorage.
- The session is renewed at start-up, when the app returns to the foreground, and when the access token is used within
  60 seconds of its expiry. A refused renewal (e.g. after 30 days without use) shows the welcome screen. A network
  error keeps the session and shows "Can't reach Washbase right now. Try again."
- Sign-out clears secure storage, then revokes the refresh token.

## Checks

```bash
pnpm --filter @washbase/mobile lint
pnpm --filter @washbase/mobile typecheck
pnpm --filter @washbase/mobile test     # Jest (jest-expo); the root `pnpm test` runs it too
npx expo-doctor                         # in apps/mobile
```

Add dependencies with `npx expo install <pkg>` (never `pnpm add`), and never edit `ios/` or `android/`.
