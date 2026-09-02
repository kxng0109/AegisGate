# syntax=docker/dockerfile:1

# -----------------------------------------------------------------------------
# Stage 1: Build Application
# -----------------------------------------------------------------------------
FROM eclipse-temurin:25-jdk-noble AS builder

WORKDIR /workspace

# Cache Maven dependencies layer
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw dependency:go-offline -B -ntp

# Build executable JAR without tests
COPY src/ src/
RUN ./mvnw clean package -DskipTests -B -ntp

# -----------------------------------------------------------------------------
# Stage 2: Hardened Production Runtime
# -----------------------------------------------------------------------------
FROM eclipse-temurin:25-jre-noble AS runtime

LABEL maintainer="AegisGate Team" \
      description="Enterprise AI Gateway and Resilient Multi-Provider Reverse Proxy" \
      org.opencontainers.image.source="https://github.com/kxng0109/AegisGate"

# Install curl for health checking and update certificates
RUN apt-get update && \
    apt-get install -y --no-install-recommends curl ca-certificates && \
    rm -rf /var/lib/apt/lists/*

# Create unprivileged system user and group
RUN groupadd --system --gid 10001 aegisgate && \
    useradd --system --uid 10001 --gid aegisgate --no-create-home --shell /usr/sbin/nologin aegisgate

WORKDIR /app

# Copy application artifact with non-root ownership
COPY --from=builder --chown=aegisgate:aegisgate /workspace/target/AegisGate-*.jar /app/aegisgate.jar

USER aegisgate:aegisgate

EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=3s --start-period=20s --retries=3 \
  CMD curl -fsS http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", \
  "-XX:+UseCompactObjectHeaders", \
  "-XX:+ExitOnOutOfMemoryError", \
  "-XX:MaxRAMPercentage=75.0", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "/app/aegisgate.jar"]
