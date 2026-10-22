import { useId, type ButtonHTMLAttributes, type InputHTMLAttributes, type ReactNode, type SelectHTMLAttributes, type TextareaHTMLAttributes } from "react";
import { AlertTriangle, Inbox } from "lucide-react";
import { verdictTone, VERDICT_LABEL } from "@/lib/format";

export function cx(...c: (string | false | null | undefined)[]) {
  return c.filter(Boolean).join(" ");
}

type Variant = "primary" | "secondary" | "ghost" | "danger";

export function Button({ variant = "secondary", size = "md", className, ...p }: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: Variant; size?: "sm" | "md" | "lg" }) {
  const v = {
    primary: "ember-fill text-[#1a0c04] font-semibold shadow-[0_8px_24px_-8px_rgb(255_122_47/0.7),inset_0_1px_0_rgb(255_255_255/0.35)] hover:brightness-110 hover:shadow-[0_10px_30px_-6px_rgb(255_122_47/0.8),inset_0_1px_0_rgb(255_255_255/0.35)]",
    secondary: "bg-panel-2 text-fg border border-line-strong hover:border-faint/70 hover:bg-panel-3",
    ghost: "text-muted hover:text-fg hover:bg-panel-2",
    danger: "bg-bad/10 text-bad border border-bad/40 hover:bg-bad/20",
  }[variant];
  const s = size === "sm" ? "h-8 px-3 text-xs" : size === "lg" ? "h-11 px-5 text-[15px]" : "h-9 px-4 text-sm";
  return <button className={cx("inline-flex items-center justify-center gap-2 rounded-lg font-medium transition-all duration-150 active:scale-[0.98] disabled:opacity-50 disabled:pointer-events-none whitespace-nowrap", v, s, className)} {...p} />;
}

export function Card({ title, icon, actions, children, className, glow = false, padded = true }: {
  title?: ReactNode; icon?: ReactNode; actions?: ReactNode; children: ReactNode; className?: string; glow?: boolean; padded?: boolean;
}) {
  return (
    <section className={cx("rounded-2xl", glow ? "ember-border shadow-[0_24px_60px_-30px_rgb(255_122_47/0.45)]" : "surface", className)}>
      {(title || actions) && (
        <header className="flex items-center justify-between gap-3 px-5 pt-4 pb-1">
          <h2 className="flex items-center gap-2 text-[15px] font-semibold text-fg">
            {icon && <span className="text-accent">{icon}</span>}
            {title}
          </h2>
          {actions}
        </header>
      )}
      <div className={padded ? "p-5 pt-3" : undefined}>{children}</div>
    </section>
  );
}

export function Badge({ children, className, title }: { children: ReactNode; className?: string; title?: string }) {
  return <span title={title} className={cx("inline-flex items-center gap-1 rounded-full border px-2 py-0.5 text-[11px] font-medium leading-4", className ?? "border-line-strong bg-panel-2/60 text-muted")}>{children}</span>;
}

export function Verdict({ status, short = false }: { status?: string; short?: boolean }) {
  if (!status) return null;
  return (
    <Badge className={verdictTone(status)}>
      <span className="h-1.5 w-1.5 rounded-full bg-current" aria-hidden />
      {short ? status.replaceAll("_", " ") : VERDICT_LABEL[status] ?? status}
    </Badge>
  );
}

const field = "rounded-lg border border-line-strong bg-panel-2/80 px-3 text-sm text-fg placeholder:text-faint transition-colors hover:border-faint/60 focus:border-accent focus:outline-none focus:ring-2 focus:ring-accent/25";
// Full width unless the caller sets a width (two width utilities would conflict).
const width = (c?: string) => (c && /(^|\s)w-/.test(c) ? "" : "w-full");

export function Input(p: InputHTMLAttributes<HTMLInputElement>) {
  return <input {...p} className={cx(field, width(p.className), "h-9", p.className)} />;
}

export function Select(p: SelectHTMLAttributes<HTMLSelectElement>) {
  return <select {...p} className={cx(field, width(p.className), "h-9 pr-8", p.className)} />;
}

export function Textarea(p: TextareaHTMLAttributes<HTMLTextAreaElement>) {
  return <textarea {...p} className={cx(field, width(p.className), "py-2 font-mono text-[13px]", p.className)} />;
}

export function Label({ children, htmlFor }: { children: ReactNode; htmlFor?: string }) {
  return <label htmlFor={htmlFor} className="mb-1.5 block text-xs font-medium text-muted">{children}</label>;
}

export function Spinner({ label }: { label?: string }) {
  return (
    <span className="inline-flex items-center gap-2.5 text-sm text-muted" role="status">
      <span className="relative h-4 w-4" aria-hidden>
        <span className="absolute inset-0 rounded-full border-2 border-line-strong" />
        <span className="absolute inset-0 animate-spin rounded-full border-2 border-transparent border-t-accent border-r-gold" />
      </span>
      {label ?? "Loading…"}
    </span>
  );
}

export function ErrorNote({ message, onRetry }: { message: string; onRetry?: () => void }) {
  return (
    <div role="alert" className="flex items-center justify-between gap-3 rounded-xl border border-bad/40 bg-bad/10 px-3.5 py-2.5 text-sm text-bad">
      <span className="flex items-center gap-2"><AlertTriangle className="h-4 w-4 shrink-0" aria-hidden />{message}</span>
      {onRetry && <Button size="sm" variant="ghost" onClick={onRetry}>Retry</Button>}
    </div>
  );
}

