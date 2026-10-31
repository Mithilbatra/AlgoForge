"use client";

import Link from "next/link";
import { ArrowRight, Flame, Timer } from "lucide-react";
import { useMe } from "@/components/AppShell";
import { RecommendationList } from "@/components/RecommendationList";
import { ActivityHeatmap, type ActivityDay } from "@/components/ActivityHeatmap";
import { Badge, ButtonLink, Callout, Card, cx, Empty, ErrorNote, Meter, Page, PageHeader, Skeleton, Stat, StatStrip, Verdict } from "@/components/ui";
import { MASTERY_LABEL, masteryTone, relativeTime, tzQuery } from "@/lib/format";
import type { ConceptMastery, Page as Paged, ProblemSummary, Recommendation } from "@/lib/types";
import { useApi } from "@/lib/useApi";
import { useTitle } from "@/lib/useTitle";

interface Dashboard {
  recentSubmissions: { id: number; problemId: number; title: string; language: string; status: string; passed?: number; total?: number; createdAt: string; oaSessionId?: number }[];
  recentAssessments: { id: number; title: string; status: string; startedAt: string; totalScore?: number; maxScore?: number }[];
  recentEvaluations: { id: number; title: string; overallScore: number; correctness: string; status: string }[];
  preferences: { goalText?: string; goalDate?: string; dailyMinutes: number; showStreak: boolean };
  recommendations: Recommendation[];
  weakestConcepts: ConceptMastery[];
  conceptStatusCounts: Record<string, number>;
  revisionQueue: string[];
  trend: { week: string; accepted: number; judged: number; acceptRate: number }[];
  activity: ActivityDay[];
  timeInsights: { assessments: number; avgPercent?: number; avgMinutesUsed?: number };
  streakDays?: number;
}

const STATUS_ORDER = ["STRONG", "DEVELOPING", "WEAK", "INSUFFICIENT_EVIDENCE"];
const STATUS_FILL: Record<string, string> = { STRONG: "bg-ok", DEVELOPING: "bg-info", WEAK: "bg-bad", INSUFFICIENT_EVIDENCE: "bg-line-strong" };

function greeting() {
  const h = new Date().getHours();
  return h < 12 ? "Good morning" : h < 18 ? "Good afternoon" : "Good evening";
}

const linkAction = (href: string, label: string) => <Link href={href} className="text-[13px] font-medium text-muted transition-colors hover:text-fg">{label}</Link>;

