# arc42-generator Build-Container (Alpine-based for minimal size)
FROM alpine:3.20

# Install system dependencies in a single layer
# - openjdk21: Java runtime for Groovy
# - bash: Required for build scripts
# - git: For submodule management
# - wget & unzip: For downloading Groovy
# - pandoc: Document converter (already in Alpine repos!)
RUN apk add --no-cache \
    openjdk21 \
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

# Set environment variables
ENV JAVA_HOME=/usr/lib/jvm/java-21-openjdk
ENV GROOVY_HOME=/opt/groovy
ENV PATH="${GROOVY_HOME}/bin:${JAVA_HOME}/bin:${PATH}"

# Working directory
WORKDIR /workspace

# Copy project files
COPY . /workspace

# Pre-download Groovy dependencies to cache them in the image
RUN groovy ./init-groovy-deps.groovy 2>&1 || echo "Warning: Could not pre-cache dependencies"

# Default command: run the build script
CMD ["/bin/bash", "./build-arc42.sh"]
