"use client";

import Link from "next/link";
import { ArrowLeft, Flag, Medal, Swords, Timer } from "lucide-react";
import { useParams, useRouter } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { Countdown } from "@/components/Countdown";
import { Button, cx, ErrorNote, Spinner } from "@/components/ui";
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
      <header className="flex shrink-0 flex-wrap items-center gap-x-5 gap-y-2 border-b border-line bg-panel/80 px-4 py-2.5 backdrop-blur">
        <Link href="/oa" className="flex items-center gap-1.5 text-sm text-muted transition-colors hover:text-fg"><ArrowLeft className="h-4 w-4" aria-hidden />Arena</Link>
        <h1 className="flex items-center gap-2 font-display text-sm font-semibold"><Swords className="h-4 w-4 text-accent" aria-hidden />{state.title}</h1>
        <nav className="flex gap-1.5" aria-label="Problems">
          {state.problems.map((p, i) => {
            const shown = p.bestStatus ?? p.lastStatus;
            return (
              <button key={p.problemId} onClick={() => setCurrent(p.problemId)} aria-current={current === p.problemId}
                title={`${p.title} · ${p.points} points`}
                className={cx("flex h-9 items-center gap-2 rounded-xl border px-2.5 text-xs transition-all",
                  current === p.problemId ? "border-accent/70 bg-accent/10 text-fg shadow-[0_0_0_1px_rgb(255_122_47/0.25)]" : "border-line-strong text-muted hover:border-faint hover:text-fg")}>
                <span className={cx("grid h-5 w-5 place-items-center rounded-md font-display text-[11px] font-bold", current === p.problemId ? "ember-fill text-[#1a0c04]" : "bg-panel-3")}>{String.fromCharCode(65 + i)}</span>
                {shown && <span className={cx("rounded-md border px-1 font-mono text-[10px]", verdictTone(shown))}>{VERDICT_SHORT[shown]}</span>}
                <span className="font-mono text-faint">{p.score != null && p.score > 0 ? `${p.score}/${p.points}` : p.points}</span>
              </button>
            );
          })}
        </nav>
        <div className="ml-auto flex items-center gap-4 text-sm">
          <span className="flex items-center gap-2 text-muted" aria-label={`Points ${state.pointsEarned} of ${state.maxPoints}`}>
            <Medal className="h-4 w-4 text-gold" aria-hidden />
            Points <span data-testid="points" className="font-display text-base font-bold text-fg tabular-nums">{state.pointsEarned}</span><span className="text-faint">/ {state.maxPoints}</span>
          </span>
          {open ? (
            <span className="flex items-center gap-2 rounded-xl border border-line-strong bg-bg/60 px-3 py-1.5"><Timer className="h-4 w-4 text-accent" aria-hidden /><Countdown remainingMs={state.remainingMs} receivedAt={receivedAt} onExpire={refresh} /></span>
          ) : <span className="text-warn">Finalising…</span>}
          {open && <Button size="sm" variant="danger" onClick={finish}><Flag className="h-3.5 w-3.5" aria-hidden />Submit assessment</Button>}
        </div>
      </header>
      {!open && (
        <div className="border-b border-warn/40 bg-warn/10 px-4 py-2 text-sm text-warn" role="status">
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
