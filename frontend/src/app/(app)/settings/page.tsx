"use client";

import { useState } from "react";
import { Monitor, Moon, Sun } from "lucide-react";
import { useMe } from "@/components/AppShell";
import { Badge, Button, Card, cx, ErrorNote, Input, Label, Page, PageHeader, Segmented, Select, Spinner } from "@/components/ui";
import { errorMessage, put } from "@/lib/api";
import { LANGUAGE_LABEL } from "@/lib/format";
import { useTheme, type ThemePref } from "@/lib/theme";
import type { ConceptMastery, Preferences } from "@/lib/types";
import { useApi } from "@/lib/useApi";
import { useTitle } from "@/lib/useTitle";

interface AiStatus { dualMode: boolean; providers: { provider: string; enabled: boolean; configured: boolean; model: string; roles: string[] }[] }

const THEMES: { value: ThemePref; label: string; icon: typeof Sun }[] = [
  { value: "system", label: "System", icon: Monitor }, { value: "light", label: "Light", icon: Sun }, { value: "dark", label: "Dark", icon: Moon },
];

export default function SettingsPage() {
  useTitle("Settings");
  const me = useMe();
  const theme = useTheme();
  const prefs = useApi<Preferences>("/api/me/preferences");
  const concepts = useApi<ConceptMastery[]>("/api/learning/concepts");
  const ai = useApi<AiStatus>("/api/ai/status");
  const [edited, setForm] = useState<Preferences>();
  const [msg, setMsg] = useState<string>();
  const form = edited ?? prefs.data;

  async function save() {
    setMsg(undefined);
    try {
      setForm(await put<Preferences>("/api/me/preferences", { ...form, goalText: form!.goalText || null, goalDate: form!.goalDate || null }));
      setMsg("Saved. Tomorrow's session will use these settings; today's is already fixed.");
    } catch (e) {
      setMsg(errorMessage(e));
    }
  }

  return (
    <Page width="max-w-3xl">
      <PageHeader title="Settings" description={me ? `Signed in as ${me.email}` : undefined} />
      <div className="space-y-6">
        <Card title="Appearance" description="Follow your system, or pick a theme for this browser">
          <Segmented label="Theme" value={theme.pref} onChange={theme.setPref} options={THEMES} />
        </Card>

        <Card title="Practice preferences" description="Used to build your daily session">
          {prefs.error ? <ErrorNote message={prefs.error} /> : !form ? <Spinner /> : (
            <div className="grid gap-5 sm:grid-cols-2">
              <div><Label htmlFor="lang">Default language</Label>
                <Select id="lang" value={form.preferredLanguage} onChange={(e) => setForm({ ...form, preferredLanguage: e.target.value as Preferences["preferredLanguage"] })}>
                  {Object.entries(LANGUAGE_LABEL).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
                </Select></div>
              <div><Label htmlFor="min" hint="minutes">Daily practice time</Label>
                <Input id="min" type="number" min={10} max={480} value={form.dailyMinutes} onChange={(e) => setForm({ ...form, dailyMinutes: Number(e.target.value) })} /></div>
              <div><Label htmlFor="diff">Preferred difficulty</Label>
                <Select id="diff" value={form.targetDifficulty} onChange={(e) => setForm({ ...form, targetDifficulty: e.target.value as Preferences["targetDifficulty"] })}>
                  <option value="EASY">Easy</option><option value="MEDIUM">Medium</option><option value="HARD">Hard</option>
                </Select></div>
              <div><Label htmlFor="date">Goal date</Label>
                <Input id="date" type="date" value={form.goalDate ?? ""} onChange={(e) => setForm({ ...form, goalDate: e.target.value })} /></div>
              <div className="sm:col-span-2"><Label htmlFor="goal">Goal</Label>
                <Input id="goal" maxLength={200} placeholder="e.g. Internship OA season" value={form.goalText ?? ""} onChange={(e) => setForm({ ...form, goalText: e.target.value })} /></div>
              <fieldset className="sm:col-span-2">
                <legend className="mb-1.5 text-[13px] font-medium text-fg">Goal concepts <span className="font-normal text-faint">prioritised in daily sessions</span></legend>
                <div className="flex flex-wrap gap-1.5">
                  {concepts.data?.filter((c) => c.problemsAvailable > 0).map((c) => {
                    const on = form.goalConcepts.includes(c.code);
                    return (
                      <button key={c.code} type="button" aria-pressed={on} onClick={() => setForm({ ...form, goalConcepts: on ? form.goalConcepts.filter((x) => x !== c.code) : [...form.goalConcepts, c.code] })}
                        className={cx("h-7 rounded-md border px-2.5 text-[13px] transition-colors", on ? "border-fg bg-fg text-bg" : "border-line-strong text-muted hover:bg-panel-2 hover:text-fg")}>{c.name}</button>
                    );
                  })}
                </div>
              </fieldset>
              <label className="flex items-start gap-2.5 text-sm text-fg sm:col-span-2">
                <input type="checkbox" className="mt-0.5" checked={form.showStreak} onChange={(e) => setForm({ ...form, showStreak: e.target.checked })} />
                <span>Show a daily streak on the dashboard <span className="block text-xs text-faint">A consistency nudge, not a measure of learning.</span></span>
              </label>
              <div className="flex items-center gap-3 border-t border-line pt-5 sm:col-span-2"><Button variant="primary" onClick={save}>Save preferences</Button>{msg && <span className="text-xs text-muted">{msg}</span>}</div>
            </div>
          )}
        </Card>

        <Card title="AI providers" description="Used for reviews, test proposals and Forge Coach" padded={false}>
          {!ai.data ? <div className="p-5"><Spinner /></div> : (
            <>
              <ul className="divide-y divide-line">
                {ai.data.providers.map((p) => (
                  <li key={p.provider} className="flex flex-wrap items-center gap-3 px-5 py-3 text-sm">
                    <span className={cx("size-2 rounded-full", p.configured && p.enabled ? "bg-ok" : "bg-line-strong")} aria-hidden />
                    <span className="w-20 font-medium capitalize text-fg">{p.provider}</span>
                    {p.configured && p.enabled ? <Badge tone="ok">active</Badge> : <Badge>{p.configured ? "disabled" : "no API key"}</Badge>}
                    <span className="font-mono text-xs text-muted">{p.model}</span>
                    <span className="ml-auto text-xs text-faint">roles: {p.roles.join(", ").toLowerCase()}</span>
                  </li>
                ))}
              </ul>
              <div className="space-y-1.5 border-t border-line px-5 py-4 text-xs">
                <p className="text-muted">Dual-provider review is {ai.data.dualMode ? "on" : "off"}. API keys are read by the server from environment variables (see .env.example) and never reach the browser; models are set in the ai_provider_configs table (see README).</p>
                <p className="text-faint">Without a provider, evaluations still run every deterministic check: execution, generated tests with the reference solution, benchmarks and static analysis.</p>
              </div>
            </>
          )}
        </Card>
      </div>
    </Page>
  );
}
