// Starts Expo for testing on a phone with Expo Go, pointing the app at the API on this PC.
// Run `pnpm dev:all --lan` first (in another terminal) so the API is reachable from the phone.
//
// Sign-in (CAR-20): the phone discovers the authorization server from EXPO_PUBLIC_AUTH_ISSUER, which must equal the
// API's issuer (`dev:all --lan` makes it this PC's LAN URL). Expo Go's redirect URI is
// exp://<LAN-IP>:<EXPO_GO_PORT>/--/auth/callback; `dev:all --lan` registers exactly that, so Metro is pinned to the
// same address and port here.
import { spawn } from "node:child_process";
import { EXPO_GO_PORT, lanAddress } from "./local-env.mjs";
import { instance } from "./instance.mjs";

const ip = lanAddress();
const apiUrl = `http://${ip}:${instance().apiPort}`;
console.log(`[dev:mobile] the app will call the API and sign in at ${apiUrl}`);
console.log("[dev:mobile] phone and PC must be on the same Wi-Fi. Wrong address? Rerun both commands with LAN_IP=<your Wi-Fi IP>.");

try {
  const res = await fetch(`${apiUrl}/.well-known/openid-configuration`, { signal: AbortSignal.timeout(3000) });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  const { issuer } = await res.json();
  if (issuer !== apiUrl) {
    console.log(`[dev:mobile] WARNING: the API's sign-in issuer is ${issuer}, not ${apiUrl}: sign-in on the phone`);
    console.log("[dev:mobile] will fail. Restart the API with `pnpm dev:all --lan` (same LAN_IP, if you set one).");
  }
} catch {
  console.log(`[dev:mobile] WARNING: the API isn't reachable at ${apiUrl}. Start it with \`pnpm dev:all --lan\`.`);
  if (process.platform === "win32") {
    console.log("[dev:mobile] If it is running with --lan, allow Java through Windows Firewall for private networks.");
  }
}
console.log();

const child = spawn("pnpm", ["--filter", "@washbase/mobile", "start", "--port", String(EXPO_GO_PORT)], {
  stdio: "inherit",
  shell: process.platform === "win32",
  env: {
    ...process.env,
    EXPO_PUBLIC_API_BASE_URL: apiUrl,
    EXPO_PUBLIC_AUTH_ISSUER: apiUrl,
    // Expo Go builds its redirect URI from the address Metro advertises: make it the same LAN address.
    REACT_NATIVE_PACKAGER_HOSTNAME: ip,
  },
});
child.on("exit", (code) => process.exit(code ?? 0));
