export type Language = "python" | "cpp" | "java";
export type Difficulty = "EASY" | "MEDIUM" | "HARD";

export interface Me { id: number; email: string; displayName: string; role: string }

export interface Capabilities {
  discovery: boolean; metadataSync: boolean; statementAccess: boolean; constraintsAndExamples: boolean;
  submissionLinks: boolean; verdictSync: boolean; judging: boolean; ratingAndTags: boolean; notes: string;
}

export interface ProblemSummary {
  id: number; source: string; externalId?: string; canonicalUrl?: string; title: string; difficulty?: Difficulty;
  sourceRating?: number; estMinutes?: number; status: string; tags: string[]; tagsHidden: boolean; judgeable: boolean; owned: boolean;
}

export interface Page<T> { items: T[]; total: number; page: number; size: number }

export interface ProblemDetail {
  id: number; source: string; sourceName: string; externalId?: string; canonicalUrl?: string; title: string;
  statementMd?: string; constraintsMd?: string; difficulty?: Difficulty; sourceRating?: number; estMinutes?: number;
  timeLimitMs: number; memoryLimitMb: number; tags: string[]; tagsHidden: boolean;
  concepts: { code: string; name: string; tier: number }[]; samples: { input: string; output: string }[];
  hints?: string[]; assessmentLocked: boolean; editorialAvailable: boolean; judgeable: boolean; officialTests: number;
  capabilities: Capabilities; state: { status: string; notes?: string; personalDifficulty?: number }; owned: boolean;
  twinId?: number; twinOfId?: number; twinOfTitle?: string;
}

export interface SourceInfo {
  code: string; name: string; homepage?: string; capabilities: Capabilities; lastSyncAt?: string;
  lastSyncStatus: string; lastSyncMessage?: string; lastSyncCount?: number; problemCount: number;
}

export interface TestRow {
  ordinal: number; kind: string; verdict: string; timeMs?: number; memoryKb?: number; outputExcerpt?: string;
  errorExcerpt?: string; input?: string; expected?: string; inputLength?: number; provenance?: string;
}

export interface SubmissionDetail {
  id: number; problemId: number; problemTitle: string; language: Language; code: string; status: string;
  passed?: number; total?: number; maxTimeMs?: number; maxMemoryKb?: number; compileOutput?: string; message?: string;
  runtimeVersion?: string; statedComplexity?: string; oaSessionId?: number; createdAt: string; judgedAt?: string;
  timeLimitMs: number; memoryLimitMb: number; evaluationId?: number; tests: TestRow[]; detailsHidden: boolean;
}

export interface SubmissionSummary {
  id: number; problemId: number; problemTitle: string; language: Language; status: string; passed?: number; total?: number;
  maxTimeMs?: number; maxMemoryKb?: number; oaSessionId?: number; createdAt: string;
}

export interface RunCase { verdict: string; message?: string; timeMs: number; memoryKb?: number; stdout: string; stderr: string; expected?: string }

export interface Job<R = unknown> { id: number; type: string; status: "QUEUED" | "RUNNING" | "DONE" | "FAILED"; result?: R; error?: string; queuePosition?: number }

export interface RunResult { compile: { ok: boolean; output: string }; runs?: RunCase[]; runtimeVersion?: string }

export interface Dimension { key: string; label: string; weight: number; score?: number; evidence: string; provisional: boolean }

export interface Finding { severity: string; category: string; message: string }

export interface GeneratedCase {
  source: string; category: string; rationale?: string; verification: string; verdict?: string; result?: string;
  message?: string; reason?: string; testId?: number; input?: string; expected?: string; actual?: string; size?: number;
}

export interface AiReview {
  summary: string; approach: string; correctnessOpinion: string; suspectedBugs: { description: string; location: string }[];
  timeComplexity: string; spaceComplexity: string; complexityExplanation: string; fitsLimits: string;
  suggestions: { title: string; rationale: string; tradeoff: string; code: string }[];
}

