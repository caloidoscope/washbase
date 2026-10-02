import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  logging: {
    // The dev server logs each request URL. /auth/callback's URL carries the one-time authorization code, which
    // must never appear in logs (ADR-001). Production builds don't log requests.
    incomingRequests: { ignore: [/\/auth\/callback(\?|$)/] },
  },
};

export default nextConfig;
