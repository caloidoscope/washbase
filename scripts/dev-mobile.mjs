// Starts Expo for testing on a phone with Expo Go, pointing the app at the API on this PC.
// Run `pnpm dev:all` first (in another terminal) so the API is up.
import { spawn } from "node:child_process";
import { lanAddress } from "./local-env.mjs";

const ip = lanAddress();
const apiUrl = `http://${ip}:8080`;
console.log(`[dev:mobile] the app will call the API at ${apiUrl}`);
console.log("[dev:mobile] phone and PC must be on the same Wi-Fi. If that address is wrong, rerun with LAN_IP=<your Wi-Fi IP>.");
console.log("[dev:mobile] If the app can't reach the API,");
console.log("[dev:mobile] allow Java through Windows Firewall for private networks.\n");

const child = spawn("pnpm", ["--filter", "@washbase/mobile", "start"], {
  stdio: "inherit",
  shell: process.platform === "win32",
  env: { ...process.env, EXPO_PUBLIC_API_BASE_URL: apiUrl },
});
child.on("exit", (code) => process.exit(code ?? 0));
