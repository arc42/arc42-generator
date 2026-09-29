# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is the **arc42-generator** project, a Groovy-based build system that converts the arc42 architecture documentation template from its "Golden Master" format (AsciiDoc) into multiple output formats (HTML, PDF, Markdown, DOCX, etc.) in multiple languages.

The actual template content lives in the `arc42-template` git submodule (the "Golden Master"). This generator project transforms that content into various formats for distribution.

## Build Commands

### Initial Setup
```bash
# Check out the arc42-template submodule at the commit recorded in this repository
git submodule update --init --recursive
```
To build the newest Golden Master instead, run `./build-arc42.sh --update-template` (or set `UPDATE_TEMPLATE=1`); it moves the submodule to the tip of `master` and tells you to pin the new commit with `git add arc42-template && git commit`. Local changes inside the submodule are never deleted.

### In Docker Only (make + docker, nothing else installed)
```bash
make help                     # all targets
make build                    # full arc42 build in the container (./build-arc42.sh)
make test                     # all test scripts in the container
make convert FORMAT=html      # single phase / single format; OPTS="..." passes options to build.groovy
make generate TEMPLATE=../req42-framework   # another template repository
```
The Makefile only wraps `docker compose run`; output is written below the current directory (`build/`, `arc42-template/dist/`) and, on Linux, handed back to the calling user.

### Full Build Process (Automated)
```bash
./build-arc42.sh
```
This script handles everything: installs pandoc 3.7.0.2 if it is missing (checksum-verified), checks out the submodule at the recorded commit (opt-in `--update-template`), runs the full build pipeline and validates the generated Markdown (`cmark`, non-fatal) and the images of the with-help flavors (fatal). `./build-arc42.sh --help` lists options and exit codes.

### Manual Build Steps
```bash
# Full build (all phases)
groovy build.groovy

# Individual phases
groovy build.groovy templates      # Phase 1: Generate templates from golden master
groovy build.groovy convert        # Phases 2-3: Discover + convert templates
groovy build.groovy distribution   # Phase 4: Create distribution ZIP files

# Format-specific build (faster)
groovy build.groovy --format=html  # Build only HTML format
```

### Without a Groovy Installation (Gradle Wrapper)
```bash
./gradlew check                                   # all test scripts (same as groovy run-all-tests.groovy)
./gradlew templates                               # groovy build.groovy templates
./gradlew convert -Popts="--format=html"          # options for build.groovy are passed with -Popts
./gradlew generate                                # groovy build.groovy (all phases)
```
`build.gradle` only launches the Groovy scripts with the dependencies resolved by Gradle (`@Grab` is disabled there); keep its dependency versions in sync with the `@Grab` annotations in `lib/*.groovy`. Needs a JDK and Pandoc, nothing else.

### CLI Options
- **Phase selection**: `templates`, `convert`, `distribution`, or `all` (default)
- **Format filter**: `--format=html` (only convert to specified format)
- **Parallel control**: `--parallel=false` (disable parallel execution)
- **Config file**: `--config=path/to/config.groovy` (default `buildconfig.groovy`; paths inside are relative to that file)
- **Failure level**: `--failure-level=warn|error|fatal|none` (default `warn`): Asciidoctor and Pandoc diagnostics at this level or above fail the build
- **Lint**: `--lint=warn`: report golden master problems (unbalanced `ifdef`, help blocks without `ifdef`, missing images, incomplete `version.properties`) instead of failing on them

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
- **Language Auto-Discovery**: Scans `arc42-template/` for language directories matching `/^[A-Z]{2,}$/`
- **Golden Master Validation**: `validateGoldenMaster()` reports errors (unbalanced `ifdef`/`endif`, help blocks without `ifdef`, missing images, incomplete `version.properties`) and warnings (chapter set or help-block count differs from EN); errors fail `createFromGoldenMaster()` unless `failOnLintErrors` is false
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

**Performance**: Creates 18 ZIPs in ~0.6s (vs ~15s with Gradle)

### Key Configuration Files
- **buildconfig.groovy**: Defines template styles, output formats, and paths
  - `templateStyles`: `plain` (no help), `with-help` (includes help text)
  - `formats`: 15+ output formats including asciidoc, html, markdown, docx, epub, latex, etc.
  - `goldenMaster`: Path to arc42-template submodule

### Supported Languages
**Auto-discovered**: CZ, DE, EN, ES, FR, HU, IT, NL, PT, RU, UKR, ZH (12 languages)

The system automatically discovers all language directories in `arc42-template/` that match the pattern `/^[A-Z]{2,}$/`. No hardcoding required.

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

### Performance Comparison
**Full HTML Build** (18 templates):
- **Groovy**: 17.4s (template generation + conversion + packaging)
- **Gradle**: ~90s
- **Speedup**: 5.2x faster

**Why Faster**:
1. True parallel execution with GParsPool (better CPU utilization)
2. No Gradle initialization overhead
3. Direct library calls (AsciidoctorJ, Pandoc)
4. Simpler architecture (no chicken-and-egg problems)

