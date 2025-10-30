# LLM Knowledge Graph: arc42-generator

This document provides a structured knowledge graph optimized for LLM consumption. Concepts are organized hierarchically with explicit dependencies, validation criteria, and common mistakes.

## Concept Hierarchy

```
Level 0: Fundamental Concepts (must understand first)
  ├─ Golden Master Pattern
  └─ Git Submodules

Level 1: Architectural Concepts (build on fundamentals)
  ├─ Two-Stage Conversion Pipeline
  ├─ Dynamic Subproject Generation
  └─ Gradle Build Lifecycle (Chicken-and-Egg)

Level 2: Implementation Concepts (build on architecture)
  ├─ Feature Flag Processing (Regex)
  ├─ Placeholder Substitution
  └─ Version Management

Level 3: Advanced Concepts
  ├─ Multi-Page Format Generation
  └─ Language-Specific Handling
```

---

## Concepts (Structured Data)

```yaml
concepts:
  - name: "Golden Master Pattern"
    level: 0  # Fundamental
    priority: CRITICAL
    learning_time: "30 minutes"
    prerequisites: []
    enables:
      - "Feature Flag Processing"
      - "Template Style Variants"
      - "Understanding build output"

    description: |
      Single source file contains ALL variants marked with feature flags.
      Build system removes unwanted content to create specific variants.

    why_it_matters: |
      Foundation of entire system. Without understanding this, nothing else makes sense.
      Reduces 300+ files to ~10 per language.

    how_it_works: |
      1. Author writes Golden Master with feature markers: [role="arc42help"]
      2. Build reads config to know which features to keep/remove
      3. Regex removes unwanted feature blocks
      4. Filtered content written to build/src_gen/{STYLE}/

    common_mistakes:
      - mistake: "Editing generated files in build/src_gen/"
        why: "Build regenerates from Golden Master, changes lost"
        correct: "Edit arc42-template/{LANG}/asciidoc/src/*.adoc, then rebuild"

      - mistake: "Adding help text without [role='arc42help'] wrapper"
        why: "Content appears in ALL variants, not just with-help"
        correct: "Wrap in [role='arc42help']\n****\nContent\n****"

      - mistake: "Using 3 asterisks instead of 4"
        why: "Regex expects exactly [*]{4}, won't match"
        correct: "Always use exactly 4 asterisks for block delimiters"

    validation: |
      Q: Where do you add explanatory text that should appear in "with-help" but not "plain"?
      A: In arc42-template/{LANG}/asciidoc/src/{file}.adoc, wrapped in:
         [role="arc42help"]
         ****
         Text here
         ****

    code_locations:
      - "buildconfig.groovy:8-14 (feature definitions)"
      - "build.gradle:88-94 (removal logic)"
      - "arc42-template/{LANG}/asciidoc/src/*.adoc (Golden Master sources)"

    related_adrs:
      - "ADR-001"
      - "ADR-004"


  - name: "Git Submodules"
    level: 0
    priority: HIGH
    learning_time: "15 minutes"
    prerequisites: []
    enables:
      - "Understanding repository structure"
      - "Content updates"
      - "Distribution workflow"

    description: |
      Template content (arc42-template) maintained as separate Git repository,
      included in generator via git submodule.

    why_it_matters: |
      Content and build are separate concerns. Submodule enables independent evolution
      and allows content experts to work without build system knowledge.

    how_it_works: |
      1. arc42-generator repo contains: .gitmodules file
      2. arc42-template/ directory is a git submodule pointer
      3. Must run: git submodule init && git submodule update
      4. Submodule has own commit history, version, branches

    common_mistakes:
      - mistake: "Forgetting git submodule update after clone"
        why: "arc42-template/ empty or stale"
        correct: "Always: git submodule init && git submodule update"

      - mistake: "Committing in main repo when arc42-template changed"
        why: "Only updates submodule pointer, not content"
        correct: "cd arc42-template; git add/commit/push; cd ..; git add arc42-template"

    validation: |
      Q: Why is arc42-template a submodule instead of regular directory?
      A: Separates content (maintained by content experts) from build (maintained by engineers).
         Enables independent evolution, versioning, and contribution workflows.

    code_locations:
      - ".gitmodules (submodule configuration)"
      - "arc42-template/ (submodule directory)"

    related_adrs:
      - "ADR-005"


  - name: "Two-Stage Conversion Pipeline"
    level: 1
    priority: CRITICAL
    learning_time: "20 minutes"
    prerequisites:
      - "Golden Master Pattern"
    enables:
      - "Understanding format conversion"
      - "Adding new output formats"
      - "Debugging conversion issues"

    description: |
      Convert AsciiDoc to target formats via DocBook XML intermediate:
      AsciiDoc → (Asciidoctor) → DocBook → (Pandoc) → DOCX/Markdown/etc.

    why_it_matters: |
      Explains why builds are slower (two steps) and why DocBook files appear.
      Understanding this is essential for adding formats or debugging output issues.

    how_it_works: |
      Phase 1: Asciidoctor converts AsciiDoc → DocBook XML
        - High quality, captures all AsciiDoc semantics
        - Outputs to build/{LANG}/docbook/

      Phase 2: Pandoc converts DocBook → target formats
        - Reads DocBook (well-supported by Pandoc)
        - Outputs to build/{LANG}/{FORMAT}/

      Exception: HTML uses direct AsciiDoc → HTML (higher quality)

    common_mistakes:
      - mistake: "Assuming direct AsciiDoc → DOCX conversion"
        why: "Pandoc's AsciiDoc support is limited"
        correct: "Use two-stage: AsciiDoc → DocBook → DOCX"

      - mistake: "Editing DocBook XML to fix output"
        why: "DocBook regenerated every build"
        correct: "Fix in Golden Master AsciiDoc or adjust Pandoc args"

    validation: |
      Q: Why not convert AsciiDoc directly to DOCX using Pandoc?
      A: Pandoc's AsciiDoc parsing is basic. Asciidoctor → DocBook → Pandoc leverages
         strengths of both tools: Asciidoctor for AsciiDoc, Pandoc for format conversion.

    code_locations:
      - "subBuild.gradle:105-129 (DocBook generation)"
      - "subBuild.gradle:174-512 (Pandoc conversions)"

    related_adrs:
      - "ADR-003"
      - "ADR-006"


  - name: "Dynamic Subproject Generation"
    level: 1
    priority: HIGH
    learning_time: "25 minutes"
    prerequisites:
      - "Golden Master Pattern"
      - "Gradle Build Lifecycle"
    enables:
      - "Adding new languages"
      - "Understanding build parallelization"
      - "Debugging task not found errors"

    description: |
      Gradle subprojects NOT hardcoded - discovered by scanning build/src_gen/
      directory structure. Convention over configuration.

    why_it_matters: |
      This is why adding a new language is easy (just add files, rebuild).
      Also explains "chicken-and-egg" problem and task discovery.

    how_it_works: |
      1. settings.gradle scans build/src_gen/ for directories named "src/"
      2. Extracts language/style from parent path (e.g., EN/asciidoc/plain/)
      3. Copies subBuild.gradle → build.gradle with substitutions
      4. Registers subproject: include("{LANG}:{STYLE}")
      5. Result: :EN:plain, :DE:with-help, etc.

    common_mistakes:
      - mistake: "Running ./gradlew arc42 before createTemplatesFromGoldenMaster"
        why: "Subprojects don't exist yet (build/src_gen/ doesn't exist)"
        correct: "Always createTemplatesFromGoldenMaster first"

      - mistake: "Expecting IDE to show subprojects immediately"
        why: "Subprojects created dynamically after first build phase"
        correct: "Run createTemplatesFromGoldenMaster, then refresh IDE"

      - mistake: "Modifying generated build.gradle in subproject"
        why: "Regenerated from template on next build"
        correct: "Modify subBuild.gradle template (affects all subprojects)"

    validation: |
      Q: How does settings.gradle know which subprojects to create?
      A: Scans build/src_gen/ recursively, looking for "src/" directories.
         Extracts language/style from path, creates subproject for each.

    code_locations:
      - "settings.gradle:36-60 (discovery loop)"
      - "subBuild.gradle (template for all subprojects)"

    related_adrs:
      - "ADR-002"


  - name: "Gradle Build Lifecycle (Chicken-and-Egg)"
    level: 1
    priority: HIGH
    learning_time: "20 minutes"
    prerequisites:
      - "Dynamic Subproject Generation"
    enables:
      - "Understanding build sequence"
      - "Debugging 'task not found' errors"
      - "Proper build workflow"

    description: |
      settings.gradle evaluates BEFORE tasks run, but needs output from
      createTemplatesFromGoldenMaster task. Solved via conditional discovery.

    why_it_matters: |
      This is THE most confusing aspect for newcomers. Explains why you can't
      just run ./gradlew arc42 on fresh clone.

    how_it_works: |
      Gradle lifecycle: Settings → Configuration → Execution
      Our requirement: Task (execution) must run before Settings

      Solution: if (target.exists()) { discover subprojects }

      First run: createTemplatesFromGoldenMaster creates structure
      Gradle re-evaluates: settings.gradle discovers structure
      Subsequent: All tasks work

    common_mistakes:
      - mistake: "./gradlew arc42 on fresh clone"
        why: "No subprojects (build/src_gen/ doesn't exist)"
        correct: "./gradlew createTemplatesFromGoldenMaster first"

      - mistake: "rm -rf build/ then ./gradlew arc42"
        why: "Deleted structure, subprojects gone"
        correct: "Re-run createTemplatesFromGoldenMaster after deleting build/"

    validation: |
      Q: Why can't you run arc42 task immediately after cloning?
      A: settings.gradle needs to scan build/src_gen/ to discover subprojects,
         but that directory doesn't exist until createTemplatesFromGoldenMaster runs.

    code_locations:
      - "settings.gradle:35 (conditional: if target.exists())"

    related_adrs:
      - "ADR-002"


  - name: "Feature Flag Processing (Regex)"
    level: 2
    priority: MEDIUM
    learning_time: "20 minutes"
    prerequisites:
      - "Golden Master Pattern"
    enables:
      - "Adding new feature types"
      - "Debugging variant issues"
      - "Understanding removal fragility"

    description: |
      Feature flags removed using regex pattern matching, not AsciiDoc preprocessing.
      "String surgery" approach.

    why_it_matters: |
      Understanding the regex is essential for troubleshooting why content
      appears in wrong variants. Also shows fragility points.

    how_it_works: |
      Pattern: /(?ms)\[role="arc42FEATURE"\][ \r\n]+[*]{4}.*?[*]{4}/

      Breakdown:
      - (?ms): Multiline, dot matches newlines
      - \[role="arc42help"\]: Literal match
      - [ \r\n]+: Whitespace after
      - [*]{4}: Exactly 4 asterisks
      - .*?: Non-greedy content match
      - [*]{4}: Closing delimiter

    common_mistakes:
      - mistake: "Inconsistent asterisk count (3 or 5 instead of 4)"
        why: "Regex won't match [*]{4}"
        correct: "Always exactly 4 asterisks"

      - mistake: "Whitespace inside delimiters: * * * *"
        why: "Doesn't match [*]{4} (expects continuous)"
        correct: "**** (4 asterisks, no spaces)"

      - mistake: "Nested feature blocks"
        why: "Non-greedy match stops at first ****, partial removal"
        correct: "Never nest blocks, use single block per feature"

    validation: |
      Q: What happens if you use [role="arc42help"] but only 3 asterisks?
      A: Regex won't match. Help text will appear in plain variant (wrong!).

    code_locations:
      - "build.gradle:88-94 (regex patterns)"
      - "buildconfig.groovy:7 (allFeatures definition)"

    related_adrs:
      - "ADR-004"


  - name: "Placeholder Substitution"
    level: 2
    priority: MEDIUM
    learning_time: "15 minutes"
    prerequisites:
      - "Dynamic Subproject Generation"
    enables:
      - "Understanding subproject customization"
      - "Debugging version issues"

    description: |
      subBuild.gradle template contains placeholders (%LANG%, %TYPE%, etc.)
      replaced with actual values when creating subproject build.gradle.

    why_it_matters: |
      This is how each subproject gets language-specific configuration
      (version numbers, language codes) from a single template.

    how_it_works: |
      1. settings.gradle loads version.properties for language
      2. Reads subBuild.gradle as text
      3. Replaces: %LANG% → "EN", %TYPE% → "plain", %REVNUMBER% → "9.0-EN"
      4. Writes to build/src_gen/{LANG}/asciidoc/{STYLE}/build.gradle

    common_mistakes:
      - mistake: "Expecting placeholders to exist at runtime"
        why: "Replaced at config time, not execution time"
        correct: "After substitution, build.gradle has actual values"

    validation: |
      Q: Where do the %LANG% and %REVNUMBER% values come from?
      A: Language from directory path, version data from {LANG}/version.properties

    code_locations:
      - "settings.gradle:44-52 (substitution logic)"
      - "subBuild.gradle (template with placeholders)"

    related_adrs:
      - "ADR-002"
      - "ADR-007"


  - name: "Version Management"
    level: 2
    priority: LOW
    learning_time: "10 minutes"
    prerequisites:
      - "Git Submodules"
    enables:
      - "Understanding release process"
      - "Adding new languages"

    description: |
      Each language has independent version.properties file with
      revnumber, revdate, revremark.

    why_it_matters: |
      New languages can start at v1.0 while mature ones at v9.0.
      Version metadata appears in generated documents.

    how_it_works: |
      1. Each arc42-template/{LANG}/version.properties defines version
      2. settings.gradle loads properties
      3. Substitutes into subproject build.gradle
      4. Asciidoctor uses as document attributes
      5. Appears in rendered output and ZIP filenames

    common_mistakes:
      - mistake: "Expecting all languages at same version"
        why: "Each language independently versioned"
        correct: "Check version.properties for each language"

    validation: |
      Q: Why are languages versioned independently?
      A: Different maturity levels. New translations may be at v1.0 while
         established ones at v9.0. Signals completeness to users.

    code_locations:
      - "arc42-template/{LANG}/version.properties"
      - "settings.gradle:15-33 (loading logic)"

    related_adrs:
      - "ADR-007"


  - name: "Multi-Page Format Generation"
    level: 3
    priority: LOW
    learning_time: "30 minutes"
    prerequisites:
      - "Two-Stage Conversion Pipeline"
      - "Dynamic Subproject Generation"
    enables:
      - "Understanding MP format tasks"
      - "Adding new MP variants"

    description: |
      Some formats (markdownMP, mkdocsMP, gitHubMarkdownMP) split template
      into multiple files (one per section). Uses dynamic task creation.

    why_it_matters: |
      More complex than single-file formats. Understanding this helps
      debug MP format issues and add new MP variants.

    how_it_works: |
      1. generateDocbookMP creates one XML file per section
      2. Task scans output directory at configuration time
      3. For each XML file, creates Pandoc conversion task dynamically
      4. Tasks execute, each producing one output file
      5. Post-processing task assembles index/navigation

    common_mistakes:
      - mistake: "Expecting statically defined tasks for MP formats"
        why: "Tasks created dynamically based on XML file count"
        correct: "Tasks created at config time via doLast { tasks.create() }"

    validation: |
      Q: Why are MP format tasks harder to debug than regular formats?
      A: Dynamic task creation. Tasks don't exist in static configuration,
         only created when parent task evaluates.

    code_locations:
      - "subBuild.gradle:229-453 (MP task implementations)"

    related_adrs:
      - "ADR-003"


  - name: "Language-Specific Handling"
    level: 3
    priority: LOW
    learning_time: "10 minutes"
    prerequisites:
      - "Two-Stage Conversion Pipeline"
    enables:
      - "Adding language-specific features"

    description: |
      Some languages need special handling (e.g., Russian LaTeX font encoding).

    why_it_matters: |
      Pattern for adding language-specific behavior when needed.

    how_it_works: |
      Check language code in conversion task, add format-specific args:
      if (language=='RU') {
          args += ['-V','fontenc=T1,T2A']
      }

    common_mistakes:
      - mistake: "Hardcoding logic for specific language in wrong place"
        why: "Should be in format conversion tasks, not global config"
        correct: "Add conditional in subBuild.gradle conversion tasks"

    validation: |
      Q: Where would you add LaTeX-specific handling for German?
      A: In subBuild.gradle:convert2Latex task, add:
         if (language=='DE') { args += [...] }

    code_locations:
      - "subBuild.gradle:163-165 (Russian example)"

    related_adrs: []
```

