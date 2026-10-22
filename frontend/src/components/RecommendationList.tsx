"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { ArrowRight, Clock, Crosshair, Repeat, Shuffle, Sparkles, Target, Waypoints, type LucideIcon } from "lucide-react";
import { errorMessage, post } from "@/lib/api";
import { difficultyTone } from "@/lib/format";
import type { Recommendation } from "@/lib/types";
import { Badge, Button, cx, ErrorNote } from "./ui";

const KIND: Record<string, { label: string; icon: LucideIcon; tone: string }> = {
  TRANSFER: { label: "Transfer test", icon: Waypoints, tone: "text-accent bg-accent/12 border-accent/35" },
  WEAKNESS: { label: "Weak spot", icon: Crosshair, tone: "text-bad bg-bad/10 border-bad/30" },
  REVISION: { label: "Revision", icon: Repeat, tone: "text-violet bg-violet/10 border-violet/30" },
  GOAL: { label: "Goal", icon: Target, tone: "text-gold bg-gold/10 border-gold/30" },
  NEW: { label: "New concept", icon: Sparkles, tone: "text-cyan bg-cyan/10 border-cyan/30" },
};

export function RecommendationList({ items, onChange, compact = false }: { items: Recommendation[]; onChange: (r: Recommendation[]) => void; compact?: boolean }) {
  const router = useRouter();
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState<number>();

  async function act(r: Recommendation, action: "accept" | "skip" | "replace") {
    setBusy(r.id);
    setError(undefined);
    try {
      const updated = await post<Recommendation>(`/api/recommendations/${r.id}/${action}`);
      if (action === "accept") { router.push(`/problems/${r.problemId}`); return; }
      onChange(items.map((x) => (x.id === r.id ? updated : x)));  // a replacement takes the old item's place
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(undefined);
    }
  }

  return (
    <div className="space-y-3">
      {error && <ErrorNote message={error} />}
      {items.map((r, i) => {
        const k = KIND[r.kind] ?? KIND.NEW;
        const Icon = k.icon;
        const done = r.status === "SKIPPED" || r.status === "COMPLETED";
        return (
          <article key={r.id} data-rec aria-label={r.title} style={{ animationDelay: `${i * 60}ms` }}
            className={cx("group flex gap-4 rounded-2xl border border-line bg-panel-2/40 p-4 transition-colors hover:border-line-strong hover:bg-panel-2/70 animate-fade-up", done && "opacity-55")}>
            <span className={cx("grid h-10 w-10 shrink-0 place-items-center rounded-xl border", k.tone)}><Icon className="h-[18px] w-[18px]" aria-hidden /></span>
            <div className="min-w-0 flex-1">
              <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
                <span className={cx("text-[11px] font-semibold uppercase tracking-wider", k.tone.split(" ")[0])}>{k.label}</span>
                {r.status !== "PENDING" && <Badge>{r.status.toLowerCase()}</Badge>}
              </div>
              <Link href={`/problems/${r.problemId}`} className="mt-0.5 block font-display text-base font-semibold hover:text-accent">{r.title}</Link>
              <p className="mt-1 text-sm leading-relaxed text-muted">{r.reason}</p>
              <div className="mt-2 flex flex-wrap items-center gap-3 text-xs">
                {r.difficulty && <span className={cx("font-medium", difficultyTone(r.difficulty))}>{r.difficulty.toLowerCase()}</span>}
                {r.estMinutes && <span className="flex items-center gap-1 text-faint"><Clock className="h-3 w-3" aria-hidden />~{r.estMinutes} min</span>}
              </div>
              {!compact && (r.status === "PENDING" || r.status === "ACCEPTED") && (
                <div className="mt-3 flex gap-2">
                  <Button size="sm" variant="primary" disabled={busy === r.id} onClick={() => act(r, "accept")}>Start <ArrowRight className="h-3.5 w-3.5" aria-hidden /></Button>
                  <Button size="sm" disabled={busy === r.id} onClick={() => act(r, "replace")}><Shuffle className="h-3.5 w-3.5" aria-hidden />Replace</Button>
                  <Button size="sm" variant="ghost" disabled={busy === r.id} onClick={() => act(r, "skip")}>Skip</Button>
                </div>
              )}
            </div>
          </article>
        );
      })}
    </div>
  );
}
