import Link from "next/link";
import { LogoMark } from "@/components/Logo";

export default function NotFound() {
  return (
    <main className="grid min-h-screen place-items-center px-6 text-center">
      <div className="animate-fade-up">
        <div className="mx-auto mb-6 w-fit animate-float drop-shadow-[0_20px_50px_rgb(255_122_47/0.35)]"><LogoMark size={80} /></div>
        <p className="font-mono text-[11px] uppercase tracking-[0.25em] text-accent">404 · node not found</p>
        <h1 className="mt-3 text-4xl font-bold tracking-tight">This edge leads nowhere.</h1>
        <p className="mx-auto mt-3 max-w-md text-muted">The page you followed isn&apos;t in the graph. Head back to the dashboard and pick a new path.</p>
        <Link href="/" className="mt-8 inline-flex h-11 items-center rounded-lg px-5 font-semibold text-[#1a0c04] ember-fill shadow-[0_8px_24px_-8px_rgb(255_122_47/0.7)] hover:brightness-110">Back to the forge</Link>
      </div>
    </main>
  );
}
