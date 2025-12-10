# ============================================================================
# Stage 1: Builder - Contains all build tools and dependencies
# ============================================================================
FROM alpine:3.20 AS builder

# Install build dependencies
# - openjdk21-jre-headless: Minimal Java runtime (no GUI, no JDK tools)
# - bash: Required for build scripts
# - git: For submodule management
# - wget & unzip: For downloading Groovy
# - pandoc: Document converter (already in Alpine repos!)
RUN apk add --no-cache \
    openjdk21-jre-headless \
    bash \
    git \
    wget \
    unzip \
    pandoc

# Install Groovy
RUN wget -q https://groovy.jfrog.io/artifactory/dist-release-local/groovy-zips/apache-groovy-binary-5.0.3.zip && \
    unzip -q apache-groovy-binary-5.0.3.zip -d /opt/ && \
    rm apache-groovy-binary-5.0.3.zip && \
    ln -s /opt/groovy-5.0.3 /opt/groovy

# Set environment variables for builder
ENV JAVA_HOME=/usr/lib/jvm/java-21-openjdk
ENV GROOVY_HOME=/opt/groovy
ENV PATH="${GROOVY_HOME}/bin:${JAVA_HOME}/bin:${PATH}"

WORKDIR /workspace

# Copy project files
COPY . /workspace

# Pre-download Groovy dependencies to cache them in the image
# Then clean up unnecessary files (source JARs, javadocs) to save space
RUN groovy ./init-groovy-deps.groovy 2>&1 && \
    echo "Cleaning up Groovy Grape cache..." && \
    find /root/.groovy/grapes -name "*-sources.jar" -delete 2>/dev/null || true && \
    find /root/.groovy/grapes -name "*-javadoc.jar" -delete 2>/dev/null || true && \
    find /root/.groovy/grapes -type d -name "cache" -exec rm -rf {} + 2>/dev/null || true && \
    echo "Cleanup complete" || echo "Warning: Could not pre-cache dependencies"

# ============================================================================
# Stage 2: Runtime - Minimal image with only what's needed to run builds
# ============================================================================
FROM alpine:3.20

# Install only runtime dependencies (no wget/unzip needed)
RUN apk add --no-cache \
    openjdk21-jre-headless \
    bash \
    git \
    pandoc

# Copy Groovy installation from builder
COPY --from=builder /opt/groovy-5.0.3 /opt/groovy-5.0.3
RUN ln -s /opt/groovy-5.0.3 /opt/groovy

# Copy cached Groovy dependencies from builder
COPY --from=builder /root/.groovy /root/.groovy

# Set environment variables
ENV JAVA_HOME=/usr/lib/jvm/java-21-openjdk
ENV GROOVY_HOME=/opt/groovy
ENV PATH="${GROOVY_HOME}/bin:${JAVA_HOME}/bin:${PATH}"

WORKDIR /workspace

# Copy only necessary project files (build scripts and source)
COPY build.groovy buildconfig.groovy init-groovy-deps.groovy build-arc42.sh ./
COPY lib/ ./lib/

# Default command: run the build script
CMD ["/bin/bash", "./build-arc42.sh"]
