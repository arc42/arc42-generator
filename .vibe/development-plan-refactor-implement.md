# Development Plan: Remove Gradle from arc42-generator

*Generated on 2025-10-30 by Vibe Feature MCP*
*Workflow: [epcc](https://mrsimpson.github.io/responsible-vibe-mcp/workflows/epcc)*

## Goal

Remove Gradle from the arc42-generator project and replace it with standalone Groovy scripts. The new system should:
- Use Groovy as the scripting language (team already has expertise)
- Keep buildconfig.groovy unchanged
- Use AsciidoctorJ library directly instead of Gradle plugin
- Restore language auto-discovery (remove hardcoded language list)
- Maintain all existing functionality (Golden Master pattern, format conversions, distribution packaging)
- Simplify the build process (eliminate chicken-and-egg problem)

**Maintainer Confirmation**: Ralf D. Müller confirmed (2025-10-30): "Gradle will be removed in the future"

## Explore

### Completed
- [x] Created development plan file
- [x] Read and analyzed build.gradle (main build orchestration)
- [x] Read and analyzed buildconfig.groovy (configuration - will remain unchanged)
- [x] Read and analyzed settings.gradle (dynamic subproject generation)
- [x] Read and analyzed subBuild.gradle (subproject template with conversion tasks)
- [x] Read architecture documentation (solution-strategy.md, building-blocks.md, concepts.md)
- [x] Identified current Gradle responsibilities:
  - Golden Master template generation (createTemplatesFromGoldenMaster)
  - Dynamic subproject generation (settings.gradle chicken-and-egg pattern)
  - Format conversion orchestration (Asciidoctor plugin, Pandoc integration)
  - Distribution packaging (ZIP creation)
- [x] Evaluated alternatives (Python+Jinja2, Make, Bash, Groovy standalone)
- [x] User confirmed: Use Groovy standalone scripts
- [x] Documented findings in architecture docs

## Plan

### Target Architecture

**New Structure:**
```
arc42-generator/
├── build.groovy                    # Main build orchestration script
├── buildconfig.groovy              # Configuration (unchanged)
├── lib/
│   ├── Templates.groovy            # Golden Master processing + language discovery
│   ├── Discovery.groovy            # Directory scanning and structure discovery
│   ├── Converter.groovy            # Format conversion (AsciidoctorJ + Pandoc)
│   └── Packager.groovy             # ZIP distribution creation
├── arc42-template/                 # Git submodule (unchanged)
└── build/                          # Output directories (unchanged)
```

**Build Flow:**
1. `groovy build.groovy` - Main entry point
2. Calls Templates.createFromGoldenMaster()
3. Calls Discovery.findTemplates() to scan build/src_gen/
4. Calls Converter.convertAll() with parallel execution
5. Calls Packager.createDistributions()

### Tasks

**Phase 1: Proof of Concept** ✅ COMPLETED
- [x] Create lib/ directory structure
- [x] Implement lib/Templates.groovy:
  - [x] Language auto-discovery (scan arc42-template/ for [A-Z]{2} pattern)
  - [x] Golden Master filtering (port regex from build.gradle:88-94)
  - [x] createFromGoldenMaster() method
  - [x] Feature flag removal (ifdef::arc42help, role="arc42help")
- [x] Test with single language (EN)
- [x] Test with all languages
- [x] Validate output matches Gradle-generated templates - **100% identical output!**
- [x] Document API and usage (well-commented code + test script)

**Phase 2: Core Conversion**
- [ ] Implement lib/Discovery.groovy:
  - [ ] scanSourceDirectory() method
  - [ ] Return list of (language, style) tuples
  - [ ] Validate discovered structure
- [ ] Implement lib/Converter.groovy:
  - [ ] Set up AsciidoctorJ dependencies (@Grab)
  - [ ] Implement convertToHTML() using AsciidoctorJ API
  - [ ] Implement convertToDocBook() using AsciidoctorJ
  - [ ] Implement Pandoc integration (DocBook → all formats)
  - [ ] Add parallel execution (GParsPool or ExecutorService)
  - [ ] Handle all 15+ formats from buildconfig.groovy
- [ ] Implement build.groovy main orchestration:
  - [ ] Load buildconfig.groovy using ConfigSlurper
  - [ ] Call Templates.createFromGoldenMaster()
  - [ ] Call Discovery.findTemplates()
  - [ ] Call Converter.convertAll()
  - [ ] Error handling and logging
- [ ] Test all languages and all formats
- [ ] Validate output matches Gradle versions
- [ ] Performance testing (parallel execution)

**Phase 3: Packaging & Cleanup**
- [ ] Implement lib/Packager.groovy:
  - [ ] createDistributions() method
  - [ ] ZIP file creation for each language/format combination
  - [ ] Copy to arc42-template/dist/
  - [ ] Validate ZIP contents
- [ ] Update build-arc42.sh:
  - [ ] Replace `./gradlew` calls with `groovy build.groovy`
  - [ ] Keep Pandoc installation logic
  - [ ] Keep git submodule update logic
  - [ ] Test full automated build
- [ ] Update documentation:
  - [ ] CLAUDE.md (update build commands)
  - [ ] docs/arc42/05-building-blocks.md (update component descriptions)
  - [ ] docs/arc42/08-concepts.md (update build process)
  - [ ] README.md if it exists
- [ ] Remove Gradle files:
  - [ ] Delete build.gradle
  - [ ] Delete settings.gradle
  - [ ] Delete subBuild.gradle
  - [ ] Delete gradle/ directory
  - [ ] Delete gradlew and gradlew.bat
  - [ ] Update .gitignore (remove Gradle-specific entries)
- [ ] Final validation:
  - [ ] Full build test (all languages, all formats)
  - [ ] Compare output with previous Gradle build
  - [ ] Distribution ZIP integrity check
  - [ ] Update version metadata if needed

### Completed
- [x] Evaluated build system alternatives
- [x] Chose Groovy standalone scripts (user confirmed)
- [x] Designed new architecture (build.groovy + lib/ components)
- [x] Created 3-phase migration strategy
- [x] Documented risk analysis and mitigation
- [x] Updated architecture documentation with Gradle removal warning
- [x] Integrated maintainer's answers into documentation
- [x] Cleaned up open-questions.md
- [x] Committed planning work to refactor-implement branch

## Code

### Tasks
**Currently working on Phase 2: Core Conversion**
- [ ] Implement lib/Discovery.groovy
- [ ] Implement lib/Converter.groovy with AsciidoctorJ
- [ ] Implement build.groovy main orchestration
- [ ] Test all formats and languages

### Completed
**Phase 1: Proof of Concept (2025-10-30)**
- [x] Created lib/ directory structure
- [x] Implemented lib/Templates.groovy with language auto-discovery (discovers 9 languages vs. Gradle's 4)
- [x] Ported regex patterns for feature flag removal from build.gradle:88-94
- [x] Implemented createFromGoldenMaster() method
- [x] Created test-templates.groovy test script
- [x] Validated output matches Gradle version 100%
- [x] Installed Groovy 5.0.2 development environment

## Commit

### Tasks
- [ ] *Tasks will be added when this phase becomes active*

### Completed
- [x] Committed conversation.sqlite with planning session
- [x] Committed documentation updates from refactor branch

## Key Decisions

### Decision 1: Use Groovy Standalone Scripts (Not Python)
**Context**: User asked "oh, is it possible to stick with groovy as scripting language instead of python?"

**Decision**: Use Groovy standalone scripts instead of Python+Jinja2

**Rationale**:
- Team already has Groovy expertise (existing build uses Groovy)
- Zero learning curve
- buildconfig.groovy stays unchanged (uses ConfigSlurper)
- Can use AsciidoctorJ library directly (Java interop)
- Familiar syntax for maintenance

**Alternatives Rejected**:
- Python + Jinja2: Would require learning new language
- Make: Too basic, poor error handling
- Bash: Fragile for complex logic
- Keep Gradle: Maintainer wants it removed

### Decision 2: Restore Language Auto-Discovery
**Context**: build.gradle:41 has hardcoded language list

**Decision**: Remove hardcoded list, restore automatic discovery by scanning arc42-template/

**Rationale**:
- Maintainer confirmed: "This was added four weeks ago when we did a live deployment...Can be removed again"
- Reduces configuration maintenance
- Convention over configuration principle
- Scan for /^[A-Z]{2}$/ directory pattern

### Decision 3: Use AsciidoctorJ Library Directly
**Context**: Currently uses Asciidoctor Gradle plugin

**Decision**: Use AsciidoctorJ Java library via @Grab in Groovy scripts

**Rationale**:
- Direct API access (no plugin abstraction)
- Same underlying library (AsciidoctorJ)
- Better control over conversion process
- Simpler dependency management with @Grab

### Decision 4: Keep buildconfig.groovy Unchanged
**Context**: Configuration file defines formats, paths, styles

**Decision**: Keep exact same format and structure

**Rationale**:
- Works well, no reason to change
- ConfigSlurper parsing already understood
- Minimizes risk in migration
- Can be improved later if needed

### Decision 5: Three-Phase Migration Strategy
**Context**: Need safe, testable migration path

**Decision**: Phase 1 (PoC with Templates), Phase 2 (Core Conversion), Phase 3 (Packaging & Cleanup)

**Rationale**:
- Incremental validation at each step
- Can compare output with Gradle version
- Easier to debug issues
- Can abort if problems found early

## Risk Analysis

### Risk 1: AsciidoctorJ API Differences
**Impact**: Medium | **Probability**: Medium

**Mitigation**:
- Test with single language first (Phase 1)
- Compare output with Gradle version line-by-line
- Keep Gradle build available for comparison during migration

### Risk 2: Pandoc Integration Complexity
**Impact**: Medium | **Probability**: Low

**Mitigation**:
- Pandoc CLI interface is stable
- Current build already uses CLI (not Gradle plugin)
- Same command-line args will work

### Risk 3: Parallel Execution Performance
**Impact**: Low | **Probability**: Medium

**Mitigation**:
- Use GParsPool (battle-tested Groovy library)
- Fall back to Java ExecutorService if needed
- Test with all languages to measure speedup

### Risk 4: Hidden Gradle Dependencies
**Impact**: High | **Probability**: Low

**Mitigation**:
- Thorough testing after each phase
- Keep Gradle build for 1-2 releases as fallback
- Document all discovered issues in onboarding/common-issues.md

## Notes

### Maintainer Confirmations (2025-10-30)
From open-questions.md answers by Ralf D. Müller:
- "Gradle will be removed in the future"
- "We wanted to be able to have clean docs without the help texts"
- Hardcoded language list: "This was added four weeks ago...Can be removed again"
- Version numbering: "No versioning, always use most current version"
- Submodule separation: "Just a separation of concerns"

### Technical Context
- Current Gradle chicken-and-egg problem: settings.gradle runs before tasks but needs createTemplatesFromGoldenMaster output
- Regex patterns for feature removal in build.gradle:88-94 must be ported exactly
- Pandoc version pinned to 3.7.0.2 for stability
- Distribution ZIPs committed to arc42-template/dist/ (unusual but expected pattern)

### Branch Information
- Working on: refactor-implement
- Planning started from: refactor branch
- Documentation copied from refactor branch (commit 096bfc8)

### Implementation Progress

**Phase 1 Completed (2025-10-30):**
- ✅ Created `lib/Templates.groovy` (265 lines, fully documented)
- ✅ Language auto-discovery working: Found **9 languages** (CZ, DE, EN, ES, FR, IT, NL, PT, RU) vs. Gradle's hardcoded 4
- ✅ Feature removal regex ported exactly from build.gradle:88-94
- ✅ Output validation: **100% identical** to Gradle version for all tested languages
- ✅ Created comprehensive test script: `test-templates.groovy`
- ✅ Installed Groovy 5.0.2 via SDKMAN for development
- 📊 Successfully generated 18 templates (9 languages × 2 styles)

**Key Achievement:** Templates.groovy replaces the most complex part of the Gradle build (createTemplatesFromGoldenMaster task) while simultaneously fixing the hardcoded language limitation!

---
*This plan is maintained by the LLM. Updates reflect progress and new discoveries.*
