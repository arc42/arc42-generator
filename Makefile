# arc42 generator: every task is a make target, and every target runs in Docker.
# Needed locally: make and Docker with Compose v2. Only 'pin' and 'release' use your local git,
# because they commit (and push) with your identity and credentials.
#
# The repository is bind-mounted into the container, so all output lands below the current
# directory: build/ (converted templates) and arc42-template/dist/ (distribution ZIPs).
# The image sets ARC42_IN_CONTAINER=1; inside the container (make shell, docker compose up)
# the same targets run directly instead of starting another container.
#
#   make help                                    all targets and variables
#   make build                                   full arc42 build: checkout, validation, all formats, ZIPs, output checks
#   make build UPDATE_TEMPLATE=1                 the same with the newest arc42-template master
#   make test                                    all test scripts; make test-lint etc. for one
#   make convert FORMAT=html                     one format only
#   make templates OPTS="--lint=warn"            options for build.groovy
#   make generate TEMPLATE=../req42-framework    another template repository (its own buildconfig.groovy)
#   make release                                 build and push the regenerated ZIPs to a branch of arc42-template

SHELL := /bin/bash
.DEFAULT_GOAL := help

COMPOSE ?= docker compose
SERVICE ?= arc42-builder
OPTS ?=
DOCKER_RUN_OPTS ?=
FORMAT ?=
TEMPLATE ?=
UPDATE_TEMPLATE ?=

SUBMODULE := arc42-template

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
BUILD_DIR := /project/build
else
MOUNT :=
OUTPUT_DIRS := /workspace/build /workspace/build2 /workspace/$(SUBMODULE)/dist
BUILD_DIR := build
endif

# RUN prefixes every command that needs the tools of the image (Java, Groovy, Pandoc, cmark, git)
ifdef ARC42_IN_CONTAINER
RUN :=
else
RUN := $(COMPOSE) run --rm $(MOUNT) $(DOCKER_RUN_OPTS) $(SERVICE)
endif
HOST_UID := $(shell id -u)
HOST_GID := $(shell id -g)
FIX_OWNER = @$(MAKE) --no-print-directory fix-owner

# with-help output of these formats must ship a non-empty images/ directory
FORMATS_WITH_IMAGES := markdown asciidoc textile rst html
TEST_SCRIPTS := $(patsubst test-%.groovy,%,$(wildcard test-*.groovy))

.PHONY: help image image-fresh versions shell \
        build generate templates convert distribution validate \
        test check \
        template-checkout template-update pin release \
        clean clean-dist fix-owner host-only _validate-markdown _validate-images

##@ Help

help: ## Show this help
	@echo "arc42 generator in Docker. Needs only make and Docker; output is written below the current directory."
	@echo
	@echo "Usage: make <target> [VARIABLE=value ...]"
	@awk 'BEGIN {FS = ":.*## "} \
	  /^##@ / {printf "\n%s\n", substr($$0, 5)} \
	  /^[a-zA-Z_%-]+:.*## / {printf "  %-18s %s\n", $$1, $$2}' $(MAKEFILE_LIST)
	@echo
	@echo "Variables:"
	@echo '  OPTS="..."         options for build.groovy, e.g. --lint=warn, --failure-level=error|fatal|none, --parallel=false'
	@echo "  FORMAT=html        convert one format only (templates, convert, generate, build)"
	@echo "  TEMPLATE=path      another template repository with its own buildconfig.groovy (not for build, release)"
	@echo "  UPDATE_TEMPLATE=1  build: use the newest arc42-template master instead of the recorded commit"
	@echo "  SOURCE_DATE_EPOCH  fixed timestamp for DOCX/EPUB/ZIP entries (default: last golden master commit outside dist/)"
	@echo "  DOCKER_RUN_OPTS    extra options for docker compose run, e.g. -e GITHUB_ACTIONS=true (used by the CI of arc42-template)"
	@echo
	@echo "Test scripts: $(TEST_SCRIPTS)"

