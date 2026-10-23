import { LogoMark } from "@/components/Logo";
import { ButtonLink } from "@/components/ui";

export default function NotFound() {
  return (
    <main className="grid min-h-screen place-items-center bg-panel px-6 text-center">
      <div>
        <LogoMark size={48} className="mx-auto" />
        <p className="mt-6 font-mono text-xs text-faint">404</p>
        <h1 className="mt-2 text-2xl font-semibold tracking-tight text-fg">Page not found</h1>
        <p className="mx-auto mt-2 max-w-sm text-sm text-muted">The page you followed doesn&apos;t exist or has moved.</p>
        <ButtonLink href="/" variant="primary" className="mt-6">Back to dashboard</ButtonLink>
      </div>
    </main>
  );
}
