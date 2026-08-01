FROM maven:3.9.9-eclipse-temurin-21 AS backend-build

WORKDIR /backend

COPY pom.xml .
COPY .mvn .mvn
COPY src src

RUN mvn -s .mvn/local-settings.xml -DskipTests package


FROM node:24-alpine AS frontend-deps

WORKDIR /frontend

COPY web/package.json web/package-lock.json ./

RUN npm ci


FROM node:24-alpine AS frontend-build

WORKDIR /frontend

ENV NEXT_PUBLIC_API_URL=""

COPY --from=frontend-deps /frontend/node_modules ./node_modules
COPY web/ .

RUN npm run build


FROM node:24-alpine AS runtime

RUN apk add --no-cache openjdk21-jre

WORKDIR /app

ENV NODE_ENV=production

COPY --from=backend-build \
    /backend/target/iwrite-backend-0.0.1-SNAPSHOT.jar \
    /app/backend/app.jar

COPY --from=frontend-build /frontend/package.json /app/frontend/package.json
COPY --from=frontend-build /frontend/next.config.ts /app/frontend/next.config.ts
COPY --from=frontend-build /frontend/public /app/frontend/public
COPY --from=frontend-build /frontend/.next /app/frontend/.next
COPY --from=frontend-build /frontend/node_modules /app/frontend/node_modules

# OpenTelemetry Java Agent 2.30.0, versão fixa com SHA-256 validado pelo BuildKit.
ADD --checksum=sha256:9d6bc2ad8dd8fb7f730984988e57b8ac0a82d81c7b3b8ae795378718733a509d \
    --chmod=444 \
    https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.30.0/opentelemetry-javaagent.jar \
    /app/otel/opentelemetry-javaagent.jar

COPY --chmod=555 docker/start.sh /app/start.sh

EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=5s --start-period=30s --retries=5 \
  CMD node -e "fetch('http://127.0.0.1:8080/health',{redirect:'manual'}).then(r => process.exit(r.status >= 200 && r.status < 400 ? 0 : 1)).catch(() => process.exit(1))"
CMD ["/app/start.sh"]