import { expect, type Page } from "@playwright/test";
import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";

const SEED = join(__dirname, "../../backend/src/main/resources/seed/problems");

/** title -> trusted Python reference solution, used as a known-correct submission. */
export const REFERENCE: Record<string, string> = Object.fromEntries(
  readdirSync(SEED).map((slug) => [JSON.parse(readFileSync(join(SEED, slug, "problem.json"), "utf8")).title, readFileSync(join(SEED, slug, "ref.py"), "utf8")]),
);

export async function register(page: Page, name: string) {
  const email = `${name}.${Date.now()}@e2e.algoforge.test`;
  await page.goto("/register");
  await page.getByLabel("Display name").fill(name);
  await page.getByLabel("Email").fill(email);
  await page.getByLabel("Password").fill("e2e-password-not-secret");
  await page.getByRole("button", { name: "Create account" }).click();
  await expect(page.getByRole("heading", { level: 1, name: new RegExp(`, ${name}`) })).toBeVisible();
  return email;
}

/** Replaces the Monaco model's contents (typing would fight auto-indent and bracket completion). */
export async function setCode(page: Page, code: string) {
  await page.waitForFunction(() => {
    const w = window as unknown as { monaco?: { editor: { getEditors(): unknown[] } } };
    return (w.monaco?.editor.getEditors().length ?? 0) > 0;
  });
  await page.evaluate((c) => {
    const w = window as unknown as { monaco: { editor: { getEditors(): { getModel(): { setValue(v: string): void } }[] } } };
    const editors = w.monaco.editor.getEditors();
    editors[editors.length - 1].getModel().setValue(c);
  }, code);
}

export async function chooseLanguage(page: Page, label: "Python 3" | "C++17" | "Java 21") {
  await page.getByLabel("Language").selectOption({ label });
}
