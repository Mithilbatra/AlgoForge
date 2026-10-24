"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from "react";
import { Group, Panel, Separator } from "react-resizable-panels";
import { ArrowRight, BookOpen, Bot, Check, Copy, ExternalLink, EyeOff, History as HistoryIcon, Lightbulb, Play, Sparkles, StickyNote, TerminalSquare } from "lucide-react";
import { errorMessage, get, poll, post, put } from "@/lib/api";
import { draftKey, loadDraft, saveDraft, TEMPLATES } from "@/lib/drafts";
import { difficultyTone, LANGUAGE_LABEL, relativeTime, STATUS_LABEL } from "@/lib/format";
import type { ConceptMastery, Job, Language, ProblemDetail, RunResult, SubmissionDetail, SubmissionSummary } from "@/lib/types";
import { useApi } from "@/lib/useApi";
import { Coach } from "./Coach";
import { CodeEditor } from "./CodeEditor";
import { Markdown } from "./Markdown";
import { RunView, SubmissionView } from "./Results";
import { Badge, Button, ButtonLink, Callout, cx, ErrorNote, Input, Kbd, Select, Spinner, TabBar, Textarea, Verdict } from "./ui";

const NARROW = "(max-width: 767px)";
function useNarrow() {
  return useSyncExternalStore(
    (cb) => { const m = window.matchMedia(NARROW); m.addEventListener("change", cb); return () => m.removeEventListener("change", cb); },
    () => window.matchMedia(NARROW).matches,
    () => false,
  );
}

