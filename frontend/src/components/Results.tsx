"use client";

import Link from "next/link";
import { AlertTriangle, ArrowRight, CheckCircle2, Clock, Cpu, FlaskConical, Hammer, MemoryStick, Timer, XCircle, type LucideIcon } from "lucide-react";
import { formatMemory, PENDING, VERDICT_LABEL, VERDICT_SHORT, verdictTone } from "@/lib/format";
import type { Job, RunResult, SubmissionDetail } from "@/lib/types";
import { Badge, cx, Sparks, Spinner, Verdict } from "./ui";

function Pre({ label, text, tone }: { label: string; text?: string; tone?: string }) {
  if (text == null) return null;
  return (
    <div className="min-w-0">
      <p className="mb-1 text-[11px] font-medium uppercase tracking-wider text-faint">{label}</p>
      <pre className={cx("max-h-48 overflow-auto rounded-lg border border-line bg-bg/80 p-2.5 font-mono text-xs leading-relaxed", tone)}>{text === "" ? "(empty)" : text}</pre>
    </div>
  );
}

const VERDICT_ICON: Record<string, LucideIcon> = {
  ACCEPTED: CheckCircle2, WRONG_ANSWER: XCircle, TIME_LIMIT_EXCEEDED: Timer, MEMORY_LIMIT_EXCEEDED: MemoryStick,
  RUNTIME_ERROR: AlertTriangle, COMPILATION_ERROR: Hammer, INTERNAL_ERROR: AlertTriangle,
};

export function RunView({ job }: { job: Job<RunResult> }) {
  if (job.status === "QUEUED" || job.status === "RUNNING") {
    return <Spinner label={job.status === "QUEUED" ? `Queued${job.queuePosition ? ` (${job.queuePosition} ahead)` : ""}…` : "Compiling and running in the sandbox…"} />;
  }
  if (job.status === "FAILED" || !job.result) return <p className="text-sm text-bad">{job.error ?? "The run failed."}</p>;
  const r = job.result;
  if (!r.compile.ok) {
    return <div className="space-y-2"><Verdict status="COMPILATION_ERROR" /><Pre label="Compiler output" text={r.compile.output} tone="text-warn" /></div>;
  }
  return (
    <div className="space-y-4">
      {r.runs?.map((run, i) => {
        const matches = run.expected != null ? run.verdict === "ACCEPTED" : undefined;
        return (
          <div key={i} className="space-y-2 animate-fade-up">
            <div className="flex flex-wrap items-center gap-2 text-sm">
              <span className="font-display font-semibold">{r.runs!.length > 1 ? `Sample ${i + 1}` : "Custom input"}</span>
              {run.expected != null || run.verdict !== "ACCEPTED" ? <Verdict status={run.verdict} /> : <Badge className="border-ok/40 bg-ok/10 text-ok">Ran</Badge>}
              <span className="flex items-center gap-1 font-mono text-xs text-muted"><Clock className="h-3 w-3" aria-hidden />{run.timeMs} ms</span>
              <span className="flex items-center gap-1 font-mono text-xs text-muted"><Cpu className="h-3 w-3" aria-hidden />{formatMemory(run.memoryKb)}</span>
              {run.message && <span className="text-xs text-muted">{run.message}</span>}
            </div>
            <div className="grid gap-2 md:grid-cols-2">
              <Pre label="Your output" text={run.stdout} tone={matches === false ? "text-bad" : undefined} />
              {run.expected != null && <Pre label="Expected" text={run.expected} />}
            </div>
            {run.stderr && <Pre label="stderr" text={run.stderr} tone="text-warn" />}
          </div>
        );
      })}
      {r.runtimeVersion && <p className="font-mono text-[11px] text-faint">Runtime: {r.runtimeVersion}</p>}
    </div>
  );
}