##@ Docker image

image: ## Build the Docker image (Java, Groovy, Pandoc, cmark); rerun after changing the Dockerfile
	$(COMPOSE) build

image-fresh: ## Rebuild the Docker image from scratch (no cache)
	$(COMPOSE) build --no-cache

versions: ## Show the tool versions inside the image
	$(RUN) sh -c 'java -version 2>&1 | head -n 1; groovy --version; pandoc --version | head -n 1; \
	  cmark --version | head -n 1; git --version; make --version | head -n 1'

shell: ## Interactive shell in the container (the make targets work there as well)
	$(RUN) /bin/bash

##@ Build arc42

build: ## Full arc42 build: check out arc42-template, all phases, then validate the output
ifneq ($(TEMPLATE),)
	$(error build is for arc42 only; use: make generate TEMPLATE=$(TEMPLATE))
endif
	@$(MAKE) --no-print-directory $(if $(filter 1 true yes,$(UPDATE_TEMPLATE)),template-update,template-checkout)
	@$(MAKE) --no-print-directory generate
	@$(MAKE) --no-print-directory validate
	@echo
	@echo "✓ Build complete. Distribution ZIPs: $(SUBMODULE)/dist/"
	@echo "  Publish them with: make release"

generate: ## All phases of build.groovy: templates, convert, distribution (no checkout, no output checks)
	$(RUN) groovy build.groovy $(BUILD_OPTS)
	$(FIX_OWNER)

templates: ## Phase 1: validate the golden master and generate the templates into build/src_gen/
	$(RUN) groovy build.groovy templates $(BUILD_OPTS)
	$(FIX_OWNER)

convert: ## Phases 2-3: discover and convert the templates (FORMAT=html for a single format)
	$(RUN) groovy build.groovy convert $(BUILD_OPTS)
	$(FIX_OWNER)

distribution: ## Phase 4: create the distribution ZIPs
	$(RUN) groovy build.groovy distribution $(BUILD_OPTS)
	$(FIX_OWNER)

validate: ## Check the output: Markdown with cmark (warnings only), images of the with-help styles (fails)
	$(RUN) make --no-print-directory _validate-markdown _validate-images BUILD_DIR=$(BUILD_DIR)

##@ Tests

test: ## Run all test scripts
	$(RUN) groovy run-all-tests.groovy
	$(FIX_OWNER)

# not .PHONY: make ignores pattern rules for phony targets
test-%: ## Run one test script, e.g. make test-lint (list: make help)
	@[ -f test-$*.groovy ] || { echo "No test script test-$*.groovy. Available: $(TEST_SCRIPTS)" >&2; exit 2; }
	$(RUN) groovy test-$*.groovy
	$(FIX_OWNER)

check: test ## Alias of test

##@ Golden master (arc42-template submodule)

template-checkout: ## Check out arc42-template at the commit recorded in this repository (keeps local changes)
	@$(RUN) sh -c 'for attempt in 1 2 3; do \
	    rm -f .git/index.lock .git/modules/$(SUBMODULE)/index.lock; \
	    if git submodule update --init --recursive; then \
	      git -C $(SUBMODULE) log -1 --format="✓ $(SUBMODULE) at recorded commit %h (%ad) %s" --date=short; exit 0; \
	    fi; \
	    echo "submodule update failed (attempt $$attempt/3), retrying..."; sleep 2; \
	  done; \
	  echo "✗ Could not check out $(SUBMODULE). Local changes? See: git -C $(SUBMODULE) status" >&2; exit 1'

template-update: template-checkout ## Move arc42-template to the newest master (record it with: make pin)
	@$(RUN) sh -c 'git -C $(SUBMODULE) checkout -q master && git -C $(SUBMODULE) pull -q --ff-only && \
	  git -C $(SUBMODULE) log -1 --format="✓ $(SUBMODULE) at master %h (%ad) %s" --date=short || { \
	  echo "✗ $(SUBMODULE) has local changes (rebuilt ZIPs? make clean-dist). See: git -C $(SUBMODULE) status" >&2; exit 1; }'
	@echo "  Builds use this commit until the next template-checkout; to record it: make pin"

