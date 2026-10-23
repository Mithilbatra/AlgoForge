"use client";

import { useCountUp } from "@/lib/motion";

/** A number that counts up from zero when it first appears. */
export function CountUp({ value, decimals = 0, suffix = "" }: { value: number; decimals?: number; suffix?: string }) {
  const v = useCountUp(value);
  return <>{v.toFixed(decimals)}{suffix}</>;
}
