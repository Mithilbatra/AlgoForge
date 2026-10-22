"use client";

import Link from "next/link";
import { Badge, Empty, ErrorNote, PageHeader, Spinner, Verdict } from "@/components/ui";
import { LANGUAGE_LABEL, relativeTime } from "@/lib/format";
import type { EvaluationSummary } from "@/lib/types";
import { useApi } from "@/lib/useApi";
import { useTitle } from "@/lib/useTitle";

const CORRECTNESS_TONE: Record<string, string> = {
  VERIFIED: "border-ok/40 text-ok", PROVISIONAL: "border-warn/40 text-warn", FAILED: "border-bad/40 text-bad",
};

export default function EvaluationsPage() {
  useTitle("Code Evaluations");
  const { data, error, reload } = useApi<EvaluationSummary[]>("/api/evaluations");
  return (
    <div className="mx-auto max-w-5xl px-4 py-8 sm:px-6">
      <PageHeader kicker="Code Evaluations · reports" title="How good was that code?" subtitle="Every judged submission gets a report built from execution evidence. Deep evaluations add generated tests, a benchmark and optional AI review." />
      {error ? <ErrorNote message={error} onRetry={reload} /> : !data ? <Spinner /> : data.length === 0 ? (
        <Empty title="No evaluations yet">Submit a solution in the <Link className="text-accent" href="/problems">Problem Hub</Link>.</Empty>
      ) : (
        <div className="surface overflow-hidden rounded-2xl">
          <table className="w-full text-sm">
            <thead className="border-b border-line text-left text-[11px] uppercase tracking-wider text-faint">
              <tr><th className="px-3 py-2 font-medium">Problem</th><th className="px-3 py-2 font-medium">Verdict</th><th className="px-3 py-2 font-medium">Score</th><th className="hidden px-3 py-2 font-medium sm:table-cell">Correctness</th><th className="hidden px-3 py-2 font-medium md:table-cell">Updated</th></tr>
            </thead>
            <tbody className="divide-y divide-line/70">
              {data.map((e) => (
                <tr key={e.id} className="transition-colors hover:bg-gradient-to-r hover:from-accent/[0.06] hover:to-transparent">
                  <td className="px-3 py-2.5"><Link className="font-medium hover:text-accent" href={`/evaluations/${e.id}`}>{e.problemTitle}</Link>
                    <span className="ml-2 text-xs text-faint">{LANGUAGE_LABEL[e.language]}{e.status.startsWith("DEEP") ? " · deep" : ""}</span></td>
                  <td className="px-3 py-2.5"><Verdict status={e.verdict} /></td>
                  <td className="px-3 py-2.5"><span className="font-display text-base font-bold tabular-nums ember-text">{Number(e.overallScore).toFixed(1)}</span></td>
                  <td className="hidden px-3 py-2.5 sm:table-cell"><Badge className={CORRECTNESS_TONE[e.correctness]}>{e.correctness.toLowerCase()}</Badge></td>
                  <td className="hidden px-3 py-2.5 text-muted md:table-cell">{relativeTime(e.updatedAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
