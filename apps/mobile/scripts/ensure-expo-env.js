// expo-env.d.ts is generated (and git-ignored) by `expo start`. Create it when missing so
// `tsc` works in CI and on fresh checkouts without starting Expo first.
const fs = require("node:fs");
const path = require("node:path");

const file = path.join(__dirname, "..", "expo-env.d.ts");
if (!fs.existsSync(file)) {
  fs.writeFileSync(file, '/// <reference types="expo/types" />\n');
}
