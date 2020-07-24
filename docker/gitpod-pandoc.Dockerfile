FROM gitpod/workspace-full:commit-c22653ec89f7ad562d086ad24c26569cc3cb660f

USER root


# Install custom tools, runtime, etc.
RUN apt-get update && apt-get install -y \
        pandoc \
    && apt-get clean && rm -rf /var/cache/apt/* && rm -rf /var/lib/apt/lists/* && rm -rf /tmp/*

USER gitpod
# Apply user-specific settings
#ENV ...

# Give back control
USER root
