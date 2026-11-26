# --- Build Stage ---
FROM --platform=$BUILDPLATFORM eclipse-temurin:25-jdk-resolute AS builder
WORKDIR /app
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN tr -d '\r' < gradlew > gradlew-lf && chmod +x gradlew-lf
RUN --mount=type=cache,target=/root/.gradle/caches \
    --mount=type=cache,target=/root/.gradle/wrapper \
    ./gradlew-lf dependencies --no-daemon || true
COPY . .
RUN --mount=type=cache,target=/root/.gradle/caches \
    --mount=type=cache,target=/root/.gradle/wrapper \
    ./gradlew-lf bootJar --no-daemon

# --- Runner Stage ---
FROM eclipse-temurin:25-jre-resolute
RUN groupadd -r -g 999 ukulele && useradd -rd /opt/ukulele -g ukulele -u 999 -ms /bin/bash ukulele
COPY --from=builder --chown=ukulele:ukulele /app/build/libs/ukulele.jar /opt/ukulele/ukulele.jar
USER ukulele
WORKDIR /opt/ukulele/
HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
    CMD curl -fsSk https://127.0.0.1:11112/actuator/health/liveness || curl -fsS http://127.0.0.1:11112/actuator/health/liveness || exit 1
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-XX:+UseCompactObjectHeaders", "-XX:+UseZGC", "-XX:ZUncommitDelay=60", "-jar", "/opt/ukulele/ukulele.jar"]
