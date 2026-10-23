import { get } from "./api";
import type { Page, ProblemSummary } from "./types";

/** A random judgeable problem, preferring ones the user has never opened; null when the catalogue has none. */
export async function randomProblemId(): Promise<number | null> {
  const fresh = await get<Page<ProblemSummary>>("/api/problems?judgeable=true&status=UNSEEN&size=100");
  const pool = fresh.items.length > 0 ? fresh.items : (await get<Page<ProblemSummary>>("/api/problems?judgeable=true&size=100")).items;
  return pool.length > 0 ? pool[Math.floor(Math.random() * pool.length)].id : null;
}
