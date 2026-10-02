// `pnpm test:e2e:watch [spec-name]`: watch E2E scenarios play out in a visible, slowed-down browser,
// one at a time. Same servers and settings as playwright.config.ts.
import base from "./playwright.config";

export default {
  ...base,
  workers: 1,
  retries: 0,
  reporter: "list",
  use: {
    ...base.use,
    headless: false,
    launchOptions: { slowMo: 900 },
    viewport: { width: 1100, height: 750 },
  },
};
