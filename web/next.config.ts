import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  outputFileTracingRoot: __dirname,

  async rewrites() {
    return [
      {
        source: "/ping",
        destination: "http://127.0.0.1:8085/ping",
      },
      {
        source: "/api/:path*",
        destination: "http://127.0.0.1:8085/api/:path*",
      },
    ];
  },
};

export default nextConfig;