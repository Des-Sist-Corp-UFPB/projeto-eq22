FROM node:24-alpine AS deps

WORKDIR /app

COPY package.json package-lock.json ./
RUN npm install

FROM node:24-alpine AS build

WORKDIR /app

# next build serialises the rewrites into .next/routes-manifest.json, and `next start` reads that
# manifest rather than re-running next.config.ts. So the backend origin has to be known here at
# build time - supplying it only at runtime leaves the image pinned to the local fallback.
ARG BACKEND_ORIGIN=http://127.0.0.1:8085
ENV BACKEND_ORIGIN=$BACKEND_ORIGIN

COPY --from=deps /app/node_modules ./node_modules
COPY . .

RUN npm run build

FROM node:24-alpine AS runner

WORKDIR /app

ENV NODE_ENV=production
# BACKEND_ORIGIN is read by next.config.ts at runtime and supplied by compose: the browser only
# ever calls /api on this origin, so no backend URL is baked into the bundle.

COPY --from=build /app/package.json ./package.json
COPY --from=build /app/next.config.ts ./next.config.ts
COPY --from=build /app/public ./public
COPY --from=build /app/.next ./.next
COPY --from=build /app/node_modules ./node_modules

EXPOSE 3000

CMD ["npm", "run", "start"]
