import Link from "next/link";
import type { ButtonHTMLAttributes, InputHTMLAttributes, ReactNode, SelectHTMLAttributes, TextareaHTMLAttributes } from "react";
import { AlertTriangle, ChevronLeft, Inbox, type LucideIcon } from "lucide-react";
import { verdictTone, VERDICT_LABEL } from "@/lib/format";
import { CountUp } from "./CountUp";

export function cx(...c: (string | false | null | undefined)[]) {
  return c.filter(Boolean).join(" ");
}

/** Status colours for badges and chips: a 10% tint, the colour as text, and a hairline ring. */
export const TONE = {
  neutral: "bg-panel-2 text-muted ring-line",
  accent: "bg-accent/10 text-accent ring-accent/25",
  ok: "bg-ok/10 text-ok ring-ok/25",
  bad: "bg-bad/10 text-bad ring-bad/25",
  warn: "bg-warn/10 text-warn ring-warn/25",
  info: "bg-info/10 text-info ring-info/25",
  violet: "bg-violet/10 text-violet ring-violet/25",
} as const;
export type Tone = keyof typeof TONE;

// ---- layout -----------------------------------------------------------------------------------------------------

/** Page container: one width and gutter for every screen. */
export function Page({ children, width = "max-w-6xl" }: { children: ReactNode; width?: string }) {
  return <div className={cx("mx-auto px-4 py-8 sm:px-8", width)}>{children}</div>;
}

export function PageHeader({ title, description, actions }: { title: ReactNode; description?: ReactNode; actions?: ReactNode }) {
  return (
    <div className="mb-8 flex flex-wrap items-end justify-between gap-4 animate-enter">
      <div className="min-w-0 max-w-3xl">
        <h1 className="text-2xl font-semibold tracking-tight text-fg">{title}</h1>
        {description && <p className="mt-1.5 text-sm leading-relaxed text-muted">{description}</p>}
      </div>
      {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
    </div>
  );
}

export function BackLink({ href, children }: { href: string; children: ReactNode }) {
  return (
    <Link href={href} className="mb-4 inline-flex items-center gap-1 text-[13px] text-muted transition-colors hover:text-fg">
      <ChevronLeft className="size-4" aria-hidden />{children}
    </Link>
  );
}

/** Section heading inside a page, above a group of cards. */
export function SectionTitle({ children, actions }: { children: ReactNode; actions?: ReactNode }) {
  return (
    <div className="mb-3 flex items-center justify-between gap-3">
      <h2 className="text-sm font-semibold text-fg">{children}</h2>
      {actions}
    </div>
  );
}

export function Card({ title, description, icon, actions, children, className, bodyClassName, padded = true }: {
  title?: ReactNode; description?: ReactNode; icon?: ReactNode; actions?: ReactNode; children: ReactNode;
  className?: string; bodyClassName?: string; padded?: boolean;
}) {
  return (
    <section className={cx("card min-w-0 animate-enter", className)}>
      {(title || actions) && (
        <header className="flex min-h-12 items-center justify-between gap-3 border-b border-line px-5 py-2.5">
          <div className="min-w-0">
            <h2 className="flex items-center gap-2 text-sm font-semibold text-fg">
              {icon && <span className="text-faint [&_svg]:size-4">{icon}</span>}
              {title}
            </h2>
            {description && <p className="mt-0.5 text-xs text-muted">{description}</p>}
          </div>
          {actions && <div className="flex shrink-0 items-center gap-2">{actions}</div>}
        </header>
      )}
      <div className={cx(padded && "p-5", bodyClassName)}>{children}</div>
    </section>
  );
}

/** A quiet notice row (empty-state nudges, in-progress banners). */
export function Callout({ icon, children, action, tone = "neutral" }: { icon?: ReactNode; children: ReactNode; action?: ReactNode; tone?: "neutral" | "accent" | "warn" }) {
  return (
    <div className={cx("flex items-center gap-3 rounded-lg border px-4 py-3 text-sm animate-enter",
      tone === "accent" ? "border-accent/25 bg-accent/5" : tone === "warn" ? "border-warn/25 bg-warn/5" : "border-line bg-panel-2/60")}>
      {icon && <span className={cx("shrink-0 [&_svg]:size-4", tone === "accent" ? "text-accent" : tone === "warn" ? "text-warn" : "text-faint")}>{icon}</span>}
      <div className="min-w-0 flex-1 text-muted">{children}</div>
      {action}
    </div>
  );
}

// ---- actions ----------------------------------------------------------------------------------------------------

type Variant = "primary" | "secondary" | "ghost" | "danger";

const VARIANT: Record<Variant, string> = {
  primary: "bg-fg text-bg shadow-xs hover:bg-fg/85",
  secondary: "border border-line-strong bg-panel text-fg shadow-xs hover:bg-panel-2",
  ghost: "text-muted hover:bg-panel-2 hover:text-fg",
  danger: "border border-bad/30 bg-bad/10 text-bad hover:bg-bad/15",
};

const SIZE = {
  sm: "h-8 gap-1.5 px-2.5 text-[13px] [&_svg]:size-3.5",
  md: "h-9 gap-2 px-3.5 text-sm [&_svg]:size-4",
  lg: "h-10 gap-2 px-4 text-sm [&_svg]:size-4",
};

export function Button({ variant = "secondary", size = "md", className, ...p }: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: Variant; size?: keyof typeof SIZE }) {
  return (
    <button
      className={cx("inline-flex shrink-0 items-center justify-center whitespace-nowrap rounded-md font-medium transition-colors",
        "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent/50 focus-visible:ring-offset-2 focus-visible:ring-offset-panel",
        "disabled:pointer-events-none disabled:opacity-50 [&_svg]:shrink-0", VARIANT[variant], SIZE[size], className)}
      {...p}
    />
  );
}

