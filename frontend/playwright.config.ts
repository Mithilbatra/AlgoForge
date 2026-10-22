import { defineConfig, devices } from "@playwright/test";

// End-to-end journeys against the real stack: Postgres, the API (:8080) and the worker with sandbox images must be
// running (see README). The Next.js dev server is started here unless one is already listening on :3000.
export default defineConfig({
  testDir: "./e2e",
  timeout: 240_000,
  expect: { timeout: 15_000 },
  workers: 1,
  reporter: [["list"]],
  use: { baseURL: process.env.E2E_BASE_URL ?? "http://localhost:3000", trace: "retain-on-failure", ...devices["Desktop Chrome"], viewport: { width: 1400, height: 900 } },
  webServer: { command: "npm run dev", url: "http://localhost:3000/login", reuseExistingServer: true, timeout: 120_000 },
  projects: [
    { name: "setup", testMatch: /global\.setup\.ts/ },
    { name: "journeys", dependencies: ["setup"], use: { storageState: "e2e/.auth/user.json" } },
  ],
});
