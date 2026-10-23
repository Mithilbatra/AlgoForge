"use client";

import { Bar, BarChart, CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { useTheme } from "@/lib/theme";

// SVG marks need literal colours; these mirror the design tokens. Neutral marks keep 3:1 against the panel.
const PALETTE = {
  light: { grid: "#e4e4e7", axis: "#6b6b75", neutral: "#8b8b94", accent: "#b33d0b", panel: "#ffffff", line: "#e4e4e7", fg: "#18181b", muted: "#52525b" },
  dark: { grid: "#232327", axis: "#85858f", neutral: "#6b6b75", accent: "#fb923c", panel: "#18181b", line: "#34343a", fg: "#ededef", muted: "#a1a1aa" },
};

function useChart() {
  const c = PALETTE[useTheme().resolved];
  return {
    c,
    axis: { stroke: c.axis, fontSize: 12, tickLine: false, axisLine: false },
    tooltip: {
      contentStyle: { background: c.panel, border: `1px solid ${c.line}`, borderRadius: 6, fontSize: 12, boxShadow: "none", padding: "6px 10px" },
      labelStyle: { color: c.muted, marginBottom: 2 },
      itemStyle: { color: c.fg, padding: 0 },
    },
  };
}

export function TrendChart({ data }: { data: { week: string; accepted: number; judged: number; acceptRate: number }[] }) {
  const { c, axis, tooltip } = useChart();
  return (
    <ResponsiveContainer width="100%" height={200}>
      <BarChart data={data} margin={{ top: 4, right: 4, left: -24, bottom: 0 }} barGap={3}>
        <CartesianGrid stroke={c.grid} vertical={false} />
        <XAxis dataKey="week" {...axis} tickFormatter={(w: string) => w.slice(5)} />
        <YAxis {...axis} allowDecimals={false} />
        <Tooltip {...tooltip} cursor={{ fill: c.grid, opacity: 0.5 }} />
        <Bar dataKey="judged" name="Judged" fill={c.neutral} radius={[3, 3, 0, 0]} maxBarSize={18} />
        <Bar dataKey="accepted" name="Accepted" fill={c.accent} radius={[3, 3, 0, 0]} maxBarSize={18} />
      </BarChart>
    </ResponsiveContainer>
  );
}

export function BenchmarkChart({ points }: { points: { n: number; medianMs: number; minMs: number; maxMs: number }[] }) {
  const { c, axis, tooltip } = useChart();
  return (
    <ResponsiveContainer width="100%" height={220}>
      <LineChart data={points} margin={{ top: 8, right: 16, left: -10, bottom: 0 }}>
        <CartesianGrid stroke={c.grid} vertical={false} />
        <XAxis dataKey="n" {...axis} tickFormatter={(n: number) => n.toLocaleString()} />
        <YAxis {...axis} unit=" ms" />
        <Tooltip {...tooltip} />
        <Line type="monotone" dataKey="maxMs" name="Max" stroke={c.neutral} dot={false} strokeDasharray="4 3" />
        <Line type="monotone" dataKey="medianMs" name="Median" stroke={c.accent} strokeWidth={2} dot={{ r: 3, fill: c.accent, strokeWidth: 0 }} />
        <Line type="monotone" dataKey="minMs" name="Min" stroke={c.neutral} dot={false} strokeDasharray="4 3" />
      </LineChart>
    </ResponsiveContainer>
  );
}

/** Legend chips for TrendChart, shown in its card header. */
export function TrendLegend() {
  return (
    <span className="flex items-center gap-4 text-xs text-muted">
      <span className="flex items-center gap-1.5"><i className="size-2 rounded-sm bg-[#8b8b94] dark:bg-[#6b6b75]" />Judged</span>
      <span className="flex items-center gap-1.5"><i className="size-2 rounded-sm bg-accent" />Accepted</span>
    </span>
  );
}
