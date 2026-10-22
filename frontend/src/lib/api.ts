export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

function csrfToken(): string {
  const m = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
  return m ? decodeURIComponent(m[1]) : "";
}

/** JSON fetch against the proxied API. Mutations send the double-submit CSRF header. */
export async function api<T>(path: string, init: { method?: string; body?: unknown; signal?: AbortSignal } = {}): Promise<T> {
  const method = init.method ?? "GET";
  if (method !== "GET" && !csrfToken()) await fetch("/api/auth/csrf", { credentials: "same-origin" });
  const res = await fetch(path, {
    method,
    credentials: "same-origin",
    signal: init.signal,
    headers: { "content-type": "application/json", "X-XSRF-TOKEN": csrfToken() },
    body: init.body === undefined ? undefined : JSON.stringify(init.body),
  });
  if (res.status === 401 && path !== "/api/auth/me" && path !== "/api/auth/login") {
    window.dispatchEvent(new Event("af:unauthorized"));
  }
  const text = await res.text();
  const data = text ? safeJson(text) : undefined;
  if (!res.ok) {
    const msg = (data as { error?: string } | undefined)?.error ?? (res.status === 429 ? "Too many requests" : `Request failed (${res.status})`);
    throw new ApiError(res.status, msg);
  }
  return data as T;
}

function safeJson(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return undefined;
  }
}

export const get = <T>(path: string, signal?: AbortSignal) => api<T>(path, { signal });
export const post = <T>(path: string, body: unknown = {}) => api<T>(path, { method: "POST", body });
export const put = <T>(path: string, body: unknown) => api<T>(path, { method: "PUT", body });

/** Polls until `done` holds; stops on unmount through the signal. */
export async function poll<T>(fetcher: () => Promise<T>, done: (v: T) => boolean, signal?: AbortSignal, intervalMs = 700): Promise<T> {
  for (;;) {
    const v = await fetcher();
    if (done(v) || signal?.aborted) return v;
    await new Promise((r) => setTimeout(r, intervalMs));
  }
}

export function errorMessage(e: unknown): string {
  return e instanceof Error ? e.message : "Something went wrong";
}
