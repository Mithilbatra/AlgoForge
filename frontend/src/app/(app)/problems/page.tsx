"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useMemo, useState, type FormEvent } from "react";
import { CheckCircle2, ChevronLeft, ChevronRight, Circle, CircleDashed, EyeOff, Link2, RefreshCw, RotateCcw, Search, SlidersHorizontal } from "lucide-react";
import { Badge, Button, Card, cx, Empty, ErrorNote, Input, Page, PageHeader, Segmented, Select, Skeleton } from "@/components/ui";
import { errorMessage, get, poll, post } from "@/lib/api";
import { difficultyTone, STATUS_LABEL } from "@/lib/format";
import type { Page as Paged, ProblemSummary, SourceInfo } from "@/lib/types";
import { useApi } from "@/lib/useApi";
import { useTitle } from "@/lib/useTitle";

const CAPS: [keyof SourceInfo["capabilities"], string][] = [
  ["discovery", "Discovery"], ["metadataSync", "Metadata sync"], ["statementAccess", "Statements"],
  ["constraintsAndExamples", "Examples"], ["submissionLinks", "Links"], ["verdictSync", "Verdict sync"],
  ["judging", "Local judging"], ["ratingAndTags", "Ratings & tags"],
];

function StatusIcon({ status }: { status: string }) {
  const s = { SOLVED: [CheckCircle2, "text-ok"], ATTEMPTED: [CircleDashed, "text-warn"], NEEDS_REVISION: [RotateCcw, "text-violet"] }[status] as [typeof Circle, string] | undefined;
  const [Icon, tone] = s ?? [Circle, "text-line-strong"];
  return <Icon className={cx("size-4", tone)} aria-label={STATUS_LABEL[status]} />;
}

const opts = <T extends string>(o: [T, string][]) => o.map(([value, label]) => ({ value, label }));

