"use client";

import { Area, AreaChart, CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";

const axis = { stroke: "#7a8296", fontSize: 11, tickLine: false, axisLine: false };
const tooltip = {
  contentStyle: { background: "#151a23", border: "1px solid #2b3341", borderRadius: 12, fontSize: 12, boxShadow: "0 12px 30px -10px rgba(0,0,0,.6)" },
  labelStyle: { color: "#a3abba" },
};

export function TrendChart({ data }: { data: { week: string; accepted: number; judged: number; acceptRate: number }[] }) {
  return (
    <ResponsiveContainer width="100%" height={200}>
      <AreaChart data={data} margin={{ top: 8, right: 8, left: -24, bottom: 0 }}>
        <defs>
          <linearGradient id="trend-judged" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stopColor="#60a5fa" stopOpacity={0.35} /><stop offset="1" stopColor="#60a5fa" stopOpacity={0} /></linearGradient>
          <linearGradient id="trend-accepted" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stopColor="#ff7a2f" stopOpacity={0.55} /><stop offset="1" stopColor="#ff7a2f" stopOpacity={0} /></linearGradient>
        </defs>
        <CartesianGrid stroke="#1d232e" vertical={false} />
        <XAxis dataKey="week" {...axis} tickFormatter={(w: string) => w.slice(5)} />
        <YAxis {...axis} allowDecimals={false} />
        <Tooltip {...tooltip} cursor={{ stroke: "#2b3341" }} />
        <Area type="monotone" dataKey="judged" name="Judged" stroke="#60a5fa" strokeWidth={2} fill="url(#trend-judged)" />
        <Area type="monotone" dataKey="accepted" name="Accepted" stroke="#ff7a2f" strokeWidth={2.5} fill="url(#trend-accepted)" />
      </AreaChart>
    </ResponsiveContainer>
  );
}

export function BenchmarkChart({ points }: { points: { n: number; medianMs: number; minMs: number; maxMs: number }[] }) {
  return (
    <ResponsiveContainer width="100%" height={220}>
      <LineChart data={points} margin={{ top: 8, right: 16, left: -10, bottom: 0 }}>
        <CartesianGrid stroke="#1d232e" />
        <XAxis dataKey="n" {...axis} tickFormatter={(n: number) => n.toLocaleString()} />
        <YAxis {...axis} unit=" ms" />
        <Tooltip {...tooltip} />
        <Line type="monotone" dataKey="maxMs" name="Max" stroke="#7a8296" dot={false} strokeDasharray="4 3" />
        <Line type="monotone" dataKey="medianMs" name="Median" stroke="#ff7a2f" strokeWidth={2.5} dot={{ r: 3, fill: "#ffc35a", strokeWidth: 0 }} />
        <Line type="monotone" dataKey="minMs" name="Min" stroke="#7a8296" dot={false} strokeDasharray="4 3" />
      </LineChart>
    </ResponsiveContainer>
  );
}
