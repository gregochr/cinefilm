# syntax=docker/dockerfile:1
# One image: Spring Boot serves the React SPA. Build from the repo root:  docker build -t cinefilm .
# linux/amd64 only (both Macs are Intel).

# --- 1. SPA ---------------------------------------------------------------------------------------------------------
FROM node:24-slim AS web
WORKDIR /web
COPY frontend/package.json frontend/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci
COPY frontend/ ./
RUN npm run build

# --- 2. Jar (SPA copied into Spring's static resources) --------------------------------------------------------------
FROM eclipse-temurin:25-jdk AS build
WORKDIR /build
COPY backend/ ./
COPY --from=web /web/dist/ src/main/resources/static/
# Tests, coverage and SpotBugs are CI's job (ci.yml); this stage only packages.
RUN --mount=type=cache,target=/root/.m2 chmod +x mvnw && ./mvnw -B --no-transfer-progress -DskipTests package \
    && cp target/cinefilm-*.jar /build/app.jar

# --- 3. Runtime: Ubuntu (noble) JRE, with ffmpeg/ffprobe and the legacy Intel VAAPI driver for later phases ---------
FROM eclipse-temurin:25-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl tzdata ffmpeg i965-va-driver vainfo \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd -g 1001 cinefilm \
    && useradd -u 1001 -g 1001 -r -M -s /usr/sbin/nologin cinefilm \
    && mkdir -p /app/logs /app/cache \
    && chown -R cinefilm:cinefilm /app

ENV TZ=Europe/London \
    SPRING_PROFILES_ACTIVE=prod

ARG APP_VERSION=dev
ARG GIT_COMMIT_SHA=
LABEL org.opencontainers.image.source="https://github.com/gregochr/cinefilm" \
      org.opencontainers.image.version="${APP_VERSION}" \
      org.opencontainers.image.revision="${GIT_COMMIT_SHA}"
ENV APP_VERSION=${APP_VERSION} GIT_COMMIT_SHA=${GIT_COMMIT_SHA}

WORKDIR /app
COPY --from=build --chown=cinefilm:cinefilm /build/app.jar app.jar
USER cinefilm
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -fsS http://localhost:8080/actuator/health/container >/dev/null || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
