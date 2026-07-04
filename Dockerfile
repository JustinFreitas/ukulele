# --- Build Stage ---
FROM eclipse-temurin:25-jdk-jammy AS builder
WORKDIR /app
COPY . .
RUN tr -d '\r' < gradlew > gradlew-lf && chmod +x gradlew-lf
RUN ./gradlew-lf bootJar --no-daemon

# --- Runner Stage ---
FROM eclipse-temurin:25-jdk-jammy
RUN groupadd -r -g 999 ukulele && useradd -rd /opt/ukulele -g ukulele -u 999 -ms /bin/bash ukulele
COPY --from=builder --chown=ukulele:ukulele /app/build/libs/ukulele.jar /opt/ukulele/ukulele.jar
USER ukulele
WORKDIR /opt/ukulele/
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "/opt/ukulele/ukulele.jar"]
