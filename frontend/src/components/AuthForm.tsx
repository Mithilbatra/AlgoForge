"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useState, type FormEvent } from "react";
import { ArrowRight, Bot, Network, ShieldCheck, Timer } from "lucide-react";
import { errorMessage, post } from "@/lib/api";
import { LogoMark, Wordmark } from "./Logo";
import { Button, ErrorNote, Input, Label } from "./ui";

const FEATURES = [
  { icon: ShieldCheck, title: "A real judge, not a guess", text: "Python, C++ and Java compiled and run in isolated sandboxes, with real time and memory figures." },
  { icon: Bot, title: "Forge Coach", text: "An AI coach that nudges you towards the idea and never hands over the solution." },
  { icon: Timer, title: "OA Arena", text: "Timed assessments with server-side clocks, partial credit and a full post-mortem." },
  { icon: Network, title: "Evidence-driven learning", text: "A concept map that only moves when you prove you can transfer an idea to new problems." },
];

// Fixed positions so server and client render the same embers.
const EMBERS = Array.from({ length: 26 }, (_, i) => ({
  left: `${(i * 37) % 100}%`, t: `${7 + (i % 5) * 1.6}s`, delay: `${(i * 0.53) % 7}s`, x: `${((i * 23) % 60) - 30}px`,
}));

/** Abstract algorithm-graph constellation behind the hero copy. */
function Constellation() {
  const nodes = [[60, 80], [180, 40], [300, 120], [140, 200], [260, 250], [380, 60], [420, 220], [90, 320], [340, 340], [470, 330]];
  const edges = [[0, 1], [1, 2], [0, 3], [3, 4], [2, 4], [1, 5], [5, 6], [4, 6], [3, 7], [4, 8], [8, 9], [6, 9], [2, 5]];
  return (
    <svg viewBox="0 0 520 400" className="absolute inset-0 h-full w-full opacity-30 [mask-image:radial-gradient(70%_60%_at_75%_25%,black,transparent)]" preserveAspectRatio="xMidYMid slice" aria-hidden>
      {edges.map(([a, b], i) => <line key={i} x1={nodes[a][0]} y1={nodes[a][1]} x2={nodes[b][0]} y2={nodes[b][1]} stroke="#ff7a2f" strokeOpacity="0.35" strokeWidth="1" />)}
      {nodes.map(([x, y], i) => (
        <circle key={i} cx={x} cy={y} r={i % 3 === 0 ? 3.5 : 2.5} fill="#ffc35a" className="animate-glow" style={{ animationDelay: `${i * 0.4}s` }} />
      ))}
    </svg>
  );
}

export function AuthForm({ mode }: { mode: "login" | "register" }) {
  const router = useRouter();
  const next = useSearchParams().get("next");
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const f = new FormData(e.currentTarget);
    setBusy(true);
    setError(undefined);
    try {
      await post(mode === "login" ? "/api/auth/login" : "/api/auth/register", Object.fromEntries(f.entries()));
      // only same-site relative paths are followed after sign-in
      router.replace(next && next.startsWith("/") && !next.startsWith("//") ? next : "/");
    } catch (err) {
      setError(errorMessage(err));
      setBusy(false);
    }
  }

  return (
    <div className="grid min-h-screen lg:grid-cols-[1.15fr_1fr]">
      <section className="relative hidden overflow-hidden border-r border-line bg-[radial-gradient(900px_600px_at_20%_100%,rgb(255_122_47/0.22),transparent_60%),radial-gradient(700px_500px_at_100%_0%,rgb(255_77_109/0.12),transparent_60%)] lg:block">
        <Constellation />
        <div className="ember-field absolute inset-0" aria-hidden>
          {EMBERS.map((e, i) => <i key={i} style={{ left: e.left, ["--t" as string]: e.t, ["--delay" as string]: e.delay, ["--x" as string]: e.x }} />)}
        </div>
        <div className="relative flex h-full flex-col justify-between p-12 xl:p-16">
          <Link href="/" className="flex items-center gap-3 text-xl"><LogoMark size={40} /><Wordmark /></Link>
          <div className="max-w-xl animate-fade-up">
            <div className="mb-8 inline-block animate-float drop-shadow-[0_20px_50px_rgb(255_122_47/0.45)]"><LogoMark size={92} /></div>
            <h1 className="text-5xl font-bold leading-[1.05] tracking-tight xl:text-6xl">
              Forge your <span className="ember-text">algorithms.</span>
            </h1>
            <p className="mt-5 max-w-lg text-lg leading-relaxed text-muted">
              Practise on a real judge, pressure-test yourself in timed assessments, and learn from evidence, not guesswork.
            </p>
            <ul className="mt-10 grid gap-5 sm:grid-cols-2">
              {FEATURES.map(({ icon: Icon, title, text }) => (
                <li key={title} className="flex gap-3">
                  <span className="grid h-9 w-9 shrink-0 place-items-center rounded-xl border border-accent/30 bg-accent/10 text-accent"><Icon className="h-[18px] w-[18px]" aria-hidden /></span>
                  <span><span className="block text-sm font-semibold text-fg">{title}</span><span className="text-[13px] leading-snug text-muted">{text}</span></span>
                </li>
              ))}
            </ul>
          </div>
          <p className="font-mono text-[11px] uppercase tracking-[0.2em] text-faint">Execution decides correctness · AI explains · evidence drives learning</p>
        </div>
      </section>

      <section className="relative grid place-items-center px-5 py-12">
        <div className="w-full max-w-sm animate-fade-up">
          <div className="mb-8 flex flex-col items-center gap-3 lg:hidden">
            <LogoMark size={56} />
            <Wordmark className="text-2xl" />
          </div>
          <div className="ember-border rounded-3xl p-7 shadow-[0_30px_80px_-30px_rgb(255_122_47/0.35)]">
            <h2 className="text-2xl font-bold">{mode === "login" ? "Welcome back" : "Start forging"}</h2>
            <p className="mt-1 text-sm text-muted">{mode === "login" ? "Sign in to pick up where you left off." : "Create your account. It takes ten seconds."}</p>
            <form onSubmit={submit} className="mt-6 space-y-4">
              {mode === "register" && (
                <div><Label htmlFor="displayName">Display name</Label><Input id="displayName" name="displayName" required maxLength={60} autoComplete="nickname" className="h-11" /></div>
              )}
              <div><Label htmlFor="email">Email</Label><Input id="email" name="email" type="email" required autoComplete="email" className="h-11" /></div>
              <div>
                <Label htmlFor="password">Password</Label>
                <Input id="password" name="password" type="password" required minLength={mode === "register" ? 8 : 1} className="h-11"
                  autoComplete={mode === "login" ? "current-password" : "new-password"} />
                {mode === "register" && <p className="mt-1.5 text-xs text-faint">At least 8 characters.</p>}
              </div>
              {error && <ErrorNote message={error} />}
              <Button variant="primary" size="lg" className="w-full" disabled={busy} type="submit">
                {busy ? "Please wait…" : mode === "login" ? "Sign in" : "Create account"}
                {!busy && <ArrowRight className="h-4 w-4" aria-hidden />}
              </Button>
            </form>
            <p className="mt-6 text-center text-sm text-muted">
              {mode === "login" ? <>New here? <Link className="font-medium text-accent hover:underline" href="/register">Create an account</Link></>
                : <>Have an account? <Link className="font-medium text-accent hover:underline" href="/login">Sign in</Link></>}
            </p>
          </div>
        </div>
      </section>
    </div>
  );
}
