"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useEffect, useState } from "react";
import { ArrowLeft, CheckCircle2, Flame, History, Lightbulb, ListChecks, Network, Scale, Send } from "lucide-react";
import { Badge, Card, ErrorNote, Ring, Spinner } from "@/components/ui";
import { ApiError, errorMessage, get } from "@/lib/api";
import { formatDuration, VERDICT_LABEL, verdictTone } from "@/lib/format";
import type { OaReport } from "@/lib/types";
import { useTitle } from "@/lib/useTitle";

export default function ReportPage() {
  const { id } = useParams<{ id: string }>();
  const [data, setData] = useState<{ title: string; startedAt: string; report: OaReport }>();
  const [waiting, setWaiting] = useState(false);
  const [error, setError] = useState<string>();
  useTitle("Assessment report");

  useEffect(() => {
    let stop = false;
    const load = async () => {
      try {
        const d = await get<{ title: string; startedAt: string; report: OaReport }>(`/api/oa/sessions/${id}/report`);
        if (!stop) { setData(d); setWaiting(false); }
      } catch (e) {
        if (e instanceof ApiError && e.status === 409) { setWaiting(true); if (!stop) setTimeout(load, 3000); }
        else setError(errorMessage(e));
      }
    };
    load();
    return () => { stop = true; };
  }, [id]);

  if (error) return <div className="p-6"><ErrorNote message={error} /></div>;
  if (!data) return <div className="p-6"><Spinner label={waiting ? "Finalising: waiting for the last submissions to be judged…" : undefined} /></div>;
  const r = data.report;
  const used = r.secondsUsed;

  return (
    <div className="mx-auto max-w-6xl px-4 py-8 sm:px-8">
      <Link href="/oa" className="mb-4 inline-flex items-center gap-1.5 text-sm text-muted hover:text-fg"><ArrowLeft className="h-4 w-4" aria-hidden />OA Arena</Link>
      <section className="ember-border relative mb-6 overflow-hidden rounded-3xl p-6 sm:p-8 animate-fade-up">
        <div className="pointer-events-none absolute inset-0 bg-[radial-gradient(500px_200px_at_15%_0%,rgb(255_122_47/0.16),transparent_70%)]" aria-hidden />
        <div className="relative flex flex-wrap items-center gap-8">
          <Ring value={r.percentage} size={150} stroke={12} label={`Score ${r.percentage}%`}>
            <div><p className="font-display text-4xl font-bold tabular-nums">{Math.round(r.percentage)}<span className="text-lg text-faint">%</span></p></div>
          </Ring>
          <div className="min-w-0 flex-1">
            <p className="font-mono text-[11px] uppercase tracking-[0.2em] text-accent">Assessment report</p>
            <h1 className="mt-1 text-3xl font-bold tracking-tight">{data.title}</h1>
            <p className="mt-2 font-display text-2xl font-semibold tabular-nums">{r.totalScore} / {r.maxScore}</p>
            <p className="mt-1 text-sm text-muted">{new Date(data.startedAt).toLocaleString()} · {r.endReason === "EXPIRED" ? "time ran out" : "submitted"} after {formatDuration(used)}</p>
          </div>
          <div className="grid w-full grid-cols-3 gap-3 sm:w-auto">
            <Stat icon={<CheckCircle2 className="h-4 w-4 text-ok" aria-hidden />} label="Solved" value={String(r.solved)} sub={`${r.partiallySolved} partially`} />
            <Stat icon={<Send className="h-4 w-4 text-info" aria-hidden />} label="Submissions" value={String(r.timeline.length)} />
            <Stat icon={<Scale className="h-4 w-4 text-gold" aria-hidden />} label="Scoring" value={r.scoringRules.partialCredit ? "Partial" : "Strict"} sub={r.scoringRules.wrongPenaltyPct ? `−${r.scoringRules.wrongPenaltyPct}%/rejection` : "no penalty"} />
          </div>
        </div>
      </section>

      {r.insights.length > 0 && (
        <Card title="What to take away" icon={<Lightbulb className="h-4 w-4" />} className="mb-6">
          <ul className="space-y-2.5 text-sm">
            {r.insights.map((x, i) => (
              <li key={i} className="flex gap-3"><span className="mt-1.5 h-1.5 w-1.5 shrink-0 rounded-full ember-fill" aria-hidden /><span className="leading-relaxed">{x}</span></li>
            ))}
          </ul>
        </Card>
      )}

      <Card title="Problems" icon={<ListChecks className="h-4 w-4" />} className="mb-6">
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead className="text-left text-xs text-muted">
              <tr><th className="py-2 pr-3 font-medium">Problem</th><th className="py-2 pr-3 font-medium">Score</th><th className="py-2 pr-3 font-medium">Time spent</th><th className="py-2 pr-3 font-medium">Attempts</th><th className="py-2 font-medium">Evaluation</th></tr>
            </thead>
            <tbody className="divide-y divide-line">
              {r.problems.map((p) => (
                <tr key={p.problemId} className="align-top">
                  <td className="py-2.5 pr-3"><Link className="font-medium hover:text-accent" href={`/problems/${p.problemId}`}>{p.title}</Link>
                    <div className="mt-1 flex flex-wrap gap-1">{Object.entries(p.verdicts).map(([v, n]) => <Badge key={v} className={verdictTone(v)}>{n}× {VERDICT_LABEL[v]}</Badge>)}</div>
                    {(p.failedOnSamples > 0 || p.failedOnHiddenTests > 0) && <p className="mt-1 text-xs text-muted">Failed on visible samples {p.failedOnSamples}×, on hidden tests {p.failedOnHiddenTests}×</p>}
                  </td>
                  <td className="py-2.5 pr-3 tabular-nums">{p.score} / {p.points}</td>
                  <td className="py-2.5 pr-3 tabular-nums">{formatDuration(p.timeSpentSeconds)} <span className="text-xs text-faint">({Math.round((100 * p.timeSpentSeconds) / Math.max(1, used))}%)</span></td>
                  <td className="py-2.5 pr-3">{p.attempts}</td>
                  <td className="py-2.5">
                    {p.evaluation ? <>
                      <Link className="text-accent hover:underline" href={`/evaluations/${p.evaluation.evaluationId}`}>{p.evaluation.overallScore.toFixed(0)}/100</Link>
                      {p.evaluation.staticTimeComplexity && <span className="ml-2 text-xs text-muted">{p.evaluation.staticTimeComplexity}</span>}
                      {p.evaluation.optimisationFindings.map((f, i) => <p key={i} className="mt-1 text-xs text-warn">{f}</p>)}
                    </> : <span className="text-faint">—</span>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Card>

      <Card title="Timeline" icon={<History className="h-4 w-4" />} className="mb-6">
        {r.timeline.length === 0 ? <p className="text-sm text-muted">No submissions.</p> : (
          <>
            <div className="relative mb-5 h-12 rounded-xl border border-line bg-[linear-gradient(90deg,var(--color-panel-2),var(--color-panel-3))]" aria-hidden>
              {r.timeline.map((t) => (
                <span key={t.submissionId} title={`${formatDuration(t.atSeconds)} · ${t.title} · ${VERDICT_LABEL[t.status]}`}
                  className={`absolute top-2.5 h-7 w-2 -translate-x-1/2 rounded-full border ${verdictTone(t.status)}`}
                  style={{ left: `${Math.min(100, (100 * t.atSeconds) / Math.max(1, used))}%` }} />
              ))}
            </div>
            <ol className="space-y-1.5 text-sm">
              {r.timeline.map((t) => (
                <li key={t.submissionId} className="flex items-center gap-3">
                  <span className="w-14 font-mono text-xs text-muted tabular-nums">{formatDuration(t.atSeconds)}</span>
                  <span className="flex-1 truncate">{t.title}</span>
                  <Badge className={verdictTone(t.status)}>{VERDICT_LABEL[t.status]}</Badge>
                  {t.total != null && <span className="w-12 text-right text-xs text-muted">{t.passed}/{t.total}</span>}
                </li>
              ))}
            </ol>
          </>
        )}
      </Card>

      <div className="grid gap-6 md:grid-cols-2">
        <Card title="Concepts to revise" icon={<Network className="h-4 w-4" />}>
          {r.conceptsToRevise.length === 0 ? <p className="text-sm text-muted">Everything was fully solved.</p>
            : <div className="flex flex-wrap gap-1.5">{r.conceptsToRevise.map((c) => <Badge key={c}>{c}</Badge>)}</div>}
        </Card>
        <Card title="Recommended follow-up" icon={<Flame className="h-4 w-4" />}>
          {r.followUps.length === 0 ? <p className="text-sm text-muted">No unsolved follow-up problems for these concepts.</p> : (
            <ul className="space-y-2 text-sm">
              {r.followUps.map((f) => <li key={f.problemId}><Link className="font-medium text-accent hover:underline" href={`/problems/${f.problemId}`}>{f.title}</Link><p className="text-xs text-muted">{f.reason}</p></li>)}
            </ul>
          )}
        </Card>
      </div>
    </div>
  );
}

function Stat({ label, value, sub, icon }: { label: string; value: string; sub?: string; icon?: React.ReactNode }) {
  return (
    <div className="rounded-2xl border border-line bg-panel-2/60 px-4 py-3 sm:min-w-32">
      <p className="flex items-center gap-1.5 text-xs text-muted">{icon}{label}</p>
      <p className="mt-1 font-display text-xl font-bold tabular-nums">{value}</p>
      {sub && <p className="text-[11px] text-faint">{sub}</p>}
    </div>
  );
}
