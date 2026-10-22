"use client";

import Link from "next/link";
import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from "react";
import { Group, Panel, Separator } from "react-resizable-panels";
import { BookOpen, Bot, Check, Copy, Cpu, ExternalLink, EyeOff, Gauge, History as HistoryIcon, Lightbulb, Play, Send, StickyNote, Timer, TerminalSquare, Waypoints, type LucideIcon } from "lucide-react";
import { errorMessage, get, poll, post, put } from "@/lib/api";
import { draftKey, loadDraft, saveDraft, TEMPLATES } from "@/lib/drafts";
import { difficultyPill, LANGUAGE_LABEL, relativeTime, STATUS_LABEL } from "@/lib/format";
import type { Job, Language, ProblemDetail, RunResult, SubmissionDetail, SubmissionSummary } from "@/lib/types";
import { useApi } from "@/lib/useApi";
import { Coach } from "./Coach";
import { CodeEditor } from "./CodeEditor";
import { Markdown } from "./Markdown";
import { RunView, SubmissionView } from "./Results";
import { Badge, Button, cx, ErrorNote, Input, Kbd, Select, Spinner, Textarea, Verdict } from "./ui";

const NARROW = "(max-width: 767px)";
function useNarrow() {
  return useSyncExternalStore(
    (cb) => { const m = window.matchMedia(NARROW); m.addEventListener("change", cb); return () => m.removeEventListener("change", cb); },
    () => window.matchMedia(NARROW).matches,
    () => false,
  );
}

type Output = { kind: "run"; job: Job<RunResult> } | { kind: "submission"; s: SubmissionDetail } | { kind: "error"; message: string };

const MAX_CODE = 65536;

export interface WorkspaceProps {
  problemId: number;
  mode: "practice" | "assessment";
  sessionId?: number;
  languages?: Language[];
  defaultLanguage?: Language;
  disabled?: boolean;
  onJudged?: () => void;
}

