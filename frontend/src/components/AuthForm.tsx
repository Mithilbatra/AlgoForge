"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useState, type FormEvent } from "react";
import { Check } from "lucide-react";
import { errorMessage, post } from "@/lib/api";
import { Logo } from "./Logo";
import { Badge, Button, ErrorNote, Input, Label, Meter } from "./ui";

const POINTS = [
  "Python, C++ and Java judged in isolated sandboxes",
  "Server-timed assessments with a full report",
  "A coach that gives hints, never the solution",
];

const PREVIEW_CONCEPTS = [
  { name: "Dynamic programming", status: "Strong", tone: "text-ok", bar: "bg-ok", value: 81 },
  { name: "Binary search", status: "Developing", tone: "text-info", bar: "bg-info", value: 62 },
  { name: "Graph traversal", status: "Weak", tone: "text-bad", bar: "bg-bad", value: 34 },
];

/** A static picture of the product (a verdict and the learning map), drawn with the real components. */
function Preview() {
  return (
    <div className="stagger space-y-3 [&>*]:animate-enter" aria-hidden>
      <div className="card p-4">
        <div className="flex items-center justify-between gap-3">
          <div>
            <p className="text-sm font-semibold text-fg">Courier Capacity</p>
            <p className="mt-0.5 text-xs text-muted">Python 3 · submitted just now</p>
          </div>
          {/* Looping demo: tests pass one by one, then the verdict replaces "Judging". */}
          <span className="relative h-5 w-24">
            <span className="demo-pending absolute right-0"><Badge tone="info"><span className="size-1.5 animate-pulse rounded-full bg-current" />Judging…</Badge></span>
            <span className="demo-verdict absolute right-0"><Badge tone="ok"><span className="size-1.5 rounded-full bg-current" />Accepted</Badge></span>
          </span>
        </div>
        <div className="mt-3 flex gap-1">
          {Array.from({ length: 8 }, (_, i) => <span key={i} className="demo-chip h-6 flex-1 rounded" style={{ ["--i" as string]: i }} />)}
        </div>
        <p className="mt-2 font-mono text-xs text-faint">8/8 tests passed · 48 ms · 19.2 MB</p>
      </div>
      <div className="card p-4">
        <p className="text-sm font-semibold text-fg">Learning map</p>
        <ul className="mt-3 space-y-3">
          {PREVIEW_CONCEPTS.map((c) => (
            <li key={c.name}>
              <div className="flex justify-between text-xs"><span className="text-fg">{c.name}</span><span className={c.tone}>{c.status} · {c.value}%</span></div>
              <div className="mt-1.5"><Meter value={c.value} tone={c.bar} label={c.name} /></div>
            </li>
          ))}
        </ul>
      </div>
    </div>
  );
}

/** The page to open after sign-in: only paths on this site are followed (browsers read "/\evil.com" as another host). */
function safeNext(next: string | null): string {
  try {
    const u = new URL(next ?? "/", window.location.origin);
    return u.origin === window.location.origin ? u.pathname + u.search + u.hash : "/";
  } catch {
    return "/";
  }
}

export function AuthForm({ mode }: { mode: "login" | "register" }) {
  const router = useRouter();
  const next = useSearchParams().get("next");
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState(false);
  const login = mode === "login";

  async function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const f = new FormData(e.currentTarget);
    setBusy(true);
    setError(undefined);
    try {
      await post(login ? "/api/auth/login" : "/api/auth/register", Object.fromEntries(f.entries()));
      router.replace(safeNext(next));
    } catch (err) {
      setError(errorMessage(err));
      setBusy(false);
    }
  }

  return (
    <div className="grid min-h-screen bg-panel lg:grid-cols-2">
      <section className="flex flex-col px-6 py-6 sm:px-10">
        <Link href="/" className="w-fit" aria-label="AlgoForge home"><Logo /></Link>
        <div className="flex flex-1 items-center justify-center py-12">
          <div className="w-full max-w-sm">
            <h1 className="text-2xl font-semibold tracking-tight text-fg">{login ? "Sign in to AlgoForge" : "Create your account"}</h1>
            <p className="mt-1.5 text-sm text-muted">{login ? "Welcome back. Pick up where you left off." : "Start practising in under a minute."}</p>
            <form onSubmit={submit} className="mt-8 space-y-4">
              {!login && (
                <div><Label htmlFor="displayName">Display name</Label><Input id="displayName" name="displayName" required maxLength={60} autoComplete="nickname" className="h-10" /></div>
              )}
              <div><Label htmlFor="email">Email</Label><Input id="email" name="email" type="email" required autoComplete="email" className="h-10" /></div>
              <div>
                <Label htmlFor="password" hint={login ? undefined : "8 to 72 characters"}>Password</Label>
                <Input id="password" name="password" type="password" required minLength={login ? 1 : 8} maxLength={login ? undefined : 72} className="h-10"
                  autoComplete={login ? "current-password" : "new-password"} />
              </div>
              {error && <ErrorNote message={error} />}
              <Button variant="primary" size="lg" className="w-full" disabled={busy} type="submit">
                {busy ? "Please wait…" : login ? "Sign in" : "Create account"}
              </Button>
            </form>
            <p className="mt-6 text-sm text-muted">
              {login ? <>New to AlgoForge? <Link className="font-medium text-fg underline-offset-4 hover:underline" href="/register">Create an account</Link></>
                : <>Already have an account? <Link className="font-medium text-fg underline-offset-4 hover:underline" href="/login">Sign in</Link></>}
            </p>
          </div>
        </div>
        <p className="text-xs text-faint">Execution decides correctness. AI explains. Evidence drives learning.</p>
      </section>

      <section className="hidden border-l border-line bg-bg lg:flex lg:items-center lg:justify-center lg:px-12">
        <div className="w-full max-w-md">
          <h2 className="text-xl font-semibold tracking-tight text-fg">Practise on a real judge.</h2>
          <p className="mt-2 text-sm leading-relaxed text-muted">Timed assessments, a learning map that only moves on evidence, and feedback from actually running your code.</p>
          <div className="mt-8"><Preview /></div>
          <ul className="mt-8 space-y-2.5">
            {POINTS.map((p) => (
              <li key={p} className="flex items-center gap-2.5 text-sm text-muted">
                <span className="grid size-5 place-items-center rounded-full bg-panel-3 text-fg"><Check className="size-3" aria-hidden /></span>{p}
              </li>
            ))}
          </ul>
        </div>
      </section>
    </div>
  );
}