/** Same look as Button, for navigation. */
export function ButtonLink({ href, variant = "secondary", size = "md", className, children }: { href: string; variant?: Variant; size?: keyof typeof SIZE; className?: string; children: ReactNode }) {
  return (
    <Link href={href} className={cx("inline-flex shrink-0 items-center justify-center whitespace-nowrap rounded-md font-medium transition-colors [&_svg]:shrink-0",
      VARIANT[variant], SIZE[size], className)}>
      {children}
    </Link>
  );
}

// ---- status -----------------------------------------------------------------------------------------------------

export function Badge({ children, tone = "neutral", className, title }: { children: ReactNode; tone?: Tone; className?: string; title?: string }) {
  return (
    <span title={title} className={cx("inline-flex h-5 items-center gap-1 whitespace-nowrap rounded-md px-1.5 text-xs font-medium ring-1 ring-inset [&_svg]:size-3", TONE[tone], className)}>
      {children}
    </span>
  );
}

export function Verdict({ status }: { status?: string }) {
  if (!status) return null;
  return (
    <Badge tone={verdictTone(status)}>
      <span className="size-1.5 rounded-full bg-current" aria-hidden />
      {VERDICT_LABEL[status] ?? status}
    </Badge>
  );
}

export function Meter({ value, tone = "bg-accent", label }: { value: number; tone?: string; label: string }) {
  const pct = Math.max(0, Math.min(100, value));
  return (
    <div className="h-1.5 w-full overflow-hidden rounded-full bg-panel-3" role="meter" aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round(pct)} aria-label={label}>
      <div className={cx("h-full rounded-full transition-[width] duration-500 animate-grow", tone)} style={{ width: `${pct}%` }} />
    </div>
  );
}