/** Statement | editor | console. Used by Practice (hints, notes, evaluations) and the OA Arena (none of those). */
export function Workspace({ problemId, mode, sessionId, languages = ["python", "cpp", "java"], defaultLanguage, disabled, onJudged }: WorkspaceProps) {
  const practice = mode === "practice";
  const { data: p, error, reload } = useApi<ProblemDetail>(`/api/problems/${problemId}`);
  const [language, setLanguage] = useState<Language>(defaultLanguage && languages.includes(defaultLanguage) ? defaultLanguage : languages[0]);
  const [code, setCode] = useState("");
  const [customInput, setCustomInput] = useState("");
  const [stated, setStated] = useState("");
  const [busy, setBusy] = useState<"run" | "submit" | null>(null);
  const [output, setOutput] = useState<Output>();
  const [consoleTab, setConsoleTab] = useState<"result" | "input">("result");
  const [leftTab, setLeftTab] = useState<"problem" | "coach" | "submissions" | "hints" | "notes">("problem");
  const [deepBusy, setDeepBusy] = useState(false);
  const [transfer, setTransfer] = useState<{ problemId: number; title: string; reason: string } | null>(null);
  const [historyTick, setHistoryTick] = useState(0);
  const alive = useRef(new AbortController());
  const pendingCode = useRef<string | null>(null);
  const narrow = useNarrow();

  useEffect(() => {
    alive.current = new AbortController();
    return () => alive.current.abort();
  }, []);

  // Load the draft for this problem/language (or the starter template) whenever either changes.
  useEffect(() => {
    setCode(pendingCode.current ?? loadDraft(draftKey(problemId, language, sessionId)) ?? TEMPLATES[language]);
    pendingCode.current = null;
  }, [problemId, language, sessionId]);

  useEffect(() => {
    const t = setTimeout(() => saveDraft(draftKey(problemId, language, sessionId), code), 400);
    return () => clearTimeout(t);
  }, [code, problemId, language, sessionId]);

  const codeTooLong = code.length > MAX_CODE;

  const run = useCallback(async (samples: boolean) => {
    if (busy || codeTooLong || disabled) return;
    setBusy("run");
    setConsoleTab("result");
    try {
      const { jobId } = await post<{ jobId: number }>("/api/runs", { problemId, language, code, samples, input: samples ? undefined : customInput });
      const job = await poll(() => get<Job<RunResult>>(`/api/jobs/${jobId}`), (j) => {
        setOutput({ kind: "run", job: j });
        return j.status === "DONE" || j.status === "FAILED";
      }, alive.current.signal);
      setOutput({ kind: "run", job });
    } catch (e) {
      setOutput({ kind: "error", message: errorMessage(e) });
    } finally {
      setBusy(null);
    }
  }, [busy, codeTooLong, disabled, problemId, language, code, customInput]);

  const submit = useCallback(async () => {
    if (busy || codeTooLong || disabled) return;
    setBusy("submit");
    setConsoleTab("result");
    setTransfer(null);
    try {
      const { submissionId } = await post<{ submissionId: number }>("/api/submissions",
        { problemId, language, code, statedComplexity: practice ? stated || undefined : undefined, oaSessionId: sessionId });
      const s = await poll(() => get<SubmissionDetail>(`/api/submissions/${submissionId}`), (d) => {
        setOutput({ kind: "submission", s: d });
        return d.judgedAt != null;
      }, alive.current.signal);
      setOutput({ kind: "submission", s });
      setHistoryTick((t) => t + 1);
      onJudged?.();
      if (practice && s.status === "ACCEPTED") {
        get<{ problemId?: number; title: string; reason: string }>(`/api/learning/transfer?problemId=${problemId}`)
          .then((t) => setTransfer(t.problemId ? { problemId: t.problemId, title: t.title, reason: t.reason } : null))
          .catch(() => undefined);
        reload();
      }
    } catch (e) {
      setOutput({ kind: "error", message: errorMessage(e) });
    } finally {
      setBusy(null);
    }
  }, [busy, codeTooLong, disabled, problemId, language, code, practice, stated, sessionId, onJudged, reload]);

  async function deepEvaluate(submissionId: number) {
    setDeepBusy(true);
    try {
      await post(`/api/submissions/${submissionId}/evaluate`, { useAi: true });
    } catch (e) {
      setOutput({ kind: "error", message: errorMessage(e) });
      setDeepBusy(false);
    }
  }

  if (error) return <div className="p-6"><ErrorNote message={error} onRetry={reload} /></div>;
  if (!p) return <div className="p-6"><Spinner /></div>;

  const canJudge = p.judgeable;
  const tabs: { id: typeof leftTab; label: string; icon: LucideIcon }[] = [
    { id: "problem", label: "Problem", icon: BookOpen },
    ...(practice ? [{ id: "coach" as const, label: "Coach", icon: Bot }] : []),
    { id: "submissions", label: "Submissions", icon: HistoryIcon },
    ...(practice && p.hints && p.hints.length > 0 ? [{ id: "hints" as const, label: "Hints", icon: Lightbulb }] : []),
    ...(practice ? [{ id: "notes" as const, label: "Notes", icon: StickyNote }] : []),
  ];
  const lastSubmissionId = output?.kind === "submission" && output.s.judgedAt ? output.s.id : undefined;

  return (
    // Side by side on desktop; stacked (statement above editor) on phones.
    <Group key={narrow ? "v" : "h"} orientation={narrow ? "vertical" : "horizontal"} className="h-full">
      <Panel defaultSize="42" minSize="20" className="flex min-h-0 min-w-0 flex-col bg-panel/80">
        <div className="flex shrink-0 gap-1 overflow-x-auto border-b border-line px-3 pt-2.5" role="tablist">
          {tabs.map((t) => {
            const Icon = t.icon;
            const on = leftTab === t.id;
            return (
              <button key={t.id} role="tab" aria-selected={on} onClick={() => setLeftTab(t.id)}
                className={cx("relative flex items-center gap-1.5 whitespace-nowrap px-3 pb-2.5 pt-1.5 text-sm transition-colors", on ? "text-fg" : "text-muted hover:text-fg")}>
                <Icon className={cx("h-4 w-4", on ? "text-accent" : "text-faint")} aria-hidden />{t.label}
                {t.id === "coach" && !on && <span className="h-1.5 w-1.5 rounded-full bg-accent shadow-[0_0_8px_rgb(255_122_47)]" aria-hidden />}
                {on && <span className="absolute inset-x-2 -bottom-px h-0.5 rounded-full ember-fill" aria-hidden />}
              </button>
            );
          })}
        </div>
        <div className="min-h-0 flex-1 overflow-auto px-6 py-5">
          {leftTab === "problem" && <Statement p={p} />}
          {practice && (
            <div hidden={leftTab !== "coach"} className="h-full">
              <Coach problemId={problemId} code={code} language={language} submissionId={lastSubmissionId} />
            </div>
          )}
          {leftTab === "submissions" && <History problemId={problemId} sessionId={sessionId} tick={historyTick}
            onOpen={(s) => { setOutput({ kind: "submission", s }); setConsoleTab("result"); }}
            onRestore={(s) => {
              if (!confirm("Replace the editor contents with this submission's code?")) return;
              if (s.language === language) setCode(s.code);
              else { pendingCode.current = s.code; setLanguage(s.language); }
            }} />}
          {leftTab === "hints" && p.hints && <Hints hints={p.hints} problemId={problemId} editorial={p.editorialAvailable} />}
          {leftTab === "notes" && <Notes p={p} onSaved={reload} />}
        </div>
      </Panel>
      <Separator className={narrow ? "h-1" : "w-1"} />
      <Panel defaultSize="58" minSize="30" className="min-h-0 min-w-0">
        <Group orientation="vertical" className="h-full">
          <Panel defaultSize="62" minSize="25" className="flex min-h-0 flex-col">
            <div className="flex shrink-0 flex-wrap items-center gap-2 border-b border-line bg-panel/80 px-3 py-2">
              <label className="sr-only" htmlFor="lang">Language</label>
              <Select id="lang" value={language} onChange={(e) => setLanguage(e.target.value as Language)} className="h-8 w-32 text-xs font-medium">
                {languages.map((l) => <option key={l} value={l}>{LANGUAGE_LABEL[l]}</option>)}
              </Select>
              {practice && canJudge && (
                <Input aria-label="Your complexity estimate" placeholder="Your O(…) guess" value={stated}
                  onChange={(e) => setStated(e.target.value)} maxLength={40} className="h-8 w-36 font-mono text-xs"
                  title="Optional. Compared with the measured complexity as evidence of complexity reasoning." />
              )}
              <div className="ml-auto flex items-center gap-2">
                {codeTooLong && <span className="text-xs text-bad">Code exceeds 64 KB</span>}
                {p.samples.length > 0 && (
                  <Button size="sm" onClick={() => run(true)} disabled={!!busy || disabled} title="Run the visible samples (Ctrl/Cmd+Enter)">
                    <Play className="h-3.5 w-3.5 text-ok" aria-hidden />{busy === "run" ? "Running…" : "Run samples"} <span className="hidden 2xl:inline"><Kbd>⌘↵</Kbd></span>
                  </Button>
                )}
                <Button size="sm" onClick={() => run(false)} disabled={!!busy || disabled}><TerminalSquare className="h-3.5 w-3.5 text-info" aria-hidden />Run custom</Button>
                {canJudge && (
                  <Button size="sm" variant="primary" onClick={submit} disabled={!!busy || disabled} title="Submit for judging (Ctrl/Cmd+Shift+Enter)" className="px-4">
                    <Send className="h-3.5 w-3.5" aria-hidden />{busy === "submit" ? "Judging…" : "Submit"}
                  </Button>
                )}
              </div>
            </div>
            <div className="min-h-0 flex-1">
              <CodeEditor language={language} value={code} onChange={setCode} readOnly={disabled}
                commands={{ run: () => (p.samples.length > 0 ? run(true) : run(false)), submit: () => canJudge && submit() }} />
            </div>
          </Panel>
          <Separator className="h-1" />
          <Panel defaultSize="38" minSize="12" className="flex min-h-0 flex-col bg-panel">
            <div className="flex shrink-0 items-center gap-1 border-b border-line px-3 pt-1.5" role="tablist">
              {(["result", "input"] as const).map((t) => (
                <button key={t} role="tab" aria-selected={consoleTab === t} onClick={() => setConsoleTab(t)}
                  className={cx("relative px-3 pb-2 pt-1 text-xs font-medium", consoleTab === t ? "text-fg" : "text-muted hover:text-fg")}>
                  {t === "result" ? "Result" : "Custom input"}
                  {consoleTab === t && <span className="absolute inset-x-2 -bottom-px h-0.5 rounded-full ember-fill" aria-hidden />}
                </button>
              ))}
              {busy && <span className="ml-auto flex items-center gap-1.5 pb-1.5 text-[11px] text-info"><span className="h-1.5 w-1.5 animate-pulse rounded-full bg-info" aria-hidden />{busy === "run" ? "Running" : "Judging"}</span>}
            </div>
            <div className="min-h-0 flex-1 overflow-auto p-4">
              {consoleTab === "input" ? (
                <Textarea aria-label="Custom input" className="h-full min-h-28 resize-none" placeholder="Input passed to your program on stdin"
                  value={customInput} onChange={(e) => setCustomInput(e.target.value)} />
              ) : !output ? (
                <div className="flex h-full flex-col items-center justify-center gap-2 text-center">
                  <Cpu className="h-6 w-6 text-faint" aria-hidden />
                  <p className="max-w-sm text-sm text-faint">Run the samples, try custom input, or submit. Every result comes from compiling and running your code in an isolated sandbox.</p>
                </div>
              ) : output.kind === "error" ? (
                <ErrorNote message={output.message} />
              ) : output.kind === "run" ? (
                <RunView job={output.job} />
              ) : (
                <div className="space-y-3">
                  <SubmissionView s={output.s} practice={practice} deepBusy={deepBusy} onDeepEvaluate={() => deepEvaluate(output.s.id)} />
                  {transfer && (
                    <div className="ember-border flex gap-3 rounded-2xl p-4 text-sm animate-fade-up">
                      <Waypoints className="mt-0.5 h-5 w-5 shrink-0 text-accent" aria-hidden />
                      <div>
                        <p className="font-display font-semibold text-accent">Next: transfer test</p>
                        <p className="mt-1 text-muted">{transfer.reason}</p>
                        <Link className="mt-2 inline-block font-medium text-accent hover:underline" href={`/problems/${transfer.problemId}`}>Open “{transfer.title}” →</Link>
                      </div>
                    </div>
                  )}
                </div>
              )}
            </div>
          </Panel>
        </Group>
      </Panel>
    </Group>
  );
}

