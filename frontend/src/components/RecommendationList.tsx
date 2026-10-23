"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { ArrowRight, Clock, Crosshair, Repeat, Shuffle, Sparkles, Target, Waypoints, type LucideIcon } from "lucide-react";
import { errorMessage, post } from "@/lib/api";
import { difficultyTone } from "@/lib/format";
import type { Recommendation } from "@/lib/types";
import { Badge, Button, cx, ErrorNote, type Tone } from "./ui";

const KIND: Record<string, { label: string; icon: LucideIcon; tone: Tone }> = {
  TRANSFER: { label: "Transfer test", icon: Waypoints, tone: "accent" },
  WEAKNESS: { label: "Weak spot", icon: Crosshair, tone: "bad" },
  REVISION: { label: "Revision", icon: Repeat, tone: "violet" },
  GOAL: { label: "Goal", icon: Target, tone: "warn" },
  NEW: { label: "New concept", icon: Sparkles, tone: "info" },
};

/** Divided rows; the parent card supplies the border. Compact rows (dashboard) have no actions. */
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
    <div>
      {error && <div className="px-5 pt-4"><ErrorNote message={error} /></div>}
      <div className="stagger divide-y divide-line">
        {items.map((r) => {
          const k = KIND[r.kind] ?? KIND.NEW;
          const Icon = k.icon;
          const done = r.status === "SKIPPED" || r.status === "COMPLETED";
          const actionable = !compact && (r.status === "PENDING" || r.status === "ACCEPTED");
          return (
            <article key={r.id} data-rec aria-label={r.title} className={cx("flex gap-4 px-5 py-4 animate-enter", done && "opacity-60")}>
              <span className="mt-0.5 grid size-8 shrink-0 place-items-center rounded-md border border-line bg-panel-2 text-muted"><Icon className="size-4" aria-hidden /></span>
              <div className="min-w-0 flex-1 gap-4 sm:flex sm:items-start sm:justify-between">
                <div className="min-w-0">
                  <div className="flex flex-wrap items-center gap-2">
                    <Link href={`/problems/${r.problemId}`} className="text-sm font-semibold text-fg underline-offset-4 hover:underline">{r.title}</Link>
                    <Badge tone={k.tone}>{k.label}</Badge>
                    {r.status !== "PENDING" && <Badge>{r.status.toLowerCase()}</Badge>}
                  </div>
                  <p className="mt-1 text-[13px] leading-relaxed text-muted">{r.reason}</p>
                  <div className="mt-2 flex flex-wrap items-center gap-3 text-xs text-faint">
                    {r.difficulty && <Badge tone={difficultyTone(r.difficulty)}>{r.difficulty.toLowerCase()}</Badge>}
                    {r.estMinutes && <span className="flex items-center gap-1"><Clock className="size-3" aria-hidden />~{r.estMinutes} min</span>}
                  </div>
                </div>
                {actionable && (
                  <div className="mt-3 flex shrink-0 gap-2 sm:mt-0">
                    <Button size="sm" variant="ghost" disabled={busy === r.id} onClick={() => act(r, "skip")}>Skip</Button>
                    <Button size="sm" disabled={busy === r.id} onClick={() => act(r, "replace")}><Shuffle aria-hidden />Replace</Button>
                    <Button size="sm" variant="primary" disabled={busy === r.id} onClick={() => act(r, "accept")}>Start<ArrowRight aria-hidden /></Button>
                  </div>
                )}
              </div>
            </article>
          );
        })}
      </div>
    </div>
  );
}
