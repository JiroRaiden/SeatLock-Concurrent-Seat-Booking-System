# ============================================================================
# Multi-stage build.
#
# Stage 1 has Node, npm, the whole dependency tree and the TypeScript sources —
# roughly 400 MB. Stage 2 has nginx and a folder of static files, around 50 MB.
# Only stage 2 is shipped. Nothing that built the app is present in the image
# that runs it, which is both a size win and a security one: there is no npm,
# no compiler and no source map of our dependencies for an attacker who gets a
# shell in the container.
# ============================================================================

# ---- Stage 1: build ---------------------------------------------------------
FROM node:22-alpine AS build

WORKDIR /app

# Copy the manifests ALONE first, then install, then copy the source.
# Docker caches each layer against the files it was built from, so as long as
# package.json and the lockfile are unchanged, `npm ci` is served from cache —
# editing a component does not reinstall 250 packages.
COPY package.json package-lock.json ./

# `npm ci` rather than `npm install`: it installs exactly what the lockfile
# pins and fails if the lockfile and package.json disagree. A build must be
# reproducible; `npm install` is allowed to silently resolve a newer version.
RUN npm ci

COPY . .

# Baked in at build time, because Vite substitutes VITE_* variables into the
# bundle during the build — there is no runtime environment in a static site.
# Override per environment with: docker build --build-arg VITE_API_BASE_URL=...
ARG VITE_API_BASE_URL=""
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL

RUN npm run build

# ---- Stage 2: serve ---------------------------------------------------------
FROM nginx:1.27-alpine AS serve

# Replace the default site with ours (SPA fallback + security headers).
COPY nginx.conf /etc/nginx/conf.d/default.conf

COPY --from=build /app/dist /usr/share/nginx/html

# Documentation only — it does not publish anything. `docker run -p` does that.
EXPOSE 80

# Lets an orchestrator tell "the container is up" from "nginx is answering".
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
  CMD wget --quiet --tries=1 --spider http://localhost/ || exit 1

# `daemon off;` keeps nginx in the foreground. A container's job is to run one
# process; if nginx daemonises, PID 1 exits and Docker considers the container
# finished.
CMD ["nginx", "-g", "daemon off;"]
