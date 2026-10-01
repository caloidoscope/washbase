// Settings shared by the local manual-testing scripts. Local only: never used in CI or production.
import os from "node:os";

/** Accounts for manual testing. The Admin comes from the bootstrap (ADR-001); the others are
 *  created by the local-only seed once the Features that introduce those roles exist. */
export const LOCAL_ACCOUNTS = [
  { role: "Admin", email: "admin@example.com" },
  { role: "Owner", email: "owner@example.com" },
  { role: "Staff", email: "staff@example.com" },
  { role: "Client", email: "client@example.com" },
];
export const LOCAL_PASSWORD = "Washbase-Local-1";

/** Environment for the API when run for local manual testing. */
export const localApiEnv = {
  SPRING_PROFILES_ACTIVE: "local",
  WASHBASE_ADMIN_EMAIL: "admin@example.com",
  WASHBASE_ADMIN_INITIAL_PASSWORD: LOCAL_PASSWORD,
};

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
  for (const a of LOCAL_ACCOUNTS) console.log(`  ${a.role.padEnd(7)} ${a.email.padEnd(20)} ${LOCAL_PASSWORD}`);
  console.log("  (Owner/Staff/Client exist once the Features that add those roles are built.)\n");
}
