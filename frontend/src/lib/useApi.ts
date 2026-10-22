"use client";

import { useCallback, useEffect, useState } from "react";
import { errorMessage, get } from "./api";

/** Loads a GET endpoint; `reload` refetches. `path` null skips loading. Previous data stays visible while reloading. */
export function useApi<T>(path: string | null) {
  const [tick, setTick] = useState(0);
  const key = path === null ? null : `${tick}:${path}`;
  const [state, setState] = useState<{ key: string | null; data?: T; error?: string }>({ key: null });

  useEffect(() => {
    if (path === null || key === null) return;
    const ctl = new AbortController();
    get<T>(path, ctl.signal)
      .then((data) => setState({ key, data }))
      .catch((e) => { if (!ctl.signal.aborted) setState((s) => ({ key, data: s.data, error: errorMessage(e) })); });
    return () => ctl.abort();
  }, [path, key]);

  const reload = useCallback(() => setTick((t) => t + 1), []);
  const setData = useCallback((data: T) => setState((s) => ({ ...s, data })), []);
  return { data: state.data, error: state.error, loading: key !== null && state.key !== key, reload, setData };
}