function Statement({ p }: { p: ProblemDetail }) {
  return (
    <article className="animate-fade-up">
      <p className="font-mono text-[10px] uppercase tracking-[0.2em] text-faint">{p.sourceName}{p.externalId ? ` · ${p.externalId}` : ""}</p>
      <h1 className="mt-1 text-2xl font-bold tracking-tight">{p.title}</h1>
      <div className="mt-3 flex flex-wrap items-center gap-1.5">
        {p.difficulty && <Badge className={difficultyPill(p.difficulty)}>{p.difficulty.toLowerCase()}</Badge>}
        {p.sourceRating && <Badge className="border-gold/30 bg-gold/10 text-gold">rating {p.sourceRating}</Badge>}
        <Badge><Timer className="h-3 w-3" aria-hidden />{p.timeLimitMs / 1000} s</Badge>
        <Badge><Gauge className="h-3 w-3" aria-hidden />{p.memoryLimitMb} MB</Badge>
        {p.tags.map((t) => <Badge key={t}>{t}</Badge>)}
        {p.tagsHidden && !p.assessmentLocked && <Badge className="border-violet/30 bg-violet/[0.07] text-violet" title="This problem tests whether you recognise the technique without being told."><EyeOff className="h-3 w-3" aria-hidden />topic hidden until solved</Badge>}
        {p.assessmentLocked && <Badge className="border-warn/40 bg-warn/10 text-warn">assessment: hints and topics hidden</Badge>}
      </div>
      {p.statementMd ? <div className="mt-5"><Markdown>{p.statementMd}</Markdown></div> : (
        <div className="mt-5 space-y-3 rounded-2xl border border-line bg-panel-2/50 p-5 text-sm">
          <p>The statement for this problem lives on {p.sourceName}; AlgoForge stores only the link, metadata and your notes.</p>
          {p.canonicalUrl && <a className="inline-flex items-center gap-1 font-medium text-accent hover:underline" href={p.canonicalUrl} target="_blank" rel="noopener noreferrer">Open on {p.sourceName}<ExternalLink className="h-3.5 w-3.5" aria-hidden /></a>}
          <p className="text-muted">{p.capabilities.notes}</p>
          <p className="text-muted">You can still run code here against your own input, and Forge Coach can help. Submit on {p.sourceName} for an official verdict, then record the outcome under Notes.</p>
        </div>
      )}
      {p.constraintsMd && (
        <div className="mt-6 rounded-xl border border-line bg-bg/50 px-4 py-3">
          <p className="font-mono text-[11px] uppercase tracking-[0.15em] text-gold">Constraints</p>
          <p className="mt-1.5 font-mono text-[13px] leading-relaxed text-fg/90">{p.constraintsMd}</p>
        </div>
      )}
      {p.samples.map((s, i) => (
        <div key={i} className="mt-4 grid gap-2 sm:grid-cols-2">
          <SampleBox label={`Sample input ${i + 1}`} text={s.input} />
          <SampleBox label={`Sample output ${i + 1}`} text={s.output} />
        </div>
      ))}
      {p.concepts.length > 0 && (
        <p className="mt-6 text-xs text-faint">Concepts: {p.concepts.map((c) => `${c.name}${c.tier > 1 ? ` (tier ${c.tier})` : ""}`).join(", ")}</p>
      )}
      {p.judgeable && <p className="mt-2 text-xs text-faint">Judged on {p.samples.length} samples + {p.officialTests} hidden tests.</p>}
    </article>
  );
}

