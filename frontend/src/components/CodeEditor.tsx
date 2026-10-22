"use client";

import dynamic from "next/dynamic";
import { useEffect, useRef } from "react";
import type { Language } from "@/lib/types";
import { Spinner } from "./ui";

const Monaco = dynamic(() => import("@monaco-editor/react"), { ssr: false, loading: () => <div className="p-4"><Spinner label="Loading editor…" /></div> });

const MONACO_LANG: Record<Language, string> = { python: "python", cpp: "cpp", java: "java" };

export interface EditorCommands {
  run: () => void;
  submit: () => void;
}

/** Monaco with the AlgoForge theme. Ctrl/Cmd+Enter runs the samples, Ctrl/Cmd+Shift+Enter submits. */
export function CodeEditor({ language, value, onChange, commands, readOnly = false }: {
  language: Language; value: string; onChange?: (v: string) => void; commands?: EditorCommands; readOnly?: boolean;
}) {
  const latest = useRef(commands);
  useEffect(() => {
    latest.current = commands;
  });
  return (
    <Monaco
      height="100%"
      language={MONACO_LANG[language]}
      value={value}
      theme="algoforge"
      onChange={(v) => onChange?.(v ?? "")}
      beforeMount={(monaco) => {
        monaco.editor.defineTheme("algoforge", {
          base: "vs-dark",
          inherit: true,
          rules: [],
          colors: { "editor.background": "#0f1217", "editorLineNumber.foreground": "#4b5262", "editor.lineHighlightBackground": "#161a22" },
        });
      }}
      onMount={(editor, monaco) => {
        editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.Enter, () => latest.current?.run());
        editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyMod.Shift | monaco.KeyCode.Enter, () => latest.current?.submit());
      }}
      options={{
        readOnly,
        minimap: { enabled: false },
        fontSize: 13,
        fontFamily: "var(--font-geist-mono), ui-monospace, monospace",
        tabSize: 4,
        scrollBeyondLastLine: false,
        automaticLayout: true,
        renderLineHighlight: "line",
        padding: { top: 10 },
        accessibilitySupport: "auto",
      }}
    />
  );
}