export function Empty({ title, children, icon }: { title: string; children?: ReactNode; icon?: ReactNode }) {
  return (
    <div className="rounded-2xl border border-dashed border-line-strong px-6 py-12 text-center">
      <div className="mx-auto mb-3 grid h-11 w-11 place-items-center rounded-xl bg-panel-2 text-accent">{icon ?? <Inbox className="h-5 w-5" aria-hidden />}</div>
      <p className="font-display text-[15px] font-semibold text-fg">{title}</p>
      {children && <div className="mt-1.5 text-sm text-muted">{children}</div>}
    </div>
  );
}

/** Page title block: mono kicker in ember, display title, optional subtitle and actions. */
export function PageHeader({ kicker, title, subtitle, actions }: { kicker?: string; title: ReactNode; subtitle?: ReactNode; actions?: ReactNode }) {
  return (
    <div className="mb-8 flex flex-wrap items-end justify-between gap-4 animate-fade-up">
      <div className="max-w-3xl">
        {kicker && <p className="mb-2 font-mono text-[11px] font-medium uppercase tracking-[0.2em] text-accent">{kicker}</p>}
        <h1 className="text-[1.75rem] font-bold leading-tight tracking-tight sm:text-[2rem]">{title}</h1>
        {subtitle && <p className="mt-2 text-[15px] leading-relaxed text-muted">{subtitle}</p>}
      </div>
      {actions && <div className="flex items-center gap-2">{actions}</div>}
    </div>
  );
}

export function Meter({ value, tone, label }: { value: number; tone?: string; label: string }) {
  const pct = Math.max(0, Math.min(100, value));
  return (
    <div className="h-1.5 w-full overflow-hidden rounded-full bg-line" role="meter" aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round(pct)} aria-label={label}>
      <div className={cx("h-full rounded-full transition-[width] duration-700", tone ?? "ember-fill")} style={{ width: `${pct}%` }} />
    </div>
  );
}

/** Circular gauge with the ember gradient; children render in the centre. */
export function Ring({ value, size = 120, stroke = 10, label, children, tone }: {
  value: number; size?: number; stroke?: number; label: string; children?: ReactNode; tone?: string;
}) {
  const id = useId().replace(/:/g, "");
  const r = (size - stroke) / 2;
  const c = 2 * Math.PI * r;
  const pct = Math.max(0, Math.min(100, value));
  return (
    <div className="relative grid place-items-center" style={{ width: size, height: size }} role="meter" aria-label={label} aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round(pct)}>
      <svg width={size} height={size} className="-rotate-90">
        <defs>
          <linearGradient id={id} x1="0" y1="0" x2="1" y2="1"><stop offset="0" stopColor="#ffc35a" /><stop offset="0.5" stopColor="#ff7a2f" /><stop offset="1" stopColor="#ff4d6d" /></linearGradient>
        </defs>
        <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke="var(--color-line)" strokeWidth={stroke} />
        <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke={tone ?? `url(#${id})`} strokeWidth={stroke} strokeLinecap="round"
          strokeDasharray={c} strokeDashoffset={c * (1 - pct / 100)} className="transition-[stroke-dashoffset] duration-1000 ease-out" />
      </svg>
      <div className="absolute inset-0 grid place-items-center text-center">{children}</div>
    </div>
  );
}

export function StatTile({ label, value, sub, icon, accent = "text-accent" }: { label: string; value: ReactNode; sub?: ReactNode; icon?: ReactNode; accent?: string }) {
  return (
    <div className="surface group relative overflow-hidden rounded-2xl px-5 py-4">
      <div className="pointer-events-none absolute -right-6 -top-6 h-20 w-20 rounded-full bg-accent/10 blur-2xl transition-opacity group-hover:opacity-100 opacity-60" aria-hidden />
      <div className="flex items-center justify-between">
        <p className="text-xs font-medium text-muted">{label}</p>
        {icon && <span className={accent}>{icon}</span>}
      </div>
      <p className="mt-2 font-display text-[1.75rem] font-bold leading-none tabular-nums">{value}</p>
      {sub && <p className="mt-1.5 text-xs text-faint">{sub}</p>}
    </div>
  );
}

export function Kbd({ children }: { children: ReactNode }) {
  return <kbd className="rounded border border-line-strong bg-bg/60 px-1 font-mono text-[10px] text-muted">{children}</kbd>;
}

export function Skeleton({ className }: { className?: string }) {
  return <div className={cx("skeleton rounded-lg", className)} aria-hidden />;
}

/** Celebratory spark burst (forge theme). Purely decorative and disabled for reduced motion. */
export function Sparks({ count = 14 }: { count?: number }) {
  return (
    <span className="spark-burst pointer-events-none absolute inset-0" aria-hidden>
      {Array.from({ length: count }, (_, i) => (
        <i key={i} style={{ ["--a" as string]: `${(360 / count) * i}deg`, ["--d" as string]: `${38 + (i % 4) * 14}px`, animationDelay: `${(i % 3) * 40}ms` }} />
      ))}
    </span>
  );
}
