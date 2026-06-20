# ---- Build stage: install deps and produce the Next.js standalone bundle ----
FROM node:22-alpine AS build
WORKDIR /app

# Install with the lockfile for reproducible builds (deps cached independently
# of source changes).
COPY package.json package-lock.json ./
RUN npm ci

COPY . .

# NEXT_PUBLIC_* values are inlined at build time, so the API base URL must be
# supplied as a build arg (Render passes the service env var as a build arg of
# the same name). Default is the local dev API for a bare `docker build`.
ARG NEXT_PUBLIC_API_BASE_URL=http://localhost:8080
ENV NEXT_PUBLIC_API_BASE_URL=$NEXT_PUBLIC_API_BASE_URL
ENV BUILD_STANDALONE=1
ENV NEXT_TELEMETRY_DISABLED=1
RUN npm run build

# ---- Runtime stage: run the standalone server as a non-root user ----
FROM node:22-alpine AS runtime
WORKDIR /app
ENV NODE_ENV=production
ENV NEXT_TELEMETRY_DISABLED=1

RUN addgroup -S nodejs && adduser -S nextjs -G nodejs

# Standalone output bundles the server + traced node_modules; static assets and
# public/ are copied alongside it (Next does not include them in standalone).
COPY --from=build /app/public ./public
COPY --from=build --chown=nextjs:nodejs /app/.next/standalone ./
COPY --from=build --chown=nextjs:nodejs /app/.next/static ./.next/static

USER nextjs

# Host injects PORT (Render/Railway/Fly); the standalone server reads PORT/HOSTNAME.
ENV PORT=3000
ENV HOSTNAME=0.0.0.0
EXPOSE 3000
CMD ["node", "server.js"]
