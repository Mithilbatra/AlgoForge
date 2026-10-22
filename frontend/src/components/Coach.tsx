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
  { level: 1, label: "Nudge", icon: Lightbulb, text: "A question that points at the key idea" },
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
        <div className="relative"><LogoMark size={38} /><span className="absolute -bottom-0.5 -right-0.5 h-3 w-3 rounded-full border-2 border-panel bg-ok" aria-hidden /></div>
        <div>
          <p className="font-display text-[15px] font-semibold">Forge Coach</p>
          <p className="text-xs text-muted">Nudges, not solutions. It sees your code and your last verdict.</p>
        </div>
      </div>

      <div className="mt-4 grid grid-cols-3 gap-2" role="radiogroup" aria-label="Hint level">
        {LEVELS.map(({ level: l, label, icon: Icon, text: t }) => (
          <button key={l} role="radio" aria-checked={level === l} onClick={() => setLevel(l)}
            className={cx("rounded-xl border p-2.5 text-left transition-all",
              level === l ? "border-accent/60 bg-accent/10 shadow-[0_0_0_1px_rgb(255_122_47/0.25)]" : "border-line bg-panel-2/50 hover:border-line-strong")}>
            <Icon className={cx("h-4 w-4", level === l ? "text-accent" : "text-faint")} aria-hidden />
            <p className="mt-1.5 text-xs font-semibold">{l}. {label}</p>
            <p className="mt-0.5 text-[11px] leading-snug text-faint">{t}</p>
          </button>
        ))}
      </div>

      <div ref={scroller} className="mt-4 min-h-40 flex-1 space-y-3 overflow-y-auto pr-1" aria-live="polite">
        {msgs.length === 0 && (
          <div className="space-y-2">
            <p className="text-xs text-faint">Try one of these, or ask your own question:</p>
            {STARTERS.map((s) => (
              <button key={s} onClick={() => ask(s)} className="block w-full rounded-xl border border-dashed border-line-strong px-3 py-2 text-left text-sm text-muted transition-colors hover:border-accent/50 hover:text-fg">{s}</button>
            ))}
          </div>
        )}
        {msgs.map((m, i) => m.role === "user" ? (
          <div key={i} className="flex justify-end animate-fade-up">
            <div className="max-w-[85%] rounded-2xl rounded-br-md bg-panel-3 px-3.5 py-2 text-sm">
              <span className="mr-2 font-mono text-[10px] uppercase tracking-wider text-accent">{LEVELS[m.level - 1].label}</span>{m.text}
            </div>
          </div>
        ) : m.role === "error" ? (
          <p key={i} className="rounded-xl border border-bad/40 bg-bad/10 px-3 py-2 text-sm text-bad">{m.text}</p>
        ) : (
          <div key={i} className="flex gap-2.5 animate-fade-up">
            <LogoMark size={26} className="mt-0.5 shrink-0" />
            <div className="min-w-0 flex-1 rounded-2xl rounded-tl-md border border-line bg-panel-2/70 px-3.5 py-2.5">
              <div className="prose-af text-[13.5px] leading-relaxed text-fg/90"><ReactMarkdown>{m.reply.reply}</ReactMarkdown></div>
              {m.reply.nextStep && (
                <p className="mt-2 rounded-lg border border-accent/25 bg-accent/[0.07] px-2.5 py-1.5 text-xs text-fg/90"><span className="font-semibold text-accent">Next step: </span>{m.reply.nextStep}</p>
              )}
              <div className="mt-2 flex flex-wrap items-center gap-1.5">
                <Badge>{m.reply.source === "ai" ? `AI · ${m.reply.provider}` : "Built-in coach"}</Badge>
                {m.reply.notice && <span className="text-[11px] text-faint">{m.reply.notice}</span>}
              </div>
            </div>
          </div>
        ))}
        {busy && (
          <div className="flex items-center gap-2.5 text-sm text-muted">
            <LogoMark size={26} />
            <span className="flex gap-1" aria-label="Coach is thinking">
              {[0, 1, 2].map((d) => <i key={d} className="h-1.5 w-1.5 animate-bounce rounded-full bg-accent" style={{ animationDelay: `${d * 120}ms` }} />)}
            </span>
          </div>
        )}
      </div>

      <form className="mt-3 flex items-end gap-2 rounded-2xl border border-line-strong bg-panel-2/80 p-2 focus-within:border-accent/60" onSubmit={(e) => { e.preventDefault(); ask(text.trim()); }}>
        <textarea aria-label="Ask Forge Coach" rows={2} value={text} maxLength={1000} onChange={(e) => setText(e.target.value)} onKeyDown={onKey}
          placeholder={`Ask for a ${LEVELS[level - 1].label.toLowerCase()}, or describe where you're stuck…`}
          className="min-h-10 flex-1 resize-none bg-transparent px-1.5 py-1 text-sm text-fg placeholder:text-faint focus:outline-none" />
        <button type="submit" disabled={busy} aria-label="Send to Forge Coach"
          className="grid h-9 w-9 shrink-0 place-items-center rounded-xl ember-fill text-[#1a0c04] transition-all hover:brightness-110 disabled:opacity-50">
          <ArrowUp className="h-4 w-4" aria-hidden />
        </button>
      </form>
    </div>
  );
}
