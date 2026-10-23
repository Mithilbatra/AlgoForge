"use client";

import Link from "next/link";
import { Badge, Empty, ErrorNote, Page, PageHeader, Skeleton, Verdict, type Tone } from "@/components/ui";
import { LANGUAGE_LABEL, relativeTime } from "@/lib/format";
import type { EvaluationSummary } from "@/lib/types";
import { useApi } from "@/lib/useApi";
import { useTitle } from "@/lib/useTitle";

const CORRECTNESS_TONE: Record<string, Tone> = { VERIFIED: "ok", PROVISIONAL: "warn", FAILED: "bad" };

export default function EvaluationsPage() {
  useTitle("Code Evaluations");
  const { data, error, reload } = useApi<EvaluationSummary[]>("/api/evaluations");
  return (
    <Page width="max-w-5xl">
      <PageHeader title="Code Evaluations"
        description="Every judged submission gets a report built from execution evidence. Deep evaluations add generated tests, a benchmark and optional AI review." />
      {error ? <ErrorNote message={error} onRetry={reload} /> : !data ? <Skeleton className="h-64" /> : data.length === 0 ? (
        <Empty title="No evaluations yet">Submit a solution in the <Link className="text-accent hover:underline" href="/problems">Problem Hub</Link>.</Empty>
      ) : (
        <div className="card overflow-hidden">
          <table className="w-full text-sm">
            <thead className="border-b border-line bg-panel-2/60 text-left text-xs text-muted">
              <tr>
                <th className="px-5 py-2.5 font-medium">Problem</th><th className="px-3 py-2.5 font-medium">Verdict</th><th className="px-3 py-2.5 text-right font-medium">Score</th>
                <th className="hidden px-3 py-2.5 font-medium sm:table-cell">Correctness</th><th className="hidden px-3 py-2.5 pr-5 text-right font-medium md:table-cell">Updated</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-line">
              {data.map((e) => (
                <tr key={e.id} className="transition-colors hover:bg-panel-2/60">
                  <td className="px-5 py-3"><Link className="font-medium text-fg underline-offset-4 hover:underline" href={`/evaluations/${e.id}`}>{e.problemTitle}</Link>
                    <span className="ml-2 text-xs text-faint">{LANGUAGE_LABEL[e.language]}{e.status.startsWith("DEEP") ? " · deep" : ""}</span></td>
                  <td className="px-3 py-3"><Verdict status={e.verdict} /></td>
                  <td className="px-3 py-3 text-right font-medium tabular-nums text-fg">{Number(e.overallScore).toFixed(1)}</td>
                  <td className="hidden px-3 py-3 sm:table-cell"><Badge tone={CORRECTNESS_TONE[e.correctness]}>{e.correctness.toLowerCase()}</Badge></td>
                  <td className="hidden px-3 py-3 pr-5 text-right text-[13px] text-muted md:table-cell">{relativeTime(e.updatedAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Page>
  );
}
