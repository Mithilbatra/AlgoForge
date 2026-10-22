"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { createContext, useContext, useEffect, useState, type ReactNode } from "react";
import { Flame, LayoutDashboard, Library, LogOut, Network, ScanSearch, Settings2, Timer, type LucideIcon } from "lucide-react";
import { get, post } from "@/lib/api";
import { clearDrafts } from "@/lib/drafts";
import type { Me } from "@/lib/types";
import { Logo, LogoMark } from "./Logo";
import { cx } from "./ui";

const MeContext = createContext<Me | null>(null);
export const useMe = () => useContext(MeContext);

const NAV: { group: string; items: { href: string; label: string; icon: LucideIcon }[] }[] = [
  { group: "Train", items: [
    { href: "/", label: "Dashboard", icon: LayoutDashboard },
    { href: "/problems", label: "Problem Hub", icon: Library },
    { href: "/practice", label: "Practice", icon: Flame },
  ] },
  { group: "Compete", items: [{ href: "/oa", label: "OA Arena", icon: Timer }] },
  { group: "Grow", items: [
    { href: "/learning", label: "Learning Map", icon: Network },
    { href: "/evaluations", label: "Code Evaluations", icon: ScanSearch },
  ] },
];
const SETTINGS = { href: "/settings", label: "Settings", icon: Settings2 };

function initials(name?: string) {
  return (name ?? "").split(/\s+/).filter(Boolean).slice(0, 2).map((w) => w[0]?.toUpperCase()).join("") || "·";
}

export function AppShell({ children }: { children: ReactNode }) {
  const [me, setMe] = useState<Me | null>(null);
  const router = useRouter();
  const pathname = usePathname();

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
        className={cx("group relative flex items-center gap-3 rounded-xl px-3 py-2 text-sm transition-all",
          on ? "bg-gradient-to-r from-accent/15 via-accent/5 to-transparent text-fg" : "text-muted hover:bg-panel-2 hover:text-fg")}>
        {on && <span className="absolute left-0 top-1/2 h-5 w-[3px] -translate-y-1/2 rounded-full ember-fill shadow-[0_0_12px_rgb(255_122_47/0.9)]" aria-hidden />}
        <Icon className={cx("h-[18px] w-[18px] shrink-0 transition-colors", on ? "text-accent" : "text-faint group-hover:text-muted")} aria-hidden />
        {n.label}
      </Link>
    );
  };

  return (
    <MeContext.Provider value={me}>
      <div className="flex h-screen flex-col md:flex-row">
        <aside className="relative hidden w-64 shrink-0 flex-col border-r border-line bg-panel/70 backdrop-blur-xl md:flex">
          <div className="pointer-events-none absolute inset-x-0 top-0 h-40 bg-[radial-gradient(240px_120px_at_30%_0%,rgb(255_122_47/0.16),transparent)]" aria-hidden />
          <Link href="/" className="relative px-5 pb-6 pt-5" aria-label="AlgoForge home"><Logo /></Link>
          <nav className="relative flex-1 space-y-6 overflow-y-auto px-3" aria-label="Primary">
            {NAV.map((g) => (
              <div key={g.group}>
                <p className="mb-1.5 px-3 font-mono text-[10px] font-medium uppercase tracking-[0.22em] text-faint">{g.group}</p>
                <div className="space-y-0.5">{g.items.map(navLink)}</div>
              </div>
            ))}
          </nav>
          <div className="relative space-y-2 border-t border-line p-3">
            {navLink(SETTINGS)}
            <div className="flex items-center gap-3 rounded-xl bg-panel-2/70 px-3 py-2.5">
              <span className="grid h-9 w-9 shrink-0 place-items-center rounded-full ember-fill p-[2px]">
                <span className="grid h-full w-full place-items-center rounded-full bg-panel font-display text-xs font-bold text-fg">{initials(me?.displayName)}</span>
              </span>
              <div className="min-w-0 flex-1">
                <p className="truncate text-sm font-medium" title={me?.email}>{me?.displayName ?? "…"}</p>
                <p className="truncate text-[11px] text-faint">{me?.email ?? " "}</p>
              </div>
              <button onClick={logout} className="rounded-lg p-1.5 text-faint transition-colors hover:bg-panel-3 hover:text-fg" aria-label="Sign out" title="Sign out">
                <LogOut className="h-4 w-4" aria-hidden />
              </button>
            </div>
          </div>
        </aside>
        <header className="border-b border-line bg-panel/80 backdrop-blur-xl md:hidden">
          <div className="flex items-center justify-between px-4 py-2.5">
            <Link href="/" aria-label="AlgoForge home" className="flex items-center gap-2 font-display font-bold"><LogoMark size={26} />Algo<span className="-ml-2 ember-text">Forge</span></Link>
            <button onClick={logout} className="text-faint" aria-label="Sign out"><LogOut className="h-4 w-4" aria-hidden /></button>
          </div>
          <nav className="flex gap-1 overflow-x-auto px-2 pb-2" aria-label="Primary">
            {[...NAV.flatMap((g) => g.items), SETTINGS].map((n) => (
              <Link key={n.href} href={n.href} aria-current={active(n.href) ? "page" : undefined}
                className={cx("whitespace-nowrap rounded-full px-3 py-1.5 text-xs", active(n.href) ? "bg-accent/15 text-accent" : "text-muted")}>{n.label}</Link>
            ))}
          </nav>
        </header>
        <main className="min-h-0 flex-1 overflow-auto">{children}</main>
      </div>
    </MeContext.Provider>
  );
}