export interface Evaluation {
  id: number; submissionId: number; status: string; correctness: string; overallScore: number;
  dimensions: { weights: Record<string, number>; items: Dimension[] }; findings: Finding[];
  generatedTests?: {
    aiStatus?: string; verifiedPassed: number; verifiedTotal: number; unverified: number; rejected: number; randomRun?: number;
    randomSeedFormula?: string; referenceAvailable: boolean; counterexample?: GeneratedCase; cases: GeneratedCase[];
  };
  benchmark?: {
    baselineMs: number; points: { n: number; medianMs: number; minMs: number; maxMs: number }[]; conclusive: boolean;
    exponent?: number; label?: string; note: string; runtimeVersion?: string; inputSizePower: number;
  };
  aiReview?: Record<string, { model: string; status: string; review?: AiReview; error?: string }>;
  aiDisagreements?: { topic: string; claims: Record<string, string>; status: string; conclusion: string }[];
  aiStatus?: string; updatedAt: string; code: string; language: Language; verdict: string; passed?: number; total?: number;
  maxTimeMs?: number; maxMemoryKb?: number; runtimeVersion?: string; statedComplexity?: string; problemId: number;
  problemTitle: string; timeLimitMs: number; memoryLimitMb: number;
  complexity: { source: string; timeComplexity?: string; spaceComplexity?: string; explanation?: string; confidence: string }[];
}

export interface EvaluationSummary {
  id: number; submissionId: number; problemId: number; problemTitle: string; language: Language; verdict: string;
  status: string; correctness: string; overallScore: number; updatedAt: string;
}

export interface Recommendation {
  id: number; problemId: number; title: string; difficulty?: Difficulty; estMinutes?: number; conceptCode?: string;
  kind: string; reason: string; status: string;
}

export interface DimensionEstimate { dimension: string; estimate?: number; evidence: number; lastEvidenceAt?: string }

export interface ConceptMastery {
  code: string; name: string; description: string; prerequisites: string[]; status: string; mastery?: number; evidence: number;
  dimensions: DimensionEstimate[]; dueForRevision: boolean; lastSuccessAt?: string; problemsSolved: number; problemsAvailable: number;
}

export interface OaDefinition {
  id: number; title: string; description?: string; isTemplate: boolean; durationMinutes: number; mode: string;
  partialCredit: boolean; wrongPenaltyPct: number; languages: Language[]; allowHints: boolean;
  problems: { problemId: number; title: string; difficulty?: Difficulty; points: number; ordinal: number }[];
  recipe?: { slots: { difficulty: Difficulty; points: number }[] };
}

export interface OaState {
  id: number; title: string; status: "ACTIVE" | "FINALIZING" | "COMPLETED"; mode: string; startedAt: string; endsAt: string;
  remainingMs: number; endReason?: string; languages: Language[]; allowHints: boolean; partialCredit: boolean;
  wrongPenaltyPct: number; pointsEarned: number; maxPoints: number;
  problems: { problemId: number; title: string; points: number; ordinal: number; score?: number; attempts?: number; bestStatus?: string; lastStatus?: string }[];
  submissions: { id: number; problemId: number; status: string; passed: number; total: number; at: string }[];
}

export interface OaReport {
  totalScore: number; maxScore: number; percentage: number; endReason: string; secondsUsed: number; solved: number;
  partiallySolved: number; insights: string[]; conceptsToRevise: string[];
  scoringRules: { partialCredit: boolean; wrongPenaltyPct: number };
  problems: {
    problemId: number; title: string; points: number; score: number; solved: boolean; partial: boolean; attempts: number;
    rejected: number; verdicts: Record<string, number>; failedOnSamples: number; failedOnHiddenTests: number;
    timeSpentSeconds: number; bestSubmissionId?: number;
    evaluation?: { evaluationId: number; overallScore: number; optimisationFindings: string[]; staticTimeComplexity?: string };
  }[];
  timeline: { submissionId: number; problemId: number; title: string; atSeconds: number; status: string; passed?: number; total?: number }[];
  followUps: { problemId: number; title: string; reason: string }[];
}

export interface Preferences {
  preferredLanguage: Language; dailyMinutes: number; targetDifficulty: Difficulty; goalText?: string; goalDate?: string;
  goalConcepts: string[]; showStreak: boolean;
}
