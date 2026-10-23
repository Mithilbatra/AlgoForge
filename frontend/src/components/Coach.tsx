"use client";

import { useEffect, useRef, useState, type KeyboardEvent } from "react";
import ReactMarkdown from "react-markdown";
import { ArrowUp, Compass, Lightbulb, ListOrdered, type LucideIcon } from "lucide-react";
import { errorMessage, post } from "@/lib/api";
import type { Language } from "@/lib/types";
import { LogoMark } from "./Logo";
import { Badge, cx } from "./ui";

interface CoachReply { reply: string; nextStep?: string; level: number; source: "ai" | "built-in"; provider?: string; model?: string; notice?: string }
type Msg = { role: "user"; text: string; level: number } | { role: "coach"; reply: CoachReply } | { role: "error"; text: string };

const LEVELS: { level: number; label: string; icon: LucideIcon; text: string }[] = [
  { level: 1, label: "Nudge", icon: Lightbulb, text: "A question pointing at the key idea" },
  { level: 2, label: "Approach", icon: Compass, text: "The technique, and why it fits" },
  { level: 3, label: "Outline", icon: ListOrdered, text: "Step-by-step plan. AI outlines count as a reveal." },
];

const STARTERS = ["Give me a nudge to get started", "Why is my last submission failing?", "Is my approach fast enough for these constraints?"];

/** Forge Coach chat. The server decides what the coach may say; this only sends the learner's context. */
export function Coach({ problemId, code, language, submissionId }: { problemId: number; code: string; language: Language; submissionId?: number }) {
  const [level, setLevel] = useState(1);
  const [msgs, setMsgs] = useState<Msg[]>([]);
  const [text, setText] = useState("");
  const [busy, setBusy] = useState(false);
  const scroller = useRef<HTMLDivElement>(null);

  useEffect(() => {
    scroller.current?.scrollTo({ top: scroller.current.scrollHeight, behavior: "smooth" });
  }, [msgs, busy]);

  async function ask(message: string) {
    if (busy) return;
    const history = msgs.flatMap((m) => m.role === "user" ? [{ role: "user", text: m.text }] : m.role === "coach" ? [{ role: "coach", text: m.reply.reply }] : []).slice(-8);
    setMsgs((m) => [...m, { role: "user", text: message || LEVELS[level - 1].label, level }]);
    setText("");
    setBusy(true);
    try {
      const reply = await post<CoachReply>(`/api/problems/${problemId}/coach`, { level, message: message || null, code, language, history, submissionId });
      setMsgs((m) => [...m, { role: "coach", reply }]);
    } catch (e) {
      setMsgs((m) => [...m, { role: "error", text: errorMessage(e) }]);
    } finally {
      setBusy(false);
    }
  }

  const onKey = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); ask(text.trim()); }
  };

  return (
    <div className="flex h-full flex-col">
      <div className="flex items-center gap-3">
        <LogoMark size={32} />
        <div>
          <p className="text-sm font-semibold text-fg">Forge Coach</p>
          <p className="text-xs text-muted">Hints, not solutions. It reads your code and your last verdict.</p>
        </div>
      </div>

      <div className="mt-4 grid grid-cols-3 gap-2" role="radiogroup" aria-label="Hint level">
        {LEVELS.map(({ level: l, label, icon: Icon, text: t }) => (
          <button key={l} role="radio" aria-checked={level === l} onClick={() => setLevel(l)}
            className={cx("flex flex-col items-start justify-start rounded-md border p-2.5 text-left transition-colors",
              level === l ? "border-accent/60 bg-accent/5 ring-1 ring-accent/30" : "border-line hover:bg-panel-2/60")}>
            <span className="flex items-center gap-1.5 text-[13px] font-medium text-fg">
              <Icon className={cx("size-3.5", level === l ? "text-accent" : "text-faint")} aria-hidden />{l}. {label}
            </span>
            <span className="mt-1 block text-xs leading-snug text-muted">{t}</span>
          </button>
        ))}
      </div>

      <div ref={scroller} className="mt-4 min-h-40 flex-1 space-y-4 overflow-y-auto" aria-live="polite">
        {msgs.length === 0 && (
          <div className="space-y-1.5">
            <p className="mb-2 text-xs text-faint">Try one of these, or ask your own question.</p>
            {STARTERS.map((s) => (
              <button key={s} onClick={() => ask(s)} className="block w-full rounded-md border border-line px-3 py-2 text-left text-[13px] text-muted transition-colors hover:bg-panel-2/60 hover:text-fg">{s}</button>
            ))}
          </div>
        )}
        {msgs.map((m, i) => m.role === "user" ? (
          <div key={i} className="flex justify-end">
            <div className="max-w-[85%] rounded-lg bg-panel-2 px-3 py-2 text-[13px] text-fg">
              <span className="mr-1.5 text-xs text-faint">{LEVELS[m.level - 1].label} ·</span>{m.text}
            </div>
          </div>
        ) : m.role === "error" ? (
          <p key={i} className="rounded-md border border-bad/25 bg-bad/5 px-3 py-2 text-[13px] text-bad">{m.text}</p>
        ) : (
          <div key={i} className="flex gap-2.5">
            <LogoMark size={24} className="mt-0.5 shrink-0" />
            <div className="min-w-0 flex-1">
              <div className="prose-af text-[13.5px]"><ReactMarkdown>{m.reply.reply}</ReactMarkdown></div>
              {m.reply.nextStep && (
                <p className="mt-2.5 rounded-md border border-line bg-panel-2/50 px-3 py-2 text-[13px] text-fg"><span className="font-medium">Next step: </span>{m.reply.nextStep}</p>
              )}
              <div className="mt-2 flex flex-wrap items-center gap-2">
                <Badge>{m.reply.source === "ai" ? `AI · ${m.reply.provider}` : "Built-in coach"}</Badge>
                {m.reply.notice && <span className="text-xs text-faint">{m.reply.notice}</span>}
              </div>
            </div>
          </div>
        ))}
        {busy && (
          <div className="flex items-center gap-2.5">
            <LogoMark size={24} />
            <span className="flex gap-1" aria-label="Coach is thinking">
              {[0, 1, 2].map((d) => <i key={d} className="size-1.5 animate-pulse rounded-full bg-faint" style={{ animationDelay: `${d * 150}ms` }} />)}
            </span>
          </div>
        )}
      </div>

      <form className="mt-3 flex items-end gap-2 rounded-lg border border-line-strong bg-panel p-1.5 shadow-xs focus-within:border-accent focus-within:ring-3 focus-within:ring-accent/15"
        onSubmit={(e) => { e.preventDefault(); ask(text.trim()); }}>
        <textarea aria-label="Ask Forge Coach" rows={2} value={text} maxLength={1000} onChange={(e) => setText(e.target.value)} onKeyDown={onKey}
          placeholder={`Ask for a ${LEVELS[level - 1].label.toLowerCase()}, or describe where you're stuck…`}
          className="min-h-10 flex-1 resize-none bg-transparent px-1.5 py-1 text-[13px] text-fg placeholder:text-faint focus:outline-none" />
        <button type="submit" disabled={busy} aria-label="Send to Forge Coach"
          className="grid size-8 shrink-0 place-items-center rounded-md bg-fg text-bg transition-colors hover:bg-fg/85 disabled:opacity-50">
          <ArrowUp className="size-4" aria-hidden />
        </button>
      </form>
    </div>
  );
}
