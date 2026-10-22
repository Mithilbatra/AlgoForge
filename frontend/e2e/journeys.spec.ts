import { expect, test } from "@playwright/test";
import { chooseLanguage, REFERENCE, setCode } from "./helpers";

test("practice: open a problem, run, submit, read the evaluation, run a deep evaluation", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("link", { name: "Problem Hub" }).first().click();
  await page.getByPlaceholder("Search titles…").fill("Range");
  await page.getByRole("link", { name: "Range Totals" }).click();
  await expect(page.getByRole("heading", { name: "Range Totals" })).toBeVisible();

  await chooseLanguage(page, "Python 3");
  await setCode(page, "print(0)\n");
  await page.getByRole("button", { name: /Run samples/ }).click();
  await expect(page.getByText("Sample 1")).toBeVisible({ timeout: 60_000 });
  await expect(page.getByText("Wrong answer").first()).toBeVisible();

  await page.getByRole("tab", { name: "Custom input" }).click();
  await page.getByLabel("Custom input").fill("3 1\n5 -2 4\n1 3\n");
  await setCode(page, REFERENCE["Range Totals"]);
  await page.getByRole("button", { name: "Run custom" }).click();
  await expect(page.locator("pre").filter({ hasText: /^7\s*$/ })).toBeVisible({ timeout: 60_000 });

  await page.getByRole("button", { name: /^Submit/ }).click();
  await expect(page.getByText("Accepted").first()).toBeVisible({ timeout: 120_000 });
  await expect(page.getByText(/(\d+)\/\1 tests passed/)).toBeVisible();
  await expect(page.getByText("Next: transfer test")).toBeVisible();

  await page.getByRole("link", { name: /Open evaluation report/ }).click();
  await expect(page.getByText("correctness verified")).toBeVisible();
  await expect(page.getByText("Score breakdown")).toBeVisible();
  await page.getByLabel("include AI review").uncheck();
  await page.getByRole("button", { name: "Run deep evaluation" }).click();
  await expect(page.getByText(/verified generated tests passed/)).toBeVisible({ timeout: 180_000 });
});

test("Forge Coach gives a nudge, then sees a failing submission", async ({ page }) => {
  await page.goto("/problems");
  await page.getByPlaceholder("Search titles…").fill("Stepping");
  await page.getByRole("link", { name: "Stepping Stones" }).click();
  await expect(page.getByRole("heading", { name: "Stepping Stones" })).toBeVisible();
  await page.getByRole("tab", { name: "Coach" }).click();
  await page.getByRole("button", { name: "Give me a nudge to get started" }).click();
  await expect(page.getByText(/Hint 1 of \d/)).toBeVisible({ timeout: 30_000 });

  await chooseLanguage(page, "Python 3");
  await setCode(page, "print(0)\n");
  await page.getByRole("button", { name: /^Submit/ }).click();
  await expect(page.getByText("Wrong answer").first()).toBeVisible({ timeout: 120_000 });
  await page.getByRole("tab", { name: "Coach" }).click();
  await page.getByRole("radio", { name: /Approach/ }).click();
  await page.getByLabel("Ask Forge Coach").fill("What am I missing?");
  await page.getByRole("button", { name: "Send to Forge Coach" }).click();
  await expect(page.getByText(/fails a visible sample|hidden test fails/).first()).toBeVisible({ timeout: 30_000 });
  await expect(page.getByText(/Hint 2 of \d/)).toBeVisible();
});

test("OA Arena: start a timed assessment, solve one problem, submit, read the report", async ({ page }) => {
  page.on("dialog", (d) => d.accept());
  await page.goto("/oa");
  const card = page.locator("section").filter({ hasText: "Internship coding round" });
  await card.getByRole("button", { name: "Pick problems and start" }).click();
  await expect(page).toHaveURL(/\/oa\/\d+$/);
  await expect(page.getByRole("timer")).toBeVisible();
  await expect(page.getByText("assessment: hints and topics hidden")).toBeVisible();
  await expect(page.getByRole("tab", { name: "Hints" })).toHaveCount(0);

  const title = (await page.locator("article h1").textContent())!.trim();
  await chooseLanguage(page, "Python 3");
  await setCode(page, REFERENCE[title]);
  await page.getByRole("button", { name: /^Submit$/ }).click();
  await expect(page.getByText("Accepted").first()).toBeVisible({ timeout: 120_000 });
  await expect(page.getByText(/Hidden test data and outputs are shown after the assessment ends|tests passed/).first()).toBeVisible();
  await expect(page.getByTestId("points")).toHaveText("100", { timeout: 30_000 });

  await page.getByRole("button", { name: "Submit assessment" }).click();
  await expect(page).toHaveURL(/\/oa\/\d+\/report$/, { timeout: 60_000 });
  await expect(page.getByText("100 / 300", { exact: true })).toBeVisible({ timeout: 60_000 });
  await expect(page.getByRole("heading", { name: "Timeline" })).toBeVisible();
  await expect(page.getByText("Recommended follow-up")).toBeVisible();
});

test("recommendations: accept today's first pick and complete it", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("link", { name: "Practice" }).first().click();
  await expect(page.getByRole("heading", { name: "Today's training" })).toBeVisible();
  const first = page.locator("article[data-rec]").filter({ has: page.getByRole("button", { name: "Start" }) }).first();
  const title = (await first.locator("a").first().textContent())!.trim();
  await first.getByRole("button", { name: "Start" }).click();
  await expect(page.getByRole("heading", { name: title })).toBeVisible();

  await chooseLanguage(page, "Python 3");
  await setCode(page, REFERENCE[title]);
  await page.getByRole("button", { name: /^Submit/ }).click();
  await expect(page.getByText("Accepted").first()).toBeVisible({ timeout: 120_000 });

  await page.goto("/practice");
  const done = page.locator("article[data-rec]").filter({ hasText: title }).first();
  await expect(done.getByText("completed")).toBeVisible();
  await page.goto("/learning");
  await expect(page.getByText(/Evidence|Developing|Weak|Strong/).first()).toBeVisible();
});

test.describe("signed out", () => {
  test.use({ storageState: { cookies: [], origins: [] } });

  test("sign-in is required and bad credentials are rejected", async ({ page }) => {
    await page.goto("/oa");
    await expect(page).toHaveURL(/\/login\?next=%2Foa/);
    await page.getByLabel("Email").fill("nobody@e2e.algoforge.test");
    await page.getByLabel("Password").fill("wrong-password");
    await page.getByRole("button", { name: "Sign in" }).click();
    await expect(page.getByRole("alert").filter({ hasText: "Invalid email or password" })).toBeVisible();
  });
});
