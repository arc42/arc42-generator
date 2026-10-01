# arc42 generator: every task is a make target, and every target runs in Docker.
# Needed locally: make and Docker with Compose v2. Only 'pin', 'release' and 'release-tools' run on
# the host: 'pin' commits with your git identity, the releases publish with the GitHub CLI gh
# (logged in), which is not part of the image.
#
# The repository is bind-mounted into the container, so all output lands below the current
# directory: build/ (converted templates, distribution ZIPs and manifest.json in build/dist/).
# The image sets ARC42_IN_CONTAINER=1; inside the container (make shell, docker compose up)
# the same targets run directly instead of starting another container.
#
#   make help                                    all targets and variables
#   make build                                   full arc42 build: checkout, validation, all formats, ZIPs, output checks
#   make build UPDATE_TEMPLATE=1                 the same with the newest arc42-template main
#   make test                                    all test scripts; make test-lint etc. for one
#   make convert FORMAT=html                     one format only
#   make templates OPTS="--lint=warn"            options for build.groovy
#   make generate TEMPLATE=../req42-framework    another template repository (its own buildconfig.groovy)
#   make release                                 build and publish the ZIPs as GitHub Release of arc42-template
#   make release REPO=you/arc42-template PRERELEASE=1   the same as pre-release on a fork (for tests)

SHELL := /bin/bash
.DEFAULT_GOAL := help

COMPOSE ?= docker compose
SERVICE ?= arc42-builder
OPTS ?=
DOCKER_RUN_OPTS ?=
FORMAT ?=
TEMPLATE ?=
UPDATE_TEMPLATE ?=
# release (host only, needs the GitHub CLI gh): tag, repository, pre-release, tag of the tools release
TAG ?= $(shell date +%Y.%m.%d)
REPO ?= arc42/arc42-template
# the website that is notified after a release (repository_dispatch template-released)
SITE_REPO ?= arc42/arc42.org-site
PRERELEASE ?=
TOOLS_TAG ?=
# check-downloads: where the files are served; CHECKSUMS=1 downloads them and compares the SHA-256
PREFIX ?=
CHECKSUMS ?=
PRERELEASE_ON := $(filter 1 true yes,$(PRERELEASE))
# a template release (tag YYYY.MM.DD[.n], not a pre-release) exists on REPO; tools releases do not count
HAS_TEMPLATE_RELEASE = gh release list -R $(REPO) --exclude-pre-releases --exclude-drafts --limit 100 --json tagName -q '.[].tagName' | grep -qE '^[0-9]{4}\.[0-9]{2}\.[0-9]{2}(\.[0-9]+)?$$'

