"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useMemo, useState, type FormEvent } from "react";
import { CheckCircle2, ChevronLeft, ChevronRight, Circle, CircleDashed, Clock, Database, EyeOff, Link2, RefreshCw, RotateCcw, Search, SlidersHorizontal } from "lucide-react";
import { Badge, Button, Card, cx, Empty, ErrorNote, Input, PageHeader, Select, Skeleton } from "@/components/ui";
import { errorMessage, get, poll, post } from "@/lib/api";
import { difficultyPill, STATUS_LABEL } from "@/lib/format";
import type { Page, ProblemSummary, SourceInfo } from "@/lib/types";
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
  return <Icon className={cx("h-[18px] w-[18px]", tone)} aria-label={STATUS_LABEL[status]} />;
}

function Segmented({ label, value, options, onChange }: { label: string; value: string; options: [string, string][]; onChange: (v: string) => void }) {
  return (
    <div role="radiogroup" aria-label={label} className="inline-flex max-w-full overflow-x-auto rounded-xl border border-line bg-panel-2/60 p-1">
      {options.map(([v, l]) => (
        <button key={v} role="radio" aria-checked={value === v} onClick={() => onChange(v)}
          className={cx("whitespace-nowrap rounded-lg px-2.5 py-1.5 text-xs font-medium transition-all sm:px-3", value === v ? "bg-panel-3 text-fg shadow-[inset_0_1px_0_rgb(255_255_255/0.06)]" : "text-muted hover:text-fg")}>{l}</button>
      ))}
    </div>
  );
}

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
  const { data, error, loading, reload } = useApi<Page<ProblemSummary>>(query);
  const sources = useApi<SourceInfo[]>("/api/sources");
  const set = (k: keyof typeof f) => (v: string) => { setF({ ...f, [k]: v }); setPage(0); };

  return (
    <div className="mx-auto max-w-7xl px-4 py-8 sm:px-8">
      <PageHeader kicker="Problem Hub · catalogue" title="Find your next problem"
        subtitle="Original problems judged right here, plus problems synced or linked from other platforms."
        actions={data ? <Badge className="border-line-strong bg-panel-2 px-3 py-1 text-sm text-muted"><Database className="h-3.5 w-3.5" aria-hidden />{data.total.toLocaleString()} problems</Badge> : undefined} />
      <div className="grid gap-6 lg:grid-cols-[1fr_320px]">
        <div className="min-w-0 space-y-4">
          <div className="surface space-y-3 rounded-2xl p-3">
            <div className="flex flex-wrap items-center gap-2">
              <div className="relative min-w-56 flex-1">
                <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-faint" aria-hidden />
                <Input aria-label="Search titles" placeholder="Search titles…" value={q} onChange={(e) => setQ(e.target.value)} className="h-10 pl-9" />
              </div>
              <Segmented label="Difficulty" value={f.difficulty} onChange={set("difficulty")} options={[["", "All"], ["EASY", "Easy"], ["MEDIUM", "Medium"], ["HARD", "Hard"]]} />
              <Button variant={more ? "secondary" : "ghost"} onClick={() => setMore(!more)} aria-expanded={more}><SlidersHorizontal className="h-4 w-4" aria-hidden />Filters</Button>
            </div>
            <div className="flex flex-wrap items-center gap-2">
              <Segmented label="Status" value={f.status} onChange={set("status")} options={[["", "Any status"], ["UNSEEN", "Unseen"], ["ATTEMPTED", "Attempted"], ["SOLVED", "Solved"], ["NEEDS_REVISION", "Revise"]]} />
              <Segmented label="Judgeable" value={f.judgeable} onChange={set("judgeable")} options={[["", "Everything"], ["true", "Judged here"], ["false", "Links only"]]} />
            </div>
            {more && (
              <div className="grid grid-cols-2 gap-2 border-t border-line pt-3 sm:grid-cols-5 animate-fade-up">
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
            <div className="space-y-2">{Array.from({ length: 6 }, (_, i) => <Skeleton key={i} className="h-16 rounded-xl" />)}</div>
          ) : data.items.length === 0 ? (
            <Empty title="No problems match these filters" icon={<Search className="h-5 w-5" />}>Clear a filter, sync a source, or add a problem by link.</Empty>
          ) : (
            <div className={cx("surface overflow-hidden rounded-2xl transition-opacity", loading && "opacity-60")}>
              <table className="w-full text-sm">
                <thead className="border-b border-line text-left text-[11px] uppercase tracking-wider text-faint">
                  <tr>
                    <th className="w-10 py-3 pl-4"><span className="sr-only">Status</span></th>
                    <th className="px-3 py-3 font-medium">Problem</th>
                    <th className="hidden px-3 py-3 font-medium md:table-cell">Source</th>
                    <th className="px-3 py-3 font-medium">Difficulty</th>
                    <th className="hidden px-3 py-3 pr-5 text-right font-medium lg:table-cell">Time</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-line/70">
                  {data.items.map((p) => (
                    <tr key={p.id} className="group cursor-pointer transition-colors hover:bg-gradient-to-r hover:from-accent/[0.06] hover:to-transparent" onClick={() => router.push(`/problems/${p.id}`)}>
                      <td className="py-3 pl-4 align-top"><StatusIcon status={p.status} /></td>
                      <td className="px-3 py-3">
                        <Link href={`/problems/${p.id}`} className="font-medium transition-colors group-hover:text-accent" onClick={(e) => e.stopPropagation()}>{p.title}</Link>
                        <div className="mt-1.5 flex flex-wrap gap-1">
                          {p.judgeable ? <Badge className="border-ok/30 bg-ok/[0.07] text-ok">judged here</Badge> : <Badge><Link2 className="h-3 w-3" aria-hidden />link</Badge>}
                          {p.owned && <Badge>private</Badge>}
                          {p.tags.slice(0, 4).map((t) => <Badge key={t}>{t}</Badge>)}
                          {p.tagsHidden && <Badge className="border-violet/30 bg-violet/[0.07] text-violet" title="Solve it to reveal the topic"><EyeOff className="h-3 w-3" aria-hidden />topic hidden</Badge>}
                        </div>
                      </td>
                      <td className="hidden px-3 py-3 text-muted md:table-cell"><span className="text-xs">{p.source.toLowerCase()}</span>{p.externalId && <span className="ml-1 font-mono text-[11px] text-faint">{p.externalId}</span>}</td>
                      <td className="px-3 py-3">
                        <Badge className={difficultyPill(p.difficulty)}>{p.difficulty?.toLowerCase() ?? "unrated"}</Badge>
                        {p.sourceRating ? <span className="ml-1.5 font-mono text-[11px] text-faint">{p.sourceRating}</span> : null}
                      </td>
                      <td className="hidden px-3 py-3 pr-5 text-right text-xs text-faint lg:table-cell">{p.estMinutes ? <span className="inline-flex items-center gap-1"><Clock className="h-3 w-3" aria-hidden />{p.estMinutes}m</span> : "—"}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          {data && data.total > data.size && (
            <div className="flex items-center justify-between text-sm text-muted">
              <span className="font-mono text-xs">{data.page * data.size + 1}–{Math.min(data.total, (data.page + 1) * data.size)} of {data.total.toLocaleString()}</span>
              <div className="flex gap-2">
                <Button size="sm" disabled={page === 0} onClick={() => setPage(page - 1)}><ChevronLeft className="h-4 w-4" aria-hidden />Previous</Button>
                <Button size="sm" disabled={(page + 1) * data.size >= data.total} onClick={() => setPage(page + 1)}>Next<ChevronRight className="h-4 w-4" aria-hidden /></Button>
              </div>
            </div>
          )}
        </div>
        <div className="space-y-6">
          <AddByLink onAdded={(id) => router.push(`/problems/${id}`)} />
          <Sources state={sources} onSynced={reload} />
        </div>
      </div>
    </div>
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
    <Card title="Add by link" icon={<Link2 className="h-4 w-4" />} glow>
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
    <Card title="Sources" icon={<Database className="h-4 w-4" />}>
      {!state.data ? <Skeleton className="h-40" /> : (
        <ul className="-mx-1 space-y-1">
          {state.data.map((s) => (
            <li key={s.code} className="rounded-xl px-1 py-2 text-sm">
              <div className="flex items-center justify-between gap-2">
                <span className="font-medium">{s.name}</span>
                <span className="font-mono text-xs text-faint">{s.problemCount.toLocaleString()}</span>
              </div>
              <div className="mt-1.5 flex flex-wrap gap-1">
                {CAPS.filter(([k]) => s.capabilities[k]).map(([k, label]) => <Badge key={k} className="border-ok/25 bg-ok/[0.06] text-ok">{label}</Badge>)}
              </div>
              <details className="mt-1.5 text-xs text-muted">
                <summary className="cursor-pointer text-faint hover:text-muted">What is supported?</summary>
                <p className="mt-1 leading-relaxed">{s.capabilities.notes}</p>
                <p className="mt-1">Not available: {CAPS.filter(([k]) => !s.capabilities[k]).map(([, l]) => l.toLowerCase()).join(", ") || "none"}.</p>
              </details>
              {s.capabilities.metadataSync && (
                <div className="mt-2 flex items-center justify-between gap-2 text-xs">
                  <span className={s.lastSyncStatus === "FAILED" ? "text-bad" : "text-faint"}>
                    {s.lastSyncStatus === "NEVER" ? "Never synced" : s.lastSyncStatus === "RUNNING" ? "Sync in progress…"
                      : s.lastSyncStatus === "FAILED" ? `Last sync failed: ${s.lastSyncMessage}` : `Synced ${new Date(s.lastSyncAt!).toLocaleString()}`}
                  </span>
                  <Button size="sm" onClick={() => sync(s.code)} disabled={!!syncing || s.lastSyncStatus === "RUNNING"}>
                    <RefreshCw className={cx("h-3.5 w-3.5", (syncing === s.code || s.lastSyncStatus === "RUNNING") && "animate-spin")} aria-hidden />
                    {syncing === s.code ? "Syncing…" : "Sync"}
                  </Button>
                </div>
              )}
            </li>
          ))}
        </ul>
      )}
      {msg && <p className="mt-3 text-xs text-muted">{msg}</p>}
    </Card>
  );
}
