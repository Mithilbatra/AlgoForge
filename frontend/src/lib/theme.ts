import { useCallback, useSyncExternalStore } from "react";
import { reducedMotion } from "./motion";
import { THEME_KEY as KEY } from "./themeScript";

export type ThemePref = "system" | "light" | "dark";

const systemDark = () => window.matchMedia("(prefers-color-scheme: dark)");

function readPref(): ThemePref {
  try {
    const p = localStorage.getItem(KEY);
    return p === "light" || p === "dark" ? p : "system";
  } catch {
    return "system";
  }
}

function apply(pref: ThemePref) {
  const dark = pref === "dark" || (pref === "system" && systemDark().matches);
  document.documentElement.setAttribute("data-theme", dark ? "dark" : "light");
}

const listeners = new Set<() => void>();

function subscribe(onChange: () => void) {
  listeners.add(onChange);
  const media = systemDark();
  const onSystem = () => {
    if (readPref() === "system") apply("system");
    onChange();
  };
  media.addEventListener("change", onSystem);
  return () => {
    listeners.delete(onChange);
    media.removeEventListener("change", onSystem);
  };
}

const resolvedNow = (): "dark" | "light" => (document.documentElement.getAttribute("data-theme") === "dark" ? "dark" : "light");

// Where the last click happened, so a theme change can spread out from the control that caused it.
let lastPointer: { x: number; y: number } | null = null;
if (typeof window !== "undefined") {
  window.addEventListener("pointerdown", (e) => { lastPointer = { x: e.clientX, y: e.clientY }; }, { capture: true, passive: true });
}

/** Runs `change` inside a circular-reveal view transition when the visible theme flips and the browser supports it. */
function withReveal(pref: ThemePref, change: () => void) {
  const darkAfter = pref === "dark" || (pref === "system" && systemDark().matches);
  const flips = (darkAfter ? "dark" : "light") !== resolvedNow();
  if (!flips || !document.startViewTransition || reducedMotion()) return change();
  const { x, y } = lastPointer ?? { x: window.innerWidth / 2, y: 0 };
  const root = document.documentElement.style;
  root.setProperty("--vt-x", `${x}px`);
  root.setProperty("--vt-y", `${y}px`);
  root.setProperty("--vt-r", `${Math.hypot(Math.max(x, window.innerWidth - x), Math.max(y, window.innerHeight - y))}px`);
  document.startViewTransition(change);
}

/** The saved preference (system, light or dark), the theme actually shown, and a setter that persists it. */
export function useTheme() {
  const pref = useSyncExternalStore(subscribe, readPref, () => "system" as ThemePref);
  const resolved = useSyncExternalStore(subscribe, resolvedNow, () => "light" as const);
  const setPref = useCallback((p: ThemePref) => {
    try {
      if (p === "system") localStorage.removeItem(KEY);
      else localStorage.setItem(KEY, p);
    } catch {
      /* storage blocked: the choice lasts for this page only */
    }
    withReveal(p, () => {
      apply(p);
      listeners.forEach((l) => l());
    });
  }, []);
  return { pref, resolved, setPref };
}
