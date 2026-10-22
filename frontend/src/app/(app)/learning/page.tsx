"use client";

import { useMemo, useState } from "react";
import { Network, X } from "lucide-react";
import { Badge, Card, cx, ErrorNote, Meter, PageHeader, Skeleton } from "@/components/ui";
import { MASTERY_LABEL, masteryTone, relativeTime } from "@/lib/format";
import type { ConceptMastery } from "@/lib/types";
import { useApi } from "@/lib/useApi";
import { useTitle } from "@/lib/useTitle";

const DIMENSION_INFO: Record<string, string> = {
  IMPLEMENTATION: "Accepted vs rejected judged submissions on problems using the concept.",
  TRANSFER: "Results on variant and disguised problems (tier 2–3), not the standard form.",
  RECOGNITION: "Solving without being told the technique: disguised problems and assessments, where topics are hidden.",
  TIME_PRESSURE: "Results inside timed assessments.",
  RETENTION: "Re-solving a problem a week or more after the first accepted solution.",
  COMPLEXITY: "Whether the complexity you state at submission matches the measured or high-confidence estimate.",
  EXPLANATION: "Not measured yet: AlgoForge has no graded explanation exercise, so this stays empty.",
};

const STATUS_STYLE: Record<string, { ring: string; dot: string; edge: string }> = {
  STRONG: { ring: "border-ok/50 shadow-[0_0_24px_-6px_rgb(52_211_153/0.55)]", dot: "bg-ok", edge: "#34d399" },
  DEVELOPING: { ring: "border-info/45 shadow-[0_0_24px_-8px_rgb(96_165_250/0.5)]", dot: "bg-info", edge: "#60a5fa" },
  WEAK: { ring: "border-bad/50 shadow-[0_0_24px_-6px_rgb(251_113_133/0.5)]", dot: "bg-bad", edge: "#fb7185" },
  INSUFFICIENT_EVIDENCE: { ring: "border-line-strong", dot: "bg-line-strong", edge: "#2b3341" },
};

const NODE_W = 176, NODE_H = 88, COL_GAP = 52, ROW_GAP = 14;