---

## Concept Dependencies Graph

```
┌─────────────────────────────────────────────────────────────┐
│ Golden Master Pattern (L0) ◀──────────────────┐             │
└──────────┬──────────────────────────────────────────────────┘
           │                                   │
           ├─ enables ────────────────────────┤
           │                                   │
           ▼                                   ▼
┌──────────────────────────────┐   ┌──────────────────────────┐
│ Feature Flag Processing (L2) │   │ Template Style Variants  │
└──────────────────────────────┘   └──────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│ Git Submodules (L0)                                         │
└──────────┬──────────────────────────────────────────────────┘
           │
           ├─ enables ──────────┐
           │                    │
           ▼                    ▼
┌──────────────────────┐   ┌────────────────────┐
│ Submodule Updates    │   │ Content Versioning │
└──────────────────────┘   └────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│ Two-Stage Conversion Pipeline (L1)                          │
└──────────┬──────────────────────────────────────────────────┘
           │
           ├─ enables ──────────────┐
           │                        │
           ▼                        ▼
┌──────────────────────┐   ┌────────────────────────┐
│ Adding Formats       │   │ Multi-Page Generation  │
└──────────────────────┘   └────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│ Dynamic Subproject Generation (L1)                          │
└──────────┬──────────────────────────────────────────────────┘
           │
           ├─ enables ─────────────────┐
           │                           │
           ▼                           ▼
┌──────────────────────┐   ┌───────────────────────────────┐
│ Adding Languages     │   │ Placeholder Substitution (L2) │
└──────────────────────┘   └───────────────────────────────┘
           │
           └─ requires ──────────────────┐
                                        │
                                        ▼
                        ┌───────────────────────────────┐
                        │ Gradle Build Lifecycle (L1)   │
                        └───────────────────────────────┘
```

