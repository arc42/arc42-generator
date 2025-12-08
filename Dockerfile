# arc42-generator Build-Container
FROM ubuntu:24.04

# System-Updates und benötigte Tools
RUN apt-get update && \
    apt-get install -y wget git openjdk-21-jre-headless groovy sudo unzip && \
    rm -rf /var/lib/apt/lists/*

# Pandoc installieren (Version wie im build-arc42.sh)
RUN wget https://github.com/jgm/pandoc/releases/download/3.7.0.2/pandoc-3.7.0.2-1-amd64.deb && \
    dpkg -i pandoc-3.7.0.2-1-amd64.deb && \
    rm pandoc-3.7.0.2-1-amd64.deb

# Arbeitsverzeichnis
WORKDIR /workspace

# Projektdateien kopieren (nur für Build, für Entwicklung besser mit Volume)
COPY . /workspace

# Standardkommando: Build ausführen
CMD ["/bin/bash", "./build-arc42.sh"]
