FROM gitpod/workspace-full:2025-05-14-07-50-25
USER root

# Install custom tools, runtime, etc.
#RUN apt-get update && apt-get install -y \
#        pandoc \
#    && apt-get clean && rm -rf /var/cache/apt/* && rm -rf /var/lib/apt/lists/* && rm -rf /tmp/*

# apt fetches an old version of pandoc, so let's use another machanism:
RUN wget https://github.com/jgm/pandoc/releases/download/3.7/pandoc-3.7-1-amd64.deb \
    && dpkg -i pandoc-3.7-1-amd64.deb \
    && rm pandoc-3.7-1-amd64.deb
    
USER gitpod
# Apply user-specific settings
#ENV ...

# Give back control
USER root
