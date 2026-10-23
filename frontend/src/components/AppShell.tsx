"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { createContext, useContext, useEffect, useState, useSyncExternalStore, type ReactNode } from "react";
import { Flame, LayoutGrid, Library, LogOut, Monitor, Moon, Network, ScanSearch, Search, Settings, Sun, Timer, type LucideIcon } from "lucide-react";
import { get, post } from "@/lib/api";
import { clearDrafts } from "@/lib/drafts";
import { useTheme, type ThemePref } from "@/lib/theme";
import type { Me } from "@/lib/types";
import { CommandPalette } from "./CommandPalette";
import { Logo, LogoMark } from "./Logo";
import { cx, Kbd, Segmented } from "./ui";

const MeContext = createContext<Me | null>(null);
export const useMe = () => useContext(MeContext);

const NAV: { group: string; items: { href: string; label: string; icon: LucideIcon }[] }[] = [
  { group: "Train", items: [
    { href: "/", label: "Dashboard", icon: LayoutGrid },
    { href: "/problems", label: "Problem Hub", icon: Library },
    { href: "/practice", label: "Practice", icon: Flame },
  ] },
  { group: "Compete", items: [{ href: "/oa", label: "OA Arena", icon: Timer }] },
  { group: "Grow", items: [
    { href: "/learning", label: "Learning Map", icon: Network },
    { href: "/evaluations", label: "Code Evaluations", icon: ScanSearch },
  ] },
];
const SETTINGS = { href: "/settings", label: "Settings", icon: Settings };

export const THEME_OPTIONS: { value: ThemePref; label: string; icon: LucideIcon; iconOnly: boolean }[] = [
  { value: "system", label: "System theme", icon: Monitor, iconOnly: true },
  { value: "light", label: "Light theme", icon: Sun, iconOnly: true },
  { value: "dark", label: "Dark theme", icon: Moon, iconOnly: true },
];

const noop = () => () => {};
const useIsMac = () => useSyncExternalStore(noop, () => /Mac|iPhone|iPad/.test(navigator.platform), () => false);

function initials(name?: string) {
  return (name ?? "").split(/\s+/).filter(Boolean).slice(0, 2).map((w) => w[0]?.toUpperCase()).join("") || "·";
}

