# Feature status

What each label means:

- **Tested**: covered by automated tests that pass.
- **Verified**: also exercised by hand in the browser or against the live service.
- **Partial**: works with stated gaps.
- **Unconfigured**: implemented, but not exercised live because credentials were unavailable.
- **Not implemented**: deliberately left out.

Last full run: 94 backend tests (3 opt-in live tests skipped without keys) and 8 Playwright journeys against a production build of the web app with a local API and worker.

## Status by area

| Area | Feature | Status | Evidence |
|---|---|---|---|
| Auth | Register, login, logout, sessions in PostgreSQL, CSRF, per-account and per-address limits | Tested, verified | `ApiIntegrationTest`, e2e sign-in guard; sessions survive API restarts |
| Problem Hub | 12 original problems with statements, samples, hidden tests, hints, editorials, reference solutions | Tested, verified | Reference solutions cross-checked against brute force on 119 random cases each |
| Problem Hub | Filters (text, source, difficulty, tag, status, rating, estimated time, judgeable), pagination | Tested, verified | `catalogueFiltersAndHidesDisguisedTechniqueTags`, browser |
| Problem Hub | Codeforces catalogue sync (official API), background job with status | Verified live | 11,452 problems synced in about 12 s; opt-in `LiveIntegrationTest` |
| Problem Hub | Link-only adapters (LeetCode, CodeChef, AtCoder, CSES, HackerRank), dedupe by platform ID and canonical URL | Tested | `SourceAdaptersTest`, `manualLinksDeduplicateAndStayPrivate` |
| Problem Hub | Original twins of linked problems: AI-written from the topics only, reference cross-checked against a brute force in the sandbox, then judged locally | Tested, verified live | `TwinIntegrationTest` (scripted AI, real sandbox), `AiOutputsTest`; live with Gemini: a prefix-sums twin of a LeetCode link in 112 s, its reference accepted on 11 of 11 tests |
| Problem Hub | User-authored private problems with author tests | Partial | API only (`POST /api/problems`); no authoring UI; no reference solution, so their generated tests stay unverified |
| Practice Lab | Monaco editor, language switch, autosaved drafts, shortcuts (Cmd/Ctrl+Enter, +Shift to submit), resizable panels | Verified | Browser, e2e |
| Practice Lab | Sample runs, custom input, submission history, restoring past code | Tested, verified | e2e practice journey |
| Judge | Python 3.12, C++17 (g++ 14), Java 21 in Docker sandboxes; AC/WA/TLE/MLE/RE/CE verdicts; time and peak memory per test; runtime versions recorded | Tested | `JudgeIntegrationTest`, `VerdictsTest`, manual hostile-program checks |
| Judge | Worker start-up check that every sandbox image exists in its Docker daemon | Tested | `SandboxPreflightTest` |
| Judge | Postgres job queue, worker heartbeat, stale-job requeue, then failure and an internal-error verdict | Tested | `stalledJobsAreRequeuedThenFailedAndTheSubmissionMarked` |
| Evaluation | Weighted, configurable scoring with per-dimension evidence; verified vs provisional correctness | Tested | `ScoringModelTest`, `JudgeIntegrationTest` |
| Evaluation | Static complexity and pitfall analysis (loops, binary search, amortised loops, recursion, sorting, slow I/O, endl, Scanner, 32-bit overflow) | Tested, partial | `StaticAnalyzerTest`. Heuristic, labelled low or medium confidence |
| Evaluation | Deep evaluation: seeded random differential tests against the reference, validator-checked inputs, smallest counterexample, stored provenance | Tested, verified | `deepEvaluationFindsASmallVerifiedCounterexample`, e2e |
| Evaluation | Benchmark on growing inputs (median of 3, start-up subtracted, noise floor, spread check) | Verified | Reports "inconclusive" when runs are too fast to measure |
| AI | Two AI provider adapters with schema-constrained output, provider abstraction, model config in DB | Unconfigured | No API keys were available. Request shapes follow the current docs; parsing, validation, retries, caching, rate limits, dual mode and failure handling are tested with fake providers (`AiServiceIntegrationTest`) |
| AI | AI-proposed test inputs, validated and answered by the reference solution | Unconfigured | Same pipeline as random tests (tested); the live call is not exercised |
| AI | Dual-provider comparison with evidence-based resolution (no voting) | Tested | `DisagreementsTest` |
| Forge Coach | Hint agent with three levels (nudge, approach, outline), grounded in the statement, current code, last verdict and static analysis; built-in fallback from authored hints; AI-written outlines count as an audited reveal; off during assessments that forbid hints; rate-limited | Tested, verified | `CoachIntegrationTest`, e2e coach journey, browser. The AI path is unconfigured live (same provider pipeline as reviews); long code blocks in replies are stripped (`AiOutputsTest`) |
| OA Arena | Templates (labelled as practice simulations), custom builder (chosen and/or random problems, partial credit, penalties, languages, mode) | Tested, verified | `OaIntegrationTest`, e2e |
| OA Arena | Server-authoritative timer, refresh recovery, one active session, explicit submit, expiry sweeper, waiting for pending judging | Tested | `expiryFinalisesDeterministicallyAndBlocksLateSubmissions`, `finalisationWaitsForSubmissionsStillBeingJudged` |
| OA Arena | Hidden test data, editorials and evaluations withheld during the assessment, even when it allows hints; hints, topics and Forge Coach withheld unless it does | Tested | `allowingHintsStillHidesSolutionsDuringTheAssessment`, e2e |
| OA Arena | Report: score, solved and partial, time per problem, verdict mix, sample-vs-hidden failures, timeline, insights, concepts to revise, follow-ups, evaluation links | Tested, verified | Integration, e2e, browser |
| Learning | Concept graph (22 concepts, prerequisites), per-dimension Beta estimates with a 30-day half-life, no mastery from a single solve, solution reveals discounted | Tested | `LearningEstimatesTest`, `oldFailuresFadeEvenWhenNewEvidenceArrives`, OA and API flows |
| Learning | Transfer Test Engine (tier 1 → 2 → 3 within a concept; disguised picks never name the technique), daily session with reasons built once per day, accept/skip/replace, completion on accepted | Tested, verified | e2e recommendations journey, `LearningIntegrationTest`, browser |
| Dashboard | Recent activity, today's session, weakest concepts, weekly judged/accepted chart, time management from assessments, optional streak | Verified | Browser |
| UI | Design system (tokens, AA contrast, shared components), light and dark themes with a system default and no flash on load, responsive app shell, concept-graph Learning Map | Verified | Every page screenshotted at 1440 px in both themes and at phone width; 7 Playwright journeys |
| UI | Command palette (⌘K), activity heatmap counted in the browser's time zone, live judging steps, animated verdicts, typing coach replies, timer ring, flowing learning-map edges, circular theme transition; all motion off for reduced motion | Tested, verified | `activityDaysAreCountedInTheBrowsersTimeZone`, e2e command palette journey, screenshots mid-animation in both themes and at phone width |
| Ops | Flyway migrations, Dockerfiles, compose (dev DB or full stack), health and metrics endpoints, OpenAPI at `/api/docs`, retention job, audit log | Verified | Full stack started and passed e2e |

