"use client";

import { useEffect, useState } from "react";
import { formatDuration } from "@/lib/format";
import { cx } from "./ui";

const C = 2 * Math.PI * 8;

/**
 * Display only: counts down from the server's remainingMs, anchored to when it was received, with a ring that empties
 * as time runs out (amber in the last five minutes, red and pulsing in the last one). The server decides when time is
 * up; this component just calls onExpire so the page re-fetches.
 */
export function Countdown({ remainingMs, receivedAt, onExpire, totalMs }: { remainingMs: number; receivedAt: number; onExpire: () => void; totalMs?: number }) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 250);
    return () => clearInterval(t);
  }, []);
  const left = Math.max(0, remainingMs - (now - receivedAt));
  useEffect(() => {
    if (left === 0) onExpire();
    // fire once when it reaches zero
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [left === 0]);
  const tone = left < 60_000 ? "text-bad" : left < 5 * 60_000 ? "text-warn" : "text-fg";
  return (
    <span className="flex items-center gap-2">
      {totalMs != null && totalMs > 0 && (
        <svg viewBox="0 0 20 20" className={cx("size-4 -rotate-90", tone, left < 60_000 && "animate-pulse")} aria-hidden>
          <circle cx="10" cy="10" r="8" fill="none" stroke="var(--panel-3)" strokeWidth="3" />
          <circle cx="10" cy="10" r="8" fill="none" stroke="currentColor" strokeWidth="3" strokeLinecap="round"
            strokeDasharray={C} strokeDashoffset={C * (1 - Math.min(1, left / totalMs))} className="transition-[stroke-dashoffset] duration-300" />
        </svg>
      )}
      <span role="timer" aria-live="off" aria-label="Time remaining" className={cx("font-mono text-sm font-medium tabular-nums", tone)}>
        {formatDuration(left / 1000)}
      </span>
    </span>
  );
}
