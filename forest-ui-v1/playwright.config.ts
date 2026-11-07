import { defineConfig, devices } from "@playwright/test";
export default defineConfig({
  testDir: "./e2e",
  globalTeardown: "../scripts/e2e-teardown.mjs",
  timeout: 60_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  use: {
    baseURL: "http://127.0.0.1:18080",
    trace: "retain-on-failure",
    channel: process.env.PLAYWRIGHT_CHANNEL,
  },
  projects: [
    { name: "desktop", use: { ...devices["Desktop Chrome"] } },
    { name: "mobile", use: { ...devices["Pixel 7"] } },
  ],
  webServer: {
    command: "node ../scripts/e2e-server.mjs",
    url: "http://127.0.0.1:18080/api/health/ready",
    reuseExistingServer: false,
    timeout: 60_000,
    gracefulShutdown:
      process.platform === "win32"
        ? undefined
        : { signal: "SIGTERM", timeout: 10_000 },
  },
});
