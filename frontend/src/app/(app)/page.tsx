"use client";

import Link from "next/link";
import { ArrowRight, CheckCircle2, ClipboardList, Flame, Gauge, Medal, Swords, Timer, TrendingUp } from "lucide-react";
import { useMe } from "@/components/AppShell";
import { LogoMark } from "@/components/Logo";
import { RecommendationList } from "@/components/RecommendationList";
import { TrendChart } from "@/components/TrendChart";
import { Badge, Button, Card, Empty, ErrorNote, Meter, Skeleton, StatTile, Verdict } from "@/components/ui";
import { MASTERY_LABEL, masteryTone, relativeTime } from "@/lib/format";
import type { ConceptMastery, Page, ProblemSummary, Recommendation } from "@/lib/types";
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
  timeInsights: { assessments: number; avgPercent?: number; avgMinutesUsed?: number };
  streakDays?: number;
}

const STATUS_COLOR: Record<string, string> = { STRONG: "#34d399", DEVELOPING: "#60a5fa", WEAK: "#fb7185", INSUFFICIENT_EVIDENCE: "#2b3341" };
const STATUS_ORDER = ["STRONG", "DEVELOPING", "WEAK", "INSUFFICIENT_EVIDENCE"];

function greeting() {
  const h = new Date().getHours();
  return h < 5 ? "Burning the midnight oil" : h < 12 ? "Good morning" : h < 18 ? "Good afternoon" : "Good evening";
}