pin: host-only ## Record the checked-out arc42-template commit in this repository (git commit; uses your git)
	@sha=$$(git -C $(SUBMODULE) rev-parse --short HEAD); \
	if git diff --quiet -- $(SUBMODULE); then \
	  echo "✓ $(SUBMODULE) is already pinned at $$sha"; \
	else \
	  git add $(SUBMODULE) && git commit -q -m "Pin $(SUBMODULE) $$sha" && \
	  echo "✓ Pinned $(SUBMODULE) at $$sha on branch $$(git branch --show-current); push it (via a pull request)"; \
	fi

##@ Release

release: host-only ## Build, then commit the regenerated ZIPs to a new branch of arc42-template and push it (uses your git)
ifneq ($(TEMPLATE),)
	$(error release is for arc42 only)
endif
	@git -C $(SUBMODULE) fetch -q origin master
	@pinned=$$(git rev-parse HEAD:$(SUBMODULE)); \
	if [ -n "$(filter 1 true yes,$(UPDATE_TEMPLATE))" ] || [ "$$pinned" = "$$(git -C $(SUBMODULE) rev-parse origin/master)" ]; then \
	  exit 0; \
	elif git -C $(SUBMODULE) diff --quiet "$$pinned" origin/master -- . ':(exclude)dist'; then \
	  echo "✓ $(SUBMODULE) master differs from the recorded commit $$(git -C $(SUBMODULE) rev-parse --short $$pinned) only in dist/: releasing from master"; \
	else \
	  echo "✗ The recorded $(SUBMODULE) commit is not the newest master: the ZIPs would not match the template." >&2; \
	  echo "  Merge the pending pin update (Dependabot) first, or: make release UPDATE_TEMPLATE=1" >&2; exit 1; \
	fi
	@# the same template content as recorded (or UPDATE_TEMPLATE): build from master, so that the
	@# release branch starts from the current dist/ and ZIPs deleted there cannot come back
	@$(MAKE) --no-print-directory build UPDATE_TEMPLATE=1
	@cd $(SUBMODULE) && \
	if [ -z "$$(git status --porcelain -- dist)" ]; then \
	  echo "✓ dist/ is unchanged: nothing to release"; exit 0; \
	fi; \
	branch="dist/$$(date +%Y-%m-%d)-$$(git rev-parse --short HEAD)"; \
	git switch -q -c "$$branch" && git add -A dist && \
	git commit -q -m "Update the distribution ZIPs (generated from $$(git rev-parse --short HEAD))" && \
	git push -q -u origin "$$branch" && \
	echo "✓ Pushed $$branch. Open a pull request: https://github.com/arc42/$(SUBMODULE)/compare/$$branch?expand=1"

##@ Cleanup

clean: ## Remove the build output (build/ and build2/; the distribution ZIPs are kept)
	$(RUN) sh -c 'rm -rf $(filter-out %/dist,$(OUTPUT_DIRS))'

clean-dist: ## Discard regenerated ZIPs in arc42-template/dist/ (restore the committed ones)
	$(RUN) sh -c 'git -C $(SUBMODULE) checkout -- dist && git -C $(SUBMODULE) clean -fq -- dist'

fix-owner: ## Linux only: give files created by the container (root) back to the calling user
	@if [ -z "$(ARC42_IN_CONTAINER)" ] && [ "$$(uname -s)" = Linux ] && [ "$(HOST_UID)" != 0 ]; then \
	  $(RUN) sh -c 'for d in $(OUTPUT_DIRS); do [ -d "$$d" ] && chown -R $(HOST_UID):$(HOST_GID) "$$d"; done; true'; \
	fi