---

## Learning Paths

### Path 1: Basic User (wants to understand system)
```
1. Golden Master Pattern (30 min)
2. Git Submodules (15 min)
3. Two-Stage Conversion (20 min)
Total: 65 minutes
```

### Path 2: Contributor (wants to add language/format)
```
1. Golden Master Pattern (30 min)
2. Git Submodules (15 min)
3. Dynamic Subproject Generation (25 min)
4. Gradle Build Lifecycle (20 min)
5. Two-Stage Conversion (20 min)
Total: 110 minutes
```

### Path 3: Maintainer (wants full system mastery)
```
All concepts in level order: L0 → L1 → L2 → L3
Total: ~3-4 hours
```

---

## Quick Reference: Common Tasks → Concepts Needed

| Task | Required Concepts |
|------|-------------------|
| Add new language | Dynamic Subproject Generation, Version Management |
| Add new format | Two-Stage Conversion Pipeline |
| Fix variant content issue | Golden Master Pattern, Feature Flag Processing |
| Debug build failure | Gradle Build Lifecycle, Dynamic Subproject Generation |
| Update template content | Git Submodules, Golden Master Pattern |
| Add language-specific logic | Language-Specific Handling, Two-Stage Conversion |

---

## Concept Interconnections

**Most Central Concepts** (highest connectivity):
1. Golden Master Pattern (enables 5+ other concepts)
2. Dynamic Subproject Generation (enables 4+ concepts)
3. Two-Stage Conversion (enables 3+ concepts)

**Entry Point Concepts** (no prerequisites):
- Golden Master Pattern
- Git Submodules

**Bottleneck Concepts** (many depend on these):
- Gradle Build Lifecycle (blocks understanding of build flow)
- Feature Flag Processing (blocks debugging variants)

---

## For LLMs: Usage Guidance

When answering questions about arc42-generator:

1. **Identify concept level**: Start with L0 concepts for beginners
2. **Check prerequisites**: Don't explain L2 before L0/L1 understood
3. **Reference validation questions**: Use to test user understanding
4. **Cite code locations**: Point to specific files/lines
5. **Explain common mistakes**: Preempt likely errors
6. **Link to ADRs**: Provide deeper context when needed

**Example**: User asks "How do I add Spanish support?"
- **Concept needed**: Dynamic Subproject Generation (L1)
- **Prerequisites check**: Do they understand Golden Master (L0)?
- **If yes**: Explain steps with validation questions
- **If no**: Start with L0, then build up

This knowledge graph enables precise, context-aware assistance.