export function SubmissionView({ s, practice, onDeepEvaluate, deepBusy }: {
  s: SubmissionDetail; practice: boolean; onDeepEvaluate?: () => void; deepBusy?: boolean;
}) {
  if (PENDING.includes(s.status)) {
    return (
      <div className="flex items-center gap-3 rounded-xl border border-info/30 bg-info/[0.06] px-4 py-3">
        <Spinner label={`${VERDICT_LABEL[s.status]}…`} />
        <span className="text-xs text-faint">Real compile and run in an isolated sandbox</span>
      </div>
    );
  }
  const accepted = s.status === "ACCEPTED";
  const Icon = VERDICT_ICON[s.status] ?? AlertTriangle;
  const firstFail = s.tests.find((t) => t.verdict !== "ACCEPTED" && (t.input != null || t.outputExcerpt != null));
  return (
    <div className="space-y-4">
      <div className={cx("relative flex flex-wrap items-center gap-4 overflow-hidden rounded-2xl border px-4 py-3.5 animate-fade-up",
        accepted ? "border-ok/40 bg-gradient-to-r from-ok/15 via-ok/5 to-transparent" : verdictTone(s.status))}>
        <span className="relative grid h-11 w-11 shrink-0 place-items-center rounded-xl bg-bg/40">
          <Icon className={cx("h-6 w-6", accepted ? "text-ok" : "")} aria-hidden />
          {accepted && <Sparks />}
        </span>
        <div className="min-w-0">
          <p className={cx("font-display text-lg font-bold leading-tight", accepted ? "text-ok" : "")}>{VERDICT_LABEL[s.status] ?? s.status}</p>
          {s.total != null && <p className="text-sm text-fg/80">{s.passed}/{s.total} tests passed</p>}
        </div>
        <div className="ml-auto flex flex-wrap items-center gap-3 font-mono text-xs text-muted">
          {s.maxTimeMs != null && <span className="flex items-center gap-1"><Clock className="h-3.5 w-3.5" aria-hidden />{s.maxTimeMs} ms</span>}
          {s.maxMemoryKb != null && <span className="flex items-center gap-1"><Cpu className="h-3.5 w-3.5" aria-hidden />{formatMemory(s.maxMemoryKb)}</span>}
          {s.runtimeVersion && <span className="text-faint">{s.runtimeVersion}</span>}
        </div>
      </div>
      {s.message && <p className="text-sm text-muted">{s.message}</p>}
      {s.compileOutput && <Pre label="Compiler output" text={s.compileOutput} tone="text-warn" />}
      {s.tests.length > 0 && (
        <div className="flex flex-wrap gap-1.5" aria-label="Per-test verdicts">
          {s.tests.map((t, i) => (
            <span key={t.ordinal} title={`Test ${i + 1} (${t.kind.toLowerCase()}): ${VERDICT_LABEL[t.verdict]}${t.timeMs != null ? `, ${t.timeMs} ms` : ""}`}
              style={{ animationDelay: `${i * 35}ms` }}
              className={cx("inline-flex h-8 min-w-11 items-center justify-center gap-1 rounded-lg border px-1.5 font-mono text-[11px] animate-fade-up", verdictTone(t.verdict))}>
              <span className="text-faint">{i + 1}</span>{VERDICT_SHORT[t.verdict]}
            </span>
          ))}
        </div>
      )}
      {s.detailsHidden && <p className="text-xs text-faint">Hidden test data and outputs are shown after the assessment ends.</p>}
      {firstFail && (
        <div className="space-y-2 rounded-xl border border-line bg-panel-2/40 p-3">
          <p className="text-sm font-medium">First failing test <span className="text-faint">({firstFail.kind.toLowerCase()})</span>
            {firstFail.inputLength && firstFail.inputLength > 2000 ? <span className="ml-2 text-xs text-faint">input truncated from {firstFail.inputLength.toLocaleString()} characters</span> : null}
          </p>
          <div className="grid gap-2 md:grid-cols-3">
            <Pre label="Input" text={firstFail.input} />
            <Pre label="Expected" text={firstFail.expected} />
            <Pre label="Your output" text={firstFail.outputExcerpt} tone="text-bad" />
          </div>
          {firstFail.errorExcerpt && <Pre label="stderr" text={firstFail.errorExcerpt} tone="text-warn" />}
          {firstFail.provenance && <p className="text-xs text-faint">Test provenance: {firstFail.provenance}</p>}
        </div>
      )}
      {practice && s.evaluationId && (
        <div className="flex flex-wrap items-center gap-x-5 gap-y-2">
          <Link href={`/evaluations/${s.evaluationId}`} className="inline-flex items-center gap-1 text-sm font-medium text-accent hover:underline">Open evaluation report <ArrowRight className="h-3.5 w-3.5" aria-hidden /></Link>
          {onDeepEvaluate && s.status !== "COMPILATION_ERROR" && s.status !== "INTERNAL_ERROR" && (
            <button onClick={onDeepEvaluate} disabled={deepBusy} className="inline-flex items-center gap-1.5 text-sm text-muted hover:text-fg disabled:opacity-50">
              <FlaskConical className="h-3.5 w-3.5" aria-hidden />{deepBusy ? "Deep evaluation queued…" : "Run deep evaluation (generated tests, benchmark, AI review)"}
            </button>
          )}
        </div>
      )}
    </div>
  );
}
