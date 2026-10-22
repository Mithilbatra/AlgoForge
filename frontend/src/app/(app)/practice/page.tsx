"use client";

import Link from "next/link";
import { Flame, Hourglass, Scale, Waypoints } from "lucide-react";
import { RecommendationList } from "@/components/RecommendationList";
import { Card, Empty, ErrorNote, PageHeader, Skeleton } from "@/components/ui";
import type { Recommendation } from "@/lib/types";
import { useApi } from "@/lib/useApi";
import { useTitle } from "@/lib/useTitle";

const RULES = [
  { icon: Scale, title: "Only judged work counts", text: "Marking a linked problem as solved records your status, but only judged submissions are evidence." },
  { icon: Waypoints, title: "Transfer before mastery", text: "One accepted solution is never mastery. A concept turns strong only after you solve it in a less familiar setting." },
  { icon: Hourglass, title: "Evidence cools down", text: "Viewing a solution weakens the solve, and evidence fades over a 30-day half-life. That's why revision picks appear." },
];

export default function PracticePage() {
  useTitle("Practice");
  const recs = useApi<Recommendation[]>("/api/recommendations/today");
  const minutes = recs.data?.filter((r) => r.status === "PENDING" || r.status === "ACCEPTED").reduce((s, r) => s + (r.estMinutes ?? 20), 0) ?? 0;
  return (
    <div className="mx-auto max-w-5xl px-4 py-8 sm:px-8">
      <PageHeader kicker="Practice · daily forge" title="Today's training"
        subtitle="Transfer tests after recent solves first, then your goals and weak spots, overdue revision, and finally new concepts whose prerequisites are in place. Every pick says why it was chosen."
        actions={recs.data && recs.data.length > 0 ? <span className="flex items-center gap-2 rounded-full border border-accent/30 bg-accent/10 px-3 py-1.5 text-sm text-accent"><Flame className="h-4 w-4" aria-hidden />~{minutes} min left today</span> : undefined} />
      {recs.error ? <ErrorNote message={recs.error} onRetry={recs.reload} /> : !recs.data ? (
        <div className="space-y-3">{[0, 1, 2].map((i) => <Skeleton key={i} className="h-28 rounded-2xl" />)}</div>
      ) : recs.data.length === 0 ? (
        <Empty title="Nothing to recommend yet" icon={<Flame className="h-5 w-5" />}>Solve a problem from the <Link className="text-accent" href="/problems">Problem Hub</Link> and your session will adapt.</Empty>
      ) : <RecommendationList items={recs.data} onChange={(r) => recs.setData(r)} />}
      <Card title="How evidence is counted" className="mt-10">
        <div className="grid gap-5 md:grid-cols-3">
          {RULES.map(({ icon: Icon, title, text }) => (
            <div key={title}>
              <Icon className="h-5 w-5 text-accent" aria-hidden />
              <p className="mt-2 text-sm font-semibold">{title}</p>
              <p className="mt-1 text-sm leading-relaxed text-muted">{text}</p>
            </div>
          ))}
        </div>
      </Card>
    </div>
  );
}
