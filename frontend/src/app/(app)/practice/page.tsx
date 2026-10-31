"use client";

import Link from "next/link";
import { Flame, Hourglass, Scale, Waypoints } from "lucide-react";
import { RecommendationList } from "@/components/RecommendationList";
import { Badge, Card, Empty, ErrorNote, Page, PageHeader, Skeleton } from "@/components/ui";
import { tzQuery } from "@/lib/format";
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
  const recs = useApi<Recommendation[]>(`/api/recommendations/today?${tzQuery()}`);
  const minutes = recs.data?.filter((r) => r.status === "PENDING" || r.status === "ACCEPTED").reduce((s, r) => s + (r.estMinutes ?? 20), 0) ?? 0;
  return (
    <Page width="max-w-5xl">
      <PageHeader title="Today's training"
        description="Transfer tests after recent solves come first, then your goals and weak spots, overdue revision, and new concepts whose prerequisites are in place. Every pick says why it was chosen."
        actions={recs.data && recs.data.length > 0 ? <Badge className="h-7 px-2.5 text-[13px]"><Flame aria-hidden />~{minutes} min left today</Badge> : undefined} />
      {recs.error ? <ErrorNote message={recs.error} onRetry={recs.reload} /> : !recs.data ? (
        <Skeleton className="h-80" />
      ) : recs.data.length === 0 ? (
        <Empty title="Nothing to recommend yet" icon={<Flame aria-hidden />}>Solve a problem from the <Link className="text-accent hover:underline" href="/problems">Problem Hub</Link> and your session will adapt.</Empty>
      ) : (
        <Card title="Session" description={`${recs.data.length} picks for today`} padded={false}>
          <RecommendationList items={recs.data} onChange={(r) => recs.setData(r)} />
        </Card>
      )}
      <Card title="How evidence is counted" className="mt-6">
        <div className="grid gap-6 md:grid-cols-3">
          {RULES.map(({ icon: Icon, title, text }) => (
            <div key={title}>
              <span className="grid size-8 place-items-center rounded-md border border-line bg-panel-2 text-muted"><Icon className="size-4" aria-hidden /></span>
              <p className="mt-3 text-sm font-medium text-fg">{title}</p>
              <p className="mt-1 text-[13px] leading-relaxed text-muted">{text}</p>
            </div>
          ))}
        </div>
      </Card>
    </Page>
  );
}