export default function LearningMap() {
  useTitle("Learning Map");
  const { data, error, reload } = useApi<ConceptMastery[]>("/api/learning/concepts");
  const [selected, setSelected] = useState<string>();
  const [hover, setHover] = useState<string>();

  // Columns by prerequisite depth (foundations on the left), each column centred vertically.
  const layout = useMemo(() => {
    if (!data) return null;
    const byCode = new Map(data.map((c) => [c.code, c]));
    const depth = new Map<string, number>();
    const d = (code: string): number => {
      if (depth.has(code)) return depth.get(code)!;
      const c = byCode.get(code);
      const v = !c || c.prerequisites.length === 0 ? 0 : 1 + Math.max(...c.prerequisites.map(d));
      depth.set(code, v);
      return v;
    };
    const cols: ConceptMastery[][] = [];
    data.forEach((c) => { (cols[d(c.code)] ??= []).push(c); });
    const maxRows = Math.max(...cols.map((c) => c.length));
    const height = maxRows * (NODE_H + ROW_GAP) - ROW_GAP;
    const pos = new Map<string, { x: number; y: number }>();
    cols.forEach((col, i) => {
      const offset = (height - (col.length * (NODE_H + ROW_GAP) - ROW_GAP)) / 2;
      col.forEach((c, j) => pos.set(c.code, { x: i * (NODE_W + COL_GAP), y: offset + j * (NODE_H + ROW_GAP) }));
    });
    return { cols, pos, byCode, width: cols.length * (NODE_W + COL_GAP) - COL_GAP, height };
  }, [data]);

  if (error) return <div className="p-6"><ErrorNote message={error} onRetry={reload} /></div>;
  if (!data || !layout) return <div className="mx-auto max-w-7xl space-y-4 px-4 py-8 sm:px-8"><Skeleton className="h-16 w-96" /><Skeleton className="h-[560px] rounded-2xl" /></div>;
  const sel = data.find((c) => c.code === selected);
  const focus = hover ?? selected;
  const related = (a: string, b: string) => focus != null && (a === focus || b === focus);

  return (
    <div className="mx-auto max-w-[1500px] px-4 py-8 sm:px-8">
      <PageHeader kicker="Learning Map · concept graph" title="Your algorithm constellation"
        subtitle="22 concepts ordered by prerequisites. Every estimate comes from judged evidence, fades without practice, and never jumps to 'strong' from a single solve." />
      <div className="mb-4 flex flex-wrap gap-4 text-xs">
        {Object.entries(MASTERY_LABEL).map(([k, v]) => (
          <span key={k} className="flex items-center gap-1.5 text-muted"><span className={cx("h-2.5 w-2.5 rounded-full", STATUS_STYLE[k].dot)} aria-hidden />{v}</span>
        ))}
        <span className="text-faint">Select a concept to light up its prerequisites.</span>
      </div>
      <div className="relative">
        <div className="surface overflow-x-auto rounded-2xl p-6">
          <p className="mb-3 flex gap-[52px] font-mono text-[10px] uppercase tracking-[0.2em] text-faint" style={{ width: layout.width }}>
            {layout.cols.map((_, i) => <span key={i} style={{ width: NODE_W }}>{i === 0 ? "Foundations" : `Level ${i}`}</span>)}
          </p>
          <div className="relative" style={{ width: layout.width, height: layout.height }}>
            <svg width={layout.width} height={layout.height} className="absolute inset-0" aria-hidden>
              <defs>
                <linearGradient id="edge-ember" x1="0" y1="0" x2="1" y2="0"><stop offset="0" stopColor="#ffc35a" /><stop offset="1" stopColor="#ff4d6d" /></linearGradient>
              </defs>
              {data.flatMap((c) => c.prerequisites.map((req) => {
                const a = layout.pos.get(req), b = layout.pos.get(c.code);
                if (!a || !b) return null;
                const x1 = a.x + NODE_W, y1 = a.y + NODE_H / 2, x2 = b.x, y2 = b.y + NODE_H / 2, mx = (x1 + x2) / 2;
                const lit = related(req, c.code);
                return (
                  <path key={`${req}-${c.code}`} d={`M${x1} ${y1} C${mx} ${y1}, ${mx} ${y2}, ${x2} ${y2}`} fill="none"
                    stroke={lit ? "url(#edge-ember)" : STATUS_STYLE[layout.byCode.get(req)?.status ?? "INSUFFICIENT_EVIDENCE"].edge}
                    strokeWidth={lit ? 2.5 : 1.25} strokeOpacity={focus && !lit ? 0.25 : 0.9} className="transition-all duration-300" />
                );
              }))}
            </svg>
            {data.map((c) => {
              const p = layout.pos.get(c.code)!;
              const st = STATUS_STYLE[c.status];
              const dim = focus != null && focus !== c.code && !c.prerequisites.includes(focus) && !layout.byCode.get(focus)?.prerequisites.includes(c.code);
              return (
                <button key={c.code} onClick={() => setSelected(c.code === selected ? undefined : c.code)}
                  onMouseEnter={() => setHover(c.code)} onMouseLeave={() => setHover(undefined)} aria-pressed={selected === c.code}
                  style={{ left: p.x, top: p.y, width: NODE_W, height: NODE_H }}
                  className={cx("absolute rounded-2xl border bg-panel-2/95 p-3 text-left transition-all duration-300",
                    selected === c.code ? "ember-border shadow-[0_0_30px_-6px_rgb(255_122_47/0.6)]" : st.ring, dim && "opacity-40")}>
                  <div className="flex items-center justify-between gap-2">
                    <span className="truncate text-[13px] font-semibold">{c.name}</span>
                    <span className={cx("h-2 w-2 shrink-0 rounded-full", st.dot)} aria-hidden />
                  </div>
                  {c.status === "INSUFFICIENT_EVIDENCE" ? (<>
                    <p className="mt-1 text-[11px] text-faint">{c.evidence > 0 ? `Evidence ${c.evidence} of 3` : "No evidence yet"}</p>
                    <div className="mt-2"><Meter label={`${c.name} evidence collected`} value={(c.evidence / 3) * 100} tone="bg-line-strong" /></div>
                  </>) : (<>
                    <p className={cx("mt-1 text-[11px]", masteryTone(c.status))}>{MASTERY_LABEL[c.status]} · {Math.round((c.mastery ?? 0) * 100)}%</p>
                    <div className="mt-2"><Meter label={`${c.name} mastery`} value={(c.mastery ?? 0) * 100} tone={c.status === "STRONG" ? "bg-ok" : c.status === "WEAK" ? "bg-bad" : "bg-info"} /></div>
                  </>)}
                  <p className="mt-1.5 flex items-center justify-between text-[10px] text-faint">
                    <span>{c.problemsSolved}/{c.problemsAvailable} solved</span>
                    {c.dueForRevision && <span className="text-warn">revise</span>}
                  </p>
                </button>
              );
            })}
          </div>
        </div>
        {sel && (
        <Card title={sel.name} icon={<Network className="h-4 w-4" />} glow
          className="mt-6 lg:absolute lg:right-4 lg:top-4 lg:mt-0 lg:max-h-[calc(100%-2rem)] lg:w-[340px] lg:overflow-y-auto animate-fade-up"
          actions={<button onClick={() => setSelected(undefined)} className="rounded-lg p-1 text-faint hover:bg-panel-3 hover:text-fg" aria-label="Close concept detail"><X className="h-4 w-4" aria-hidden /></button>}>
          {(
            <div className="space-y-4 text-sm">
              <p className="text-muted">{sel.description}</p>
              <div className="flex flex-wrap items-center gap-2">
                <Badge className={cx(masteryTone(sel.status), "border-current/30")}>{MASTERY_LABEL[sel.status]}</Badge>
                <span className="text-xs text-muted">{sel.evidence} weighted evidence{sel.lastSuccessAt ? ` · last success ${relativeTime(sel.lastSuccessAt)}` : ""}</span>
              </div>
              {sel.prerequisites.length > 0 && <p className="text-xs text-faint">Builds on: {sel.prerequisites.map((r) => layout.byCode.get(r)?.name ?? r).join(", ")}</p>}
              {sel.status === "INSUFFICIENT_EVIDENCE" && <p className="text-xs text-faint">At least 3 weighted pieces of evidence are needed before an estimate is reported.</p>}
              {sel.status === "DEVELOPING" && (sel.mastery ?? 0) >= 0.7 && <p className="text-xs text-faint">High accuracy so far, but no successful transfer problem yet, so it is not marked strong.</p>}
              <ul className="space-y-3.5">
                {sel.dimensions.map((d) => (
                  <li key={d.dimension}>
                    <div className="flex items-center justify-between text-xs">
                      <span className="font-medium text-fg">{d.dimension.replace("_", " ").toLowerCase().replace(/^./, (m) => m.toUpperCase())}</span>
                      <span className="font-mono text-muted">{d.estimate != null ? `${Math.round(d.estimate * 100)}% · ${d.evidence} ev.` : "no evidence"}</span>
                    </div>
                    <div className="mt-1.5"><Meter label={d.dimension} value={(d.estimate ?? 0) * 100} /></div>
                    <p className="mt-1 text-[11px] leading-snug text-faint">{DIMENSION_INFO[d.dimension]}</p>
                  </li>
                ))}
              </ul>
            </div>
          )}
        </Card>
        )}
      </div>
    </div>
  );
}
