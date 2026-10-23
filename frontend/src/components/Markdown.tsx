import ReactMarkdown from "react-markdown";

/** Statements and editorials. react-markdown never renders raw HTML, so stored text cannot inject markup. */
export function Markdown({ children }: { children?: string }) {
  if (!children) return null;
  return (
    <div className="prose-af">
      <ReactMarkdown>{children}</ReactMarkdown>
    </div>
  );
}
