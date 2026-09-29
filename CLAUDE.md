# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is the **arc42-generator** project, a Groovy-based build system that converts the arc42 architecture documentation template from its "Golden Master" format (AsciiDoc) into multiple output formats (HTML, PDF, Markdown, DOCX, etc.) in multiple languages.

The actual template content lives in the `arc42-template` git submodule (the "Golden Master"). This generator project transforms that content into various formats for distribution.

## Build Commands

**Every task is a make target, and every target runs in Docker** (`docker compose run`). Needed locally: `make` and Docker with Compose v2. Only `make pin` and `make release` use the local git (commit/push with the user's identity). There are no shell scripts and no Gradle wrapper; do not add other entry points. The image sets `ARC42_IN_CONTAINER=1`, so inside the container (`make shell`, `docker compose up`) the same targets run directly.

```bash
make help                     # all targets and variables
make image                    # build the Docker image (Java, Groovy, Pandoc 3.7.0.2, cmark, make, git)
make build                    # full arc42 build: template-checkout, generate, validate
make build UPDATE_TEMPLATE=1  # the same with the newest arc42-template master
make generate                 # groovy build.groovy (all phases), no checkout, no output validation
make templates | convert | distribution   # single phases
make validate                 # output checks: cmark (warnings only), with-help images (fatal)
make test                     # groovy run-all-tests.groovy
make test-lint                # one test script (templates, discovery, converter, config, lint)
make template-checkout        # submodule at the recorded commit (git submodule update --init, in the container)
make template-update          # submodule to the newest master
make pin                      # commit the checked-out submodule commit (local git)
make release                  # build, then push the regenerated dist/*.zip to a branch dist/<date>-<sha> of arc42-template
make clean | clean-dist       # remove build/, build2/ | restore the committed ZIPs
make shell | versions | image-fresh
```
Variables: `OPTS="..."` (options for build.groovy, see below), `FORMAT=html`, `TEMPLATE=../req42-framework` (another template repository, mounted at `/project`, output below it), `UPDATE_TEMPLATE=1`, `SOURCE_DATE_EPOCH`. Output is written below the current directory (`build/`, `arc42-template/dist/`) and, on Linux, handed back to the calling user (`fix-owner`).

The output validation lives in the Makefile (`_validate-markdown`, `_validate-images`) and runs in the container. `release` stops unless the recorded submodule commit is the newest template master (or `UPDATE_TEMPLATE=1`).

### CLI Options
Options of `build.groovy`, passed through make as `OPTS="..."` (e.g. `make templates OPTS=--lint=warn`):
- **Phase selection**: `templates`, `convert`, `distribution`, or `all` (default)
- **Format filter**: `--format=html` (only convert to specified format)
- **Parallel control**: `--parallel=false` (disable parallel execution)
- **Config file**: `--config=path/to/config.groovy` (default `buildconfig.groovy`; paths inside are relative to that file)
- **Failure level**: `--failure-level=warn|error|fatal|none` (default `warn`): Asciidoctor and Pandoc diagnostics at this level or above fail the build
- **Lint**: `--lint=warn`: report golden master problems (unbalanced `ifdef`, help blocks without `ifdef`, `ifdef::arc42help[]` without `:arc42help:` being set, missing images, incomplete `version.properties`) instead of failing on them

Every run starts from a clean output: the `templates` phase deletes `build/src_gen/`, the `convert` phase deletes the output directories (and DocBook intermediates) of the formats it converts. Nothing from an earlier run survives into the distribution ZIPs.

Output is reproducible: the HTML footer carries no build timestamp (Asciidoctor `reproducible`), and the dates inside DOCX/EPUB files and the timestamps of the ZIP entries come from `SOURCE_DATE_EPOCH` or, if unset, from the last commit of the golden master. Every EPUB gets a fixed identifier (a UUID derived from project, language and style) instead of the random UUID Pandoc would otherwise create. Unchanged content produces byte-identical files and ZIPs.

## Architecture

### Build Pipeline Flow
1. **Golden Master** (`arc42-template/` submodule) → Contains source AsciiDoc templates with feature flags
2. **Validation** (`lib/Templates.groovy`, `validateGoldenMaster()`) → Checks the golden master (conditionals, help blocks, images, version.properties) and fails the build on errors
3. **Template Generation** (`lib/Templates.groovy`) → Strips feature flags to create "plain" and "with-help" versions in `build/src_gen/`
4. **Template Discovery** (`lib/Discovery.groovy`) → Scans generated templates and extracts metadata
5. **Format Conversion** (`lib/Converter.groovy`) → Converts AsciiDoc to HTML, Markdown, DOCX, etc. using AsciidoctorJ and Pandoc; collects Asciidoctor and Pandoc diagnostics and fails the build on them
6. **Distribution** (`lib/Packager.groovy`) → Packages everything into ZIP files for download

### Core Components

#### `build.groovy`
Main orchestration script that ties everything together. Supports CLI arguments for phase selection, format filtering, failure level and lint mode. Cleans the output of a phase before running it and exits with code 1 on failed conversions or diagnostics at or above the failure level.

#### `lib/Templates.groovy`
- **Language Auto-Discovery**: Scans `arc42-template/` for language directories matching `/^[A-Z]{2,}(-[A-Z]{2,})?$/` (e.g. `EN`, `UKR`, `ZH-TW`)
- **Golden Master Validation**: `validateGoldenMaster()` reports errors (unbalanced `ifdef`/`endif`, help blocks without `ifdef`, `ifdef::arc42help[]` without `:arc42help:` being set, missing images, incomplete `version.properties`) and warnings (chapter set or help-block count differs from EN); errors fail `createFromGoldenMaster()` unless `failOnLintErrors` is false; in GitHub Actions (`GITHUB_ACTIONS=true`) every problem is also printed as annotation (`githubAnnotation()`) and a Markdown report (`lintReport()`) is appended to `GITHUB_STEP_SUMMARY`
- **Feature Flag Removal**: Uses regex patterns to strip `[role="arc42help"]` blocks and `ifdef::arc42help` statements
- **Template Generation**: Creates one template variant per language and style (12 languages × 2 styles = 24 for arc42)

#### `lib/Discovery.groovy`
- **Template Scanning**: Discovers all generated templates in `build/src_gen/`
- **Metadata Extraction**: Reads version.properties, counts .adoc files, validates structure
- **Query API**: Find templates by language, style, or both

#### `lib/Converter.groovy`
- **AsciidoctorJ Integration**: Direct HTML and DocBook conversion
- **Pandoc Integration**: Two-step conversion (AsciiDoc → DocBook → target format)
- **Multi-page formats**: one DocBook and one output file per chapter; the feature attributes of the style (e.g. `arc42help` for with-help) are set for the per-chapter conversion
- **Diagnostics**: Asciidoctor log records and Pandoc's stderr are collected in `diagnostics`, printed after `convertAll()` (deduplicated) and evaluated by `build.groovy` against `--failure-level`
- **Clean outputs**: `cleanOutputs()` deletes the output directories and DocBook intermediates before a conversion
- **Parallel Execution**: Uses GParsPool for true parallel conversion
- **Supported Formats**: html, asciidoc, docbook, markdown, docx, epub, latex, and more

#### `lib/Packager.groovy`
- **ZIP Creation**: Packages templates + images into distribution archives
- **Parallel Execution**: Creates all ZIPs concurrently
- **Output**: `arc42-template/dist/*.zip` files ready for distribution

**Performance**: Creates 18 ZIPs in ~0.6s

### Key Configuration Files
- **buildconfig.groovy**: Defines template styles, output formats, and paths
  - `templateStyles`: `plain` (no help), `with-help` (includes help text)
  - `formats`: 15+ output formats including asciidoc, html, markdown, docx, epub, latex, etc.
  - `goldenMaster`: Path to arc42-template submodule

### Supported Languages
**Auto-discovered**: CZ, DE, EN, ES, FR, HU, IT, NL, PT, RU, UKR, ZH (12 languages)

The system automatically discovers all language directories in `arc42-template/` that match the pattern `/^[A-Z]{2,}(-[A-Z]{2,})?$/`: a language code, optionally with a region such as `ZH-TW`. No hardcoding required.

### Format Conversion Strategy
- **AsciiDoc → HTML**: Direct conversion via AsciidoctorJ
- **AsciiDoc → Other formats**: Two-step process
  1. AsciiDoc → DocBook XML (via AsciidoctorJ)
  2. DocBook → Target format (via Pandoc)
- **Multi-page formats**: markdownMP, mkdocsMP, etc. split the template into separate files

### Feature Flag System
The Golden Master uses AsciiDoc role attributes to mark content (prefix set by `project.featurePrefix` in the config):
- `[role="arc42help"]` - Help text (explanations, tips)
- `[role="arc42example"]` - Example content (currently unused)
- `lib/Templates.groovy` removes unwanted features using regex to create template variants

## System Requirements
Only `make` and Docker with Compose v2. The Docker image contains Java 21, Groovy 5.0.3, Pandoc 3.7.0.2 (pinned, checksum-verified), cmark, make and git. The Groovy scripts need Groovy 4.0+ and Java 11+ if they are ever run outside the image.

## Output Locations
- `build/src_gen/`: Generated AsciiDoc templates (plain, with-help variants); deleted at the start of the `templates` phase
- `build/<LANG>/<FORMAT>/<STYLE>/`: Converted templates by language, format and style; deleted at the start of the `convert` phase for the formats being converted
- `build2/`: Output of the test scripts (ignored by git)
- `arc42-template/dist/`: Final distribution ZIP files ready for upload

## Testing

### Automated Test Suite
```bash
make test               # all test scripts (groovy run-all-tests.groovy in the container)
make test-templates     # template generation
make test-discovery     # template discovery
make test-converter     # format conversion, multi-page help text, clean outputs, diagnostics, reproducible DOCX/EPUB
make test-config        # building a non-arc42 project from its own config file
make test-lint          # golden master validation on a fixture, ZH-TW discovery, GitHub annotations and report
```

The test suite validates:
- Language auto-discovery
- Golden master validation (fixture with deliberate errors)
- Feature flag removal (regex patterns)
- Template generation (output structure, file counts)
- Format conversion (HTML, DocBook, Markdown, DOCX, multi-page Markdown with help text)
- Clean outputs and collected diagnostics

## Common Development Scenarios

### Adding a New Language
1. Create language folder in `arc42-template/<LANG>/` submodule (must match `/^[A-Z]{2,}(-[A-Z]{2,})?$/`, e.g. `TR` or `ZH-TW`)
2. Add template content (AsciiDoc files)
3. Run `make build UPDATE_TEMPLATE=1` (or `make generate` for what is checked out) - language will be auto-discovered
4. No code changes needed!

### Adding a New Output Format
1. Add format to `buildconfig.groovy` formats map:
   ```groovy
   myformat: [imageFolder: true]  // or false if no images needed
   ```
2. Add conversion method in `lib/Converter.groovy`:
   ```groovy
   String convertToMyFormat(Map template, String outputDir) {
       // Implement conversion logic
   }
   ```
3. Update `convertAll()` method to handle new format
4. Test with `make convert FORMAT=myformat`

### Testing Single Format/Language
```bash
make templates                # template generation only
make convert FORMAT=html      # one format
make build                    # full build
```

### Debugging Conversion Issues
```bash
# The build prints a 'Diagnostics' section after the conversion: Asciidoctor log records
# (missing includes, unknown block styles, ...) and Pandoc warnings (e.g. images it could not find),
# each with file and line where available. Lower the bar to see whether the build passes otherwise:
make convert OPTS=--failure-level=error

# Report golden master problems without failing:
make templates OPTS=--lint=warn

# Test single template conversion
make test-converter          # Tests EN:plain and EN:with-help templates

# Look around inside the container (make targets work there too):
make shell
```

## Git Workflow
When updating templates:
1. Template changes (including translations) are made in arc42-template via pull requests; its CI validates them with this generator.
2. When arc42-template master moves, Dependabot opens a pull request here that moves the submodule pin; the CI builds it. Manually: `make template-update && make pin`.
3. `make release` builds and pushes the regenerated ZIPs to a branch of arc42-template; merging that pull request publishes them.
