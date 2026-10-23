"use client";

import { useEffect, useState } from "react";
import { formatDuration } from "@/lib/format";
import { cx } from "./ui";

/**
 * Display only: counts down from the server's remainingMs, anchored to when it was received.
 * The server decides when time is up; this component just calls onExpire so the page re-fetches.
 */
export function Countdown({ remainingMs, receivedAt, onExpire }: { remainingMs: number; receivedAt: number; onExpire: () => void }) {
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
  return (
    <span role="timer" aria-live="off" aria-label="Time remaining"
      className={cx("font-mono text-sm font-medium tabular-nums", left < 60_000 ? "text-bad" : left < 5 * 60_000 ? "text-warn" : "text-fg")}>
      {formatDuration(left / 1000)}
    </span>
  );
}
