# arc42 generator, running entirely in Docker: only make and docker are needed locally.
# The repository is bind-mounted into the container, so all output lands below the current
# directory: build/ (converted templates) and arc42-template/dist/ (distribution ZIPs).
#
#   make help
#   make build                                   full arc42 build: templates, all formats, ZIPs, validations
#   make test                                    all test scripts
#   make convert FORMAT=html                     one format only
#   make templates OPTS="--lint=warn"            options for build.groovy
#   make generate TEMPLATE=../req42-framework    another template repository (its own buildconfig.groovy)
#   UPDATE_TEMPLATE=1 make build                 build the newest golden master instead of the recorded commit

SHELL := /bin/bash
.DEFAULT_GOAL := help

COMPOSE ?= docker compose
SERVICE ?= arc42-builder
OPTS ?=
FORMAT ?=
TEMPLATE ?=

# options passed to build.groovy
BUILD_OPTS := $(OPTS)
ifneq ($(FORMAT),)
BUILD_OPTS += --format=$(FORMAT)
endif

# another template repository: mounted at /project, built from its own buildconfig.groovy
ifneq ($(TEMPLATE),)
MOUNT := -v "$(abspath $(TEMPLATE)):/project"
BUILD_OPTS += --config=/project/buildconfig.groovy
OUTPUT_DIRS := /project/build /project/build2 /project/dist
else
MOUNT :=
OUTPUT_DIRS := /workspace/build /workspace/build2 /workspace/arc42-template/dist
endif

RUN := $(COMPOSE) run --rm $(MOUNT) $(SERVICE)
HOST_UID := $(shell id -u)
HOST_GID := $(shell id -g)

.PHONY: help image build templates convert distribution generate test check shell clean fix-owner

help: ## Show this help
	@echo "arc42 generator in Docker. Needs only make and docker; output is written below the current directory."
	@echo
	@echo "Usage: make <target> [OPTS=\"--lint=warn --failure-level=error\"] [FORMAT=html] [TEMPLATE=path/to/template-repo]"
	@echo
	@awk 'BEGIN {FS = ":.*## "} /^[a-zA-Z_-]+:.*## / {printf "  %-14s %s\n", $$1, $$2}' $(MAKEFILE_LIST)
	@echo
	@echo "OPTS and FORMAT are options for build.groovy (templates, convert, distribution, generate)."
	@echo "TEMPLATE applies to templates, convert, distribution, generate and clean."
	@echo "build always builds arc42 via ./build-arc42.sh; UPDATE_TEMPLATE=1 make build uses the newest golden master."

image: ## Build the Docker image (Java, Groovy, Pandoc, cmark); rerun after changing the Dockerfile
	$(COMPOSE) build

build: ## Full arc42 build: templates, all formats, ZIPs and the script's validations (./build-arc42.sh)
	$(RUN) ./build-arc42.sh
	@$(MAKE) --no-print-directory fix-owner

templates: ## Phase 1: validate the golden master and generate the templates into build/src_gen/
	$(RUN) groovy build.groovy templates $(BUILD_OPTS)
	@$(MAKE) --no-print-directory fix-owner

convert: ## Phases 2-3: discover and convert the templates (FORMAT=html for a single format)
	$(RUN) groovy build.groovy convert $(BUILD_OPTS)
	@$(MAKE) --no-print-directory fix-owner

distribution: ## Phase 4: create the distribution ZIPs
	$(RUN) groovy build.groovy distribution $(BUILD_OPTS)
	@$(MAKE) --no-print-directory fix-owner

generate: ## All phases of build.groovy (templates, convert, distribution) without the script's extra validations
	$(RUN) groovy build.groovy $(BUILD_OPTS)
	@$(MAKE) --no-print-directory fix-owner

test: ## Run all test scripts (groovy run-all-tests.groovy) in the container
	$(RUN) groovy run-all-tests.groovy
	@$(MAKE) --no-print-directory fix-owner

check: test ## Alias of test

shell: ## Interactive shell in the container
	$(RUN) /bin/bash

clean: ## Remove the build output (build/ and build2/; the distribution ZIPs are kept)
	$(RUN) sh -c 'rm -rf $(filter-out %/dist,$(OUTPUT_DIRS))'

fix-owner: ## Linux only: give files created by the container (root) back to the calling user
	@if [ "$$(uname -s)" = Linux ] && [ "$(HOST_UID)" != 0 ]; then \
	  $(RUN) sh -c 'for d in $(OUTPUT_DIRS); do [ -d "$$d" ] && chown -R $(HOST_UID):$(HOST_GID) "$$d"; done; true'; \
	fi