/** Concept-status donut drawn with a conic gradient; every segment is a real count from the learning model. */
function MasteryDonut({ counts }: { counts: Record<string, number> }) {
  const total = STATUS_ORDER.reduce((s, k) => s + (counts[k] ?? 0), 0) || 1;
  const ends = STATUS_ORDER.map((_, i) => STATUS_ORDER.slice(0, i + 1).reduce((s, k) => s + (counts[k] ?? 0), 0));
  const stops = STATUS_ORDER.map((k, i) => `${STATUS_COLOR[k]} ${((ends[i - 1] ?? 0) / total) * 360}deg ${(ends[i] / total) * 360}deg`).join(", ");
  return (
    <div className="flex items-center gap-5">
      <div className="relative h-32 w-32 shrink-0 rounded-full" style={{ background: `conic-gradient(${stops})` }} role="img"
        aria-label={STATUS_ORDER.map((k) => `${counts[k] ?? 0} ${MASTERY_LABEL[k].toLowerCase()}`).join(", ")}>
        <div className="absolute inset-[11px] grid place-items-center rounded-full bg-panel text-center">
          <div><p className="font-display text-3xl font-bold leading-none">{counts.STRONG ?? 0}</p><p className="mt-1 text-[10px] uppercase tracking-wider text-faint">strong</p></div>
        </div>
      </div>
      <ul className="space-y-1.5 text-sm">
        {STATUS_ORDER.map((k) => (
          <li key={k} className="flex items-center gap-2">
            <span className="h-2.5 w-2.5 rounded-full" style={{ background: STATUS_COLOR[k] }} aria-hidden />
            <span className="text-muted">{MASTERY_LABEL[k]}</span>
            <span className="ml-auto pl-3 font-medium tabular-nums">{counts[k] ?? 0}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}

export default function DashboardPage() {
  useTitle("Dashboard");
  const me = useMe();
  const { data, error, reload, setData } = useApi<Dashboard>("/api/dashboard");
  const solved = useApi<Page<ProblemSummary>>("/api/problems?status=SOLVED&size=1");

  if (error) return <div className="p-6"><ErrorNote message={error} onRetry={reload} /></div>;
  if (!data) {
    return (
      <div className="mx-auto max-w-7xl space-y-6 px-4 py-8 sm:px-8">
        <Skeleton className="h-44 rounded-3xl" />
        <div className="grid gap-4 sm:grid-cols-4">{[0, 1, 2, 3].map((i) => <Skeleton key={i} className="h-24 rounded-2xl" />)}</div>
        <Skeleton className="h-72 rounded-2xl" />
      </div>
    );
  }
  const fresh = data.recentSubmissions.length === 0;
  const week = data.trend.at(-1);
  const first = me?.displayName?.split(" ")[0] ?? "";

  return (
    <div className="mx-auto max-w-7xl px-4 py-8 sm:px-8">
      <section className="relative mb-6 overflow-hidden rounded-3xl ember-border p-7 sm:p-9 animate-fade-up">
        <div className="pointer-events-none absolute -right-10 -top-16 opacity-[0.08]" aria-hidden><LogoMark size={300} tile={false} /></div>
        <div className="pointer-events-none absolute inset-0 bg-[radial-gradient(600px_220px_at_0%_0%,rgb(255_122_47/0.18),transparent_70%)]" aria-hidden />
        <div className="relative flex flex-wrap items-end justify-between gap-6">
          <div className="max-w-2xl">
            <p className="font-mono text-[11px] uppercase tracking-[0.22em] text-accent">{new Date().toLocaleDateString(undefined, { weekday: "long", month: "long", day: "numeric" })}</p>
            <h1 className="mt-2 text-3xl font-bold tracking-tight sm:text-4xl">{greeting()}, <span className="ember-text">{first}</span></h1>
            <p className="mt-2 text-[15px] text-muted">
              {data.preferences.goalText
                ? <>Goal: <span className="text-fg">{data.preferences.goalText}</span>{data.preferences.goalDate ? ` · ${new Date(data.preferences.goalDate).toLocaleDateString()}` : ""}</>
                : <>Set a goal in <Link className="text-accent hover:underline" href="/settings">Settings</Link> to steer your daily sessions.</>}
            </p>
          </div>
          <div className="flex flex-wrap items-center gap-3">
            {data.streakDays != null && (
              <Badge className="border-accent/40 bg-accent/10 text-accent" title="Days in a row with an accepted submission: consistency, not a measure of learning.">
                <Flame className="h-3.5 w-3.5" aria-hidden />{data.streakDays}-day streak
              </Badge>
            )}
            <Link href="/practice"><Button variant="primary" size="lg">Start today&apos;s session <ArrowRight className="h-4 w-4" aria-hidden /></Button></Link>
            <Link href="/oa"><Button size="lg"><Swords className="h-4 w-4" aria-hidden />OA Arena</Button></Link>
          </div>
        </div>
      </section>

      {fresh && (
        <div className="mb-6 flex items-start gap-3 rounded-2xl border border-accent/30 bg-accent/[0.07] p-4 text-sm">
          <Flame className="mt-0.5 h-5 w-5 shrink-0 text-accent" aria-hidden />
          <p className="text-muted"><span className="font-semibold text-fg">Light the forge. </span>Open a problem from the <Link className="text-accent hover:underline" href="/problems">Problem Hub</Link> or take a timed <Link className="text-accent hover:underline" href="/oa">assessment</Link>. Mastery estimates and trends appear once your code has been judged.</p>
        </div>
      )}

      <div className="mb-6 grid grid-cols-2 gap-4 lg:grid-cols-4">
        <StatTile label="Problems solved" value={solved.data?.total ?? "–"} icon={<CheckCircle2 className="h-4 w-4" aria-hidden />} accent="text-ok" sub="Judged here and accepted" />
        <StatTile label="Judged this week" value={week?.judged ?? 0} icon={<Gauge className="h-4 w-4" aria-hidden />} accent="text-info" sub={week ? `${week.accepted} accepted` : "No submissions yet"} />
        <StatTile label="Acceptance this week" value={week ? `${Math.round(week.acceptRate)}%` : "–"} icon={<TrendingUp className="h-4 w-4" aria-hidden />} sub="Of judged submissions" />
        <StatTile label="Assessments" value={data.timeInsights.assessments} icon={<Medal className="h-4 w-4" aria-hidden />} accent="text-gold"
          sub={data.timeInsights.assessments ? `${data.timeInsights.avgPercent}% average score` : "None completed yet"} />
      </div>

      <div className="grid gap-6 lg:grid-cols-3">
        <Card title="Today's session" icon={<Flame className="h-4 w-4" />} className="lg:col-span-2" actions={<Link href="/practice" className="text-xs font-medium text-accent hover:underline">Open practice →</Link>}>
          {data.recommendations.length === 0 ? <p className="text-sm text-muted">No recommendations yet.</p>
            : <RecommendationList compact items={data.recommendations.slice(0, 3)} onChange={(r) => setData({ ...data, recommendations: r })} />}
        </Card>
        <Card title="Mastery" icon={<Medal className="h-4 w-4" />} actions={<Link href="/learning" className="text-xs font-medium text-accent hover:underline">Learning map →</Link>}>
          <MasteryDonut counts={data.conceptStatusCounts} />
          {data.weakestConcepts.length > 0 && (
            <ul className="mt-5 space-y-3 border-t border-line pt-4">
              <li className="text-[11px] font-medium uppercase tracking-wider text-faint">Weakest concepts</li>
              {data.weakestConcepts.map((c) => (
                <li key={c.code} className="text-sm">
                  <div className="flex justify-between"><span>{c.name}</span><span className={masteryTone(c.status)}>{Math.round((c.mastery ?? 0) * 100)}%</span></div>
                  <div className="mt-1.5"><Meter label={`${c.name} mastery`} value={(c.mastery ?? 0) * 100} tone={c.status === "WEAK" ? "bg-bad" : "bg-info"} /></div>
                </li>
              ))}
            </ul>
          )}
          {data.revisionQueue.length > 0 && <p className="mt-3 text-xs text-warn">Due for revision: {data.revisionQueue.join(", ")}</p>}
        </Card>

        <Card title="Activity" icon={<TrendingUp className="h-4 w-4" />} className="lg:col-span-2"
          actions={<span className="flex items-center gap-3 text-[11px] text-faint"><span className="flex items-center gap-1"><i className="h-2 w-2 rounded-full bg-info" />Judged</span><span className="flex items-center gap-1"><i className="h-2 w-2 rounded-full bg-accent" />Accepted</span></span>}>
          {data.trend.length === 0 ? <Empty title="No activity in the last 8 weeks" icon={<TrendingUp className="h-5 w-5" />} /> : <TrendChart data={data.trend} />}
          <p className="mt-2 text-xs text-faint">Volume shows activity, not learning. The Learning Map tracks what you can transfer to new problems.</p>
        </Card>
        <Card title="Time management" icon={<Timer className="h-4 w-4" />}>
          {data.timeInsights.assessments === 0 ? <p className="text-sm text-muted">Complete a timed assessment to see how you use the clock.</p> : (
            <div className="space-y-2 text-sm">
              <p className="font-display text-4xl font-bold tabular-nums">{data.timeInsights.avgPercent}<span className="text-xl text-faint">%</span></p>
              <p className="text-muted">average assessment score, using {data.timeInsights.avgMinutesUsed} min on average across {data.timeInsights.assessments} assessment{data.timeInsights.assessments === 1 ? "" : "s"}.</p>
              <p className="text-xs text-faint">Per-problem time splits live in each report.</p>
            </div>
          )}
        </Card>

        <Card title="Recent submissions" icon={<ClipboardList className="h-4 w-4" />} className="lg:col-span-2">
          {data.recentSubmissions.length === 0 ? <Empty title="No submissions yet">Your judged attempts will appear here.</Empty> : (
            <ul className="-mx-2">
              {data.recentSubmissions.map((s) => (
                <li key={s.id}>
                  <Link href={`/problems/${s.problemId}`} className="flex items-center gap-3 rounded-xl px-2 py-2.5 text-sm transition-colors hover:bg-panel-2">
                    <Verdict status={s.status} />
                    <span className="flex-1 truncate font-medium">{s.title}</span>
                    {s.oaSessionId && <Badge>assessment</Badge>}
                    {s.total != null && <span className="font-mono text-xs text-muted">{s.passed}/{s.total}</span>}
                    <span className="w-20 text-right text-xs text-faint">{relativeTime(s.createdAt)}</span>
                  </Link>
                </li>
              ))}
            </ul>
          )}
        </Card>
        <div className="space-y-6">
          <Card title="Assessments" icon={<Swords className="h-4 w-4" />}>
            {data.recentAssessments.length === 0 ? <p className="text-sm text-muted">None yet.</p> : (
              <ul className="space-y-1">
                {data.recentAssessments.map((a) => (
                  <li key={a.id}>
                    <Link className="flex justify-between gap-2 rounded-lg px-2 py-1.5 text-sm hover:bg-panel-2" href={a.status === "COMPLETED" ? `/oa/${a.id}/report` : `/oa/${a.id}`}>
                      <span className="truncate">{a.title}</span>
                      <span className="font-mono text-xs text-muted">{a.status === "COMPLETED" ? `${Number(a.totalScore).toFixed(0)}/${a.maxScore}` : "in progress"}</span>
                    </Link>
                  </li>
                ))}
              </ul>
            )}
          </Card>
          <Card title="Recent evaluations" icon={<Gauge className="h-4 w-4" />}>
            {data.recentEvaluations.length === 0 ? <p className="text-sm text-muted">None yet.</p> : (
              <ul className="space-y-1">
                {data.recentEvaluations.map((e) => (
                  <li key={e.id}>
                    <Link className="flex items-center justify-between gap-2 rounded-lg px-2 py-1.5 text-sm hover:bg-panel-2" href={`/evaluations/${e.id}`}>
                      <span className="truncate">{e.title}</span>
                      <span className="font-display text-sm font-semibold tabular-nums ember-text">{Number(e.overallScore).toFixed(0)}</span>
                    </Link>
                  </li>
                ))}
              </ul>
            )}
          </Card>
        </div>
      </div>
    </div>
  );
}