function SampleBox({ label, text }: { label: string; text: string }) {
  const [copied, setCopied] = useState(false);
  return (
    <div className="min-w-0">
      <div className="mb-1 flex items-center justify-between text-[11px] font-medium uppercase tracking-wider text-faint">
        {label}
        <button className="flex items-center gap-1 normal-case text-muted transition-colors hover:text-fg" aria-label={`Copy ${label.toLowerCase()}`}
          onClick={() => { navigator.clipboard?.writeText(text); setCopied(true); setTimeout(() => setCopied(false), 1200); }}>
          {copied ? <Check className="h-3 w-3 text-ok" aria-hidden /> : <Copy className="h-3 w-3" aria-hidden />}{copied ? "copied" : "copy"}
        </button>
      </div>
      <pre className="overflow-x-auto rounded-lg border border-line bg-bg/80 p-2.5 font-mono text-xs leading-relaxed">{text}</pre>
    </div>
  );
}

function History({ problemId, sessionId, tick, onOpen, onRestore }: {
  problemId: number; sessionId?: number; tick: number; onOpen: (s: SubmissionDetail) => void; onRestore: (s: SubmissionDetail) => void;
}) {
  const { data, error, reload } = useApi<SubmissionSummary[]>(`/api/submissions?problemId=${problemId}&limit=50`);
  useEffect(() => { if (tick > 0) reload(); }, [tick, reload]);
  if (error) return <ErrorNote message={error} onRetry={reload} />;
  if (!data) return <Spinner />;
  const rows = data.filter((s) => (sessionId ? s.oaSessionId === sessionId : true));
  if (rows.length === 0) return <p className="text-sm text-muted">No submissions yet.</p>;
  const open = async (id: number, restore: boolean) => {
    const s = await get<SubmissionDetail>(`/api/submissions/${id}`);
    (restore ? onRestore : onOpen)(s);
  };
  return (
    <ul className="divide-y divide-line/70">
      {rows.map((s) => (
        <li key={s.id} className="flex items-center gap-3 py-2.5 text-sm">
          <Verdict status={s.status} />
          <span className="text-xs text-muted">{LANGUAGE_LABEL[s.language]}</span>
          {s.total != null && <span className="text-xs text-muted">{s.passed}/{s.total}</span>}
          <span className="text-xs text-faint">{relativeTime(s.createdAt)}{s.oaSessionId && !sessionId ? " · assessment" : ""}</span>
          <span className="ml-auto flex gap-2">
            <button className="text-xs text-accent hover:underline" onClick={() => open(s.id, false)}>View</button>
            <button className="text-xs text-muted hover:text-fg" onClick={() => open(s.id, true)}>Load code</button>
          </span>
        </li>
      ))}
    </ul>
  );
}

