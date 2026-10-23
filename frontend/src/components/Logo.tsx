import { useId } from "react";

/**
 * The AlgoForge mark: an "A" drawn as an algorithm graph (three nodes joined by edges) in the forge gradient, with a
 * spark at its peak. The same geometry ships as public/brand/*.svg and app/icon.svg. It is the only gradient in the UI.
 */
export function LogoMark({ size = 32, className, tile = true }: { size?: number; className?: string; tile?: boolean }) {
  const id = useId().replace(/:/g, "");
  return (
    <svg viewBox="0 0 64 64" width={size} height={size} className={className} role="img" aria-label="AlgoForge">
      <defs>
        <linearGradient id={`${id}t`} x1="0" y1="0" x2="0" y2="1"><stop offset="0" stopColor="#1c1712" /><stop offset="1" stopColor="#0a0b10" /></linearGradient>
        <radialGradient id={`${id}g`} cx="0.5" cy="1" r="0.75"><stop offset="0" stopColor="#ff7a2f" stopOpacity="0.45" /><stop offset="1" stopColor="#ff7a2f" stopOpacity="0" /></radialGradient>
        <linearGradient id={`${id}e`} gradientUnits="userSpaceOnUse" x1="0" y1="11" x2="0" y2="54">
          <stop offset="0" stopColor="#ffd27a" /><stop offset="0.5" stopColor="#ff7a2f" /><stop offset="1" stopColor="#ff3d6e" />
        </linearGradient>
      </defs>
      {tile && (
        <>
          <rect width="64" height="64" rx="14" fill={`url(#${id}t)`} />
          <rect width="64" height="64" rx="14" fill={`url(#${id}g)`} />
        </>
      )}
      <path d="M15.5 51 L32 15 L48.5 51" fill="none" stroke={`url(#${id}e)`} strokeWidth="6" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M22.2 36.4 H41.8" stroke={`url(#${id}e)`} strokeWidth="4.5" strokeLinecap="round" />
      <g fill="#fff4e3" stroke="#ff7a2f" strokeWidth="2">
        <circle cx="32" cy="15" r="4.6" /><circle cx="22.2" cy="36.4" r="4" /><circle cx="41.8" cy="36.4" r="4" />
      </g>
      <path d="M49.5 9.5 l1.4 3.6 3.6 1.4 -3.6 1.4 -1.4 3.6 -1.4 -3.6 -3.6 -1.4 3.6 -1.4z" fill="#ffd27a" />
    </svg>
  );
}

export function Logo({ size = 24, className }: { size?: number; className?: string }) {
  return (
    <span className={`inline-flex items-center gap-2 ${className ?? ""}`}>
      <LogoMark size={size} />
      <span className="text-[15px] font-semibold tracking-tight text-fg">AlgoForge</span>
    </span>
  );
}