export default function ProblemHub() {
  useTitle("Problem Hub");
  const router = useRouter();
  const [q, setQ] = useState("");
  const [debouncedQ, setDebouncedQ] = useState("");
  const [more, setMore] = useState(false);
  const [f, setF] = useState({ source: "", difficulty: "", tag: "", status: "", minRating: "", maxRating: "", maxMinutes: "", judgeable: "" });
  const [page, setPage] = useState(0);
  useEffect(() => { const t = setTimeout(() => { setDebouncedQ(q); setPage(0); }, 250); return () => clearTimeout(t); }, [q]);

  const query = useMemo(() => {
    const p = new URLSearchParams({ page: String(page), size: "25" });
    if (debouncedQ) p.set("q", debouncedQ);
    Object.entries(f).forEach(([k, v]) => v && p.set(k, v));
    return `/api/problems?${p}`;
  }, [debouncedQ, f, page]);
  const { data, error, loading, reload } = useApi<Paged<ProblemSummary>>(query);
  const sources = useApi<SourceInfo[]>("/api/sources");
  const set = (k: keyof typeof f) => (v: string) => { setF({ ...f, [k]: v }); setPage(0); };

  return (
    <Page width="max-w-7xl">
      <PageHeader title="Problem Hub"
        description={<>{data ? `${data.total.toLocaleString()} problems. ` : ""}Originals are judged here; synced and linked problems keep their statements on the source platform.</>} />
      <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_300px]">
        <div className="min-w-0">
          <div className="mb-4 space-y-3">
            <div className="flex flex-wrap items-center gap-2">
              <div className="relative min-w-56 flex-1">
                <Search className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-faint" aria-hidden />
                <Input aria-label="Search titles" placeholder="Search titles…" value={q} onChange={(e) => setQ(e.target.value)} className="pl-9" />
              </div>
              <Segmented label="Difficulty" value={f.difficulty} onChange={set("difficulty")} options={opts([["", "All"], ["EASY", "Easy"], ["MEDIUM", "Medium"], ["HARD", "Hard"]])} />
              <Button variant={more ? "secondary" : "ghost"} onClick={() => setMore(!more)} aria-expanded={more}><SlidersHorizontal aria-hidden />Filters</Button>
            </div>
            <div className="flex flex-wrap items-center gap-2">
              <Segmented label="Status" value={f.status} onChange={set("status")} options={opts([["", "Any status"], ["UNSEEN", "Unseen"], ["ATTEMPTED", "Attempted"], ["SOLVED", "Solved"], ["NEEDS_REVISION", "Revise"]])} />
              <Segmented label="Judgeable" value={f.judgeable} onChange={set("judgeable")} options={opts([["", "Everything"], ["true", "Judged here"], ["false", "Links only"]])} />
            </div>
            {more && (
              <div className="grid grid-cols-2 gap-2 rounded-lg border border-line bg-panel-2/50 p-3 sm:grid-cols-5">
                <Select aria-label="Source" value={f.source} onChange={(e) => set("source")(e.target.value)}>
                  <option value="">All sources</option>
                  {sources.data?.map((s) => <option key={s.code} value={s.code}>{s.name}</option>)}
                </Select>
                <Input aria-label="Tag" placeholder="Tag, e.g. dp" value={f.tag} onChange={(e) => set("tag")(e.target.value.trim())} />
                <Input aria-label="Minimum rating" placeholder="Rating ≥" inputMode="numeric" value={f.minRating} onChange={(e) => set("minRating")(e.target.value.replace(/\D/g, ""))} />
                <Input aria-label="Maximum rating" placeholder="Rating ≤" inputMode="numeric" value={f.maxRating} onChange={(e) => set("maxRating")(e.target.value.replace(/\D/g, ""))} />
                <Select aria-label="Estimated time" value={f.maxMinutes} onChange={(e) => set("maxMinutes")(e.target.value)}>
                  <option value="">Any time</option><option value="15">≤ 15 min</option><option value="25">≤ 25 min</option><option value="40">≤ 40 min</option>
                </Select>
              </div>
            )}
          </div>

          {error ? <ErrorNote message={error} onRetry={reload} /> : !data ? (
            <Skeleton className="h-96" />
          ) : data.items.length === 0 ? (
            <Empty title="No problems match these filters" icon={<Search aria-hidden />}>Clear a filter, sync a source, or add a problem by link.</Empty>
          ) : (
            <div className={cx("card overflow-hidden transition-opacity", loading && "opacity-60")}>
              <table className="w-full text-sm">
                <thead className="border-b border-line bg-panel-2/60 text-left text-xs text-muted">
                  <tr>
                    <th className="w-10 py-2.5 pl-4"><span className="sr-only">Status</span></th>
                    <th className="px-3 py-2.5 font-medium">Problem</th>
                    <th className="hidden px-3 py-2.5 font-medium md:table-cell">Source</th>
                    <th className="px-3 py-2.5 font-medium">Difficulty</th>
                    <th className="hidden px-3 py-2.5 pr-4 text-right font-medium lg:table-cell">Time</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-line">
                  {data.items.map((p) => (
                    <tr key={p.id} className="group cursor-pointer transition-colors hover:bg-panel-2/60" onClick={() => router.push(`/problems/${p.id}`)}>
                      <td className="py-3 pl-4"><StatusIcon status={p.status} /></td>
                      <td className="px-3 py-3">
                        <Link href={`/problems/${p.id}`} className="font-medium text-fg underline-offset-4 group-hover:underline" onClick={(e) => e.stopPropagation()}>{p.title}</Link>
                        <div className="mt-1 flex flex-wrap items-center gap-1">
                          {!p.judgeable && <Badge><Link2 aria-hidden />link</Badge>}
                          {p.owned && <Badge>private</Badge>}
                          {p.tags.slice(0, 4).map((t) => <Badge key={t}>{t}</Badge>)}
                          {p.tagsHidden && <Badge tone="violet" title="Solve it to reveal the topic"><EyeOff aria-hidden />topic hidden</Badge>}
                        </div>
                      </td>
                      <td className="hidden px-3 py-3 md:table-cell">
                        <span className="text-[13px] text-muted">{p.judgeable && p.source === "ORIGINAL" ? "AlgoForge" : p.source.charAt(0) + p.source.slice(1).toLowerCase()}</span>
                        {p.externalId && <span className="ml-1.5 font-mono text-xs text-faint">{p.externalId}</span>}
                      </td>
                      <td className="px-3 py-3 whitespace-nowrap">
                        <Badge tone={difficultyTone(p.difficulty)}>{p.difficulty?.toLowerCase() ?? "unrated"}</Badge>
                        {p.sourceRating ? <span className="ml-1.5 font-mono text-xs text-faint">{p.sourceRating}</span> : null}
                      </td>
                      <td className="hidden px-3 py-3 pr-4 text-right text-[13px] tabular-nums text-muted lg:table-cell">{p.estMinutes ? `${p.estMinutes} min` : "—"}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {data.total > data.size && (
                <div className="flex items-center justify-between gap-3 border-t border-line px-4 py-2.5 text-[13px] text-muted">
                  <span className="tabular-nums">{data.page * data.size + 1}–{Math.min(data.total, (data.page + 1) * data.size)} of {data.total.toLocaleString()}</span>
                  <div className="flex gap-2">
                    <Button size="sm" disabled={page === 0} onClick={() => setPage(page - 1)}><ChevronLeft aria-hidden />Previous</Button>
                    <Button size="sm" disabled={(page + 1) * data.size >= data.total} onClick={() => setPage(page + 1)}>Next<ChevronRight aria-hidden /></Button>
                  </div>
                </div>
              )}
            </div>
          )}
        </div>
        <div className="space-y-6">
          <AddByLink onAdded={(id) => router.push(`/problems/${id}`)} />
          <Sources state={sources} onSynced={reload} />
        </div>
      </div>
    </Page>
  );
}

function AddByLink({ onAdded }: { onAdded: (id: number) => void }) {
  const [msg, setMsg] = useState<string>();
  const [busy, setBusy] = useState(false);
  async function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const url = String(new FormData(e.currentTarget).get("url") ?? "");
    setBusy(true);
    setMsg(undefined);
    try {
      const r = await post<{ id: number; created: boolean }>("/api/problems/link", { url });
      if (r.created) onAdded(r.id);
      else { setMsg("Already in your catalogue, opening it."); setTimeout(() => onAdded(r.id), 600); }
    } catch (err) {
      setMsg(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }
  return (
    <Card title="Add by link" description="LeetCode, Codeforces, AtCoder, CSES and more">
      <form onSubmit={submit} className="space-y-2.5">
        <Input name="url" type="url" required placeholder="https://codeforces.com/problemset/problem/4/A" aria-label="Problem URL" />
        <Button type="submit" variant="primary" size="sm" disabled={busy} className="w-full">{busy ? "Adding…" : "Add problem"}</Button>
        {msg && <p className="text-xs text-muted">{msg}</p>}
        <p className="text-xs leading-relaxed text-faint">Deduplicated by platform ID and canonical URL. Only the link, metadata and your notes are stored.</p>
      </form>
    </Card>
  );
}

function Sources({ state, onSynced }: { state: ReturnType<typeof useApi<SourceInfo[]>>; onSynced: () => void }) {
  const [syncing, setSyncing] = useState<string>();
  const [msg, setMsg] = useState<string>();
  async function sync(code: string) {
    setSyncing(code);
    setMsg(undefined);
    try {
      await post(`/api/sources/${code}/sync`);
      // The sync runs in the background; follow its status until it settles.
      const sources = await poll(() => get<SourceInfo[]>("/api/sources"), (all) => all.find((s) => s.code === code)?.lastSyncStatus !== "RUNNING", undefined, 2000);
      const s = sources.find((x) => x.code === code);
      setMsg(s?.lastSyncStatus === "OK" ? `Synced ${s.lastSyncCount?.toLocaleString()} problems.` : `Sync failed: ${s?.lastSyncMessage ?? "unknown error"}`);
      onSynced();
    } catch (e) {
      setMsg(errorMessage(e));
    } finally {
      state.reload();
      setSyncing(undefined);
    }
  }
  return (
    <Card title="Sources" padded={false}>
      {!state.data ? <div className="p-5"><Skeleton className="h-40" /></div> : (
        <ul className="divide-y divide-line">
          {state.data.map((s) => (
            <li key={s.code} className="px-5 py-3.5 text-sm">
              <div className="flex items-center justify-between gap-2">
                <span className="font-medium text-fg">{s.name}</span>
                <span className="font-mono text-xs text-faint">{s.problemCount.toLocaleString()}</span>
              </div>
              <p className="mt-1 text-xs text-muted">{CAPS.filter(([k]) => s.capabilities[k]).map(([, l]) => l).join(" · ") || "Links only"}</p>
              <details className="mt-1 text-xs text-muted">
                <summary className="cursor-pointer text-faint hover:text-muted">What is supported?</summary>
                <p className="mt-1 leading-relaxed">{s.capabilities.notes}</p>
                <p className="mt-1">Not available: {CAPS.filter(([k]) => !s.capabilities[k]).map(([, l]) => l.toLowerCase()).join(", ") || "none"}.</p>
              </details>
              {s.capabilities.metadataSync && (
                <div className="mt-2.5 flex items-center justify-between gap-2 text-xs">
                  <span className={s.lastSyncStatus === "FAILED" ? "text-bad" : "text-faint"}>
                    {s.lastSyncStatus === "NEVER" ? "Never synced" : s.lastSyncStatus === "RUNNING" ? "Sync in progress…"
                      : s.lastSyncStatus === "FAILED" ? `Last sync failed: ${s.lastSyncMessage}` : `Synced ${new Date(s.lastSyncAt!).toLocaleDateString()}`}
                  </span>
                  <Button size="sm" onClick={() => sync(s.code)} disabled={!!syncing || s.lastSyncStatus === "RUNNING"}>
                    <RefreshCw className={cx((syncing === s.code || s.lastSyncStatus === "RUNNING") && "animate-spin")} aria-hidden />
                    {syncing === s.code ? "Syncing…" : "Sync"}
                  </Button>
                </div>
              )}
            </li>
          ))}
        </ul>
      )}
      {msg && <p className="border-t border-line px-5 py-3 text-xs text-muted">{msg}</p>}
    </Card>
  );
}
