import type { Language } from "./types";

export const TEMPLATES: Record<Language, string> = {
  python: "import sys\n\n\ndef main():\n    data = sys.stdin.buffer.read().split()\n    \n\n\nmain()\n",
  cpp: "#include <bits/stdc++.h>\nusing namespace std;\n\nint main() {\n    ios::sync_with_stdio(false);\n    cin.tie(nullptr);\n    \n    return 0;\n}\n",
  java: "import java.io.*;\nimport java.util.*;\n\npublic class Main {\n    public static void main(String[] args) throws IOException {\n        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));\n        \n    }\n}\n",
};

// Drafts live in this browser only; storage can be unavailable (private mode), so every access is guarded.
export function loadDraft(key: string): string | null {
  try {
    return localStorage.getItem(key);
  } catch {
    return null;
  }
}

export function saveDraft(key: string, code: string) {
  try {
    localStorage.setItem(key, code);
  } catch {
    /* storage full or blocked: the editor still works, the draft just is not persisted */
  }
}

export const draftKey = (problemId: number, lang: Language, sessionId?: number) =>
  `af:draft:${sessionId ? `oa${sessionId}:` : ""}${problemId}:${lang}`;
