"use client";

import Link from "next/link";
import { Activity, ArrowLeft, BarChart3, Bot, Code2, FileCode2, FlaskConical, Gauge, ScanSearch, ShieldCheck } from "lucide-react";
import { useParams } from "next/navigation";
import { useEffect, useState } from "react";
import { BenchmarkChart } from "@/components/TrendChart";
import { Badge, Button, Card, cx, ErrorNote, Meter, Ring, Spinner, Verdict } from "@/components/ui";
import { errorMessage, post } from "@/lib/api";
import { formatMemory, LANGUAGE_LABEL } from "@/lib/format";
import type { AiReview, Evaluation } from "@/lib/types";
import { useApi } from "@/lib/useApi";
import { useTitle } from "@/lib/useTitle";

const CORRECTNESS: Record<string, { tone: string; text: string }> = {
  VERIFIED: { tone: "border-ok/40 text-ok", text: "Accepted on the full test set, including large generated tests answered by the reference solution." },
  PROVISIONAL: { tone: "border-warn/40 text-warn", text: "Accepted, but on too few tests to call it verified." },
  FAILED: { tone: "border-bad/40 text-bad", text: "At least one test failed; see the per-test verdicts on the submission." },
};

const DISAGREEMENT_TONE: Record<string, string> = {
  RESOLVED: "border-ok/40 text-ok", UNRESOLVED: "border-warn/40 text-warn", CONTRADICTED_BY_EVIDENCE: "border-bad/40 text-bad",
};