SUBMODULE := arc42-template
# default branch of arc42-template (main): asked from GitHub on first use, then remembered
TEMPLATE_BRANCH ?= $(eval TEMPLATE_BRANCH := $(or $(shell git ls-remote --symref https://github.com/arc42/arc42-template.git HEAD 2>/dev/null | sed -n 's|^ref: refs/heads/\([^[:space:]]*\).*|\1|p'),main))$(TEMPLATE_BRANCH)
# the distribution ZIPs and manifest.json (distribution.targetPath in buildconfig.groovy)
DIST_DIR := build/dist

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
OUTPUT_DIRS := /workspace/build /workspace/build2
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
        template-checkout template-update pin release release-tools check-downloads \
        clean fix-owner host-only _gh-check _validate-markdown _validate-images

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
	@echo "  UPDATE_TEMPLATE=1  build: use the newest arc42-template main instead of the recorded commit"
	@echo "  SOURCE_DATE_EPOCH  fixed timestamp for DOCX/EPUB/ZIP entries (default: last golden master commit outside dist/)"
	@echo "  TAG=2026.09.30     release: tag of the GitHub Release (default: today; a second one: TAG=YYYY.MM.DD.2)"
	@echo "  REPO=owner/repo    release, release-tools: repository of the release (default: arc42/arc42-template)"
	@echo "  PRERELEASE=1       release: publish as pre-release (never becomes latest)"
	@echo "  TOOLS_TAG=tools-YYYY.MM  release-tools: tag of the tools release"
	@echo "  PREFIX=url         check-downloads: prefix of the file URLs, e.g. https://github.com/arc42/arc42-template/releases/latest/download/"
	@echo "  CHECKSUMS=1        check-downloads: download every file and compare its SHA-256 with manifest.json"
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
	@echo "✓ Build complete. Distribution ZIPs: $(DIST_DIR)/"
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

template-update: template-checkout ## Move arc42-template to the newest main (record it with: make pin)
	@$(RUN) sh -c 'git -C $(SUBMODULE) fetch -q origin && git -C $(SUBMODULE) checkout -q $(TEMPLATE_BRANCH) && git -C $(SUBMODULE) pull -q --ff-only && \
	  git -C $(SUBMODULE) log -1 --format="✓ $(SUBMODULE) at $(TEMPLATE_BRANCH) %h (%ad) %s" --date=short || { \
	  echo "✗ $(SUBMODULE) has local changes. See: git -C $(SUBMODULE) status" >&2; exit 1; }'
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

release: host-only _gh-check ## Build and publish the ZIPs and manifest.json as GitHub Release TAG on arc42-template (uses your gh)
ifneq ($(TEMPLATE),)
	$(error release is for arc42 only)
endif
	@if gh release view "$(TAG)" -R $(REPO) >/dev/null 2>&1 || gh api "repos/$(REPO)/git/ref/tags/$(TAG)" >/dev/null 2>&1; then \
	  echo "✗ Tag $(TAG) exists on $(REPO). A second release on the same day: make release TAG=$(TAG).2" >&2; exit 1; \
	fi
	@git -C $(SUBMODULE) fetch -q origin $(TEMPLATE_BRANCH)
	@pinned=$$(git rev-parse HEAD:$(SUBMODULE)); \
	if [ -n "$(filter 1 true yes,$(UPDATE_TEMPLATE))" ] || [ "$$pinned" = "$$(git -C $(SUBMODULE) rev-parse origin/$(TEMPLATE_BRANCH))" ]; then \
	  exit 0; \
	elif git -C $(SUBMODULE) diff --quiet "$$pinned" origin/$(TEMPLATE_BRANCH) -- . ':(exclude)dist'; then \
	  echo "✓ $(SUBMODULE) $(TEMPLATE_BRANCH) differs from the recorded commit $$(git -C $(SUBMODULE) rev-parse --short $$pinned) only in dist/: releasing from $(TEMPLATE_BRANCH)"; \
	else \
	  echo "✗ The recorded $(SUBMODULE) commit is not the newest $(TEMPLATE_BRANCH): the ZIPs would not match the template." >&2; \
	  echo "  Merge the pending pin update (Dependabot) first, or: make release UPDATE_TEMPLATE=1" >&2; exit 1; \
	fi
	@# the same template content as recorded (or UPDATE_TEMPLATE): build from $(TEMPLATE_BRANCH)
	@$(MAKE) --no-print-directory build UPDATE_TEMPLATE=1 OPTS="$(strip $(OPTS) --release-tag=$(TAG))"
	@# nothing to release: the files (names and SHA-256) equal the manifest of the latest release;
	@# a pre-release is always published. After a real release on arc42, the website is notified
	@# (repository_dispatch template-released, arc42.org-site workflow refresh-downloads); if that
	@# fails, the site's weekly refresh catches up.
	@now=$$(grep -E '"(name|sha256)":' $(DIST_DIR)/manifest.json); \
	prev=$$(gh release download -R $(REPO) --pattern manifest.json -O - 2>/dev/null | grep -E '"(name|sha256)":'); \
	if [ -z "$(PRERELEASE_ON)" ] && [ -n "$$prev" ] && [ "$$prev" = "$$now" ]; then \
	  echo "✓ The files equal those of the latest release on $(REPO): nothing to release"; exit 0; \
	fi; \
	commit=$$(sed -n 's/^ *"templateCommit": "\([0-9a-f]*\)",*$$/\1/p' $(DIST_DIR)/manifest.json); \
	if ! gh api "repos/$(REPO)/commits/$$commit" >/dev/null 2>&1; then \
	  echo "✗ Template commit $$commit is not in $(REPO) (a fork that is behind? sync it first)" >&2; exit 1; \
	fi; \
	echo "==> Publishing release $(TAG) on $(REPO) ($$(wc -l < build/release/files.txt | tr -d ' ') files)"; \
	gh release create "$(TAG)" -R $(REPO) --target "$$commit" --title "arc42 template $(TAG)" \
	  --notes-file build/release/notes.md $(if $(PRERELEASE_ON),--prerelease,--latest) \
	  $$(sed 's|^|$(DIST_DIR)/|' build/release/files.txt) && \
	echo "✓ Published https://github.com/$(REPO)/releases/tag/$(TAG)" || exit 1; \
	if [ -z "$(PRERELEASE_ON)" ] && [ "$(REPO)" = "arc42/arc42-template" ]; then \
	  if gh api repos/$(SITE_REPO)/dispatches -f event_type=template-released -f "client_payload[tag]=$(TAG)" >/dev/null 2>&1; then \
	    echo "✓ Notified $(SITE_REPO): the download page will refresh from release $(TAG)"; \
	  else \
	    echo "⚠ Could not notify $(SITE_REPO) (template-released); its weekly refresh catches up, or run its workflow 'Refresh downloads'" >&2; \
	  fi; \
	fi

release-tools: host-only _gh-check ## Publish the hand-made tool files (arc42-template other-formats/) as GitHub Release that never becomes latest (TOOLS_TAG=tools-YYYY.MM)
	@[ -n "$(TOOLS_TAG)" ] || { echo "✗ Name the release: make release-tools TOOLS_TAG=tools-$$(date +%Y.%m)" >&2; exit 1; }
	@if gh release view "$(TOOLS_TAG)" -R $(REPO) >/dev/null 2>&1 || gh api "repos/$(REPO)/git/ref/tags/$(TOOLS_TAG)" >/dev/null 2>&1; then \
	  echo "✗ Tag $(TOOLS_TAG) exists on $(REPO)" >&2; exit 1; \
	fi
	@# without a template release GitHub makes this release latest despite --latest=false
	@$(HAS_TEMPLATE_RELEASE) || { echo "✗ $(REPO) has no template release yet: run make release first, otherwise GitHub makes the tools release latest" >&2; exit 1; }
	@# flat names; a readme.md gets its folder as prefix (other-formats/eap/readme.md -> eap-readme.md)
	@rm -rf build/release-tools && mkdir -p build/release-tools && \
	for f in $(SUBMODULE)/other-formats/*/*; do \
	  [ -f "$$f" ] || continue; \
	  name=$$(basename "$$f"); \
	  [ "$$name" = readme.md ] && name="$$(basename "$$(dirname "$$f")")-readme.md"; \
	  [ -e "build/release-tools/$$name" ] && { echo "✗ Two tool files are named $$name" >&2; exit 1; }; \
	  cp "$$f" "build/release-tools/$$name" || exit 1; \
	done; \
	[ -n "$$(ls -A build/release-tools)" ] || { echo "✗ No tool files in $(SUBMODULE)/other-formats/" >&2; exit 1; }; \
	echo "==> Publishing release $(TOOLS_TAG) on $(REPO) ($$(ls build/release-tools | wc -l | tr -d ' ') files)"; \
	gh release create "$(TOOLS_TAG)" -R $(REPO) --latest=false --title "arc42 tool templates $(TOOLS_TAG)" \
	  --notes "Hand-made arc42 templates for modelling and wiki tools: Enterprise Architect, Confluence, IBM Rhapsody, Doxygen. They are not built by the generator and change rarely; the template in all formats is in the latest release." \
	  build/release-tools/* && \
	echo "✓ Published https://github.com/$(REPO)/releases/tag/$(TOOLS_TAG) (not latest)"

check-downloads: ## Request every file of build/dist/manifest.json from PREFIX=url (CHECKSUMS=1: download and compare SHA-256)
	@[ -n "$(PREFIX)" ] || { echo "✗ Name the prefix, e.g. make check-downloads PREFIX=https://github.com/arc42/arc42-template/releases/latest/download/" >&2; exit 2; }
	$(RUN) groovy check-downloads.groovy "$(PREFIX)" $(if $(filter 1 true yes,$(CHECKSUMS)),--checksums) $(if $(TEMPLATE),--config=/project/buildconfig.groovy)

##@ Cleanup

clean: ## Remove the build output (build/ with the distribution ZIPs in build/dist/, and build2/)
	$(RUN) sh -c 'rm -rf $(filter-out %/dist,$(OUTPUT_DIRS))'

fix-owner: ## Linux only: give files created by the container (root) back to the calling user
	@if [ -z "$(ARC42_IN_CONTAINER)" ] && [ "$$(uname -s)" = Linux ] && [ "$(HOST_UID)" != 0 ]; then \
	  $(RUN) sh -c 'for d in $(OUTPUT_DIRS); do [ -d "$$d" ] && chown -R $(HOST_UID):$(HOST_GID) "$$d"; done; true'; \
	fi

# ---------------------------------------------------------------------------------------------
# Internal targets

_gh-check:
	@command -v gh >/dev/null 2>&1 || { echo "✗ Releases need the GitHub CLI gh on this machine (it is not in the image): https://cli.github.com" >&2; exit 1; }
	@gh auth status -h github.com >/dev/null 2>&1 || { echo "✗ gh is not logged in to github.com: gh auth login" >&2; exit 1; }

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
	  echo "✗ $$problems image problem(s) in the with-help styles (see above): the output in build/ is incomplete" >&2; exit 1; \
	fi; \
	echo "✓ Image directories present, all image references valid ($$checked Markdown with-help file(s) checked)"
