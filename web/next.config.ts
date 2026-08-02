import type { NextConfig } from "next";

// Server-side only: the browser never learns the backend origin, it only ever calls /api on the
// origin it was served from. Same-origin is what makes the session work — a SameSite=Lax cookie is
// not sent on cross-site XHR, and the CSRF double-submit has to read a cookie the browser would
// otherwise treat as third-party.
const BACKEND_ORIGIN = process.env.BACKEND_ORIGIN ?? "http://127.0.0.1:8085";

const nextConfig: NextConfig = {
  outputFileTracingRoot: __dirname,

  async rewrites() {
    return [
      // The backend's public liveness probe is mapped at /ping, not under /api. Exposing it as
      // /api/ping keeps every backend call on one prefix and gives the proxy a probe that needs no
      // session. Must precede the catch-all.
      {
        source: "/api/ping",
        destination: `${BACKEND_ORIGIN}/ping`,
      },
      {
        source: "/api/:path*",
        destination: `${BACKEND_ORIGIN}/api/:path*`,
      },
    ];
  },
};

export default nextConfig;
