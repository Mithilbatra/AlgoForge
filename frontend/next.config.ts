import type { NextConfig } from "next";

// The browser only ever talks to this origin; /api is proxied to the Spring Boot API so session and CSRF
// cookies stay first-party.
const apiUrl = process.env.API_URL ?? "http://localhost:8080";

const nextConfig: NextConfig = {
  output: "standalone",
  // Cache Components is off: every page here is client-rendered behind the session check, so it adds nothing, and
  // with it on, clicking a link before the first page finished hydrating could leave the next route unmounted.
  // Forge Coach waits for the AI inside the request (70 s per provider, up to two providers), past the 30 s proxy default.
  experimental: { proxyTimeout: 150_000 },
  turbopack: {
    root: __dirname,
    rules: {
      "*.css": {
        loaders: ["@tailwindcss/turbopack"],
        as: "*.css",
      },
    },
  },
  async rewrites() {
    return [{ source: "/api/:path*", destination: `${apiUrl}/api/:path*` }];
  },
  async headers() {
    return [
      {
        source: "/:path*",
        headers: [
          { key: "X-Frame-Options", value: "DENY" },
          { key: "X-Content-Type-Options", value: "nosniff" },
          { key: "Referrer-Policy", value: "same-origin" },
        ],
      },
    ];
  },
};

export default nextConfig;
