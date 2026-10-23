"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useEffect, useState } from "react";
import { BackLink, Badge, Card, cx, ErrorNote, Page, Ring, Spinner, Stat, StatStrip, TONE } from "@/components/ui";
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

  if (error) return <Page><ErrorNote message={error} /></Page>;
  if (!data) return <Page><Spinner label={waiting ? "Finalising: waiting for the last submissions to be judged…" : undefined} /></Page>;
  const r = data.report;
  const used = r.secondsUsed;

  return (
    <Page>
      <BackLink href="/oa">OA Arena</BackLink>
      <div className="mb-6 flex flex-wrap items-center gap-6">
        <Ring value={r.percentage} size={96} stroke={7} label={`Score ${r.percentage}%`}>
          <p className="text-xl font-semibold tabular-nums text-fg">{Math.round(r.percentage)}%</p>
        </Ring>
        <div className="min-w-0 flex-1">
          <p className="text-xs text-faint">Assessment report</p>
          <h1 className="mt-0.5 text-2xl font-semibold tracking-tight text-fg">{data.title}</h1>
          <p className="mt-1 text-sm text-muted">{new Date(data.startedAt).toLocaleString()} · {r.endReason === "EXPIRED" ? "time ran out" : "submitted"} after {formatDuration(used)}</p>
        </div>
      </div>

      <StatStrip className="mb-6">
        <Stat label="Score" value={`${r.totalScore} / ${r.maxScore}`} sub={`${r.percentage}%`} />
        <Stat label="Solved" value={r.solved} sub={`${r.partiallySolved} partially`} />
        <Stat label="Submissions" value={r.timeline.length} sub={`in ${formatDuration(used)}`} />
        <Stat label="Scoring" value={r.scoringRules.partialCredit ? "Partial" : "Strict"} sub={r.scoringRules.wrongPenaltyPct ? `−${r.scoringRules.wrongPenaltyPct}% per rejection` : "No penalty"} />
      </StatStrip>

      {r.insights.length > 0 && (
        <Card title="What to take away" className="mb-6">
          <ul className="space-y-2.5 text-sm">
            {r.insights.map((x, i) => (
              <li key={i} className="flex gap-3"><span className="mt-2 size-1.5 shrink-0 rounded-full bg-accent" aria-hidden /><span className="leading-relaxed text-fg">{x}</span></li>
            ))}
          </ul>
        </Card>
      )}

      <Card title="Problems" className="mb-6 overflow-hidden" padded={false}>
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead className="border-b border-line bg-panel-2/60 text-left text-xs text-muted">
              <tr>
                <th className="px-5 py-2.5 font-medium">Problem</th><th className="px-3 py-2.5 font-medium">Score</th><th className="px-3 py-2.5 font-medium">Time spent</th>
                <th className="px-3 py-2.5 font-medium">Attempts</th><th className="px-3 py-2.5 pr-5 font-medium">Evaluation</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-line">
              {r.problems.map((p) => (
                <tr key={p.problemId} className="align-top">
                  <td className="px-5 py-3"><Link className="font-medium text-fg underline-offset-4 hover:underline" href={`/problems/${p.problemId}`}>{p.title}</Link>
                    <div className="mt-1.5 flex flex-wrap gap-1">{Object.entries(p.verdicts).map(([v, n]) => <Badge key={v} tone={verdictTone(v)}>{n}× {VERDICT_LABEL[v]}</Badge>)}</div>
                    {(p.failedOnSamples > 0 || p.failedOnHiddenTests > 0) && <p className="mt-1.5 text-xs text-muted">Failed on visible samples {p.failedOnSamples}×, on hidden tests {p.failedOnHiddenTests}×</p>}
                  </td>
                  <td className="px-3 py-3 tabular-nums text-fg">{p.score} / {p.points}</td>
                  <td className="px-3 py-3 tabular-nums text-fg">{formatDuration(p.timeSpentSeconds)} <span className="text-xs text-faint">({Math.round((100 * p.timeSpentSeconds) / Math.max(1, used))}%)</span></td>
                  <td className="px-3 py-3 text-fg">{p.attempts}</td>
                  <td className="px-3 py-3 pr-5">
                    {p.evaluation ? <>
                      <Link className="font-medium text-accent hover:underline" href={`/evaluations/${p.evaluation.evaluationId}`}>{p.evaluation.overallScore.toFixed(0)}/100</Link>
                      {p.evaluation.staticTimeComplexity && <span className="ml-2 font-mono text-xs text-muted">{p.evaluation.staticTimeComplexity}</span>}
                      {p.evaluation.optimisationFindings.map((f, i) => <p key={i} className="mt-1 text-xs text-warn">{f}</p>)}
                    </> : <span className="text-faint">—</span>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Card>

      <Card title="Timeline" className="mb-6">
        {r.timeline.length === 0 ? <p className="text-sm text-muted">No submissions.</p> : (
          <>
            <div className="relative mb-5 h-8 rounded-md border border-line bg-panel-2/50" aria-hidden>
              {r.timeline.map((t) => (
                <span key={t.submissionId} title={`${formatDuration(t.atSeconds)} · ${t.title} · ${VERDICT_LABEL[t.status]}`}
                  className={cx("absolute top-1.5 h-5 w-1.5 -translate-x-1/2 rounded-sm ring-1 ring-inset", TONE[verdictTone(t.status)])}
                  style={{ left: `${Math.min(100, (100 * t.atSeconds) / Math.max(1, used))}%` }} />
              ))}
            </div>
            <ol className="divide-y divide-line text-sm">
              {r.timeline.map((t) => (
                <li key={t.submissionId} className="flex items-center gap-3 py-2">
                  <span className="w-14 font-mono text-xs tabular-nums text-muted">{formatDuration(t.atSeconds)}</span>
                  <span className="flex-1 truncate text-fg">{t.title}</span>
                  <Badge tone={verdictTone(t.status)}>{VERDICT_LABEL[t.status]}</Badge>
                  {t.total != null && <span className="w-12 text-right font-mono text-xs text-muted">{t.passed}/{t.total}</span>}
                </li>
              ))}
            </ol>
          </>
        )}
      </Card>

      <div className="grid gap-6 md:grid-cols-2">
        <Card title="Concepts to revise">
          {r.conceptsToRevise.length === 0 ? <p className="text-sm text-muted">Everything was fully solved.</p>
            : <div className="flex flex-wrap gap-1.5">{r.conceptsToRevise.map((c) => <Badge key={c}>{c}</Badge>)}</div>}
        </Card>
        <Card title="Recommended follow-up" padded={false}>
          {r.followUps.length === 0 ? <p className="p-5 text-sm text-muted">No unsolved follow-up problems for these concepts.</p> : (
            <ul className="divide-y divide-line text-sm">
              {r.followUps.map((f) => (
                <li key={f.problemId} className="px-5 py-3">
                  <Link className="font-medium text-fg underline-offset-4 hover:underline" href={`/problems/${f.problemId}`}>{f.title}</Link>
                  <p className="mt-0.5 text-xs text-muted">{f.reason}</p>
                </li>
              ))}
            </ul>
          )}
        </Card>
      </div>
    </Page>
  );
}
