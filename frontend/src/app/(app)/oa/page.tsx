"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { ArrowRight, Briefcase, ClipboardList, GraduationCap, History, Play, Swords, Trophy, Wrench } from "lucide-react";
import { Badge, Button, Card, Empty, ErrorNote, Input, Label, PageHeader, Ring, Select, Skeleton, Spinner } from "@/components/ui";
import { errorMessage, post } from "@/lib/api";
import { difficultyPill, difficultyTone, LANGUAGE_LABEL, relativeTime } from "@/lib/format";
import type { ConceptMastery, Difficulty, Language, OaDefinition, Page, ProblemSummary } from "@/lib/types";
import { useApi } from "@/lib/useApi";
import { useTitle } from "@/lib/useTitle";

const TEMPLATE_ICONS = [Trophy, Briefcase, GraduationCap];

interface SessionRow { id: number; title: string; status: string; startedAt: string; totalScore?: number; maxScore?: number; endReason?: string }

export default function OaArena() {
  useTitle("OA Arena");
  const router = useRouter();
  const defs = useApi<OaDefinition[]>("/api/oa/definitions");
  const sessions = useApi<SessionRow[]>("/api/oa/sessions");
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState<number>();
  const active = sessions.data?.find((s) => s.status !== "COMPLETED");

  async function start(definitionId: number) {
    if (!confirm("Start now? The timer begins immediately and keeps running even if you close the tab.")) return;
    setBusy(definitionId);
    setError(undefined);
    try {
      const { sessionId } = await post<{ sessionId: number }>(`/api/oa/definitions/${definitionId}/start`);
      router.push(`/oa/${sessionId}`);
    } catch (e) {
      setError(errorMessage(e));
      setBusy(undefined);
    }
  }

  async function fromTemplate(t: OaDefinition) {
    setBusy(t.id);
    setError(undefined);
    try {
      const d = await post<OaDefinition>(`/api/oa/templates/${t.id}/instantiate`);
      defs.reload();
      setBusy(undefined);
      await start(d.id);
    } catch (e) {
      setError(errorMessage(e));
      setBusy(undefined);
    }
  }

  const templates = defs.data?.filter((d) => d.isTemplate) ?? [];
  const mine = defs.data?.filter((d) => !d.isTemplate) ?? [];

  return (
    <div className="mx-auto max-w-7xl px-4 py-8 sm:px-8">
      <PageHeader kicker="OA Arena · timed assessments" title="Step into the arena"
        subtitle="Server-timed assessments with deterministic scoring and a full post-mortem. Templates are practice simulations, not copies of any company's real test." />
      {error && <div className="mb-4"><ErrorNote message={error} /></div>}
      {active && (
        <Link href={`/oa/${active.id}`} className="ember-border group mb-8 flex items-center justify-between gap-4 rounded-2xl px-5 py-4 animate-fade-up">
          <span className="flex items-center gap-3">
            <span className="relative flex h-3 w-3"><span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-accent opacity-60" /><span className="relative inline-flex h-3 w-3 rounded-full bg-accent" /></span>
            <span><span className="font-display font-semibold text-accent">In progress: </span>{active.title}</span>
          </span>
          <span className="flex items-center gap-1 text-sm font-medium text-accent">Resume <ArrowRight className="h-4 w-4 transition-transform group-hover:translate-x-0.5" aria-hidden /></span>
        </Link>
      )}
      <p className="mb-3 font-mono text-[11px] uppercase tracking-[0.2em] text-faint">Templates</p>
      {!defs.data ? <div className="mb-10 grid gap-4 md:grid-cols-3">{[0, 1, 2].map((i) => <Skeleton key={i} className="h-56 rounded-2xl" />)}</div> : (
        <div className="mb-10 grid gap-5 md:grid-cols-3">
          {templates.map((t, i) => {
            const Icon = TEMPLATE_ICONS[i % TEMPLATE_ICONS.length];
            return (
              <Card key={t.id} className="group relative overflow-hidden transition-transform hover:-translate-y-0.5">
                <div className="pointer-events-none absolute -right-8 -top-8 h-32 w-32 rounded-full bg-accent/10 blur-2xl transition-opacity group-hover:bg-accent/20" aria-hidden />
                <span className="grid h-11 w-11 place-items-center rounded-xl border border-accent/30 bg-accent/10 text-accent"><Icon className="h-5 w-5" aria-hidden /></span>
                <h2 className="mt-4 font-display text-lg font-semibold">{t.title}</h2>
                <p className="mt-1 min-h-10 text-sm leading-relaxed text-muted">{t.description}</p>
                <div className="mt-4 flex items-baseline gap-1.5">
                  <span className="font-display text-3xl font-bold tabular-nums">{t.durationMinutes}</span><span className="text-sm text-faint">min</span>
                  <span className="ml-auto font-mono text-xs text-faint">{t.recipe?.slots.reduce((a, s) => a + s.points, 0)} pts</span>
                </div>
                <div className="mt-3 flex flex-wrap gap-1.5">
                  {t.recipe?.slots.map((s, j) => <Badge key={j} className={difficultyPill(s.difficulty)}>{s.difficulty.toLowerCase()} · {s.points}</Badge>)}
                </div>
                <p className="mt-3 text-xs text-faint">{t.partialCredit ? "Partial credit per test" : "All-or-nothing"}{t.wrongPenaltyPct > 0 ? ` · −${t.wrongPenaltyPct}% per rejection` : ""}</p>
                <Button variant="primary" className="mt-5 w-full" disabled={busy !== undefined || !!active} onClick={() => fromTemplate(t)}>
                  {busy === t.id ? "Preparing…" : "Pick problems and start"}
                </Button>
              </Card>
            );
          })}
        </div>
      )}
      <div className="grid gap-6 lg:grid-cols-[1.2fr_1fr]">
        <CustomBuilder onCreated={() => defs.reload()} />
        <div className="space-y-6">
          <Card title="Your assessments" icon={<ClipboardList className="h-4 w-4" />}>
            {mine.length === 0 ? <p className="text-sm text-muted">Create one, or start from a template.</p> : (
              <ul className="divide-y divide-line/70">
                {mine.slice(0, 10).map((d) => (
                  <li key={d.id} className="flex items-center justify-between gap-3 py-3 text-sm">
                    <div className="min-w-0">
                      <p className="truncate font-medium">{d.title}</p>
                      <p className="truncate text-xs text-muted">{d.durationMinutes} min · {d.problems.map((p) => p.title).join(", ")}</p>
                    </div>
                    <Button size="sm" disabled={busy !== undefined || !!active} onClick={() => start(d.id)}><Play className="h-3.5 w-3.5" aria-hidden />{busy === d.id ? "Starting…" : "Start"}</Button>
                  </li>
                ))}
              </ul>
            )}
          </Card>
          <Card title="History" icon={<History className="h-4 w-4" />}>
            {!sessions.data ? <Skeleton className="h-24" /> : sessions.data.length === 0 ? <Empty title="No assessments yet" icon={<Swords className="h-5 w-5" />} /> : (
              <ul className="divide-y divide-line/70">
                {sessions.data.map((s) => {
                  const pct = s.maxScore ? Math.round((100 * Number(s.totalScore ?? 0)) / s.maxScore) : 0;
                  return (
                    <li key={s.id} className="flex items-center justify-between gap-3 py-3 text-sm">
                      <div className="min-w-0">
                        <p className="truncate font-medium">{s.title}</p>
                        <p className="text-xs text-muted">{relativeTime(s.startedAt)} · {s.status === "COMPLETED" ? (s.endReason === "EXPIRED" ? "time ran out" : "submitted") : s.status.toLowerCase()}</p>
                      </div>
                      {s.status === "COMPLETED"
                        ? <Link className="flex items-center gap-3 text-accent hover:underline" href={`/oa/${s.id}/report`}><span className="font-mono text-xs text-muted">{Number(s.totalScore).toFixed(0)} / {s.maxScore}</span><Ring value={pct} size={34} stroke={4} label={`${pct}%`}><span className="text-[9px] font-semibold text-fg">{pct}</span></Ring></Link>
                        : <Link className="font-medium text-accent hover:underline" href={`/oa/${s.id}`}>Resume</Link>}
                    </li>
                  );
                })}
              </ul>
            )}
          </Card>
        </div>
      </div>
    </div>
  );
}

