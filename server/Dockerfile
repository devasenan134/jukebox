# Build stage: compile the server with Gradle (works on ARM and x86).
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN ./gradlew --no-daemon -q dependencies > /dev/null
COPY src src
RUN ./gradlew --no-daemon -q installDist -x test

# Run stage: the Java runtime, the built server, and ffmpeg (reads tags, hashes audio, fingerprints with
# Chromaprint, resizes covers) for the library scanner.
FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /src/build/install/jukebox /app
ENV DB_PATH=/data/jukebox.db
# The heap stays small: a home server runs other things too. Override with JAVA_OPTS.
ENV JAVA_OPTS=-Xmx512m
VOLUME /data
EXPOSE 8095
ENTRYPOINT ["/app/bin/jukebox"]
