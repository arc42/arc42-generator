FROM gitpod/workspace-full:commit-d4b22db1963f969bcb30caed505c89b5e767a52d

USER root

# fall back java to 8

RUN curl -fsSL "https://get.sdkman.io" | bash \
 && bash -c ". /home/gitpod/.sdkman/bin/sdkman-init.sh \
             && sdk default 8.0.262-amzn \
             && sdk flush archives \
             && sdk flush temp"

# Install custom tools, runtime, etc.
RUN apt-get update && apt-get install -y \
        pandoc \
    && apt-get clean && rm -rf /var/cache/apt/* && rm -rf /var/lib/apt/lists/* && rm -rf /tmp/*

USER gitpod
# Apply user-specific settings
#ENV ...

# Give back control
USER root