export default function EvaluationPage() {
  const { id } = useParams<{ id: string }>();
  const { data: e, error, reload } = useApi<Evaluation>(`/api/evaluations/${id}`);
  const [deepError, setDeepError] = useState<string>();
  const [useAi, setUseAi] = useState(true);
  useTitle(e ? `Evaluation · ${e.problemTitle}` : "Evaluation");

  useEffect(() => {
    if (e?.status !== "DEEP_PENDING") return;
    const t = setInterval(reload, 3000);
    return () => clearInterval(t);
  }, [e?.status, reload]);

  async function runDeep() {
    setDeepError(undefined);
    try {
      await post(`/api/submissions/${e!.submissionId}/evaluate`, { useAi });
      reload();
    } catch (err) {
      setDeepError(errorMessage(err));
    }
  }

  if (error) return <div className="p-6"><ErrorNote message={error} onRetry={reload} /></div>;
  if (!e) return <div className="p-6"><Spinner /></div>;
  const g = e.generatedTests;
  const corr = CORRECTNESS[e.correctness];

  return (
    <div className="mx-auto max-w-6xl px-4 py-8 sm:px-8">
      <Link href="/evaluations" className="mb-4 inline-flex items-center gap-1.5 text-sm text-muted hover:text-fg"><ArrowLeft className="h-4 w-4" aria-hidden />Code Evaluations</Link>
      <section className="ember-border relative mb-6 overflow-hidden rounded-3xl p-6 sm:p-8 animate-fade-up">
        <div className="pointer-events-none absolute inset-0 bg-[radial-gradient(520px_220px_at_100%_0%,rgb(255_122_47/0.14),transparent_70%)]" aria-hidden />
        <div className="relative flex flex-wrap items-center gap-8">
          <Ring value={Number(e.overallScore)} size={140} stroke={11} label={`Overall score ${Number(e.overallScore).toFixed(1)} of 100`}>
            <div><p className="font-display text-4xl font-bold tabular-nums">{Number(e.overallScore).toFixed(0)}</p><p className="text-[10px] uppercase tracking-wider text-faint">of 100</p></div>
          </Ring>
          <div className="min-w-0 flex-1">
            <p className="font-mono text-[11px] uppercase tracking-[0.2em] text-accent">Evaluation report</p>
            <h1 className="mt-1 text-3xl font-bold tracking-tight">{e.problemTitle}</h1>
            <div className="mt-3 flex flex-wrap items-center gap-2 text-xs text-muted">
              <Verdict status={e.verdict} />
              <Badge className={corr.tone}>correctness {e.correctness.toLowerCase()}</Badge>
              <Badge>{LANGUAGE_LABEL[e.language]}</Badge>
              {e.total != null && <span className="font-mono">{e.passed}/{e.total} tests</span>}
              {e.maxTimeMs != null && <span className="font-mono">{e.maxTimeMs} ms · {formatMemory(e.maxMemoryKb)}</span>}
              {e.runtimeVersion && <span className="text-faint">{e.runtimeVersion}</span>}
            </div>
            <p className="mt-3 max-w-2xl text-sm text-muted">{corr.text} The overall score is the weighted mean of the dimensions below.</p>
          </div>
          <Link href={`/problems/${e.problemId}`} className="self-start"><Button size="sm"><Code2 className="h-3.5 w-3.5" aria-hidden />Open problem</Button></Link>
        </div>
      </section>

      <div className="grid gap-6 lg:grid-cols-2">
        <Card title="Score breakdown" icon={<BarChart3 className="h-4 w-4" />}>
          <ul className="space-y-4">
            {e.dimensions.items.map((d) => (
              <li key={d.key}>
                <div className="flex items-center justify-between text-sm">
                  <span>{d.label} <span className="text-xs text-faint">· weight {d.weight}</span>{d.provisional && <Badge className="ml-2 border-warn/40 text-warn">provisional</Badge>}</span>
                  <span className="tabular-nums">{d.score == null ? "n/a" : d.score.toFixed(0)}</span>
                </div>
                <div className="mt-1.5"><Meter label={d.label} value={d.score ?? 0} tone={d.provisional ? "bg-warn" : undefined} /></div>
                <p className="mt-1 text-xs text-muted">{d.evidence}</p>
              </li>
            ))}
          </ul>
        </Card>
        <Card title="Complexity" icon={<Gauge className="h-4 w-4" />}>
          <table className="w-full text-sm">
            <thead className="text-left text-xs text-muted"><tr><th className="pb-2 font-medium">Source</th><th className="pb-2 font-medium">Time</th><th className="pb-2 font-medium">Space</th><th className="pb-2 font-medium">Confidence</th></tr></thead>
            <tbody className="divide-y divide-line">
              {e.complexity.map((c) => (
                <tr key={c.source} className="align-top">
                  <td className="py-2 pr-2">{c.source === "static" ? "Static analysis" : c.source === "empirical" ? "Benchmark" : c.source.replace("ai:", "AI: ")}</td>
                  <td className="py-2 pr-2 font-mono text-xs">{c.timeComplexity ?? "—"}</td>
                  <td className="py-2 pr-2 font-mono text-xs">{c.spaceComplexity ?? "—"}</td>
                  <td className="py-2 text-xs text-muted">{c.confidence.toLowerCase()}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {e.complexity.map((c) => c.explanation && <p key={c.source} className="mt-2 text-xs text-muted"><span className="text-faint">{c.source}:</span> {c.explanation}</p>)}
          <p className="mt-3 text-xs text-faint">Static estimates are pattern-based heuristics and can be wrong for unusual code; benchmark growth and the judge&apos;s timings are the stronger evidence.</p>
        </Card>
      </div>

      {e.findings.length > 0 && (
        <Card title="Findings" icon={<ScanSearch className="h-4 w-4" />} className="mt-6">
          <ul className="space-y-2 text-sm">
            {e.findings.map((f, i) => (
              <li key={i} className="flex gap-2"><Badge className={f.severity === "WARN" ? "border-warn/40 text-warn" : undefined}>{f.category}</Badge><span>{f.message}</span></li>
            ))}
          </ul>
        </Card>
      )}

      <Card title="Deep evaluation" icon={<FlaskConical className="h-4 w-4" />} className="mt-6" actions={e.status !== "DEEP_PENDING" && (
        <div className="flex items-center gap-3">
          <label className="flex items-center gap-1.5 text-xs text-muted"><input type="checkbox" checked={useAi} onChange={(x) => setUseAi(x.target.checked)} />include AI review</label>
          <Button size="sm" variant="primary" onClick={runDeep}>{e.status === "DEEP_READY" ? "Run again" : "Run deep evaluation"}</Button>
        </div>
      )}>
        {deepError && <ErrorNote message={deepError} />}
        {e.status === "DEEP_PENDING" && <Spinner label="Generating and validating tests, benchmarking, reviewing… this can take a few minutes." />}
        {e.status === "DEEP_FAILED" && <ErrorNote message={e.aiStatus ?? "The deep evaluation failed."} />}
        {e.status === "BASIC" && <p className="text-sm text-muted">Runs seeded random and AI-proposed inputs through the input validator and the reference solution, finds the smallest failing case, benchmarks accepted code on growing inputs, then asks the configured AI provider(s) to review it with that evidence.</p>}
        {g && (
          <div className="space-y-4">
            <div className="flex flex-wrap gap-2 text-xs">
              <Badge className="border-ok/40 text-ok">{g.verifiedPassed}/{g.verifiedTotal} verified generated tests passed</Badge>
              <Badge>{g.randomRun ?? 0} seeded random</Badge>
              {g.unverified > 0 && <Badge className="border-warn/40 text-warn">{g.unverified} unverified (no reference)</Badge>}
              {g.rejected > 0 && <Badge>{g.rejected} rejected by the validator</Badge>}
              <Badge>AI tests: {aiStatusText(g.aiStatus)}</Badge>
            </div>
            {g.counterexample && (
              <div className="rounded-md border border-bad/40 bg-bad/5 p-3">
                <p className="text-sm font-medium text-bad">Smallest failing input found ({g.counterexample.category}, {g.counterexample.source})</p>
                <div className="mt-2 grid gap-2 md:grid-cols-3">
                  <Block label="Input" text={g.counterexample.input} />
                  <Block label="Expected (reference)" text={g.counterexample.expected} />
                  <Block label="Your output" text={g.counterexample.actual} tone="text-bad" />
                </div>
                {g.counterexample.message && <p className="mt-2 text-xs text-muted">{g.counterexample.message}</p>}
                <p className="mt-2 text-xs text-faint">Paste the input into Custom input on the problem page to replay it.</p>
              </div>
            )}
            {g.cases.filter((c) => c.source !== "random").length > 0 && (
              <details>
                <summary className="cursor-pointer text-sm text-muted">AI-proposed cases and their provenance</summary>
                <ul className="mt-2 space-y-1.5 text-xs">
                  {g.cases.filter((c) => c.source !== "random").map((c, i) => (
                    <li key={i} className="flex flex-wrap gap-2">
                      <Badge>{c.verification.toLowerCase()}</Badge><span className="text-fg">{c.category}</span>
                      <span className="text-muted">{c.reason ?? c.rationale}</span>{c.verdict && <Verdict status={c.verdict} />}
                    </li>
                  ))}
                </ul>
              </details>
            )}
            {g.randomSeedFormula && <p className="text-xs text-faint">Random cases are reproducible: {g.randomSeedFormula}. Expected outputs come from the reference solution, never from an AI.</p>}
          </div>
        )}
      </Card>

      {e.benchmark && (
        <Card title="Benchmark" icon={<Activity className="h-4 w-4" />} className="mt-6">
          <BenchmarkChart points={e.benchmark.points} />
          {e.benchmark.label && <p className="mt-2 text-sm">{e.benchmark.label}{e.benchmark.conclusive ? "" : " (inconclusive)"}</p>}
          <p className="mt-1 text-xs text-muted">{e.benchmark.note}</p>
          {e.benchmark.runtimeVersion && <p className="mt-1 text-xs text-faint">Runtime: {e.benchmark.runtimeVersion}</p>}
        </Card>
      )}

      {e.aiStatus && e.status !== "DEEP_PENDING" && !e.aiReview && <p className="mt-6 text-sm text-muted">AI review: {aiStatusText(e.aiStatus)}</p>}
      {e.aiReview && Object.entries(e.aiReview).map(([provider, r]) => (
        <Card key={provider} title={`AI review · ${provider} (${r.model})`} icon={<Bot className="h-4 w-4" />} className="mt-6">
          {r.review ? <ReviewView review={r.review} original={e.code} /> : <p className="text-sm text-bad">{r.error ?? r.status}</p>}
        </Card>
      ))}
      {e.aiDisagreements && e.aiDisagreements.length > 0 && (
        <Card title="Claims checked against evidence" icon={<ShieldCheck className="h-4 w-4" />} className="mt-6">
          <ul className="space-y-3 text-sm">
            {e.aiDisagreements.map((d, i) => (
              <li key={i}>
                <div className="flex flex-wrap items-center gap-2"><Badge className={DISAGREEMENT_TONE[d.status]}>{d.status.replaceAll("_", " ").toLowerCase()}</Badge>
                  <span className="font-medium">{d.topic}</span>
                  {Object.entries(d.claims).map(([p, c]) => <span key={p} className="text-xs text-muted">{p}: {c}</span>)}
                </div>
                <p className="mt-1 text-muted">{d.conclusion}</p>
              </li>
            ))}
          </ul>
        </Card>
      )}

      <Card title="Submitted code" icon={<FileCode2 className="h-4 w-4" />} className="mt-6">
        <pre className="max-h-[480px] overflow-auto rounded border border-line bg-bg p-3 font-mono text-xs leading-relaxed">{e.code}</pre>
        {e.statedComplexity && <p className="mt-2 text-xs text-muted">Your stated complexity: <span className="font-mono">{e.statedComplexity}</span></p>}
      </Card>
    </div>
  );
}

function aiStatusText(s?: string) {
  if (!s) return "pending";
  if (s === "NOT_CONFIGURED") return "no AI provider configured (deterministic checks only)";
  if (s === "NOT_REQUESTED") return "not requested";
  return s.toLowerCase().replaceAll("_", " ");
}

function Block({ label, text, tone }: { label: string; text?: string; tone?: string }) {
  return (
    <div className="min-w-0">
      <p className="mb-1 text-[11px] font-medium uppercase tracking-wide text-faint">{label}</p>
      <pre className={cx("max-h-40 overflow-auto rounded border border-line bg-bg p-2 font-mono text-xs", tone)}>{text ?? "—"}</pre>
    </div>
  );
}

function ReviewView({ review: r, original }: { review: AiReview; original: string }) {
  const [compare, setCompare] = useState<number>();
  return (
    <div className="space-y-3 text-sm">
      <p>{r.summary}</p>
      <p className="text-muted"><span className="text-faint">Approach:</span> {r.approach}</p>
      <div className="flex flex-wrap gap-2 text-xs">
        <Badge>opinion: {r.correctnessOpinion.replaceAll("_", " ").toLowerCase()}</Badge>
        <Badge>time {r.timeComplexity}</Badge><Badge>space {r.spaceComplexity}</Badge><Badge>fits limits: {r.fitsLimits.toLowerCase()}</Badge>
      </div>
      <p className="text-xs text-muted">{r.complexityExplanation}</p>
      {r.suspectedBugs.length > 0 && (
        <div><p className="text-xs font-medium text-muted">Suspected issues</p>
          <ul className="mt-1 list-disc space-y-1 pl-5">{r.suspectedBugs.map((b, i) => <li key={i}>{b.description} <span className="text-xs text-faint">({b.location})</span></li>)}</ul></div>
      )}
      {r.suggestions.map((s, i) => (
        <div key={i} className="rounded-md border border-line p-3">
          <p className="font-medium">{s.title}</p>
          <p className="mt-1 text-muted">{s.rationale}</p>
          <p className="mt-1 text-xs text-faint">Trade-off: {s.tradeoff}</p>
          {s.code && <button className="mt-2 text-xs text-accent hover:underline" onClick={() => setCompare(compare === i ? undefined : i)}>{compare === i ? "Hide comparison" : "Compare with your code"}</button>}
          {compare === i && (
            <div className="mt-2 grid gap-2 lg:grid-cols-2">
              <Block label="Your code (unchanged)" text={original} />
              <Block label="Suggested" text={s.code} />
            </div>
          )}
        </div>
      ))}
      <p className="text-xs text-faint">AI output is advisory. Your code is never changed; copy a suggestion yourself and resubmit to have it judged.</p>
    </div>
  );
}
