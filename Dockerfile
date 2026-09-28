# ============================================================================
# Stage 1: Builder - Contains all build tools and dependencies
# ============================================================================
FROM alpine:3.20 AS builder

# Install build dependencies
# - openjdk21-jre-headless: Minimal Java runtime (no GUI, no JDK tools)
# - bash: Required for build scripts
# - git: For submodule management
# - wget & unzip: For downloading Groovy and pandoc
# - cmark: CommonMark markdown validator
# pandoc is deliberately NOT taken from the Alpine repository (unpinned
# version); it is installed below from the official GitHub release, pinned to
# the same version that build-arc42.sh installs outside Docker and verified
# against a SHA-256 checksum.
RUN apk add --no-cache \
    openjdk21-jre-headless \
    bash \
    git \
    wget \
    unzip \
    cmark

# Install pandoc from the official release tarball (pinned + checksum-verified).
# The release binaries are statically linked, so they run on Alpine (musl).
# TARGETARCH (amd64|arm64) is set automatically by BuildKit/buildx; with a
# plain "docker build" it can be empty, then apk --print-arch is used instead.
ARG TARGETARCH
ARG PANDOC_VERSION=3.7.0.2
ARG PANDOC_SHA256_AMD64=8f8f67fdd540b6519326b0ac49d5c55c5d5d15e43920e80a086e02c8aff83268
ARG PANDOC_SHA256_ARM64=4ef2997ff0fa7f86ada5a217722f4f732293e38518b4442ececce16628bd0e44
RUN set -eu; \
    arch="${TARGETARCH:-$(apk --print-arch)}"; \
    case "$arch" in \
        amd64|x86_64)  arch=amd64; expected="$PANDOC_SHA256_AMD64" ;; \
        arm64|aarch64) arch=arm64; expected="$PANDOC_SHA256_ARM64" ;; \
        *) echo "Unsupported architecture for pandoc: $arch" >&2; exit 1 ;; \
    esac; \
    tarball="pandoc-${PANDOC_VERSION}-linux-${arch}.tar.gz"; \
    echo "Downloading ${tarball}..."; \
    wget -q -O "/tmp/${tarball}" \
        "https://github.com/jgm/pandoc/releases/download/${PANDOC_VERSION}/${tarball}"; \
    actual=$(sha256sum "/tmp/${tarball}"); \
    actual="${actual%% *}"; \
    if [ "$actual" != "$expected" ]; then \
        echo "Checksum mismatch for ${tarball}: expected ${expected}, got ${actual}" >&2; \
        exit 1; \
    fi; \
    echo "SHA-256 verified: ${actual}"; \
    tar -xzf "/tmp/${tarball}" -C /tmp; \
    mv "/tmp/pandoc-${PANDOC_VERSION}/bin/pandoc" /usr/local/bin/pandoc; \
    chmod 0755 /usr/local/bin/pandoc; \
    rm -rf "/tmp/${tarball}" "/tmp/pandoc-${PANDOC_VERSION}"; \
    pandoc --version

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

# Install only runtime dependencies (no wget/unzip needed; pandoc is copied
# from the builder stage below instead of being installed from apk)
RUN apk add --no-cache \
    openjdk21-jre-headless \
    bash \
    git \
    cmark

# Fix Git ownership for mounted volumes (Codespaces/CI environments)
RUN git config --global --add safe.directory '*'

# Copy the pinned, verified pandoc binary from builder (static, no dependencies)
COPY --from=builder /usr/local/bin/pandoc /usr/local/bin/pandoc

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
