// Starts Expo for testing on a phone with Expo Go, pointing the app at the API on this PC.
// Run `pnpm dev:all --lan` first (in another terminal) so the API is reachable from the phone.
import { spawn } from "node:child_process";
import { lanAddress } from "./local-env.mjs";
import { instance } from "./instance.mjs";

const ip = lanAddress();
const apiUrl = `http://${ip}:${instance().apiPort}`;
console.log(`[dev:mobile] the app will call the API at ${apiUrl}`);
console.log("[dev:mobile] phone and PC must be on the same Wi-Fi. Wrong address? Rerun with LAN_IP=<your Wi-Fi IP>.");

try {
  const res = await fetch(`${apiUrl}/actuator/health`, { signal: AbortSignal.timeout(3000) });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
} catch {
  console.log(`[dev:mobile] WARNING: the API isn't reachable at ${apiUrl}. Start it with \`pnpm dev:all --lan\`.`);
  if (process.platform === "win32") {
    console.log("[dev:mobile] If it is running with --lan, allow Java through Windows Firewall for private networks.");
  }
}
console.log();

const child = spawn("pnpm", ["--filter", "@washbase/mobile", "start"], {
  stdio: "inherit",
  shell: process.platform === "win32",
  env: { ...process.env, EXPO_PUBLIC_API_BASE_URL: apiUrl },
});
child.on("exit", (code) => process.exit(code ?? 0));
