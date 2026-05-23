# ── Build stage ───────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /app
COPY pom.xml .
# Download deps first (layer cache friendly)
RUN mvn dependency:go-offline -q

COPY src ./src
RUN mvn package -DskipTests -q

# ── Runtime stage ──────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-jammy

# Install git + common linter runtimes
RUN apt-get update && apt-get install -y --no-install-recommends \
    git \
    nodejs \
    npm \
    python3 \
    python3-pip \
    && npm install -g eslint \
    && pip3 install ruff --break-system-packages \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Workspace for cloned repos
RUN mkdir -p /tmp/hermes-reviews

COPY --from=build /app/target/hermes-pr-reviewer-*.jar app.jar

# Non-root user for security
RUN useradd -r -s /bin/false hermes
RUN chown hermes:hermes /tmp/hermes-reviews
USER hermes

EXPOSE 8080

ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-jar", "app.jar"]
