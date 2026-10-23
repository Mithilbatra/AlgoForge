import { useCallback, useSyncExternalStore } from "react";
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
    apply(p);
    listeners.forEach((l) => l());
  }, []);
  return { pref, resolved, setPref };
}
