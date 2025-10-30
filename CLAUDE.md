# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is the **arc42-generator** project, a Gradle-based build system that converts the arc42 architecture documentation template from its "Golden Master" format (AsciiDoc) into multiple output formats (HTML, PDF, Markdown, DOCX, etc.) in multiple languages.

The actual template content lives in the `arc42-template` git submodule (the "Golden Master"). This generator project transforms that content into various formats for distribution.

## Build Commands

### Initial Setup
```bash
# Initialize and update the arc42-template submodule
git submodule init
git submodule update
cd arc42-template
git checkout master
git pull
cd ..
```

### Full Build Process (Automated)
```bash
./build-arc42.sh
```
This script handles everything: installs pandoc, updates submodules, and runs the full build pipeline.

### Manual Build Steps
```bash
# 1. Generate templates from golden master (creates build/src_gen/)
./gradlew createTemplatesFromGoldenMaster

# 2. Convert templates to all output formats (creates build/<LANG>/<FORMAT>/)
./gradlew arc42

# 3. Create distribution ZIP files (creates arc42-template/dist/)
./gradlew createDistribution
```

### Gradle Command Shortcuts
Gradle allows command abbreviation: `./gradlew createT` or `./gradlew cTFGM` work the same as `./gradlew createTemplatesFromGoldenMaster`.

## Architecture

### Build Pipeline Flow
1. **Golden Master** (`arc42-template/` submodule) → Contains source AsciiDoc templates with feature flags
2. **Template Generation** (`createTemplatesFromGoldenMaster`) → Strips feature flags to create "plain" and "with-help" versions in `build/src_gen/`
3. **Format Conversion** (`arc42` task) → Converts AsciiDoc to HTML, Markdown, DOCX, etc. using Pandoc
4. **Distribution** (`createDistribution`) → Packages everything into ZIP files for download

### Key Configuration Files
- **buildconfig.groovy**: Defines template styles, output formats, and paths
  - `templateStyles`: `plain` (no help), `with-help` (includes help text)
  - `formats`: 15+ output formats including asciidoc, html, markdown, docx, epub, latex, etc.
- **build.gradle**: Main build orchestration, creates dynamic subprojects
- **subBuild.gradle**: Template for generated subproject build files (gets copied with placeholders replaced)
- **settings.gradle**: Dynamically discovers and includes subprojects from `build/src_gen/`

### Supported Languages
Currently: DE (German), EN (English), FR (French), CZ (Czech)
See `build.gradle:41` for the language list.

### Format Conversion Strategy
- AsciiDoc → HTML: Direct conversion via Asciidoctor Gradle plugin
- AsciiDoc → Other formats: Two-step process
  1. AsciiDoc → DocBook XML (via Asciidoctor)
  2. DocBook → Target format (via Pandoc)
- Multi-page formats (markdownMP, mkdocsMP, etc.) split the template into separate files

### Dynamic Subproject Generation
The build system dynamically creates Gradle subprojects for each language/format combination:
- `settings.gradle` scans `build/src_gen/` after `createTemplatesFromGoldenMaster` runs
- For each `src/` directory found, creates a subproject like `EN:plain` or `DE:with-help`
- Copies `subBuild.gradle` → `build.gradle` with placeholders replaced (%LANG%, %TYPE%, version info)

### Feature Flag System
The Golden Master uses AsciiDoc role attributes to mark content:
- `[role="arc42help"]` - Help text (explanations, tips)
- `[role="arc42example"]` - Example content (currently unused)
- `createTemplatesFromGoldenMaster` removes unwanted features using regex to create template variants

## System Requirements
- **Java Runtime**: Version 1.7 or higher (tested with modern JDKs)
- **Pandoc**: Version 1.12.4.2 or higher required for format conversions
  - Install on Debian/Ubuntu: `wget <pandoc-deb-url> && sudo dpkg -i <pandoc-deb>`
  - See `build-arc42.sh:3-4` for current recommended version

## Output Locations
- `build/src_gen/`: Generated AsciiDoc templates (plain, with-help variants)
- `build/<LANG>/<FORMAT>/`: Converted templates by language and format
- `arc42-template/dist/`: Final distribution ZIP files ready for upload

## Common Development Scenarios

### Adding a New Language
1. Add language code to `build.gradle:41` (e.g., add 'ES' for Spanish)
2. Ensure corresponding folder exists in `arc42-template/<LANG>/` submodule
3. Run full build pipeline

### Adding a New Output Format
1. Add format to `buildconfig.groovy` formats map with `imageFolder` setting
2. Add conversion task in `subBuild.gradle` (follow existing patterns)
3. Add task dependency to `arc42` task in `subBuild.gradle:520`

### Testing Single Format/Language
```bash
# After createTemplatesFromGoldenMaster, run specific subproject
./gradlew :EN:plain:generateHTML
./gradlew :DE:with-help:convert2Markdown
```

## Git Workflow
When updating templates:
1. Work in the `arc42-template` submodule (commit there first)
2. Update submodule reference in main repo: `git add arc42-template`
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
