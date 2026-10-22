import ReactMarkdown from "react-markdown";

/** Statements and editorials. react-markdown never renders raw HTML, so stored text cannot inject markup. */
export function Markdown({ children }: { children?: string }) {
  if (!children) return null;
  return (
    <div className="prose-af text-[14px] text-fg/90">
      <ReactMarkdown>{children}</ReactMarkdown>
    </div>
  );
}
