"use client";

import { useMemo, useState } from "react";
import { X } from "lucide-react";
import { Badge, Card, cx, ErrorNote, Meter, Page, PageHeader, Skeleton, type Tone } from "@/components/ui";
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

const STATUS: Record<string, { dot: string; bar: string; tone: Tone }> = {
  STRONG: { dot: "bg-ok", bar: "bg-ok", tone: "ok" },
  DEVELOPING: { dot: "bg-info", bar: "bg-info", tone: "info" },
  WEAK: { dot: "bg-bad", bar: "bg-bad", tone: "bad" },
  INSUFFICIENT_EVIDENCE: { dot: "bg-line-strong", bar: "bg-line-strong", tone: "neutral" },
};

const NODE_W = 184, NODE_H = 84, COL_GAP = 56, ROW_GAP = 12;

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

  if (error) return <Page><ErrorNote message={error} onRetry={reload} /></Page>;
  if (!data || !layout) return <Page width="max-w-7xl"><Skeleton className="mb-8 h-12 w-96" /><Skeleton className="h-[560px]" /></Page>;
  const sel = data.find((c) => c.code === selected);
  const focus = hover ?? selected;
  const related = (a: string, b: string) => focus != null && (a === focus || b === focus);

  return (
    <Page width="max-w-7xl">
      <PageHeader title="Learning Map"
        description="22 concepts ordered by prerequisites. Every estimate comes from judged evidence, fades without practice, and never jumps to strong from a single solve." />
      <div className="mb-3 flex flex-wrap items-center gap-x-4 gap-y-2 text-[13px]">
        {Object.entries(MASTERY_LABEL).map(([k, v]) => (
          <span key={k} className="flex items-center gap-1.5 text-muted"><span className={cx("size-2 rounded-full", STATUS[k].dot)} aria-hidden />{v}</span>
        ))}
        <span className="text-faint">Select a concept to highlight its prerequisites.</span>
      </div>
      <div className="relative">
        <div className="card overflow-x-auto p-6">
          <p className="mb-3 flex text-xs text-faint" style={{ width: layout.width, gap: COL_GAP }}>
            {layout.cols.map((_, i) => <span key={i} style={{ width: NODE_W }}>{i === 0 ? "Foundations" : `Level ${i}`}</span>)}
          </p>
          <div className="relative" style={{ width: layout.width, height: layout.height }}>
            <svg width={layout.width} height={layout.height} className="absolute inset-0" aria-hidden>
              {data.flatMap((c) => c.prerequisites.map((req) => {
                const a = layout.pos.get(req), b = layout.pos.get(c.code);
                if (!a || !b) return null;
                const x1 = a.x + NODE_W, y1 = a.y + NODE_H / 2, x2 = b.x, y2 = b.y + NODE_H / 2, mx = (x1 + x2) / 2;
                const lit = related(req, c.code);
                return (
                  <path key={`${req}-${c.code}`} d={`M${x1} ${y1} C${mx} ${y1}, ${mx} ${y2}, ${x2} ${y2}`} fill="none"
                    stroke={lit ? "var(--accent)" : "var(--line-strong)"} strokeWidth={lit ? 2 : 1}
                    strokeOpacity={focus && !lit ? 0.4 : 1} className={cx("transition-[stroke-opacity] duration-200", lit && "edge-flow")} />
                );
              }))}
            </svg>
            {data.map((c) => {
              const p = layout.pos.get(c.code)!;
              const st = STATUS[c.status];
              const dim = focus != null && focus !== c.code && !c.prerequisites.includes(focus) && !layout.byCode.get(focus)?.prerequisites.includes(c.code);
              const pct = c.status === "INSUFFICIENT_EVIDENCE" ? (c.evidence / 3) * 100 : (c.mastery ?? 0) * 100;
              return (
                <button key={c.code} onClick={() => setSelected(c.code === selected ? undefined : c.code)}
                  onMouseEnter={() => setHover(c.code)} onMouseLeave={() => setHover(undefined)} aria-pressed={selected === c.code}
                  style={{ left: p.x, top: p.y, width: NODE_W, height: NODE_H, animationDelay: `${(p.x / (NODE_W + COL_GAP)) * 70 + (p.y / (NODE_H + ROW_GAP)) * 15}ms` }}
                  className={cx("absolute rounded-md border bg-panel p-3 text-left transition-[opacity,border-color,box-shadow] duration-200 animate-enter",
                    selected === c.code ? "border-accent ring-2 ring-accent/25" : "border-line-strong hover:border-faint/60", dim && "opacity-40")}>
                  <div className="flex items-center justify-between gap-2">
                    <span className="truncate text-[13px] font-medium text-fg">{c.name}</span>
                    <span className={cx("size-2 shrink-0 rounded-full", st.dot)} aria-hidden />
                  </div>
                  <p className={cx("mt-0.5 text-xs", c.status === "INSUFFICIENT_EVIDENCE" ? "text-faint" : masteryTone(c.status))}>
                    {c.status === "INSUFFICIENT_EVIDENCE" ? (c.evidence > 0 ? `Evidence ${c.evidence} of 3` : "No evidence yet") : `${MASTERY_LABEL[c.status]} · ${Math.round(pct)}%`}
                  </p>
                  <div className="mt-2"><Meter label={`${c.name} ${c.status === "INSUFFICIENT_EVIDENCE" ? "evidence collected" : "mastery"}`} value={pct} tone={st.bar} /></div>
                  <p className="mt-1.5 flex items-center justify-between text-[11px] text-faint">
                    <span className="tabular-nums">{c.problemsSolved}/{c.problemsAvailable} solved</span>
                    {c.dueForRevision && <span className="text-warn">revise</span>}
                  </p>
                </button>
              );
            })}
          </div>
        </div>
        {sel && (
          <Card key={sel.code} title={sel.name} className="mt-6 lg:absolute lg:right-4 lg:top-4 lg:mt-0 lg:max-h-[calc(100%-2rem)] lg:w-[340px] lg:overflow-y-auto lg:shadow-lg"
            actions={<button onClick={() => setSelected(undefined)} className="grid size-7 place-items-center rounded-md text-faint hover:bg-panel-2 hover:text-fg" aria-label="Close concept detail"><X className="size-4" aria-hidden /></button>}>
            <div className="space-y-4 text-sm">
              <p className="text-muted">{sel.description}</p>
              <div className="flex flex-wrap items-center gap-2">
                <Badge tone={STATUS[sel.status].tone}>{MASTERY_LABEL[sel.status]}</Badge>
                <span className="text-xs text-muted">{sel.evidence} weighted evidence{sel.lastSuccessAt ? ` · last success ${relativeTime(sel.lastSuccessAt)}` : ""}</span>
              </div>
              {sel.prerequisites.length > 0 && <p className="text-xs text-faint">Builds on: {sel.prerequisites.map((r) => layout.byCode.get(r)?.name ?? r).join(", ")}</p>}
              {sel.status === "INSUFFICIENT_EVIDENCE" && <p className="text-xs text-faint">At least 3 weighted pieces of evidence are needed before an estimate is reported.</p>}
              {sel.status === "DEVELOPING" && (sel.mastery ?? 0) >= 0.7 && <p className="text-xs text-faint">High accuracy so far, but no successful transfer problem yet, so it is not marked strong.</p>}
              <ul className="space-y-4 border-t border-line pt-4">
                {sel.dimensions.map((d) => (
                  <li key={d.dimension}>
                    <div className="flex items-center justify-between text-[13px]">
                      <span className="font-medium text-fg">{d.dimension.replace("_", " ").toLowerCase().replace(/^./, (m) => m.toUpperCase())}</span>
                      <span className="font-mono text-xs text-muted">{d.estimate != null ? `${Math.round(d.estimate * 100)}% · ${d.evidence} ev.` : "no evidence"}</span>
                    </div>
                    <div className="mt-1.5"><Meter label={d.dimension} value={(d.estimate ?? 0) * 100} /></div>
                    <p className="mt-1 text-xs leading-snug text-faint">{DIMENSION_INFO[d.dimension]}</p>
                  </li>
                ))}
              </ul>
            </div>
          </Card>
        )}
      </div>
    </Page>
  );
}
