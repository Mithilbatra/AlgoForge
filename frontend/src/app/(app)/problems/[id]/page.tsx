"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { ArrowLeft, Bot, Flame } from "lucide-react";
import { Workspace } from "@/components/Workspace";
import type { Preferences } from "@/lib/types";
import { useApi } from "@/lib/useApi";
import { useTitle } from "@/lib/useTitle";

export default function PracticeProblem() {
  const { id } = useParams<{ id: string }>();
  const prefs = useApi<Preferences>("/api/me/preferences");
  useTitle("Practice");
  if (!prefs.data && !prefs.error) return null;
  return (
    <div className="flex h-full flex-col">
      <div className="flex shrink-0 items-center gap-4 border-b border-line bg-panel/70 px-4 py-2 text-sm backdrop-blur">
        <Link href="/problems" className="flex items-center gap-1.5 text-muted transition-colors hover:text-fg"><ArrowLeft className="h-4 w-4" aria-hidden />Problem Hub</Link>
        <span className="hidden items-center gap-1.5 rounded-full border border-accent/30 bg-accent/10 px-2.5 py-0.5 text-xs font-medium text-accent sm:flex"><Flame className="h-3 w-3" aria-hidden />Practice mode</span>
        <span className="hidden items-center gap-1.5 text-xs text-faint md:flex"><Bot className="h-3.5 w-3.5" aria-hidden />Hints, custom runs, Forge Coach and evaluations are on</span>
      </div>
      <div className="min-h-0 flex-1">
        <Workspace key={id} problemId={Number(id)} mode="practice" defaultLanguage={prefs.data?.preferredLanguage} />
      </div>
    </div>
  );
}
