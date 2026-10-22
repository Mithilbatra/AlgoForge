"use client";

import { useState } from "react";
import { Bot, SlidersHorizontal } from "lucide-react";
import { useMe } from "@/components/AppShell";
import { Badge, Button, Card, ErrorNote, Input, Label, PageHeader, Select, Spinner } from "@/components/ui";
import { errorMessage, put } from "@/lib/api";
import { LANGUAGE_LABEL } from "@/lib/format";
import type { ConceptMastery, Preferences } from "@/lib/types";
import { useApi } from "@/lib/useApi";
import { useTitle } from "@/lib/useTitle";

interface AiStatus { dualMode: boolean; providers: { provider: string; enabled: boolean; configured: boolean; model: string; roles: string[] }[] }

export default function SettingsPage() {
  useTitle("Settings");
  const me = useMe();
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
    <div className="mx-auto max-w-4xl px-4 py-8 sm:px-8">
      <PageHeader kicker="Settings" title="Tune your forge" subtitle={me ? `Signed in as ${me.email}` : undefined} />
      <div className="grid gap-6">
        <Card title="Practice preferences" icon={<SlidersHorizontal className="h-4 w-4" />}>
          {prefs.error ? <ErrorNote message={prefs.error} /> : !form ? <Spinner /> : (
            <div className="grid gap-4 sm:grid-cols-2">
              <div><Label htmlFor="lang">Default language</Label>
                <Select id="lang" value={form.preferredLanguage} onChange={(e) => setForm({ ...form, preferredLanguage: e.target.value as Preferences["preferredLanguage"] })}>
                  {Object.entries(LANGUAGE_LABEL).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
                </Select></div>
              <div><Label htmlFor="min">Daily practice time (minutes)</Label>
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
                <legend className="mb-1 text-xs font-medium text-muted">Goal concepts (prioritised in daily sessions)</legend>
                <div className="flex flex-wrap gap-2">
                  {concepts.data?.filter((c) => c.problemsAvailable > 0).map((c) => {
                    const on = form.goalConcepts.includes(c.code);
                    return (
                      <button key={c.code} type="button" aria-pressed={on} onClick={() => setForm({ ...form, goalConcepts: on ? form.goalConcepts.filter((x) => x !== c.code) : [...form.goalConcepts, c.code] })}
                        className={`rounded-full border px-3 py-1.5 text-xs transition-colors ${on ? "border-accent bg-accent/15 text-accent shadow-[0_0_0_1px_rgb(255_122_47/0.2)]" : "border-line-strong text-muted hover:border-faint hover:text-fg"}`}>{on ? "✓ " : ""}{c.name}</button>
                    );
                  })}
                </div>
              </fieldset>
              <label className="flex items-center gap-2 text-sm sm:col-span-2">
                <input type="checkbox" checked={form.showStreak} onChange={(e) => setForm({ ...form, showStreak: e.target.checked })} />
                Show a daily streak on the dashboard <span className="text-xs text-faint">(a consistency nudge, not a measure of learning)</span>
              </label>
              <div className="flex items-center gap-3 sm:col-span-2"><Button variant="primary" onClick={save}>Save preferences</Button>{msg && <span className="text-xs text-muted">{msg}</span>}</div>
            </div>
          )}
        </Card>
        <Card title="AI providers" icon={<Bot className="h-4 w-4" />}>
          {!ai.data ? <Spinner /> : (
            <div className="space-y-3 text-sm">
              {ai.data.providers.map((p) => (
                <div key={p.provider} className="flex flex-wrap items-center gap-2 rounded-xl border border-line bg-panel-2/50 px-3.5 py-2.5">
                  <span className={`h-2 w-2 rounded-full ${p.configured && p.enabled ? "bg-ok shadow-[0_0_8px_rgb(52_211_153)]" : "bg-line-strong"}`} aria-hidden />
                  <span className="w-16 font-medium capitalize">{p.provider}</span>
                  {p.configured && p.enabled ? <Badge className="border-ok/40 text-ok">active</Badge> : <Badge>{p.configured ? "disabled" : "no API key"}</Badge>}
                  <span className="font-mono text-xs text-muted">{p.model}</span>
                  <span className="text-xs text-faint">roles: {p.roles.join(", ").toLowerCase()}</span>
                </div>
              ))}
              <p className="text-xs text-muted">Dual-provider review is {ai.data.dualMode ? "on" : "off"}. API keys are read by the server from environment variables (see .env.example) and never reach the browser; models are set in the ai_provider_configs table (see README).</p>
              <p className="text-xs text-faint">Without a provider, evaluations still run every deterministic check: execution, generated tests with the reference solution, benchmarks and static analysis.</p>
            </div>
          )}
        </Card>
      </div>
    </div>
  );
}
