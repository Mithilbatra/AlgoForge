"use client";

import Link from "next/link";
import { ChevronLeft, Flag, Timer } from "lucide-react";
import { useParams, useRouter } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { Countdown } from "@/components/Countdown";
import { Button, cx, ErrorNote, Spinner, TONE } from "@/components/ui";
import { Workspace } from "@/components/Workspace";
import { errorMessage, get, post } from "@/lib/api";
import { VERDICT_SHORT, verdictTone } from "@/lib/format";
import type { OaState, Preferences } from "@/lib/types";
import { useApi } from "@/lib/useApi";
import { useTitle } from "@/lib/useTitle";

export default function AssessmentRoom() {
  const { id } = useParams<{ id: string }>();
  const router = useRouter();
  const [state, setState] = useState<OaState>();
  const [receivedAt, setReceivedAt] = useState(0);
  const [error, setError] = useState<string>();
  const [current, setCurrent] = useState<number>();
  const prefs = useApi<Preferences>("/api/me/preferences");
  useTitle(state?.title ?? "Assessment");

  const [tick, setTick] = useState(0);
  const refresh = useCallback(() => setTick((t) => t + 1), []);

  useEffect(() => {
    const ctl = new AbortController();
    get<OaState>(`/api/oa/sessions/${id}`, ctl.signal)
      .then((s) => {
        setState(s);
        setReceivedAt(Date.now());
        setCurrent((c) => c ?? s.problems[0]?.problemId);
      })
      .catch((e) => { if (!ctl.signal.aborted) setError(errorMessage(e)); });
    return () => ctl.abort();
  }, [id, tick]);

  useEffect(() => {
    const t = setInterval(refresh, 15_000);
    return () => clearInterval(t);
  }, [refresh]);

  useEffect(() => {
    if (state?.status === "ACTIVE" && current) post(`/api/oa/sessions/${id}/focus`, { problemId: current }).catch(() => undefined);
  }, [current, id, state?.status]);

  useEffect(() => {
    if (state?.status === "COMPLETED") router.replace(`/oa/${id}/report`);
  }, [state?.status, id, router]);

  async function finish() {
    if (!confirm("Submit the assessment now? You won't be able to submit more code.")) return;
    try {
      setState(await post<OaState>(`/api/oa/sessions/${id}/submit`));
      setReceivedAt(Date.now());
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  if (error && !state) return <div className="p-6"><ErrorNote message={error} onRetry={refresh} /></div>;
  if (!state) return <div className="p-6"><Spinner /></div>;
  const open = state.status === "ACTIVE";

  return (
    <div className="flex h-full flex-col">
      <header className="flex shrink-0 flex-wrap items-center gap-x-4 gap-y-2 border-b border-line px-4 py-2">
        <Link href="/oa" className="flex items-center gap-1 text-[13px] text-muted transition-colors hover:text-fg"><ChevronLeft className="size-4" aria-hidden />Arena</Link>
        <span className="h-4 w-px bg-line" aria-hidden />
        <h1 className="text-sm font-semibold text-fg">{state.title}</h1>
        <nav className="flex gap-1" aria-label="Problems">
          {state.problems.map((p, i) => {
            const shown = p.bestStatus ?? p.lastStatus;
            const on = current === p.problemId;
            return (
              <button key={p.problemId} onClick={() => setCurrent(p.problemId)} aria-current={on}
                title={`${p.title} · ${p.points} points`}
                className={cx("flex h-8 items-center gap-2 rounded-md border px-2 text-xs transition-colors",
                  on ? "border-line-strong bg-panel-2 text-fg" : "border-transparent text-muted hover:bg-panel-2/60 hover:text-fg")}>
                <span className={cx("grid size-5 place-items-center rounded text-[11px] font-semibold", on ? "bg-fg text-bg" : "bg-panel-3 text-fg")}>{String.fromCharCode(65 + i)}</span>
                {shown && <span className={cx("rounded px-1 font-mono text-[10px] ring-1 ring-inset", TONE[verdictTone(shown)])}>{VERDICT_SHORT[shown]}</span>}
                <span className="font-mono text-faint">{p.score != null && p.score > 0 ? `${p.score}/${p.points}` : p.points}</span>
              </button>
            );
          })}
        </nav>
        <div className="ml-auto flex items-center gap-3 text-[13px]">
          <span className="text-muted" aria-label={`Points ${state.pointsEarned} of ${state.maxPoints}`}>
            Points <span data-testid="points" className="font-semibold tabular-nums text-fg">{state.pointsEarned}</span><span className="text-faint"> / {state.maxPoints}</span>
          </span>
          {open ? (
            <span className="flex h-8 items-center gap-1.5 rounded-md border border-line px-2.5"><Timer className="size-3.5 text-faint" aria-hidden /><Countdown remainingMs={state.remainingMs} receivedAt={receivedAt} onExpire={refresh} /></span>
          ) : <span className="text-warn">Finalising…</span>}
          {open && <Button size="sm" variant="danger" onClick={finish}><Flag aria-hidden />Submit assessment</Button>}
        </div>
      </header>
      {!open && (
        <div className="border-b border-warn/25 bg-warn/5 px-4 py-2 text-[13px] text-warn" role="status">
          The assessment has ended. Waiting for the last submissions to be judged before the report is generated.
        </div>
      )}
      {error && <div className="px-4 pt-2"><ErrorNote message={error} /></div>}
      <div className="min-h-0 flex-1">
        {current && (
          <Workspace key={current} problemId={current} mode={state.mode === "ASSESSMENT" ? "assessment" : "practice"} sessionId={state.id}
            languages={state.languages} defaultLanguage={prefs.data?.preferredLanguage} disabled={!open} onJudged={refresh} />
        )}
      </div>
    </div>
  );
}