function CustomBuilder({ onCreated }: { onCreated: () => void }) {
  const [title, setTitle] = useState("Custom assessment");
  const [duration, setDuration] = useState(60);
  const [mode, setMode] = useState("ASSESSMENT");
  const [partial, setPartial] = useState(true);
  const [penalty, setPenalty] = useState(0);
  const [langs, setLangs] = useState<Language[]>(["python", "cpp", "java"]);
  const [picked, setPicked] = useState<Record<number, number>>({});
  const [random, setRandom] = useState({ count: 0, difficulty: "" as Difficulty | "", concept: "", points: 100 });
  const [msg, setMsg] = useState<string>();
  const problems = useApi<Page<ProblemSummary>>("/api/problems?judgeable=true&size=100");
  const concepts = useApi<ConceptMastery[]>("/api/learning/concepts");

  async function create() {
    setMsg(undefined);
    try {
      await post("/api/oa/definitions", {
        title, durationMinutes: duration, mode, partialCredit: partial, wrongPenaltyPct: penalty, languages: langs, allowHints: false,
        problems: Object.entries(picked).map(([id, points]) => ({ problemId: Number(id), points })),
        random: random.count > 0 ? { count: random.count, difficulty: random.difficulty || null, concepts: random.concept ? [random.concept] : [], pointsEach: random.points } : null,
      });
      setMsg("Created. Start it from “Your assessments”.");
      setPicked({});
      onCreated();
    } catch (e) {
      setMsg(errorMessage(e));
    }
  }

  return (
    <Card title="Build a custom assessment" icon={<Wrench className="h-4 w-4" />}>
      <div className="grid gap-3 sm:grid-cols-2">
        <div className="sm:col-span-2"><Label htmlFor="t">Title</Label><Input id="t" value={title} maxLength={120} onChange={(e) => setTitle(e.target.value)} /></div>
        <div><Label htmlFor="d">Duration (minutes)</Label><Input id="d" type="number" min={5} max={300} value={duration} onChange={(e) => setDuration(Number(e.target.value))} /></div>
        <div><Label htmlFor="m">Mode</Label>
          <Select id="m" value={mode} onChange={(e) => setMode(e.target.value)}>
            <option value="ASSESSMENT">Assessment (no hints, results after)</option><option value="PRACTICE">Practice (timed, hints allowed)</option>
          </Select></div>
        <div><Label htmlFor="pc">Scoring</Label>
          <Select id="pc" value={partial ? "partial" : "all"} onChange={(e) => setPartial(e.target.value === "partial")}>
            <option value="partial">Partial credit per test</option><option value="all">All-or-nothing</option>
          </Select></div>
        <div><Label htmlFor="pen">Penalty per rejected attempt (%)</Label><Input id="pen" type="number" min={0} max={50} value={penalty} onChange={(e) => setPenalty(Number(e.target.value))} /></div>
        <fieldset className="sm:col-span-2">
          <legend className="mb-1 text-xs font-medium text-muted">Languages</legend>
          <div className="flex gap-4 text-sm">
            {(["python", "cpp", "java"] as Language[]).map((l) => (
              <label key={l} className="flex items-center gap-1.5">
                <input type="checkbox" checked={langs.includes(l)} onChange={(e) => setLangs(e.target.checked ? [...langs, l] : langs.filter((x) => x !== l))} />{LANGUAGE_LABEL[l]}
              </label>
            ))}
          </div>
        </fieldset>
      </div>
      <p className="mb-1 mt-4 text-xs font-medium text-muted">Chosen problems</p>
      <div className="max-h-56 overflow-auto rounded-xl border border-line bg-bg/40">
        {!problems.data ? <div className="p-3"><Spinner /></div> : problems.data.items.map((p) => (
          <label key={p.id} className="flex items-center gap-2 border-b border-line px-3 py-1.5 text-sm last:border-0">
            <input type="checkbox" checked={p.id in picked} onChange={(e) => {
              const next = { ...picked };
              if (e.target.checked) next[p.id] = 100; else delete next[p.id];
              setPicked(next);
            }} />
            <span className="flex-1 truncate">{p.title}</span>
            <span className={`text-xs ${difficultyTone(p.difficulty)}`}>{p.difficulty?.toLowerCase()}</span>
            {p.id in picked && <Input aria-label={`Points for ${p.title}`} type="number" min={1} max={1000} value={picked[p.id]} className="h-7 w-20 text-xs"
              onChange={(e) => setPicked({ ...picked, [p.id]: Number(e.target.value) })} />}
          </label>
        ))}
      </div>
      <p className="mb-1 mt-4 text-xs font-medium text-muted">Plus random problems</p>
      <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
        <Input aria-label="Random count" type="number" min={0} max={6} value={random.count} onChange={(e) => setRandom({ ...random, count: Number(e.target.value) })} />
        <Select aria-label="Random difficulty" value={random.difficulty} onChange={(e) => setRandom({ ...random, difficulty: e.target.value as Difficulty | "" })}>
          <option value="">Any difficulty</option><option value="EASY">Easy</option><option value="MEDIUM">Medium</option><option value="HARD">Hard</option>
        </Select>
        <Select aria-label="Random topic" value={random.concept} onChange={(e) => setRandom({ ...random, concept: e.target.value })}>
          <option value="">Any topic</option>
          {concepts.data?.filter((c) => c.problemsAvailable > 0).map((c) => <option key={c.code} value={c.code}>{c.name}</option>)}
        </Select>
        <Input aria-label="Points each" type="number" min={1} max={1000} value={random.points} onChange={(e) => setRandom({ ...random, points: Number(e.target.value) })} />
      </div>
      <div className="mt-4 flex items-center gap-3">
        <Button variant="primary" size="sm" onClick={create} disabled={langs.length === 0 || (Object.keys(picked).length === 0 && random.count === 0)}>Create assessment</Button>
        {msg && <span className="text-xs text-muted">{msg}</span>}
      </div>
    </Card>
  );
}
