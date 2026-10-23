"use client";

import { useEffect, useRef, useState } from "react";
import { cx } from "./ui";

export interface ActivityDay { day: string; judged: number; accepted: number }

const MAX_WEEKS = 53;
const LEVELS = ["bg-panel-3", "bg-accent/25", "bg-accent/45", "bg-accent/70", "bg-accent"];
const level = (judged: number) => (judged === 0 ? 0 : judged === 1 ? 1 : judged <= 3 ? 2 : judged <= 5 ? 3 : 4);
const iso = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;

/** Judged submissions per day, Monday-first columns like a contribution calendar; shows as many weeks as fit (up to a year). */
export function ActivityHeatmap({ days }: { days: ActivityDay[] }) {
  const [hover, setHover] = useState<{ x: number; y: number; text: string }>();
  const [weeks, setWeeks] = useState(26);
  const box = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const el = box.current;
    if (!el) return;
    // 15 px per column (12 px cell + 3 px gap) after the 32 px weekday labels.
    const ro = new ResizeObserver(([e]) => setWeeks(Math.max(8, Math.min(MAX_WEEKS, Math.floor((e.contentRect.width - 32) / 15)))));
    ro.observe(el);
    return () => ro.disconnect();
  }, []);

  const byDay = new Map(days.map((d) => [d.day, d]));
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  const start = new Date(today);
  start.setDate(start.getDate() - ((today.getDay() + 6) % 7) - 7 * (weeks - 1));  // the Monday weeks - 1 weeks before this week's

  const columns = Array.from({ length: weeks }, (_, w) => Array.from({ length: 7 }, (_, d) => {
    const date = new Date(start);
    date.setDate(start.getDate() + w * 7 + d);
    return date > today ? null : { date, ...(byDay.get(iso(date)) ?? { judged: 0, accepted: 0 }) };
  }));
  // A month label where a new month starts, unless the previous label is too close to fit.
  let lastLabel = -3;
  const months = columns.map((col, w) => {
    const first = col[0]!.date;
    const starts = w === 0 ? first.getDate() <= 21 : first.getMonth() !== columns[w - 1][0]!.date.getMonth();
    if (!starts || w - lastLabel < 3 || w > columns.length - 3) return "";
    lastLabel = w;
    return first.toLocaleDateString(undefined, { month: "short" });
  });
  const shown = columns.flat().filter((c) => c !== null);
  const judged = shown.reduce((s, c) => s + c.judged, 0);
  const accepted = shown.reduce((s, c) => s + c.accepted, 0);
  const active = shown.filter((c) => c.judged > 0).length;

  return (
    <div>
      <div ref={box} className="relative overflow-x-auto pb-1">
        <div className="relative inline-flex gap-[3px] pl-8" onMouseLeave={() => setHover(undefined)}
          role="img" aria-label={`${judged} judged submissions, ${accepted} accepted, over ${active} active days in the last ${weeks} weeks`}>
          <div className="absolute left-0 top-5 grid grid-rows-7 gap-[3px] text-[10px] leading-3 text-faint" aria-hidden>
            {["Mon", "", "Wed", "", "Fri", "", ""].map((d, i) => <span key={i} className="h-3">{d}</span>)}
          </div>
          {columns.map((col, w) => (
            <div key={w} className="grid grid-rows-[auto_repeat(7,12px)] gap-[3px] animate-enter" style={{ animationDelay: `${w * 14}ms` }} aria-hidden>
              <span className="h-[14px] w-3 overflow-visible whitespace-nowrap text-[10px] leading-3 text-faint">{months[w]}</span>
              {col.map((c, d) => c ? (
                <span key={d} className={cx("size-3 rounded-[3px] transition-transform hover:scale-125", LEVELS[level(c.judged)])}
                  onMouseEnter={(e) => {
                    const el = e.currentTarget;
                    setHover({ x: el.offsetLeft + (el.parentElement?.offsetLeft ?? 0), y: el.offsetTop,
                      text: `${c.judged === 0 ? "No submissions" : `${c.judged} judged, ${c.accepted} accepted`} · ${c.date.toLocaleDateString(undefined, { weekday: "short", month: "short", day: "numeric" })}` });
                  }} />
              ) : <span key={d} className="size-3" />)}
            </div>
          ))}
          {hover && (
            <span className="pointer-events-none absolute z-10 -translate-x-1/2 -translate-y-full whitespace-nowrap rounded-md border border-line-strong bg-panel px-2 py-1 text-xs text-fg shadow-lg animate-fade"
              style={{ left: hover.x + 6, top: hover.y - 6 }}>{hover.text}</span>
          )}
        </div>
      </div>
      <div className="mt-3 flex flex-wrap items-center justify-between gap-3 text-xs text-muted">
        <span><span className="font-medium text-fg tabular-nums">{judged}</span> judged · <span className="font-medium text-fg tabular-nums">{accepted}</span> accepted · <span className="font-medium text-fg tabular-nums">{active}</span> active days in {weeks} weeks</span>
        <span className="flex items-center gap-1 text-faint" aria-hidden>Less{LEVELS.map((l) => <i key={l} className={cx("size-2.5 rounded-[2px]", l)} />)}More</span>
      </div>
    </div>
  );
}
