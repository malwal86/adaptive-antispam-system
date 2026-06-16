import { fileURLToPath } from "node:url";
import { dirname } from "node:path";

// Standalone output (server.js + traced deps) is only wanted for the production
// Docker image — the Dockerfile sets BUILD_STANDALONE=1. Locally `next start`
// (used by the Playwright webServer) is incompatible with standalone, so we leave
// it off unless explicitly building the image.
const standalone = process.env.BUILD_STANDALONE === "1";

/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,
  ...(standalone
    ? {
        output: "standalone",
        // Pin dependency tracing to this app (its own project, separate from the
        // Java repo root) so the standalone bundle is self-contained.
        outputFileTracingRoot: dirname(fileURLToPath(import.meta.url)),
      }
    : {}),
};

export default nextConfig;