/** Concept statuses as one stacked bar; every segment is a real count from the learning model. */
function MasteryBar({ counts }: { counts: Record<string, number> }) {
  const total = STATUS_ORDER.reduce((s, k) => s + (counts[k] ?? 0), 0) || 1;
  return (
    <div>
      <p className="text-sm text-muted"><span className="text-2xl font-semibold tabular-nums text-fg">{counts.STRONG ?? 0}</span> of {total} concepts strong</p>
      <div className="mt-3 flex h-2 gap-0.5 overflow-hidden rounded-full" role="img"
        aria-label={STATUS_ORDER.map((k) => `${counts[k] ?? 0} ${MASTERY_LABEL[k].toLowerCase()}`).join(", ")}>
        {STATUS_ORDER.map((k) => (counts[k] ? <div key={k} className={STATUS_FILL[k]} style={{ width: `${(100 * counts[k]) / total}%` }} /> : null))}
      </div>
      <ul className="mt-4 grid grid-cols-2 gap-x-4 gap-y-2 text-[13px]">
        {STATUS_ORDER.map((k) => (
          <li key={k} className="flex items-center gap-2">
            <span className={cx("size-2 shrink-0 rounded-full", STATUS_FILL[k])} aria-hidden />
            <span className="truncate text-muted">{k === "INSUFFICIENT_EVIDENCE" ? "No evidence" : MASTERY_LABEL[k]}</span>
            <span className="ml-auto font-medium tabular-nums text-fg">{counts[k] ?? 0}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}

export default function DashboardPage() {
  useTitle("Dashboard");
  const me = useMe();
  const { data, error, reload, setData } = useApi<Dashboard>(`/api/dashboard?${tzQuery()}`);
  const solved = useApi<Paged<ProblemSummary>>("/api/problems?status=SOLVED&size=1");

  if (error) return <Page><ErrorNote message={error} onRetry={reload} /></Page>;
  if (!data) {
    return (
      <Page>
        <Skeleton className="mb-8 h-12 w-80" />
        <Skeleton className="h-24" />
        <div className="stagger mt-6 grid gap-6 lg:grid-cols-3"><Skeleton className="h-72 lg:col-span-2" /><Skeleton className="h-72" /></div>
      </Page>
    );
  }
  const fresh = data.recentSubmissions.length === 0;
  const week = data.trend.at(-1);
  const first = me?.displayName?.split(" ")[0] ?? "";
  const today = new Date().toLocaleDateString(undefined, { weekday: "long", month: "long", day: "numeric" });

  return (
    <Page>
      <PageHeader
        title={`${greeting()}, ${first}`}
        description={data.preferences.goalText
          ? <>{today} · Goal: <span className="text-fg">{data.preferences.goalText}</span>{data.preferences.goalDate ? ` by ${new Date(data.preferences.goalDate).toLocaleDateString()}` : ""}</>
          : <>{today} · <Link className="text-accent hover:underline" href="/settings">Set a goal</Link> to steer your daily sessions.</>}
        actions={<>
          {data.streakDays != null && <Badge tone="accent" title="Days in a row with an accepted submission: consistency, not a measure of learning."><Flame aria-hidden />{data.streakDays}-day streak</Badge>}
          <ButtonLink href="/oa">OA Arena</ButtonLink>
          <ButtonLink href="/practice" variant="primary">Start today&apos;s session<ArrowRight aria-hidden /></ButtonLink>
        </>} />

      {fresh && (
        <div className="mb-6">
          <Callout icon={<Flame aria-hidden />}>
            <span className="font-medium text-fg">Get started. </span>Open a problem from the <Link className="text-accent hover:underline" href="/problems">Problem Hub</Link> or take a timed <Link className="text-accent hover:underline" href="/oa">assessment</Link>. Mastery estimates and trends appear once your code has been judged.
          </Callout>
        </div>
      )}

      <StatStrip>
        <Stat label="Problems solved" value={solved.data?.total ?? "–"} sub="Judged or marked solved" />
        <Stat label="Judged this week" value={week?.judged ?? 0} sub={week ? `${week.accepted} accepted` : "No submissions yet"} />
        <Stat label="Acceptance this week" value={week ? `${Math.round(week.acceptRate)}%` : "–"} sub="Of judged submissions" />
        <Stat label="Assessments" value={data.timeInsights.assessments} sub={data.timeInsights.assessments ? `${data.timeInsights.avgPercent}% average score` : "None completed yet"} />
      </StatStrip>

      <div className="stagger mt-6 grid gap-6 lg:grid-cols-3">
        <Card title="Today's session" className="lg:col-span-2" padded={false} actions={linkAction("/practice", "View all")}>
          {data.recommendations.length === 0 ? <p className="p-5 text-sm text-muted">No recommendations yet.</p>
            : <RecommendationList compact items={data.recommendations.slice(0, 3)} onChange={(r) => setData({ ...data, recommendations: r })} />}
        </Card>
        <Card title="Mastery" actions={linkAction("/learning", "Learning map")}>
          <MasteryBar counts={data.conceptStatusCounts} />
          {data.weakestConcepts.length > 0 && (
            <div className="mt-5 border-t border-line pt-4">
              <p className="mb-3 text-xs font-medium text-faint">Weakest concepts</p>
              <ul className="space-y-3">
                {data.weakestConcepts.map((c) => (
                  <li key={c.code} className="text-[13px]">
                    <div className="flex justify-between"><span className="text-fg">{c.name}</span><span className={cx("tabular-nums", masteryTone(c.status))}>{Math.round((c.mastery ?? 0) * 100)}%</span></div>
                    <div className="mt-1.5"><Meter label={`${c.name} mastery`} value={(c.mastery ?? 0) * 100} tone={c.status === "WEAK" ? "bg-bad" : "bg-info"} /></div>
                  </li>
                ))}
              </ul>
            </div>
          )}
          {data.revisionQueue.length > 0 && <p className="mt-4 text-xs text-warn">Due for revision: {data.revisionQueue.join(", ")}</p>}
        </Card>

        <Card title="Activity" description="Judged submissions per day. Volume shows practice, not learning." className="lg:col-span-2">
          <ActivityHeatmap days={data.activity ?? []} />
        </Card>
        <Card title="Time management">
          {data.timeInsights.assessments === 0 ? (
            <div className="flex items-start gap-3 text-sm text-muted"><Timer className="mt-0.5 size-4 shrink-0 text-faint" aria-hidden />Complete a timed assessment to see how you use the clock.</div>
          ) : (
            <div className="text-sm">
              <p className="text-3xl font-semibold tabular-nums tracking-tight text-fg">{data.timeInsights.avgPercent}%</p>
              <p className="mt-1 text-muted">average score, using {data.timeInsights.avgMinutesUsed} min on average across {data.timeInsights.assessments} assessment{data.timeInsights.assessments === 1 ? "" : "s"}.</p>
              <p className="mt-3 text-xs text-faint">Per-problem time splits are in each report.</p>
            </div>
          )}
        </Card>

        <Card title="Recent submissions" className="lg:col-span-2" padded={false}>
          {data.recentSubmissions.length === 0 ? <div className="p-5"><Empty title="No submissions yet">Your judged attempts will appear here.</Empty></div> : (
            <ul className="divide-y divide-line">
              {data.recentSubmissions.map((s) => (
                <li key={s.id}>
                  <Link href={`/problems/${s.problemId}`} className="flex items-center gap-3 px-5 py-3 text-sm transition-colors hover:bg-panel-2/60">
                    <span className="min-w-0 flex-1 truncate font-medium text-fg">{s.title}</span>
                    {s.oaSessionId && <Badge>Assessment</Badge>}
                    <Verdict status={s.status} />
                    <span className="hidden w-12 text-right font-mono text-xs text-muted sm:block">{s.total != null ? `${s.passed}/${s.total}` : ""}</span>
                    <span className="w-20 text-right text-xs text-faint">{relativeTime(s.createdAt)}</span>
                  </Link>
                </li>
              ))}
            </ul>
          )}
        </Card>
        <div className="space-y-6">
          <Card title="Assessments" padded={false} actions={linkAction("/oa", "Arena")}>
            {data.recentAssessments.length === 0 ? <p className="p-5 text-sm text-muted">None yet.</p> : (
              <ul className="divide-y divide-line">
                {data.recentAssessments.map((a) => (
                  <li key={a.id}>
                    <Link className="flex items-center justify-between gap-3 px-5 py-2.5 text-[13px] transition-colors hover:bg-panel-2/60" href={a.status === "COMPLETED" ? `/oa/${a.id}/report` : `/oa/${a.id}`}>
                      <span className="truncate text-fg">{a.title}</span>
                      <span className="shrink-0 font-mono text-xs text-muted">{a.status === "COMPLETED" ? `${Number(a.totalScore).toFixed(0)}/${a.maxScore}` : "in progress"}</span>
                    </Link>
                  </li>
                ))}
              </ul>
            )}
          </Card>
          <Card title="Recent evaluations" padded={false} actions={linkAction("/evaluations", "All")}>
            {data.recentEvaluations.length === 0 ? <p className="p-5 text-sm text-muted">None yet.</p> : (
              <ul className="divide-y divide-line">
                {data.recentEvaluations.map((e) => (
                  <li key={e.id}>
                    <Link className="flex items-center justify-between gap-3 px-5 py-2.5 text-[13px] transition-colors hover:bg-panel-2/60" href={`/evaluations/${e.id}`}>
                      <span className="truncate text-fg">{e.title}</span>
                      <span className="shrink-0 font-medium tabular-nums text-fg">{Number(e.overallScore).toFixed(0)}<span className="text-faint">/100</span></span>
                    </Link>
                  </li>
                ))}
              </ul>
            )}
          </Card>
        </div>
      </div>
    </Page>
  );
}
