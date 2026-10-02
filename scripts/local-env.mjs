// Settings shared by the local manual-testing scripts and E2E (Playwright, also in CI). Never used in production:
// every value here is publicly known.
import os from "node:os";

/** Accounts for manual testing. The Admin comes from the bootstrap (ADR-001); the others are
 *  created by the local-only seed once the Features that introduce those roles exist. */
export const LOCAL_ACCOUNTS = [
  { role: "Admin", email: "admin@example.com", mobile: "09171234567" },
  { role: "Owner", email: "newowner@example.com", note: "must choose a new password at first sign-in (CAR-21)" },
  { role: "Owner", email: "owner@example.com" },
  { role: "Staff", email: "staff@example.com" },
  { role: "Client", email: "client@example.com" },
];
export const LOCAL_PASSWORD = "Washbase-Local-1";
/** Secret of the `washbase-web` OAuth client for local runs and tests. Publicly known, like LOCAL_PASSWORD:
 *  never use it in a real deployment. */
export const LOCAL_WEB_CLIENT_SECRET = "washbase-web-local-secret";
/** Key that encrypts the web app's session cookies for local runs and tests (at least 32 characters).
 *  Publicly known: never use it in a real deployment. */
export const LOCAL_SESSION_SECRET = "washbase-local-session-secret-not-for-production";

/** API environment shared by local runs and E2E (Playwright, locally and in CI): the Admin bootstrap and the
 *  web app's OAuth client. Profile `e2e` (not `local`), so the local-only seed doesn't run in E2E;
 *  localApiEnv replaces it with `local`, so the unauthenticated /e2e hooks never run in manual testing. */
export const testApiEnv = {
  // Profile e2e: POST /e2e/accounts for the specs, and the bootstrapped Admin needs no password change (CAR-21).
  SPRING_PROFILES_ACTIVE: "e2e",
  WASHBASE_ADMIN_EMAIL: "admin@example.com",
  WASHBASE_ADMIN_MOBILE: "09171234567",
  WASHBASE_ADMIN_INITIAL_PASSWORD: LOCAL_PASSWORD,
  WASHBASE_WEB_CLIENT_SECRET: LOCAL_WEB_CLIENT_SECRET,
};

/** Environment for the API when run for local manual testing. */
export const localApiEnv = {
  ...testApiEnv,
  SPRING_PROFILES_ACTIVE: "local",
};

/** Environment for the web app (the `washbase-web` OAuth client, ADR-001), for local runs and E2E.
 *  `apiUrl` must be the URL scripts/api.mjs gives the API as its issuer (`WASHBASE_AUTH_ISSUER`), and `webUrl`
 *  the web app's own URL, whose `/auth/callback` scripts/api.mjs registers as the redirect URI. */
export function localWebEnv({ apiUrl, webUrl }) {
  return {
    API_BASE_URL: apiUrl,
    AUTH_ISSUER: apiUrl,
    AUTH_CLIENT_ID: "washbase-web",
    AUTH_CLIENT_SECRET: LOCAL_WEB_CLIENT_SECRET,
    AUTH_REDIRECT_URI: `${webUrl}/auth/callback`,
    SESSION_SECRET: LOCAL_SESSION_SECRET,
    // How long a web session lasts without use (CAR-18). Same as the API's default refresh-token lifetime (P30D).
    SESSION_MAX_AGE_DAYS: "30",
  };
}

/** Port of Expo's dev server (Metro) for `pnpm dev:mobile`. Pinned, because Expo Go's sign-in redirect URI contains
 *  it and the API only accepts registered redirect URIs exactly (CAR-20). */
export const EXPO_GO_PORT = 8081;
/** The mobile app's redirect path (`app.json` scheme `washbase` → `washbase://auth/callback`). */
export const MOBILE_REDIRECT_PATH = "auth/callback";

/** Settings for testing on a phone (`pnpm dev:all --lan` + `pnpm dev:mobile`, CAR-20). Tokens carry the issuer and
 *  the phone discovers the authorization server from it, so in LAN mode the issuer is this PC's LAN URL, for the
 *  API, the web app and the phone alike (one issuer, validated exactly as before). */
export function lanAuthEnv({ ip, apiPort }) {
  const issuer = `http://${ip}:${apiPort}`;
  return {
    issuer,
    /** Redirect URIs of `washbase-mobile`: the app's own scheme, plus Expo Go's form for this PC. The API accepts
     *  the exp:// one only in the `local` profile (washbase.auth.mobile-client.allow-expo-go-redirect-uris). */
    mobileRedirectUris: [
      `washbase://${MOBILE_REDIRECT_PATH}`,
      `exp://${ip}:${EXPO_GO_PORT}/--/${MOBILE_REDIRECT_PATH}`,
    ],
  };
}

/** This PC's address on the local network, so a phone running Expo Go can reach the API.
 *  Set LAN_IP to override. 192.168.x and 10.x are preferred over 172.16–31.x, which is also
 *  used by Docker, WSL and Hyper-V virtual adapters. */
export function lanAddress() {
  if (process.env.LAN_IP) return process.env.LAN_IP;
  const addresses = Object.values(os.networkInterfaces())
    .flat()
    .filter((i) => i && i.family === "IPv4" && !i.internal)
    .map((i) => i.address);
  const ranked = [/^192\.168\./, /^10\./, /^172\.(1[6-9]|2\d|3[01])\./];
  for (const pattern of ranked) {
    const match = addresses.find((a) => pattern.test(a));
    if (match) return match;
  }
  return addresses[0] ?? "localhost";
}

export function printAccounts() {
  console.log("\nTest accounts (local only):");
  for (const a of LOCAL_ACCOUNTS) {
    const signIn = a.mobile ? `${a.email} or ${a.mobile}` : a.email;
    const note = a.note ? `  (${a.note})` : "";
    console.log(`  ${a.role.padEnd(7)} ${signIn.padEnd(36)} ${LOCAL_PASSWORD}${note}`);
  }
  console.log("  (Each account exists once the Feature that adds it is built: the Admin with the sign-in");
  console.log("  foundation, CAR-17. The Admin may be asked to change the password at first sign-in.)\n");
}
