"use client";

import { useRouter } from "next/navigation";
import { useEffect, useRef, useState, type KeyboardEvent } from "react";
import { ArrowRight, CornerDownLeft, Dices, FileCode2, Flame, LayoutGrid, Library, LogOut, Monitor, Moon, Network, ScanSearch, Search, Settings, Sun, Timer, type LucideIcon } from "lucide-react";
import { get } from "@/lib/api";
import { difficultyTone } from "@/lib/format";
import { randomProblemId } from "@/lib/problems";
import { useTheme } from "@/lib/theme";
import type { Page, ProblemSummary } from "@/lib/types";
import { Badge, cx, Kbd, type Tone } from "./ui";

type Group = "Problems" | "Pages" | "Actions";
interface Item { id: string; group: Group; label: string; icon: LucideIcon; run: () => void; hint?: string; tone?: Tone }

const PAGES: [string, string, LucideIcon][] = [
  ["/", "Dashboard", LayoutGrid], ["/problems", "Problem Hub", Library], ["/practice", "Practice", Flame], ["/oa", "OA Arena", Timer],
  ["/learning", "Learning Map", Network], ["/evaluations", "Code Evaluations", ScanSearch], ["/settings", "Settings", Settings],
];

/** ⌘K / Ctrl+K: jump to a page, search problems by title, or run an action. Mounted only while open. */
export function CommandPalette({ onClose, onSignOut }: { onClose: () => void; onSignOut: () => void }) {
  const router = useRouter();
  const theme = useTheme();
  const [q, setQ] = useState("");
  const [problems, setProblems] = useState<ProblemSummary[]>([]);
  const [active, setActive] = useState(0);
  const [busy, setBusy] = useState(false);
  const list = useRef<HTMLUListElement>(null);
  const term = q.trim().toLowerCase();

  useEffect(() => {
    if (!term) return;
    const ctl = new AbortController();
    const t = setTimeout(() => {
      get<Page<ProblemSummary>>(`/api/problems?q=${encodeURIComponent(term)}&size=6`, ctl.signal).then((p) => setProblems(p.items)).catch(() => undefined);
    }, 120);
    return () => { clearTimeout(t); ctl.abort(); };
  }, [term]);

  const go = (href: string) => () => { onClose(); router.push(href); };
  const actions: Item[] = [
    { id: "start", group: "Actions", label: "Start today's session", icon: ArrowRight, run: go("/practice") },
    { id: "random", group: "Actions", label: "Surprise me with an unsolved problem", icon: Dices, run: () => {
      setBusy(true);
      randomProblemId().then((id) => { onClose(); if (id) router.push(`/problems/${id}`); }).catch(() => setBusy(false));
    } },
    ...(["light", "dark", "system"] as const).filter((p) => p !== theme.pref).map((p): Item => ({
      id: `theme-${p}`, group: "Actions", label: p === "system" ? "Use the system theme" : `Switch to ${p} theme`,
      icon: p === "light" ? Sun : p === "dark" ? Moon : Monitor, run: () => { onClose(); theme.setPref(p); },
    })),
    { id: "signout", group: "Actions", label: "Sign out", icon: LogOut, run: () => { onClose(); onSignOut(); } },
  ];
  const match = (s: string) => !term || s.toLowerCase().includes(term);
  const items: Item[] = [
    ...(term ? problems.map((p): Item => ({ id: `p${p.id}`, group: "Problems", label: p.title, icon: FileCode2, run: go(`/problems/${p.id}`),
      hint: p.difficulty?.toLowerCase(), tone: difficultyTone(p.difficulty) })) : []),
    ...PAGES.filter(([, label]) => match(label)).map(([href, label, icon]): Item => ({ id: href, group: "Pages", label, icon, run: go(href) })),
    ...actions.filter((a) => match(a.label)),
  ];
  const current = Math.min(active, items.length - 1);

  useEffect(() => {
    list.current?.querySelector(`[data-index="${current}"]`)?.scrollIntoView({ block: "nearest" });
  }, [current]);

  const onKey = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === "ArrowDown") { e.preventDefault(); setActive((current + 1) % Math.max(1, items.length)); }
    else if (e.key === "ArrowUp") { e.preventDefault(); setActive((current - 1 + items.length) % Math.max(1, items.length)); }
    else if (e.key === "Enter") { e.preventDefault(); items[current]?.run(); }
    else if (e.key === "Escape") { e.preventDefault(); onClose(); }
  };

  return (
    <div className="fixed inset-0 z-50 animate-fade" onMouseDown={onClose}>
      <div className="absolute inset-0 bg-black/30 backdrop-blur-[2px] dark:bg-black/50" aria-hidden />
      <div role="dialog" aria-modal="true" aria-label="Command palette" onMouseDown={(e) => e.stopPropagation()}
        className="relative mx-auto mt-[12vh] w-[min(640px,calc(100vw-32px))] overflow-hidden rounded-xl border border-line-strong bg-panel shadow-2xl animate-pop">
        <div className="flex items-center gap-3 border-b border-line px-4">
          <Search className="size-4 shrink-0 text-faint" aria-hidden />
          <input autoFocus value={q} onChange={(e) => { setQ(e.target.value); setActive(0); }} onKeyDown={onKey}
            role="combobox" aria-expanded="true" aria-controls="cmdk-list" aria-activedescendant={items[current] ? `cmdk-${items[current].id}` : undefined}
            aria-label="Search problems, pages and actions" placeholder="Search problems, pages and actions…"
            className="h-13 min-w-0 flex-1 bg-transparent text-[15px] text-fg placeholder:text-faint outline-none focus-visible:outline-none" />
          {busy ? <span className="size-4 animate-spin rounded-full border-2 border-line-strong border-t-fg" aria-hidden /> : <Kbd>Esc</Kbd>}
        </div>
        <ul ref={list} id="cmdk-list" role="listbox" aria-label="Results" className="max-h-[min(420px,60vh)] overflow-y-auto p-2">
          {items.length === 0 && <li className="px-3 py-8 text-center text-sm text-muted">No results for “{q}”.</li>}
          {items.map((it, i) => (
            <li key={it.id} role="presentation">
              {(i === 0 || items[i - 1].group !== it.group) && <p className="px-2 pb-1 pt-2 text-xs font-medium text-faint">{it.group}</p>}
              <div id={`cmdk-${it.id}`} role="option" aria-selected={i === current} data-index={i}
                onMouseMove={() => i !== current && setActive(i)} onClick={() => it.run()}
                className={cx("flex h-10 cursor-pointer items-center gap-3 rounded-md px-2.5 text-sm transition-colors",
                  i === current ? "bg-panel-2 text-fg" : "text-muted")}>
                <it.icon className={cx("size-4 shrink-0", i === current ? "text-accent" : "text-faint")} aria-hidden />
                <span className="min-w-0 flex-1 truncate">{it.label}</span>
                {it.hint && <Badge tone={it.tone}>{it.hint}</Badge>}
                {i === current && <CornerDownLeft className="size-3.5 text-faint" aria-hidden />}
              </div>
            </li>
          ))}
        </ul>
        <div className="flex items-center gap-4 border-t border-line px-4 py-2 text-xs text-faint">
          <span className="flex items-center gap-1"><Kbd>↑</Kbd><Kbd>↓</Kbd> move</span>
          <span className="flex items-center gap-1"><Kbd>↵</Kbd> open</span>
          <span className="flex items-center gap-1"><Kbd>Esc</Kbd> close</span>
        </div>
      </div>
    </div>
  );
}
