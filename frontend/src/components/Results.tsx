"use client";

import Link from "next/link";
import { AlertTriangle, ArrowRight, CheckCircle2, Clock, Cpu, FlaskConical, Hammer, MemoryStick, Timer, XCircle, type LucideIcon } from "lucide-react";
import { formatMemory, PENDING, VERDICT_LABEL, VERDICT_SHORT, verdictTone } from "@/lib/format";
import type { Job, RunResult, SubmissionDetail } from "@/lib/types";
import { Badge, cx, Spinner, TONE, Verdict } from "./ui";

function Pre({ label, text, tone }: { label: string; text?: string; tone?: string }) {
  if (text == null) return null;
  return (
    <div className="min-w-0">
      <p className="mb-1 text-xs text-muted">{label}</p>
      <pre className={cx("max-h-48 overflow-auto rounded-md border border-line bg-panel-2/50 px-2.5 py-2 font-mono text-xs leading-relaxed text-fg", tone)}>{text === "" ? "(empty)" : text}</pre>
    </div>
  );
}

const VERDICT_ICON: Record<string, LucideIcon> = {
  ACCEPTED: CheckCircle2, WRONG_ANSWER: XCircle, TIME_LIMIT_EXCEEDED: Timer, MEMORY_LIMIT_EXCEEDED: MemoryStick,
  RUNTIME_ERROR: AlertTriangle, COMPILATION_ERROR: Hammer, INTERNAL_ERROR: AlertTriangle,
};

const TONE_TEXT = { ok: "text-ok", bad: "text-bad", warn: "text-warn", violet: "text-violet", info: "text-info", accent: "text-accent", neutral: "text-fg" };

const Metric = ({ icon: Icon, children }: { icon: LucideIcon; children: string }) => (
  <span className="flex items-center gap-1 font-mono text-xs text-muted"><Icon className="size-3.5" aria-hidden />{children}</span>
);

export function RunView({ job }: { job: Job<RunResult> }) {
  if (job.status === "QUEUED" || job.status === "RUNNING") {
    return <Spinner label={job.status === "QUEUED" ? `Queued${job.queuePosition ? ` (${job.queuePosition} ahead)` : ""}…` : "Compiling and running in the sandbox…"} />;
  }
  if (job.status === "FAILED" || !job.result) return <p className="text-sm text-bad">{job.error ?? "The run failed."}</p>;
  const r = job.result;
  if (!r.compile.ok) {
    return <div className="space-y-3"><Verdict status="COMPILATION_ERROR" /><Pre label="Compiler output" text={r.compile.output} tone="text-warn" /></div>;
  }
  return (
    <div className="space-y-5">
      {r.runs?.map((run, i) => {
        const matches = run.expected != null ? run.verdict === "ACCEPTED" : undefined;
        return (
          <div key={i} className="space-y-2.5">
            <div className="flex flex-wrap items-center gap-x-3 gap-y-1.5 text-sm">
              <span className="font-medium text-fg">{r.runs!.length > 1 ? `Sample ${i + 1}` : "Custom input"}</span>
              {run.expected != null || run.verdict !== "ACCEPTED" ? <Verdict status={run.verdict} /> : <Badge tone="ok">Ran</Badge>}
              <Metric icon={Clock}>{`${run.timeMs} ms`}</Metric>
              <Metric icon={Cpu}>{formatMemory(run.memoryKb)}</Metric>
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
      {r.runtimeVersion && <p className="font-mono text-xs text-faint">Runtime: {r.runtimeVersion}</p>}
    </div>
  );
}

export function SubmissionView({ s, practice, onDeepEvaluate, deepBusy }: {
  s: SubmissionDetail; practice: boolean; onDeepEvaluate?: () => void; deepBusy?: boolean;
}) {
  if (PENDING.includes(s.status)) {
    return (
      <div className="flex items-center gap-3 rounded-lg border border-line bg-panel-2/50 px-4 py-3">
        <Spinner label={`${VERDICT_LABEL[s.status]}…`} />
        <span className="text-xs text-faint">Real compile and run in an isolated sandbox</span>
      </div>
    );
  }
  const tone = verdictTone(s.status);
  const Icon = VERDICT_ICON[s.status] ?? AlertTriangle;
  const firstFail = s.tests.find((t) => t.verdict !== "ACCEPTED" && (t.input != null || t.outputExcerpt != null));
  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
        <div className="flex items-center gap-2.5">
          <Icon className={cx("size-5", TONE_TEXT[tone])} aria-hidden />
          <p className={cx("text-base font-semibold", TONE_TEXT[tone])}>{VERDICT_LABEL[s.status] ?? s.status}</p>
          {s.total != null && <p className="text-[13px] text-muted">{s.passed}/{s.total} tests passed</p>}
        </div>
        <div className="ml-auto flex flex-wrap items-center gap-3">
          {s.maxTimeMs != null && <Metric icon={Clock}>{`${s.maxTimeMs} ms`}</Metric>}
          {s.maxMemoryKb != null && <Metric icon={Cpu}>{formatMemory(s.maxMemoryKb)}</Metric>}
          {s.runtimeVersion && <span className="font-mono text-xs text-faint">{s.runtimeVersion}</span>}
        </div>
      </div>
      {s.message && <p className="text-[13px] text-muted">{s.message}</p>}
      {s.compileOutput && <Pre label="Compiler output" text={s.compileOutput} tone="text-warn" />}
      {s.tests.length > 0 && (
        <div className="flex flex-wrap gap-1" aria-label="Per-test verdicts">
          {s.tests.map((t, i) => (
            <span key={t.ordinal} title={`Test ${i + 1} (${t.kind.toLowerCase()}): ${VERDICT_LABEL[t.verdict]}${t.timeMs != null ? `, ${t.timeMs} ms` : ""}`}
              className={cx("inline-flex h-7 min-w-10 items-center justify-center gap-1 rounded px-1.5 font-mono text-[11px] ring-1 ring-inset", TONE[verdictTone(t.verdict)])}>
              <span className="opacity-70">{i + 1}</span>{VERDICT_SHORT[t.verdict]}
            </span>
          ))}
        </div>
      )}
      {s.detailsHidden && <p className="text-xs text-faint">Hidden test data and outputs are shown after the assessment ends.</p>}
      {firstFail && (
        <div className="space-y-2.5 rounded-lg border border-line p-3">
          <p className="text-[13px] font-medium text-fg">First failing test <span className="font-normal text-faint">({firstFail.kind.toLowerCase()})</span>
            {firstFail.inputLength && firstFail.inputLength > 2000 ? <span className="ml-2 text-xs font-normal text-faint">input truncated from {firstFail.inputLength.toLocaleString()} characters</span> : null}
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
        <div className="flex flex-wrap items-center gap-x-5 gap-y-2 text-[13px]">
          <Link href={`/evaluations/${s.evaluationId}`} className="inline-flex items-center gap-1 font-medium text-accent hover:underline">Open evaluation report<ArrowRight className="size-3.5" aria-hidden /></Link>
          {onDeepEvaluate && s.status !== "COMPILATION_ERROR" && s.status !== "INTERNAL_ERROR" && (
            <button onClick={onDeepEvaluate} disabled={deepBusy} className="inline-flex items-center gap-1.5 text-muted transition-colors hover:text-fg disabled:opacity-50">
              <FlaskConical className="size-3.5" aria-hidden />{deepBusy ? "Deep evaluation queued…" : "Run deep evaluation (generated tests, benchmark, AI review)"}
            </button>
          )}
        </div>
      )}
    </div>
  );
}
