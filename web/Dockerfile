FROM node:24-alpine AS deps

WORKDIR /app

COPY package.json package-lock.json ./
RUN npm install

FROM node:24-alpine AS build

WORKDIR /app

ARG NEXT_PUBLIC_API_URL=http://localhost:8085
ENV NEXT_PUBLIC_API_URL=$NEXT_PUBLIC_API_URL

COPY --from=deps /app/node_modules ./node_modules
COPY . .

RUN npm run build

FROM node:24-alpine AS runner

WORKDIR /app

ENV NODE_ENV=production
ENV NEXT_PUBLIC_API_URL=http://localhost:8085

COPY --from=build /app/package.json ./package.json
COPY --from=build /app/next.config.ts ./next.config.ts
COPY --from=build /app/public ./public
COPY --from=build /app/.next ./.next
COPY --from=build /app/node_modules ./node_modules

EXPOSE 3000

CMD ["npm", "run", "start"]
