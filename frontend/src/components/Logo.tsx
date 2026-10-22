import { useId } from "react";

/**
 * The AlgoForge mark: an "A" drawn as an algorithm graph (three white-hot nodes joined by edges) in the ember
 * gradient, with a spark at its peak. The same geometry ships as public/brand/*.svg and app/icon.svg.
 */
export function LogoMark({ size = 32, className, tile = true }: { size?: number; className?: string; tile?: boolean }) {
  const id = useId().replace(/:/g, "");
  return (
    <svg viewBox="0 0 64 64" width={size} height={size} className={className} role="img" aria-label="AlgoForge">
      <defs>
        <linearGradient id={`${id}t`} x1="0" y1="0" x2="0" y2="1"><stop offset="0" stopColor="#1c1712" /><stop offset="1" stopColor="#0a0b10" /></linearGradient>
        <radialGradient id={`${id}g`} cx="0.5" cy="1" r="0.75"><stop offset="0" stopColor="#ff7a2f" stopOpacity="0.55" /><stop offset="1" stopColor="#ff7a2f" stopOpacity="0" /></radialGradient>
        <linearGradient id={`${id}e`} gradientUnits="userSpaceOnUse" x1="0" y1="11" x2="0" y2="54">
          <stop offset="0" stopColor="#ffd27a" /><stop offset="0.5" stopColor="#ff7a2f" /><stop offset="1" stopColor="#ff3d6e" />
        </linearGradient>
      </defs>
      {tile && (
        <>
          <rect width="64" height="64" rx="15" fill={`url(#${id}t)`} />
          <rect width="64" height="64" rx="15" fill={`url(#${id}g)`} />
          <rect x="0.75" y="0.75" width="62.5" height="62.5" rx="14.25" fill="none" stroke="#ffb35c" strokeOpacity="0.28" strokeWidth="1.5" />
        </>
      )}
      <path d="M15.5 51 L32 15 L48.5 51" fill="none" stroke={`url(#${id}e)`} strokeWidth="6" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M22.2 36.4 H41.8" stroke={`url(#${id}e)`} strokeWidth="4.5" strokeLinecap="round" />
      <g fill="#fff4e3" stroke="#ff7a2f" strokeWidth="2">
        <circle cx="32" cy="15" r="4.6" /><circle cx="22.2" cy="36.4" r="4" /><circle cx="41.8" cy="36.4" r="4" />
      </g>
      <path d="M49.5 9.5 l1.4 3.6 3.6 1.4 -3.6 1.4 -1.4 3.6 -1.4 -3.6 -3.6 -1.4 3.6 -1.4z" fill="#ffd27a" />
      <circle cx="55.2" cy="22.8" r="1.3" fill="#ffb35c" />
    </svg>
  );
}

export function Wordmark({ className }: { className?: string }) {
  return (
    <span className={`font-display font-bold tracking-tight ${className ?? ""}`}>
      Algo<span className="ember-text">Forge</span>
    </span>
  );
}

export function Logo({ size = 30, className }: { size?: number; className?: string }) {
  return (
    <span className={`inline-flex items-center gap-2.5 ${className ?? ""}`}>
      <LogoMark size={size} />
      <Wordmark className="text-[1.05rem]" />
    </span>
  );
}
