FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /workspace
COPY gradle ./gradle
COPY gradlew build.gradle.kts settings.gradle.kts ./
COPY src ./src
RUN chmod +x ./gradlew && ./gradlew --no-daemon installDist

FROM eclipse-temurin:17-jre-jammy
RUN groupadd --system --gid 10001 habitlab \
    && useradd --system --uid 10001 --gid habitlab --home-dir /app habitlab \
    && mkdir -p /data \
    && chown habitlab:habitlab /data
WORKDIR /app
COPY --from=build --chown=habitlab:habitlab /workspace/build/install/HabitLabSyncServer/ ./
USER habitlab
ENV HABITLAB_SYNC_HOST=0.0.0.0 \
    HABITLAB_SYNC_PORT=8787 \
    HABITLAB_SYNC_DATA=/data
EXPOSE 8787
ENTRYPOINT ["/app/bin/HabitLabSyncServer"]
