import { Suspense } from "react";
import { AppShell } from "@/components/AppShell";
import { Spinner } from "@/components/ui";

const fallback = <div className="grid h-screen place-items-center"><Spinner /></div>;

// Pages read route params on the client, so the whole signed-in area renders inside one Suspense boundary.
export default function SignedInLayout({ children }: LayoutProps<"/">) {
  return (
    <Suspense fallback={fallback}>
      <AppShell>
        <Suspense fallback={<div className="p-8"><Spinner /></div>}>{children}</Suspense>
      </AppShell>
    </Suspense>
  );
}
