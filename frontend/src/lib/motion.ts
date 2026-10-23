import { useEffect, useState } from "react";

export const reducedMotion = () => typeof window !== "undefined" && window.matchMedia("(prefers-reduced-motion: reduce)").matches;

/** Counts from 0 up to `target` once, easing out; returns the target straight away for reduced motion. */
export function useCountUp(target: number, ms = 700) {
  const [value, setValue] = useState(0);
  useEffect(() => {
    if (reducedMotion() || target === 0) {
      const id = requestAnimationFrame(() => setValue(target));
      return () => cancelAnimationFrame(id);
    }
    const start = performance.now();
    let id = 0;
    const tick = (now: number) => {
      const t = Math.min(1, (now - start) / ms);
      setValue(target * (1 - (1 - t) ** 3));
      if (t < 1) id = requestAnimationFrame(tick);
    };
    id = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(id);
  }, [target, ms]);
  return value;
}

/** Reveals text a few words at a time (coach replies); everything at once for reduced motion. */
export function useReveal(text: string, animate: boolean) {
  const words = text.split(/(\s+)/);
  const [shown, setShown] = useState(animate && !reducedMotion() ? 0 : words.length);
  useEffect(() => {
    if (shown >= words.length) return;
    // Long replies reveal faster, so every reply finishes in about a second.
    const step = Math.max(2, Math.ceil(words.length / 50));
    const id = setTimeout(() => setShown((n) => Math.min(words.length, n + step)), 22);
    return () => clearTimeout(id);
  }, [shown, words.length]);
  return { text: words.slice(0, shown).join(""), done: shown >= words.length };
}
