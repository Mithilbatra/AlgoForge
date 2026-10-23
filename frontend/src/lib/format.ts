import type { Tone } from "@/components/ui";

export const VERDICT_LABEL: Record<string, string> = {
  QUEUED: "Queued", COMPILING: "Compiling", RUNNING: "Running", ACCEPTED: "Accepted", WRONG_ANSWER: "Wrong answer",
  RUNTIME_ERROR: "Runtime error", TIME_LIMIT_EXCEEDED: "Time limit exceeded", MEMORY_LIMIT_EXCEEDED: "Memory limit exceeded",
  COMPILATION_ERROR: "Compilation error", INTERNAL_ERROR: "Internal error", SKIPPED: "Not run",
};

export const VERDICT_SHORT: Record<string, string> = {
  ACCEPTED: "AC", WRONG_ANSWER: "WA", RUNTIME_ERROR: "RE", TIME_LIMIT_EXCEEDED: "TLE", MEMORY_LIMIT_EXCEEDED: "MLE",
  COMPILATION_ERROR: "CE", INTERNAL_ERROR: "IE", SKIPPED: "—", QUEUED: "…", COMPILING: "…", RUNNING: "…",
};

/** Badge tone for a verdict (see TONE in components/ui). */
export function verdictTone(v?: string): Tone {
  switch (v) {
    case "ACCEPTED": return "ok";
    case "WRONG_ANSWER": case "RUNTIME_ERROR": return "bad";
    case "TIME_LIMIT_EXCEEDED": case "COMPILATION_ERROR": return "warn";
    case "MEMORY_LIMIT_EXCEEDED": return "violet";
    case "QUEUED": case "COMPILING": case "RUNNING": return "info";
    default: return "neutral";
  }
}

export const PENDING = ["QUEUED", "COMPILING", "RUNNING"];

export function difficultyTone(d?: string): Tone {
  return d === "EASY" ? "ok" : d === "MEDIUM" ? "warn" : d === "HARD" ? "bad" : "neutral";
}

export function formatDuration(totalSeconds: number): string {
  const s = Math.max(0, Math.floor(totalSeconds));
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), sec = s % 60;
  return h > 0 ? `${h}:${String(m).padStart(2, "0")}:${String(sec).padStart(2, "0")}` : `${m}:${String(sec).padStart(2, "0")}`;
}

export function formatMemory(kb?: number): string {
  if (kb == null) return "—";
  return kb >= 1024 ? `${(kb / 1024).toFixed(1)} MB` : `${kb} KB`;
}

export function relativeTime(iso: string): string {
  const d = (Date.now() - new Date(iso).getTime()) / 1000;
  if (d < 60) return "just now";
  if (d < 3600) return `${Math.floor(d / 60)} min ago`;
  if (d < 86400) return `${Math.floor(d / 3600)} h ago`;
  return new Date(iso).toLocaleDateString();
}

export const STATUS_LABEL: Record<string, string> = {
  UNSEEN: "Unseen", ATTEMPTED: "Attempted", SOLVED: "Solved", NEEDS_REVISION: "Needs revision",
};

export const MASTERY_LABEL: Record<string, string> = {
  INSUFFICIENT_EVIDENCE: "Not enough evidence", WEAK: "Weak", DEVELOPING: "Developing", STRONG: "Strong",
};

export function masteryTone(status: string): string {
  return status === "STRONG" ? "text-ok" : status === "DEVELOPING" ? "text-info" : status === "WEAK" ? "text-bad" : "text-faint";
}

export const LANGUAGE_LABEL: Record<string, string> = { python: "Python 3", cpp: "C++17", java: "Java 21" };
