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

EXPOSE 8080

CMD ["sh", "-c", "set -eu; SERVER_PORT=8085 java -jar /app/backend/app.jar & backend_pid=$!; /app/frontend/node_modules/.bin/next start /app/frontend -p 8080 -H 0.0.0.0 & frontend_pid=$!; trap 'kill \"$backend_pid\" \"$frontend_pid\" 2>/dev/null || true' INT TERM EXIT; while kill -0 \"$backend_pid\" 2>/dev/null && kill -0 \"$frontend_pid\" 2>/dev/null; do sleep 1; done; echo 'Backend or frontend exited unexpectedly'; exit 1"]