type Output = { kind: "run"; job: Job<RunResult> } | { kind: "submission"; s: SubmissionDetail } | { kind: "error"; message: string };
type LeftTab = "problem" | "coach" | "submissions" | "hints" | "notes";

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
  const [leftTab, setLeftTab] = useState<LeftTab>("problem");
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
  const tabs = [
    { id: "problem" as const, label: "Problem", icon: BookOpen },
    ...(practice ? [{ id: "coach" as const, label: "Coach", icon: Bot, dot: true }] : []),
    { id: "submissions" as const, label: "Submissions", icon: HistoryIcon },
    ...(practice && p.hints && p.hints.length > 0 ? [{ id: "hints" as const, label: "Hints", icon: Lightbulb }] : []),
    ...(practice ? [{ id: "notes" as const, label: "Notes", icon: StickyNote }] : []),
  ];
  const lastSubmissionId = output?.kind === "submission" && output.s.judgedAt ? output.s.id : undefined;

  return (
    // Side by side on desktop; stacked (statement above editor) on phones.
    <Group key={narrow ? "v" : "h"} orientation={narrow ? "vertical" : "horizontal"} className="h-full">
      <Panel defaultSize="42" minSize="20" className="flex min-h-0 min-w-0 flex-col bg-panel">
        <TabBar tabs={tabs} value={leftTab} onChange={setLeftTab} />
        <div className="min-h-0 flex-1 overflow-auto px-6 py-6">
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
      <Separator className={narrow ? "h-px" : "w-px"} />
      <Panel defaultSize="58" minSize="30" className="min-h-0 min-w-0">
        <Group orientation="vertical" className="h-full">
          <Panel defaultSize="62" minSize="25" className="flex min-h-0 flex-col bg-panel">
            <div className="flex h-11 shrink-0 items-center gap-2 border-b border-line px-3">
              <label className="sr-only" htmlFor="lang">Language</label>
              <Select id="lang" value={language} onChange={(e) => setLanguage(e.target.value as Language)} className="h-8 w-32 text-[13px]">
                {languages.map((l) => <option key={l} value={l}>{LANGUAGE_LABEL[l]}</option>)}
              </Select>
              {practice && canJudge && (
                <Input aria-label="Your complexity estimate" placeholder="e.g. O(n log n)" value={stated}
                  onChange={(e) => setStated(e.target.value)} maxLength={40} className="hidden h-8 w-36 font-mono text-xs sm:block"
                  title="Optional. Compared with the measured complexity as evidence of complexity reasoning." />
              )}
              <div className="ml-auto flex items-center gap-2">
                {codeTooLong && <span className="text-xs text-bad">Code exceeds 64 KB</span>}
                {p.samples.length > 0 && (
                  <Button size="sm" onClick={() => run(true)} disabled={!!busy || disabled} title="Run the visible samples (Ctrl/Cmd+Enter)">
                    <Play aria-hidden />{busy === "run" ? "Running…" : "Run samples"}<span className="hidden xl:inline"><Kbd>⌘↵</Kbd></span>
                  </Button>
                )}
                <Button size="sm" onClick={() => run(false)} disabled={!!busy || disabled}><TerminalSquare aria-hidden />Run custom</Button>
                {canJudge && (
                  <Button size="sm" variant="primary" onClick={submit} disabled={!!busy || disabled} title="Submit for judging (Ctrl/Cmd+Shift+Enter)" className="px-3.5">
                    {busy === "submit" ? "Judging…" : "Submit"}
                  </Button>
                )}
              </div>
            </div>
            <div className="min-h-0 flex-1">
              <CodeEditor language={language} value={code} onChange={setCode} readOnly={disabled}
                commands={{ run: () => (p.samples.length > 0 ? run(true) : run(false)), submit: () => canJudge && submit() }} />
            </div>
          </Panel>
          <Separator className="h-px" />
          <Panel defaultSize="38" minSize="12" className="flex min-h-0 flex-col bg-panel">
            <TabBar size="sm" value={consoleTab} onChange={setConsoleTab}
              tabs={[{ id: "result", label: "Result" }, { id: "input", label: "Custom input" }]}
              trailing={busy && <span className="flex items-center gap-1.5 text-xs text-muted"><span className="size-1.5 animate-pulse rounded-full bg-info" aria-hidden />{busy === "run" ? "Running" : "Judging"}</span>} />
            <div className="min-h-0 flex-1 overflow-auto p-4">
              {consoleTab === "input" ? (
                <Textarea aria-label="Custom input" className="h-full min-h-28 resize-none" placeholder="Input passed to your program on stdin"
                  value={customInput} onChange={(e) => setCustomInput(e.target.value)} />
              ) : !output ? (
                <div className="grid h-full place-items-center text-center">
                  <p className="max-w-xs text-[13px] text-faint">Run the samples, try custom input, or submit. Every result comes from compiling and running your code in an isolated sandbox.</p>
                </div>
              ) : output.kind === "error" ? (
                <ErrorNote message={output.message} />
              ) : output.kind === "run" ? (
                <RunView job={output.job} />
              ) : (
                <div className="space-y-3">
                  <SubmissionView s={output.s} practice={practice} deepBusy={deepBusy} onDeepEvaluate={() => deepEvaluate(output.s.id)} />
                  {transfer && (
                    <div className="rounded-lg border border-accent/25 bg-accent/5 p-4 text-sm">
                      <p className="font-medium text-fg">Next: transfer test</p>
                      <p className="mt-1 text-[13px] text-muted">{transfer.reason}</p>
                      <Link className="mt-2 inline-flex items-center gap-1 text-[13px] font-medium text-accent hover:underline" href={`/problems/${transfer.problemId}`}>Open “{transfer.title}”<ArrowRight className="size-3.5" aria-hidden /></Link>
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
    <article>
      <p className="text-xs text-faint">{p.source === "ORIGINAL" ? "AlgoForge original" : p.sourceName}{p.externalId ? ` · ${p.externalId}` : ""}</p>
      <h1 className="mt-1 text-xl font-semibold tracking-tight text-fg">{p.title}</h1>
      <div className="mt-3 flex flex-wrap items-center gap-1.5">
        {p.difficulty && <Badge tone={difficultyTone(p.difficulty)}>{p.difficulty.toLowerCase()}</Badge>}
        {p.sourceRating && <Badge>rating {p.sourceRating}</Badge>}
        {/* Synced and linked problems store no limits of their own, only the database defaults. */}
        {p.judgeable && <>
          <Badge>{p.timeLimitMs / 1000} s</Badge>
          <Badge>{p.memoryLimitMb} MB</Badge>
        </>}
        {p.tags.map((t) => <Badge key={t}>{t}</Badge>)}
        {p.tagsHidden && !p.assessmentLocked && <Badge tone="violet" title="This problem tests whether you recognise the technique without being told."><EyeOff aria-hidden />topic hidden until solved</Badge>}
        {p.assessmentLocked && <Badge tone="warn">assessment: hints and topics hidden</Badge>}
      </div>
      {p.twinOfId && (
        <div className="mt-4"><Callout tone="accent" icon={<Sparkles />}>
          An original twin of <Link className="font-medium text-fg hover:underline" href={`/problems/${p.twinOfId}`}>{p.twinOfTitle}</Link>: the same technique in a new, AI-written problem.
          Its hidden tests come from a reference solution that was cross-checked against a brute force here.
        </Callout></div>
      )}
      {p.statementMd ? <div className="mt-6"><Markdown>{p.statementMd}</Markdown></div> : (
        <div className="mt-6 space-y-3 rounded-lg border border-line bg-panel-2/50 p-4 text-sm">
          <p className="text-fg">The statement for this problem lives on {p.sourceName}; AlgoForge stores only the link, metadata and your notes.</p>
          {p.canonicalUrl && <a className="inline-flex items-center gap-1 font-medium text-accent hover:underline" href={p.canonicalUrl} target="_blank" rel="noopener noreferrer">Open on {p.sourceName}<ExternalLink className="size-3.5" aria-hidden /></a>}
          <p className="text-muted">{p.capabilities.notes}</p>
          <p className="text-muted">You can still run code here against your own input, and Forge Coach can help. Submit on {p.sourceName} for an official verdict, then record the outcome under Notes.</p>
          {!p.assessmentLocked && <TwinCard p={p} />}
        </div>
      )}
      {p.constraintsMd && (
        <div className="mt-6">
          <h3 className="text-sm font-semibold text-fg">Constraints</h3>
          <p className="mt-2 rounded-md border border-line bg-panel-2/50 px-3 py-2 font-mono text-[13px] leading-relaxed text-fg">{p.constraintsMd}</p>
        </div>
      )}
      {p.samples.length > 0 && <h3 className="mt-6 text-sm font-semibold text-fg">Examples</h3>}
      {p.samples.map((s, i) => (
        <div key={i} className="mt-3 grid gap-3 sm:grid-cols-2">
          <SampleBox label={`Sample input ${i + 1}`} text={s.input} />
          <SampleBox label={`Sample output ${i + 1}`} text={s.output} />
        </div>
      ))}
      <div className="mt-8 space-y-1 border-t border-line pt-4 text-xs text-faint">
        {p.concepts.length > 0 && <p>Concepts: {p.concepts.map((c) => `${c.name}${c.tier > 1 ? ` (tier ${c.tier})` : ""}`).join(", ")}</p>}
        {p.judgeable && <p>Judged on {p.samples.length} samples and {p.officialTests} hidden tests.</p>}
      </div>
    </article>
  );
}

/** Asks for an original twin: a new problem on the same technique, written by the AI and verified in the sandbox. */
function TwinCard({ p }: { p: ProblemDetail }) {
  const router = useRouter();
  const needsConcept = p.concepts.length === 0;
  const concepts = useApi<ConceptMastery[]>(needsConcept && !p.twinId ? "/api/learning/concepts" : null);
  const [concept, setConcept] = useState("");
  const [job, setJob] = useState<Job | "starting">();
  const [error, setError] = useState<string>();
  const alive = useRef<AbortController>(null);
  useEffect(() => () => alive.current?.abort(), []);

  if (p.twinId) {
    return (
      <div className="flex flex-wrap items-center justify-between gap-3 border-t border-line pt-3">
        <p className="text-muted">You have an original twin of this problem, judged here.</p>
        <ButtonLink href={`/problems/${p.twinId}`} size="sm" variant="primary"><Sparkles className="size-3.5" aria-hidden />Open your twin</ButtonLink>
      </div>
    );
  }

  async function create() {
    alive.current = new AbortController();
    setError(undefined);
    setJob("starting");
    try {
      const r = await post<{ jobId?: number; problemId?: number }>(`/api/problems/${p.id}/twin`, needsConcept ? { concept } : {});
      let id = r.problemId;
      if (!id) {
        const done = await poll(() => get<Job<{ problemId: number }>>(`/api/jobs/${r.jobId}`), (j) => {
          setJob(j);
          return j.status === "DONE" || j.status === "FAILED";
        }, alive.current.signal, 1500);
        if (done.status === "FAILED") throw new Error(done.error ?? "Couldn't make a twin");
        id = done.result?.problemId;
      }
      if (id && !alive.current.signal.aborted) router.push(`/problems/${id}`);
    } catch (e) {
      setError(errorMessage(e));
      setJob(undefined);
    }
  }

  const working = job !== undefined;
  const step = job === "starting" || job?.status === "QUEUED"
    ? `Waiting for the worker${job !== "starting" && job?.queuePosition ? ` (${job.queuePosition} ahead)` : ""}…`
    : "Writing a new problem, then checking its solution against a brute force on random inputs. This can take several minutes; you can leave this page and come back.";
  return (
    <div className="space-y-3 border-t border-line pt-3">
      <div className="flex items-start gap-2.5">
        <Sparkles className="mt-0.5 size-4 shrink-0 text-accent" aria-hidden />
        <p className="text-muted"><span className="font-medium text-fg">Practise it here instead.</span> An original twin is a new problem that tests the same
          technique: its own story, wording and examples, written by AI and judged here like an AlgoForge original. Only its topics are shared with the AI, never this problem&apos;s text.</p>
      </div>
      {working ? (
        <div role="status" aria-live="polite">
          <p className="text-xs text-muted">{step}</p>
          <div className="mt-2 h-1 overflow-hidden rounded-full bg-panel-3" aria-hidden><div className="progress-indeterminate h-full w-2/5 rounded-full bg-accent/70" /></div>
        </div>
      ) : (
        <div className="flex flex-wrap items-center gap-2">
          {needsConcept && (
            <Select aria-label="Technique for the twin" value={concept} onChange={(e) => setConcept(e.target.value)} className="w-56">
              <option value="">Pick the technique…</option>
              {concepts.data?.map((c) => <option key={c.code} value={c.code}>{c.name}</option>)}
            </Select>
          )}
          <Button size="sm" variant="primary" onClick={create} disabled={needsConcept && !concept}><Sparkles className="size-3.5" aria-hidden />Create an original twin</Button>
        </div>
      )}
      {error && <ErrorNote message={error} onRetry={needsConcept && !concept ? undefined : create} />}
    </div>
  );
}

function SampleBox({ label, text }: { label: string; text: string }) {
  const [copied, setCopied] = useState(false);
  return (
    <div className="min-w-0 overflow-hidden rounded-md border border-line">
      <div className="flex h-8 items-center justify-between border-b border-line bg-panel-2/60 px-3 text-xs text-muted">
        {label}
        <button className="flex items-center gap-1 transition-colors hover:text-fg" aria-label={`Copy ${label.toLowerCase()}`}
          onClick={() => { navigator.clipboard?.writeText(text); setCopied(true); setTimeout(() => setCopied(false), 1200); }}>
          {copied ? <Check className="size-3 text-ok" aria-hidden /> : <Copy className="size-3" aria-hidden />}{copied ? "Copied" : "Copy"}
        </button>
      </div>
      <pre className="overflow-x-auto bg-panel px-3 py-2.5 font-mono text-[13px] leading-relaxed text-fg">{text}</pre>
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
    <ul className="-mx-2 divide-y divide-line">
      {rows.map((s) => (
        <li key={s.id} className="flex items-center gap-3 px-2 py-2.5 text-[13px]">
          <Verdict status={s.status} />
          <span className="text-muted">{LANGUAGE_LABEL[s.language]}</span>
          {s.total != null && <span className="font-mono text-xs text-muted">{s.passed}/{s.total}</span>}
          <span className="text-xs text-faint">{relativeTime(s.createdAt)}{s.oaSessionId && !sessionId ? " · assessment" : ""}</span>
          <span className="ml-auto flex gap-1">
            <Button size="sm" variant="ghost" onClick={() => open(s.id, false)}>View</Button>
            <Button size="sm" variant="ghost" onClick={() => open(s.id, true)}>Load code</Button>
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
        <div key={i} className="rounded-md border border-line bg-panel-2/50 p-3.5 text-fg"><p className="mb-1 text-xs font-medium text-faint">Hint {i + 1}</p>{h}</div>
      ))}
      {shown < hints.length && <Button size="sm" onClick={() => setShown(shown + 1)}><Lightbulb aria-hidden />Show hint {shown + 1} of {hints.length}</Button>}
      {editorial && !solution && (
        <div className="border-t border-line pt-4">
          <p className="text-xs text-faint">Viewing the solution is recorded, and a later accepted submission then counts as weaker evidence of mastery.</p>
          <Button size="sm" variant="ghost" className="mt-2 -ml-2.5" onClick={() => get<typeof solution>(`/api/problems/${problemId}/editorial`).then(setSolution).catch((e) => setErr(errorMessage(e)))}>
            Reveal editorial and reference solution
          </Button>
        </div>
      )}
      {err && <ErrorNote message={err} />}
      {solution && (
        <div className="space-y-3 border-t border-line pt-4">
          <Markdown>{solution.editorialMd}</Markdown>
          <p className="text-xs text-faint">Reference solution ({solution.referenceLanguage})</p>
          <pre className="overflow-x-auto rounded-md border border-line bg-panel-2/50 p-3 font-mono text-xs leading-relaxed">{solution.referenceSolution}</pre>
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
    <div className="space-y-5 text-sm">
      <div>
        <label className="mb-1.5 block text-[13px] font-medium text-fg" htmlFor="st">Status</label>
        <Select id="st" value={status} onChange={(e) => setStatus(e.target.value)}>
          {Object.entries(STATUS_LABEL).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
        </Select>
        {p.judgeable && <p className="mt-1.5 text-xs text-faint">Judged problems update this automatically; only judged results count as learning evidence.</p>}
      </div>
      <div>
        <span className="mb-1.5 block text-[13px] font-medium text-fg">Personal difficulty</span>
        <div className="flex gap-1" role="radiogroup" aria-label="Personal difficulty">
          {[1, 2, 3, 4, 5].map((n) => (
            <button key={n} role="radio" aria-checked={pd === n} onClick={() => setPd(pd === n ? 0 : n)}
              className={cx("size-9 rounded-md border text-[13px] font-medium transition-colors", pd >= n ? "border-fg bg-fg text-bg" : "border-line-strong text-muted hover:bg-panel-2")}>{n}</button>
          ))}
        </div>
      </div>
      <div>
        <label className="mb-1.5 block text-[13px] font-medium text-fg" htmlFor="nt">Notes</label>
        <Textarea id="nt" rows={8} value={notes} maxLength={5000} onChange={(e) => setNotes(e.target.value)} placeholder="Key insight, mistakes to avoid, edge cases…" />
      </div>
      <div className="flex items-center gap-3"><Button size="sm" variant="primary" onClick={save}>Save notes</Button>{msg && <span className="text-xs text-muted">{msg}</span>}</div>
    </div>
  );
}