function Hints({ hints, problemId, editorial }: { hints: string[]; problemId: number; editorial: boolean }) {
  const [shown, setShown] = useState(0);
  const [solution, setSolution] = useState<{ editorialMd: string; referenceLanguage: string; referenceSolution: string }>();
  const [err, setErr] = useState<string>();
  return (
    <div className="space-y-3 text-sm">
      <p className="text-muted">Reveal hints one at a time; each nudges a bit further.</p>
      {hints.slice(0, shown).map((h, i) => (
        <div key={i} className="rounded-xl border border-line bg-panel-2/60 p-3.5 animate-fade-up"><span className="mr-2 font-mono text-[11px] font-semibold uppercase tracking-wider text-accent">Hint {i + 1}</span>{h}</div>
      ))}
      {shown < hints.length && <Button size="sm" onClick={() => setShown(shown + 1)}><Lightbulb className="h-3.5 w-3.5 text-gold" aria-hidden />Show hint {shown + 1} of {hints.length}</Button>}
      {editorial && !solution && (
        <div className="border-t border-line pt-3">
          <p className="text-xs text-faint">Viewing the solution is recorded, and a later accepted submission then counts as weaker evidence of mastery.</p>
          <Button size="sm" variant="ghost" className="mt-2" onClick={() => get<typeof solution>(`/api/problems/${problemId}/editorial`).then(setSolution).catch((e) => setErr(errorMessage(e)))}>
            Reveal editorial and reference solution
          </Button>
        </div>
      )}
      {err && <ErrorNote message={err} />}
      {solution && (
        <div className="space-y-3 border-t border-line pt-3">
          <Markdown>{solution.editorialMd}</Markdown>
          <p className="text-xs text-faint">Reference solution ({solution.referenceLanguage})</p>
          <pre className="overflow-x-auto rounded-xl border border-line bg-bg/80 p-3 font-mono text-xs">{solution.referenceSolution}</pre>
        </div>
      )}
    </div>
  );
}

