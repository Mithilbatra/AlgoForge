"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { ArrowRight, Play, Swords } from "lucide-react";
import { Badge, Button, ButtonLink, Callout, Card, Empty, ErrorNote, Input, Label, Page, PageHeader, Ring, SectionTitle, Select, Skeleton, Spinner } from "@/components/ui";
import { errorMessage, post } from "@/lib/api";
import { difficultyTone, LANGUAGE_LABEL, relativeTime } from "@/lib/format";
import type { ConceptMastery, Difficulty, Language, OaDefinition, Page as Paged, ProblemSummary } from "@/lib/types";
import { useApi } from "@/lib/useApi";
import { useTitle } from "@/lib/useTitle";

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
    <Page>
      <PageHeader title="OA Arena"
        description="Server-timed assessments with deterministic scoring and a full report afterwards. Templates are practice simulations, not copies of any company's real test." />
      {error && <div className="mb-4"><ErrorNote message={error} /></div>}
      {active && (
        <div className="mb-8">
          <Callout tone="accent" icon={<span className="relative flex size-2"><span className="absolute inline-flex size-full animate-ping rounded-full bg-accent opacity-60" /><span className="relative inline-flex size-2 rounded-full bg-accent" /></span>}
            action={<ButtonLink href={`/oa/${active.id}`} size="sm" variant="primary">Resume<ArrowRight aria-hidden /></ButtonLink>}>
            <span className="font-medium text-fg">In progress: </span>{active.title}
          </Callout>
        </div>
      )}

      <SectionTitle>Templates</SectionTitle>
      {!defs.data ? <div className="mb-10 grid gap-4 md:grid-cols-3">{[0, 1, 2].map((i) => <Skeleton key={i} className="h-60" />)}</div> : (
        <div className="mb-10 grid gap-4 md:grid-cols-3">
          {templates.map((t) => (
            <Card key={t.id} className="h-full" bodyClassName="flex h-full flex-col">
              <div className="flex items-start justify-between gap-3">
                <h3 className="text-sm font-semibold text-fg">{t.title}</h3>
                <span className="shrink-0 font-mono text-xs text-faint">{t.recipe?.slots.reduce((a, s) => a + s.points, 0)} pts</span>
              </div>
              <p className="mt-1.5 flex-1 text-[13px] leading-relaxed text-muted">{t.description}</p>
              <dl className="mt-4 grid grid-cols-2 gap-3 border-t border-line pt-4 text-[13px]">
                <div><dt className="text-xs text-faint">Duration</dt><dd className="mt-0.5 font-medium text-fg">{t.durationMinutes} min</dd></div>
                <div><dt className="text-xs text-faint">Scoring</dt><dd className="mt-0.5 font-medium text-fg">{t.partialCredit ? "Partial credit" : "All-or-nothing"}{t.wrongPenaltyPct > 0 ? `, −${t.wrongPenaltyPct}%` : ""}</dd></div>
              </dl>
              <div className="mt-3 flex flex-wrap gap-1">
                {t.recipe?.slots.map((s, j) => <Badge key={j} tone={difficultyTone(s.difficulty)}>{s.difficulty.toLowerCase()} · {s.points}</Badge>)}
              </div>
              <Button variant="primary" className="mt-5 w-full" disabled={busy !== undefined || !!active} onClick={() => fromTemplate(t)}>
                {busy === t.id ? "Preparing…" : "Pick problems and start"}
              </Button>
            </Card>
          ))}
        </div>
      )}

      <div className="grid gap-6 lg:grid-cols-[minmax(0,1.25fr)_minmax(0,1fr)]">
        <CustomBuilder onCreated={() => defs.reload()} />
        <div className="space-y-6">
          <Card title="Your assessments" padded={false}>
            {mine.length === 0 ? <p className="p-5 text-sm text-muted">Create one, or start from a template.</p> : (
              <ul className="divide-y divide-line">
                {mine.slice(0, 8).map((d) => (
                  <li key={d.id} className="flex items-center justify-between gap-3 px-5 py-3 text-sm">
                    <div className="min-w-0">
                      <p className="truncate font-medium text-fg">{d.title}</p>
                      <p className="truncate text-xs text-muted">{d.durationMinutes} min · {d.problems.map((p) => p.title).join(", ")}</p>
                    </div>
                    <Button size="sm" disabled={busy !== undefined || !!active} onClick={() => start(d.id)}><Play aria-hidden />{busy === d.id ? "Starting…" : "Start"}</Button>
                  </li>
                ))}
              </ul>
            )}
          </Card>
          <Card title="History" padded={false}>
            {!sessions.data ? <div className="p-5"><Skeleton className="h-24" /></div> : sessions.data.length === 0 ? <div className="p-5"><Empty title="No assessments yet" icon={<Swords aria-hidden />} /></div> : (
              <ul className="divide-y divide-line">
                {sessions.data.map((s) => {
                  const pct = s.maxScore ? Math.round((100 * Number(s.totalScore ?? 0)) / s.maxScore) : 0;
                  return (
                    <li key={s.id} className="flex items-center justify-between gap-3 px-5 py-3 text-sm">
                      <div className="min-w-0">
                        <p className="truncate font-medium text-fg">{s.title}</p>
                        <p className="text-xs text-muted">{relativeTime(s.startedAt)} · {s.status === "COMPLETED" ? (s.endReason === "EXPIRED" ? "time ran out" : "submitted") : s.status.toLowerCase()}</p>
                      </div>
                      {s.status === "COMPLETED"
                        ? <Link className="flex items-center gap-3 text-[13px] text-muted hover:text-fg" href={`/oa/${s.id}/report`}>
                            <span className="font-mono text-xs">{Number(s.totalScore).toFixed(0)} / {s.maxScore}</span>
                            <Ring value={pct} size={32} stroke={3.5} label={`${pct}%`}><span className="text-[9px] font-semibold text-fg">{pct}</span></Ring>
                          </Link>
                        : <ButtonLink href={`/oa/${s.id}`} size="sm">Resume</ButtonLink>}
                    </li>
                  );
                })}
              </ul>
            )}
          </Card>
        </div>
      </div>
    </Page>
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
  const problems = useApi<Paged<ProblemSummary>>("/api/problems?judgeable=true&size=100");
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
    <Card title="Build a custom assessment" description="Chosen problems, random ones, or both">
      <div className="grid gap-4 sm:grid-cols-2">
        <div className="sm:col-span-2"><Label htmlFor="t">Title</Label><Input id="t" value={title} maxLength={120} onChange={(e) => setTitle(e.target.value)} /></div>
        <div><Label htmlFor="d">Duration</Label><Input id="d" type="number" min={5} max={300} value={duration} onChange={(e) => setDuration(Number(e.target.value))} /></div>
        <div><Label htmlFor="m">Mode</Label>
          <Select id="m" value={mode} onChange={(e) => setMode(e.target.value)}>
            <option value="ASSESSMENT">Assessment (no hints, results after)</option><option value="PRACTICE">Practice (timed, hints allowed)</option>
          </Select></div>
        <div><Label htmlFor="pc">Scoring</Label>
          <Select id="pc" value={partial ? "partial" : "all"} onChange={(e) => setPartial(e.target.value === "partial")}>
            <option value="partial">Partial credit per test</option><option value="all">All-or-nothing</option>
          </Select></div>
        <div><Label htmlFor="pen" hint="% per rejected attempt">Penalty</Label><Input id="pen" type="number" min={0} max={50} value={penalty} onChange={(e) => setPenalty(Number(e.target.value))} /></div>
        <fieldset className="sm:col-span-2">
          <legend className="mb-1.5 text-[13px] font-medium text-fg">Languages</legend>
          <div className="flex gap-5 text-sm text-fg">
            {(["python", "cpp", "java"] as Language[]).map((l) => (
              <label key={l} className="flex items-center gap-2">
                <input type="checkbox" checked={langs.includes(l)} onChange={(e) => setLangs(e.target.checked ? [...langs, l] : langs.filter((x) => x !== l))} />{LANGUAGE_LABEL[l]}
              </label>
            ))}
          </div>
        </fieldset>
      </div>
      <p className="mb-1.5 mt-5 text-[13px] font-medium text-fg">Chosen problems</p>
      <div className="max-h-56 overflow-auto rounded-md border border-line">
        {!problems.data ? <div className="p-3"><Spinner /></div> : problems.data.items.map((p) => (
          <label key={p.id} className="flex min-h-10 items-center gap-2.5 border-b border-line px-3 py-1.5 text-[13px] last:border-0 hover:bg-panel-2/50">
            <input type="checkbox" checked={p.id in picked} onChange={(e) => {
              const next = { ...picked };
              if (e.target.checked) next[p.id] = 100; else delete next[p.id];
              setPicked(next);
            }} />
            <span className="flex-1 truncate text-fg">{p.title}</span>
            <Badge tone={difficultyTone(p.difficulty)}>{p.difficulty?.toLowerCase()}</Badge>
            {p.id in picked && <Input aria-label={`Points for ${p.title}`} type="number" min={1} max={1000} value={picked[p.id]} className="h-7 w-20 text-xs"
              onChange={(e) => setPicked({ ...picked, [p.id]: Number(e.target.value) })} />}
          </label>
        ))}
      </div>
      <p className="mb-1.5 mt-5 text-[13px] font-medium text-fg">Plus random problems</p>
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
      <div className="mt-5 flex items-center gap-3 border-t border-line pt-4">
        <Button variant="primary" size="sm" onClick={create} disabled={langs.length === 0 || (Object.keys(picked).length === 0 && random.count === 0)}>Create assessment</Button>
        {msg && <span className="text-xs text-muted">{msg}</span>}
      </div>
    </Card>
  );
}