/** Circular gauge; children render in the centre. */
export function Ring({ value, size = 120, stroke = 8, label, children }: { value: number; size?: number; stroke?: number; label: string; children?: ReactNode }) {
  const r = (size - stroke) / 2;
  const c = 2 * Math.PI * r;
  const pct = Math.max(0, Math.min(100, value));
  return (
    <div className="relative grid shrink-0 place-items-center" style={{ width: size, height: size }} role="meter" aria-label={label} aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round(pct)}>
      <svg width={size} height={size} className="-rotate-90" aria-hidden>
        <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke="var(--panel-3)" strokeWidth={stroke} />
        <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke="var(--accent)" strokeWidth={stroke} strokeLinecap="round"
          strokeDasharray={c} strokeDashoffset={c * (1 - pct / 100)} className="transition-[stroke-dashoffset] duration-700 ease-out"
          style={{ ["--ring-c" as string]: c, animation: "ring-in 1s cubic-bezier(0.16, 1, 0.3, 1) both" }} />
      </svg>
      <div className="absolute inset-0 grid place-items-center text-center">{children}</div>
    </div>
  );
}

/** One figure in a stats strip (see StatStrip). */
export function Stat({ label, value, sub }: { label: string; value: ReactNode; sub?: ReactNode }) {
  return (
    <div className="bg-panel px-5 py-4 animate-enter">
      <p className="text-[13px] text-muted">{label}</p>
      <p className="mt-1 text-2xl font-semibold tracking-tight tabular-nums text-fg">{typeof value === "number" ? <CountUp value={value} /> : value}</p>
      {sub && <p className="mt-0.5 text-xs text-faint">{sub}</p>}
    </div>
  );
}

/** Hairline-separated grid of Stat cells (the 1px gaps show the border colour behind them). */
export function StatStrip({ children, className }: { children: ReactNode; className?: string }) {
  return <div className={cx("stagger grid grid-cols-2 gap-px overflow-hidden rounded-lg border border-line bg-line shadow-(--shadow-card) lg:grid-cols-4", className)}>{children}</div>;
}

export function Spinner({ label }: { label?: string }) {
  return (
    <span className="inline-flex items-center gap-2 text-sm text-muted" role="status">
      <span className="size-4 animate-spin rounded-full border-2 border-line-strong border-t-fg" aria-hidden />
      {label ?? "Loading…"}
    </span>
  );
}

export function ErrorNote({ message, onRetry }: { message: string; onRetry?: () => void }) {
  return (
    <div role="alert" className="flex items-center justify-between gap-3 rounded-md border border-bad/25 bg-bad/5 px-3 py-2 text-sm text-bad">
      <span className="flex items-center gap-2"><AlertTriangle className="size-4 shrink-0" aria-hidden />{message}</span>
      {onRetry && <Button size="sm" variant="ghost" onClick={onRetry}>Retry</Button>}
    </div>
  );
}

export function Empty({ title, children, icon }: { title: string; children?: ReactNode; icon?: ReactNode }) {
  return (
    <div className="rounded-lg border border-dashed border-line-strong px-6 py-10 text-center">
      <div className="mx-auto mb-3 grid size-10 place-items-center rounded-md border border-line bg-panel-2 text-muted [&_svg]:size-[18px]">{icon ?? <Inbox aria-hidden />}</div>
      <p className="text-sm font-medium text-fg">{title}</p>
      {children && <div className="mx-auto mt-1 max-w-sm text-sm text-muted">{children}</div>}
    </div>
  );
}

export function Skeleton({ className }: { className?: string }) {
  return <div className={cx("animate-pulse rounded-md bg-panel-2", className)} aria-hidden />;
}

export function Kbd({ children }: { children: ReactNode }) {
  return <kbd className="rounded border border-line bg-panel-2 px-1 font-mono text-[11px] text-muted">{children}</kbd>;
}

// ---- forms ------------------------------------------------------------------------------------------------------

