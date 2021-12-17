FROM gitpod/workspace-full:commit-d4b22db1963f969bcb30caed505c89b5e767a52d

USER root

# Install custom tools, runtime, etc.
#RUN apt-get update && apt-get install -y \
#        pandoc \
#    && apt-get clean && rm -rf /var/cache/apt/* && rm -rf /var/lib/apt/lists/* && rm -rf /tmp/*

# apt fetches an old version of pandoc, so let's use another machanism:
RUN wget https://github.com/jgm/pandoc/releases/download/2.16.2/pandoc-2.16.2-1-amd64.deb \
    && dpkg -i pandoc-2.16.2-1-amd64.deb \
    && rm pandoc-2.16.2-1-amd64.deb
    
USER gitpod
# Apply user-specific settings
#ENV ...

# Give back control
USER root
