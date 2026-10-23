"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { ChevronLeft } from "lucide-react";
import { Workspace } from "@/components/Workspace";
import { Badge } from "@/components/ui";
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
      <div className="flex h-11 shrink-0 items-center gap-3 border-b border-line px-4 text-[13px]">
        <Link href="/problems" className="flex items-center gap-1 text-muted transition-colors hover:text-fg"><ChevronLeft className="size-4" aria-hidden />Problem Hub</Link>
        <span className="h-4 w-px bg-line" aria-hidden />
        <Badge>Practice mode</Badge>
        <span className="hidden text-xs text-faint md:inline">Hints, custom runs, Forge Coach and evaluations are on</span>
      </div>
      <div className="min-h-0 flex-1">
        <Workspace key={id} problemId={Number(id)} mode="practice" defaultLanguage={prefs.data?.preferredLanguage} />
      </div>
    </div>
  );
}