function Notes({ p, onSaved }: { p: ProblemDetail; onSaved: () => void }) {
  const [status, setStatus] = useState(p.state.status);
  const [notes, setNotes] = useState(p.state.notes ?? "");
  const [pd, setPd] = useState(p.state.personalDifficulty ?? 0);
  const [msg, setMsg] = useState<string>();
  async function save() {
    try {
      await put(`/api/problems/${p.id}/state`, { status, notes: notes || null, personalDifficulty: pd || null });
      setMsg("Saved");
      onSaved();
    } catch (e) {
      setMsg(errorMessage(e));
    }
  }
  return (
    <div className="space-y-3 text-sm">
      <div>
        <label className="mb-1 block text-xs text-muted" htmlFor="st">Status</label>
        <Select id="st" value={status} onChange={(e) => setStatus(e.target.value)}>
          {Object.entries(STATUS_LABEL).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
        </Select>
        {p.judgeable && <p className="mt-1 text-xs text-faint">Judged problems update this automatically; only judged results count as learning evidence.</p>}
      </div>
      <div>
        <span className="mb-1 block text-xs text-muted">Personal difficulty</span>
        <div className="flex gap-1" role="radiogroup" aria-label="Personal difficulty">
          {[1, 2, 3, 4, 5].map((n) => (
            <button key={n} role="radio" aria-checked={pd === n} onClick={() => setPd(pd === n ? 0 : n)}
              className={cx("h-9 w-9 rounded-lg border text-xs font-semibold transition-colors", pd >= n ? "border-accent bg-accent/15 text-accent" : "border-line-strong text-muted hover:border-faint")}>{n}</button>
          ))}
        </div>
      </div>
      <div>
        <label className="mb-1 block text-xs text-muted" htmlFor="nt">Notes</label>
        <Textarea id="nt" rows={8} value={notes} maxLength={5000} onChange={(e) => setNotes(e.target.value)} placeholder="Key insight, mistakes to avoid, edge cases…" />
      </div>
      <div className="flex items-center gap-3"><Button size="sm" variant="primary" onClick={save}>Save notes</Button>{msg && <span className="text-xs text-muted">{msg}</span>}</div>
    </div>
  );
}