const field = "rounded-md border border-line-strong bg-panel text-sm text-fg shadow-xs transition-colors placeholder:text-faint hover:border-faint/60 focus:border-accent focus:outline-none focus:ring-3 focus:ring-accent/15 disabled:opacity-50";
// Full width unless the caller sets a width (two width utilities would conflict).
const width = (c?: string) => (c && /(^|\s)w-/.test(c) ? "" : "w-full");

export function Input(p: InputHTMLAttributes<HTMLInputElement>) {
  return <input {...p} className={cx(field, width(p.className), "h-9 px-3", p.className)} />;
}

export function Select(p: SelectHTMLAttributes<HTMLSelectElement>) {
  return <select {...p} className={cx(field, width(p.className), "select-chevron h-9 pl-3 pr-8", p.className)} />;
}

export function Textarea(p: TextareaHTMLAttributes<HTMLTextAreaElement>) {
  return <textarea {...p} className={cx(field, width(p.className), "px-3 py-2 font-mono text-[13px] leading-relaxed", p.className)} />;
}

export function Label({ children, htmlFor, hint }: { children: ReactNode; htmlFor?: string; hint?: ReactNode }) {
  return (
    <label htmlFor={htmlFor} className="mb-1.5 block text-[13px] font-medium text-fg">
      {children}{hint && <span className="ml-1.5 font-normal text-faint">{hint}</span>}
    </label>
  );
}

/** Pill-group radio (filters, theme). Options may be icon-only; the label stays as the accessible name. */
export function Segmented<T extends string>({ label, value, options, onChange }: {
  label: string; value: T; onChange: (v: T) => void; options: { value: T; label: string; icon?: LucideIcon; iconOnly?: boolean }[];
}) {
  return (
    <div role="radiogroup" aria-label={label} className="inline-flex max-w-full shrink-0 overflow-x-auto rounded-md border border-line bg-panel-2 p-0.5">
      {options.map((o) => {
        const on = value === o.value;
        const Icon = o.icon;
        return (
          <button key={o.value} type="button" role="radio" aria-checked={on} aria-label={o.iconOnly ? o.label : undefined} title={o.iconOnly ? o.label : undefined}
            onClick={() => onChange(o.value)}
            className={cx("inline-flex h-7 items-center gap-1.5 whitespace-nowrap rounded-[5px] text-[13px] font-medium transition-colors",
              o.iconOnly ? "w-7 justify-center" : "px-2.5",
              on ? "bg-panel text-fg shadow-xs ring-1 ring-line" : "text-muted hover:text-fg")}>
            {Icon && <Icon className="size-3.5" aria-hidden />}
            {!o.iconOnly && o.label}
          </button>
        );
      })}
    </div>
  );
}

/** Underlined tabs for panels. `dot` marks a tab worth a look (the coach). */
export function TabBar<T extends string>({ tabs, value, onChange, trailing, size = "md" }: {
  tabs: { id: T; label: string; icon?: LucideIcon; dot?: boolean }[]; value: T; onChange: (t: T) => void; trailing?: ReactNode; size?: "sm" | "md";
}) {
  return (
    <div role="tablist" className={cx("flex shrink-0 items-stretch gap-5 overflow-x-auto border-b border-line px-4", size === "sm" ? "h-9" : "h-11")}>
      {tabs.map((t) => {
        const on = t.id === value;
        const Icon = t.icon;
        return (
          <button key={t.id} role="tab" aria-selected={on} onClick={() => onChange(t.id)}
            className={cx("-mb-px flex items-center gap-1.5 whitespace-nowrap border-b-2 text-[13px] font-medium transition-colors",
              on ? "border-accent text-fg" : "border-transparent text-muted hover:text-fg")}>
            {Icon && <Icon className={cx("size-4", on ? "text-fg" : "text-faint")} aria-hidden />}
            {t.label}
            {t.dot && !on && <span className="size-1.5 rounded-full bg-accent" aria-hidden />}
          </button>
        );
      })}
      {trailing && <div className="ml-auto flex items-center">{trailing}</div>}
    </div>
  );
}