# ---------------------------------------------------------------------------------------------
# Internal targets

host-only:
	@if [ -n "$(ARC42_IN_CONTAINER)" ]; then \
	  echo "✗ This target uses your git identity and credentials: run it outside the container." >&2; exit 1; \
	fi

# CommonMark syntax of the Markdown output; problems are reported, never fatal
_validate-markdown:
	@echo "==> CommonMark validation of the Markdown output (warnings only)"
	@checked=0; failed=0; \
	for f in $(BUILD_DIR)/*/markdown/*/*.md; do \
	  [ -f "$$f" ] || continue; \
	  checked=$$((checked + 1)); \
	  cmark "$$f" > /dev/null 2>&1 || { echo "  ⚠ $$f"; failed=$$((failed + 1)); }; \
	done; \
	if [ $$checked -eq 0 ]; then echo "⚠ No Markdown files under $(BUILD_DIR)/*/markdown/ (nothing validated)"; \
	elif [ $$failed -eq 0 ]; then echo "✓ All $$checked Markdown files are valid CommonMark"; \
	else echo "⚠ $$failed of $$checked Markdown files failed the CommonMark validation (not fatal)"; fi

# Images of the with-help styles (the plain styles contain none): every images/ directory of
# FORMATS_WITH_IMAGES present and non-empty, every image reference in the Markdown resolvable.
# Pandoc may wrap the alt text over lines, never the target, so the targets "](...)" are matched per line.
_validate-images:
	@echo "==> Images of the with-help styles (fails the build)"
	@problems=0; checked=0; \
	for lang_dir in $(BUILD_DIR)/*/; do \
	  lang=$$(basename "$$lang_dir"); \
	  for format in $(FORMATS_WITH_IMAGES); do \
	    dir="$${lang_dir}$$format/with-help"; \
	    [ -d "$$dir" ] || continue; \
	    if [ ! -d "$$dir/images" ]; then echo "  ✗ Missing images directory: $$lang/$$format/with-help/images/"; problems=$$((problems + 1)); \
	    elif [ -z "$$(ls -A "$$dir/images")" ]; then echo "  ✗ Empty images directory: $$lang/$$format/with-help/images/"; problems=$$((problems + 1)); fi; \
	  done; \
	done; \
	for md in $(BUILD_DIR)/*/markdown/with-help/*.md; do \
	  [ -f "$$md" ] || continue; \
	  checked=$$((checked + 1)); \
	  md_dir=$$(dirname "$$md"); \
	  lang=$$(basename "$$(dirname "$$(dirname "$$md_dir")")"); \
	  while IFS= read -r ref; do \
	    ref="$${ref#](}"; ref="$${ref%)}"; \
	    ref="$${ref%% \"*}"; ref="$${ref%% \'*}"; \
	    ref="$${ref#<}"; ref="$${ref%>}"; ref="$${ref#./}"; \
	    [ -n "$$ref" ] || continue; \
	    ext="$${ref##*.}"; \
	    case "$${ext,,}" in png|jpg|jpeg|gif|svg|webp|bmp) ;; *) continue ;; esac; \
	    case "$$ref" in http://*|https://*|data:*) continue ;; esac; \
	    if [ ! -f "$$md_dir/$$ref" ]; then echo "  ✗ Missing image in $$lang: $$ref (referenced in $$(basename "$$md"))"; problems=$$((problems + 1)); fi; \
	  done < <(grep -o '[]][(][^)]*[)]' "$$md" || true); \
	done; \
	if [ $$checked -eq 0 ]; then echo "⚠ No Markdown with-help files under $(BUILD_DIR)/*/markdown/with-help/"; fi; \
	if [ $$problems -ne 0 ]; then \
	  echo "✗ $$problems image problem(s) in the with-help styles (see above): the output in build/ and dist/ is incomplete" >&2; exit 1; \
	fi; \
	echo "✓ Image directories present, all image references valid ($$checked Markdown with-help file(s) checked)"