## Known limitations and future work

- **AI adapters are unverified live.** Run `LiveIntegrationTest` with keys before relying on them. The default Gemini model ID (`gemini-3.8-flash`) comes from the current docs, which mark `generateContent` as legacy in favour of the Interactions API. Check the model list for your key.
- **No AI-generated learning exercises or graded explanations.** The "explanation" dimension shows "no evidence" because nothing measures it yet.
- **Static complexity analysis is heuristic.** Unusual control flow can mislead it, which is why it is labelled and weighted below the judge's timings and the benchmark.
- **Shrinking applies only to the seeded generator.** Random tests run from the smallest size up, so the first failure is the smallest one found. AI-proposed failing inputs are reported as given.
- **The benchmark only measures growth on the worker it runs on.** Fast solutions are often "inconclusive" (runs under the 20 ms noise floor), and there is no cross-language reference baseline.
- **No verdict sync from external platforms.** Codeforces `user.status` could provide it later.
- **No company tags.** No legitimately sourced data exists for them.
- **No statement import for external problems.** The APIs used don't provide statements, and AlgoForge does not scrape. Original twins are the alternative: new problems on the same technique.
- **Original twins are only as sure as two AI-written programs.** The reference and the brute force come from the same model, so a shared misreading of the statement passes the cross-check. Their input validator is AI-written too, and there is no way to edit or delete a twin yet.
- **Drafts are per browser.** They live in `localStorage`, so autosave does not follow you across devices, and signing out deletes them.
- **Rate limits are kept in memory per API instance.** Move them to Redis or PostgreSQL before running several API instances. Sessions are already shared.
- **Monaco loads from the jsDelivr CDN.** Host it yourself for offline or strict-CSP deployments.
- **Seed problems are insert-only.** Changing a shipped problem needs a migration.
- **Account management is minimal.** There is no email verification, password reset, admin UI for models, or authoring UI for private problems.
- **The Next.js proxy waits at most 150 s** (raised from 30 s for Forge Coach's AI calls), so the API gives each AI call 70 s and never retries a timeout: even two slow providers fit, and then the built-in coach answers. Sync, deep evaluation and twins are asynchronous; keep any new long operation asynchronous too.
- **Deployment trust assumptions:** the worker needs the Docker socket, so run it on a dedicated host, preferably with `SANDBOX_RUNTIME=runsc`. The web app must sit behind a reverse proxy that sets `X-Forwarded-For` (see the README's security model).