export function AppShell({ children }: { children: ReactNode }) {
  const [me, setMe] = useState<Me | null>(null);
  const router = useRouter();
  const pathname = usePathname();
  const theme = useTheme();
  const [palette, setPalette] = useState(false);
  const mac = useIsMac();

  // ⌘K / Ctrl+K opens the command palette anywhere except inside the code editor, which keeps its own Ctrl+K chords.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "k" && !(e.target as Element | null)?.closest?.(".monaco-editor")) {
        e.preventDefault();
        setPalette((open) => !open);
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  useEffect(() => {
    const toLogin = () => router.replace(`/login?next=${encodeURIComponent(window.location.pathname)}`);
    get<Me>("/api/auth/me").then(setMe).catch(toLogin);
    const onUnauthorized = toLogin;
    window.addEventListener("af:unauthorized", onUnauthorized);
    return () => window.removeEventListener("af:unauthorized", onUnauthorized);
    // the session is checked once per shell mount; later 401s arrive through the event
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // The shell and page render straight away and load in parallel with the session check; a 401 redirects to sign-in.
  const active = (href: string) => (href === "/" ? pathname === "/" : pathname.startsWith(href));
  const logout = async () => {
    await post("/api/auth/logout").catch(() => undefined);
    clearDrafts();
    router.replace("/login");
  };

  const navLink = (n: { href: string; label: string; icon: LucideIcon }) => {
    const on = active(n.href);
    const Icon = n.icon;
    return (
      <Link key={n.href} href={n.href} aria-current={on ? "page" : undefined}
        className={cx("flex h-8 items-center gap-2.5 rounded-md px-2 text-[13px] font-medium transition-colors",
          on ? "bg-panel text-fg shadow-(--shadow-card) ring-1 ring-line" : "text-muted hover:bg-panel-2 hover:text-fg")}>
        <Icon className={cx("size-4 shrink-0", on ? "text-accent" : "text-faint")} aria-hidden />
        {n.label}
      </Link>
    );
  };

  return (
    <MeContext.Provider value={me}>
      <div className="flex h-screen flex-col bg-bg md:flex-row">
        <aside className="hidden w-60 shrink-0 flex-col md:flex">
          <div className="flex h-14 items-center px-5">
            <Link href="/" aria-label="AlgoForge home"><Logo /></Link>
          </div>
          <button onClick={() => setPalette(true)} aria-label="Search and commands"
            className="mx-3 mb-2 flex h-8 items-center gap-2 rounded-md border border-line bg-panel px-2.5 text-[13px] text-faint shadow-(--shadow-card) transition-colors hover:text-muted">
            <Search className="size-3.5" aria-hidden />Search
            <span className="ml-auto"><Kbd>{mac ? "⌘K" : "Ctrl K"}</Kbd></span>
          </button>
          <nav className="flex-1 space-y-5 overflow-y-auto px-3 pt-2" aria-label="Primary">
            {NAV.map((g) => (
              <div key={g.group}>
                <p className="mb-1 px-2 text-xs font-medium text-faint">{g.group}</p>
                <div className="space-y-0.5">{g.items.map(navLink)}</div>
              </div>
            ))}
          </nav>
          <div className="space-y-3 p-3">
            {navLink(SETTINGS)}
            <div className="flex items-center justify-between px-2">
              <span className="text-xs text-faint">Theme</span>
              <Segmented label="Theme" value={theme.pref} onChange={theme.setPref} options={THEME_OPTIONS} />
            </div>
            <div className="flex items-center gap-2.5 rounded-md border border-line bg-panel px-2.5 py-2 shadow-(--shadow-card)">
              <span className="grid size-7 shrink-0 place-items-center rounded-full bg-panel-3 text-[11px] font-semibold text-fg" aria-hidden>{initials(me?.displayName)}</span>
              <div className="min-w-0 flex-1">
                <p className="truncate text-[13px] font-medium text-fg" title={me?.email}>{me?.displayName ?? "…"}</p>
                <p className="truncate text-xs text-faint">{me?.email ?? " "}</p>
              </div>
              <button onClick={logout} className="grid size-7 shrink-0 place-items-center rounded-md text-faint transition-colors hover:bg-panel-2 hover:text-fg" aria-label="Sign out" title="Sign out">
                <LogOut className="size-4" aria-hidden />
              </button>
            </div>
          </div>
        </aside>

        <header className="border-b border-line bg-panel md:hidden">
          <div className="flex h-12 items-center justify-between px-4">
            <Link href="/" aria-label="AlgoForge home" className="flex items-center gap-2 text-[15px] font-semibold"><LogoMark size={22} />AlgoForge</Link>
            <div className="flex items-center gap-2">
              <button onClick={() => setPalette(true)} className="grid size-8 place-items-center rounded-md text-faint hover:bg-panel-2 hover:text-fg" aria-label="Search and commands"><Search className="size-4" aria-hidden /></button>
              <Segmented label="Theme" value={theme.pref} onChange={theme.setPref} options={THEME_OPTIONS} />
              <button onClick={logout} className="grid size-8 place-items-center rounded-md text-faint hover:bg-panel-2 hover:text-fg" aria-label="Sign out"><LogOut className="size-4" aria-hidden /></button>
            </div>
          </div>
          <nav className="flex gap-1 overflow-x-auto px-3 pb-2" aria-label="Primary">
            {[...NAV.flatMap((g) => g.items), SETTINGS].map((n) => (
              <Link key={n.href} href={n.href} aria-current={active(n.href) ? "page" : undefined}
                className={cx("whitespace-nowrap rounded-md px-2.5 py-1 text-[13px] font-medium", active(n.href) ? "bg-panel-2 text-fg" : "text-muted")}>{n.label}</Link>
            ))}
          </nav>
        </header>

        {/* min-w-0: a wide child (the learning graph) scrolls inside the panel instead of widening the page. */}
        <main className="min-h-0 min-w-0 flex-1 md:py-2 md:pr-2">
          <div className="h-full overflow-auto bg-panel md:rounded-lg md:border md:border-line md:shadow-(--shadow-card)">{children}</div>
        </main>
      </div>
      {palette && <CommandPalette onClose={() => setPalette(false)} onSignOut={logout} />}
    </MeContext.Provider>
  );
}
