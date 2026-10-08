# Jukebox: the server and the web app in one image (works on ARM and x86).

# The web app and the server's jars are the same on every CPU, so they're built on the machine doing the build
# ($BUILDPLATFORM); only the last stage is made per platform. (Building them under arm64 emulation in CI was slow
# enough that Gradle's download timed out.)

# 1. The web app (web/): a static site the server hands out at /.
FROM --platform=$BUILDPLATFORM node:22-slim AS web
WORKDIR /web
COPY web/package.json web/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY web ./
RUN npm run build

# 2. The server (server/), compiled with Gradle.
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY server/gradlew server/settings.gradle.kts server/build.gradle.kts ./
COPY server/gradle gradle
RUN ./gradlew --no-daemon -q dependencies > /dev/null
COPY server/src src
RUN ./gradlew --no-daemon -q installDist -x test

# 3. What runs: the Java runtime, the server, the web app, and ffmpeg (reads tags, hashes audio,
# fingerprints with Chromaprint, resizes covers) for the library scanner.
FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /src/build/install/jukebox /app
COPY --from=web /web/dist /app/web
ENV DB_PATH=/data/jukebox.db
ENV WEB_DIR=/app/web
# The heap stays small: a home server runs other things too. Override with JAVA_OPTS.
ENV JAVA_OPTS="-Xmx512m -Djava.awt.headless=true"
VOLUME /data
EXPOSE 8095
ENTRYPOINT ["/app/bin/jukebox"]
