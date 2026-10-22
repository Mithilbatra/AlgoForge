import { test as setup } from "@playwright/test";
import { register } from "./helpers";

export const AUTH_FILE = "e2e/.auth/user.json";

// One account per run, created through the real registration form, shared by the journeys.
setup("register a user", async ({ page }) => {
  await register(page, "Journey");
  await page.context().storageState({ path: AUTH_FILE });
});