## System Requirements
- **Groovy**: Version 4.0 or higher (tested with Groovy 5.0.2)
  - Install via SDKMAN: `sdk install groovy`
- **Java Runtime**: Version 11 or higher (tested with OpenJDK 21)
- **Pandoc**: Version 3.0 or higher required for format conversions (tested with 3.7.0.2)
  - Install on Debian/Ubuntu: `wget <pandoc-deb-url> && sudo dpkg -i <pandoc-deb>`
  - `build-arc42.sh` auto-installs Pandoc if missing

## Output Locations
- `build/src_gen/`: Generated AsciiDoc templates (plain, with-help variants); deleted at the start of the `templates` phase
- `build/<LANG>/<FORMAT>/<STYLE>/`: Converted templates by language, format and style; deleted at the start of the `convert` phase for the formats being converted
- `build2/`: Output of the test scripts (ignored by git)
- `arc42-template/dist/`: Final distribution ZIP files ready for upload

## Testing

### Automated Test Suite
```bash
# Run all integration tests
groovy run-all-tests.groovy
./gradlew check               # the same five scripts without a Groovy installation

# Run individual test suites
groovy test-templates.groovy   # Test template generation
groovy test-discovery.groovy   # Test template discovery
groovy test-converter.groovy   # Test format conversion, multi-page help text, clean outputs, diagnostics
groovy test-config.groovy      # Test building a non-arc42 project from its own config file
groovy test-lint.groovy        # Test the golden master validation on a fixture
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
1. Create language folder in `arc42-template/<LANG>/` submodule (must match `/^[A-Z]{2,}$/`)
2. Add template content (AsciiDoc files)
3. Run `groovy build.groovy` - language will be auto-discovered
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
4. Test with `groovy build.groovy --format=myformat`

### Testing Single Format/Language
```bash
# Test template generation only
groovy build.groovy templates

# Test specific format conversion
groovy build.groovy --format=html

# Full build
groovy build.groovy
```

### Debugging Conversion Issues
```bash
# The build prints a 'Diagnostics' section after the conversion: Asciidoctor log records
# (missing includes, unknown block styles, ...) and Pandoc warnings (e.g. images it could not find),
# each with file and line where available. Lower the bar to see whether the build passes otherwise:
groovy build.groovy convert --failure-level=error

# Report golden master problems without failing:
groovy build.groovy templates --lint=warn

# Test single template conversion
groovy test-converter.groovy  # Tests EN:plain and EN:with-help templates
```

## Git Workflow
When updating templates:
1. Work in the `arc42-template` submodule (commit there first), or move it to the newest `master` with `./build-arc42.sh --update-template`
2. Update submodule reference in main repo: `git add arc42-template` (the build is reproducible from this pinned commit)
3. Build and test distribution files
4. Commit distribution ZIPs in the submodule: `cd arc42-template && git commit dist/*.zip && git push`


You are an AI assistant that helps users develop software features.
You do this by following a structured development process guided by the responsible-vibe-mcp server.

IMPORTANT: Use responsible-vibe-mcp tools after each user message!

Use the start_development() to start a new development.

## Core Workflow

Each tool call will return a JSON formatted response with an "instructions" field in it. Follow these instructions immediately after you received them.

1. **Call whats_next() after each user interaction** to get phase-specific instructions
2. **Follow the instructions** provided by responsible-vibe-mcp exactly
3. **Update the plan file** as directed to maintain project memory
4. **Mark completed tasks** with [x] when instructed
5. **Provide conversation context** in each whats_next() call

## Development Workflow

The responsible-vibe-mcp server will guide you through development phases specific to the chosen workflow. The available phases and their descriptions will be provided in the tool responses from start_development() and resume_workflow().

## Using whats_next()

After each user interaction, call:

```
whats_next({
  context: "Brief description of current situation",
  user_input: "User's latest message",
  conversation_summary: "Summary of conversation progress so far",
  recent_messages: [
    { role: "assistant", content: "Your recent message" },
    { role: "user", content: "User's recent response" }
  ]
})
```

## Phase Transitions

You can transition to the next phase when the tasks of the current phase were completed and the entrance criteria for the current phase have been met.

Before suggesting any phase transition:
- **Check the plan file** for the "Phase Entrance Criteria" section
- **Evaluate current progress** against the defined criteria
- **Only suggest transitions** when criteria are clearly met
- **Be specific** about which criteria have been satisfied
- **Ask the user** whether he agrees that the current phase is complete.

```
proceed_to_phase({
  target_phase: "target_phase_name",  // Use phase names from the current workflow
  reason: "Why you're transitioning"
})
```

## Plan File Management

- Add new tasks as they are identified
- Mark tasks complete [x] when finished
- Document important decisions in the Decisions Log
- Keep the structure clean and readable

## Conversation Context Guidelines

Since responsible-vibe-mcp operates statelessly, provide:

- **conversation_summary**: What the user wants, key decisions, progress
- **recent_messages**: Last 3-5 relevant exchanges
- **context**: Current situation and what you're trying to determine

Remember: responsible-vibe-mcp guides the development process but relies on you to provide conversation context and follow its instructions precisely.
