# Solution Strategy

This document describes the top 5 architectural decisions that shape the arc42-generator system. Understanding these decisions and their rationale is critical for anyone working with or extending the system.

**Note**: As of 2025-10-30, the maintainer (Ralf D. Müller) has confirmed that Gradle will be removed in the future and replaced with standalone Groovy scripts. Some architectural decisions described here (particularly Decision 2) will be superseded by a simpler approach.

---

## Decision 1: Golden Master Pattern

### The Decision
Maintain a single "Golden Master" source containing ALL template variants using feature flags, rather than maintaining separate source files for each variant.

### Context
The arc42 template is offered in multiple styles:
- **Plain**: Just the structure, no help text or examples
- **With-Help**: Structure plus explanatory help text
- **With-Examples** (planned): Structure plus help plus example content

Maintaining separate source files for each variant leads to:
- Content duplication (structure must be identical)
- Synchronization nightmares (change structure in one, must change in all)
- High risk of variants drifting apart

### Rationale
**DRY Principle**: The template structure is identical across variants - only the presence/absence of help text and examples differs. This is a perfect use case for conditional compilation / feature flags.

**Single Source of Truth**: When the arc42 framework structure changes (e.g., adding a new section), there's only ONE place to make the change. The generator automatically propagates it to all variants.

**Maintainability**: Content authors work in one place. Build engineers handle the variant generation logic once.

### Implementation
Using AsciiDoc's built-in conditional processing:

```asciidoc
=== Quality Goals

ifdef::arc42help[]
[role="arc42help"]
****
.Contents
The top three (max five) quality goals...
****
endif::arc42help[]
```

The generator removes these blocks using regex for "plain" variant:
```groovy
template = template.replaceAll(/(?ms)\[role="arc42help"\][ \r\n]+[*]{4}.*?[*]{4}/, '')
```

### Consequences

**Positive**:
- ✅ Single source for all variants reduces maintenance by ~70%
- ✅ Structural changes propagate automatically
- ✅ Impossible for variants to have different structure
- ✅ Easy to add new variants (just adjust feature flags)

**Negative**:
- ❌ Golden Master files are more complex (contain all variants)
- ❌ Content authors must understand feature flag syntax
- ❌ Regex-based removal is fragile (must match exact syntax)
- ❌ Cannot preview final variant without running generator

### Alternatives Considered

**Alternative 1: Separate Source Files per Variant**
- **Rejected because**: Would require maintaining 2-3x the files, high risk of drift, structural changes need manual synchronization

**Alternative 2: Programmatic Content Assembly**
- **Rejected because**: Would require embedding content in code (bad separation of concerns), harder for content authors to maintain

**Alternative 3: AsciiDoc Preprocessing**
- **Rejected because**: AsciiDoc's ifdef is designed for build-time conditions, not dynamic generation. Regex gives us more control.

### Related ADRs
- [ADR-004: Feature Flag System](09-decisions/ADR-004-feature-flag-system.md)

---

## Decision 2: Dynamic Gradle Subproject Generation

**⚠️ Future Change**: This decision is being superseded. The maintainer confirmed (2025-10-30) that "Gradle will be removed in the future." The replacement approach will use explicit directory scanning in Groovy scripts without the subproject complexity.

### The Decision
Generate Gradle subprojects dynamically at build time based on discovered directory structure, rather than statically defining them in configuration.

### Context
With 10+ languages and 2+ template styles, we have 20+ subprojects. Each subproject needs to:
- Run format conversions independently
- Have its own build configuration
- Access language-specific version metadata
- Build in parallel for performance

Traditional Gradle approach: Manually list each subproject in `settings.gradle`.

### Rationale
**Convention Over Configuration**: The directory structure `build/src_gen/{LANG}/asciidoc/{STYLE}/` already encodes the organization. Why duplicate this in configuration?

**Scalability**: Adding a new language requires ONLY adding the Golden Master files. No build configuration changes needed (except the hardcoded language list - see open questions).

**Consistency**: All language/style combinations get identical build logic. No risk of copy-paste errors.

### Implementation
`settings.gradle` scans `build/src_gen/` after `createTemplatesFromGoldenMaster` runs:

```groovy
target.eachFileRecurse { f ->
    if (f.name == 'src') {
        def parentFilePath = f.parentFile.path
        def language = parentFilePath.split('[/\\\\]')[-3]
        def docFormat = parentFilePath.split('[/\\\\]')[-1]

        // Copy template and substitute placeholders
        new File(parentFilePath +"/build.gradle")
            .write(new File("subBuild.gradle").text
                .replaceAll('%LANG%', language)
                .replaceAll('%TYPE%', docFormat)
                // ... more substitutions
            )

        // Register subproject
        include("${language}:${docFormat}")
    }
}
```

### Consequences

**Positive**:
- ✅ Adding new language is trivial (just add Golden Master files)
- ✅ All subprojects get identical, consistent build logic
- ✅ No manual configuration maintenance
- ✅ Scales to any number of languages/styles

**Negative**:
- ❌ **"Chicken-and-egg" problem**: settings.gradle runs before tasks, but needs createTemplatesFromGoldenMaster output
- ❌ First-time builders must understand two-phase process
- ❌ Gradle tooling (IDE integration) struggles with dynamic structure
- ❌ Debugging subproject issues harder (no explicit configuration to inspect)

### The Chicken-and-Egg Solution
settings.gradle only includes subprojects if `build/src_gen/` exists:

```groovy
if (target.exists()) {
    target.eachFileRecurse { ... }
}
```

**Workflow**:
1. First run: `./gradlew createTemplatesFromGoldenMaster` (creates structure)
2. Gradle re-evaluates settings.gradle (discovers subprojects)
3. Subsequent run: `./gradlew arc42` (now has subprojects available)

Or use `./build-arc42.sh` which handles the sequence automatically.

### Alternatives Considered

**Alternative 1: Static Subproject List**
```groovy
['DE','EN','FR','CZ'].each { lang ->
    ['plain','with-help'].each { style ->
        include("${lang}:${style}")
    }
}
```
- **Rejected because**: Must manually update when adding languages, defeats the automation goal

**Alternative 2: Gradle Plugin with Custom DSL**
- **Rejected because**: Over-engineering, adds complexity, harder for community contributions

**Alternative 3: Multi-Module Maven**
- **Rejected because**: Maven less flexible for dynamic structures, Gradle ecosystem better for this use case

### Related ADRs
- [ADR-002: Dynamic Subproject Generation](09-decisions/ADR-002-dynamic-subproject-generation.md)

---

## Decision 3: Two-Stage Conversion Pipeline (AsciiDoc → DocBook → Target)

### The Decision
Convert AsciiDoc to most formats using DocBook XML as an intermediate format, rather than converting directly to target formats.

### Context
Need to support 15+ output formats:
- HTML, Markdown (multiple variants), DOCX, PDF, EPUB, LaTeX, RST, Textile, etc.

Pandoc is the universal document converter that supports all these formats, BUT Pandoc's AsciiDoc support is limited and produces lower-quality output compared to Asciidoctor.

### Rationale
**Leverage Best Tools**:
- Asciidoctor is THE reference implementation for AsciiDoc processing
- Pandoc is THE reference universal converter

**Quality**: Asciidoctor's DocBook output is high-quality and comprehensive. Pandoc excels at DocBook → everything conversions.

**Established Pattern**: DocBook is explicitly designed as an interchange format for technical documentation.

### Implementation

```
AsciiDoc Files (Golden Master)
        ↓
    Asciidoctor Plugin
        ↓
    DocBook XML (intermediate)
        ↓
    Pandoc
        ↓
DOCX, Markdown, EPUB, LaTeX, etc.
```

**Exception**: HTML uses direct conversion (AsciiDoc → HTML via Asciidoctor) because it's higher quality without DocBook intermediary.

### Consequences

**Positive**:
- ✅ High-quality output (leverages both tools' strengths)
- ✅ Asciidoctor handles AsciiDoc complexities (includes, attributes, etc.)
- ✅ Pandoc handles format-specific quirks
- ✅ DocBook is well-specified, stable intermediate representation

**Negative**:
- ❌ Two-step process is slower than direct conversion would be
- ❌ DocBook intermediate files consume disk space
- ❌ Potential for information loss in AsciiDoc → DocBook step
- ❌ Debugging format issues requires understanding DocBook
- ❌ Cannot easily add formats unsupported by Pandoc

### Alternatives Considered

**Alternative 1: Direct Pandoc (AsciiDoc → Target)**
- **Rejected because**: Pandoc's AsciiDoc support is basic, produces lower quality than Asciidoctor

**Alternative 2: Asciidoctor Converters for Each Format**
- **Rejected because**: Would require writing/maintaining 15+ custom converters, Pandoc already does this

**Alternative 3: Custom Converter per Format**
- **Rejected because**: Enormous implementation effort, Pandoc is battle-tested

### Why Not Fix Pandoc's AsciiDoc Support?
- Pandoc's AsciiDoc parsing is fundamentally different from Asciidoctor
- Achieving feature parity would require major Pandoc rewrite
- DocBook route works well, proven pattern

### Related ADRs
- [ADR-003: Two-Stage Conversion Pipeline](09-decisions/ADR-003-two-stage-conversion-pipeline.md)
- [ADR-006: Pandoc as Universal Converter](09-decisions/ADR-006-pandoc-as-converter.md)

---

## Decision 4: Submodule Architecture (Content vs. Build Separation)

### The Decision
Maintain template content in a separate Git repository (arc42-template) included as a submodule, rather than keeping everything in a monorepo.

### Context
Two distinct concerns:
1. **Template Content**: arc42 structure, help text, translations, examples
2. **Build System**: Gradle scripts, conversion logic, distribution packaging

Different stakeholders:
- Content: arc42 founders, translators, documentation experts
- Build: Build engineers, format conversion specialists

### Rationale
**Separation of Concerns**: Content authors shouldn't need to understand Gradle, regex, Pandoc, etc. Build engineers shouldn't need to understand arc42 pedagogy.

**Independent Evolution**:
- Template content changes frequently (typos, improvements, new languages)
- Build system changes infrequently (new formats, Gradle upgrades)

**Reusability**: Users can fork arc42-template directly without build system if they want to customize content.

**Release Independence**: Template content can be versioned independently (currently at 9.0).

### Implementation
```
arc42-generator/             (build system)
├── build.gradle
├── subBuild.gradle
├── buildconfig.groovy
└── arc42-template/          (git submodule)
    ├── DE/asciidoc/
    ├── EN/asciidoc/
    └── dist/                (distributions written here)
```

Users must: `git submodule update --init` before building.

### Consequences

**Positive**:
- ✅ Clean separation of concerns
- ✅ Content repo usable independently
- ✅ Different teams can work independently
- ✅ Content changes don't require build system knowledge
- ✅ Easier to review (content PRs separate from build PRs)

**Negative**:
- ❌ Submodule complexity (many developers unfamiliar with git submodules)
- ❌ Easy to forget `git submodule update` and get stale content
- ❌ Cross-repo coordination needed for breaking changes
- ❌ Distribution ZIPs committed to submodule (unusual pattern)

### The Distribution Dilemma
Generated ZIPs are committed to `arc42-template/dist/` (the submodule):

**Why?**
- Users download from arc42-template repo (that's the public interface)
- Build system is internal infrastructure
- Historic pattern from before generator existed

**Downside**: Unusual to commit generated files. But users expect them there.

### Alternatives Considered

**Alternative 1: Monorepo (everything together)**
- **Rejected because**: Mixes concerns, harder to contribute content without build system knowledge

**Alternative 2: Two Independent Repos (no submodule)**
- **Rejected because**: Must manually sync content, loses build-time connection

**Alternative 3: Template Content as npm/Maven Dependency**
- **Rejected because**: Adds packaging overhead, submodule simpler for this use case

### Related ADRs
- [ADR-005: Submodule Architecture](09-decisions/ADR-005-submodule-architecture.md)

---

## Decision 5: Pandoc as Universal Converter (vs. Custom Implementation)

### The Decision
Use Pandoc as the external tool for format conversion rather than implementing custom converters or using format-specific tools.

### Context
Need to support diverse output formats:
- Document formats: DOCX, PDF, ODT
- Markup formats: Markdown (5+ variants), RST, Textile
- Publishing formats: EPUB, LaTeX
- Web formats: HTML (already handled by Asciidoctor)

Each format has its own complexities, ecosystems, and quirks.

### Rationale
**Leverage Existing Tool**: Pandoc is the de facto standard universal document converter, maintained by a large community, supporting 40+ formats.

**Proven Quality**: Pandoc has been battle-tested for over 15 years, handles edge cases, actively maintained.

**Community Support**: When Pandoc fixes bugs or adds features, we benefit automatically (after version upgrade).

**Cost-Benefit**: Implementing even ONE custom converter (e.g., AsciiDoc → DOCX) would require substantial effort. Implementing 15+ is infeasible.

### Implementation
Dependency: Pandoc must be installed on the system running the build.

```groovy
task convert2Docx (type: Exec) {
    executable = "pandoc"
    args = ['-r','docbook',
            '-t','docx',
            '-o', outputFile,
            inputDocBookFile]
}
```

Version pinned to 3.7.0.2 for consistency (via build-arc42.sh installation).

### Consequences

**Positive**:
- ✅ Massive functionality for zero implementation cost
- ✅ Well-tested, production-grade quality
- ✅ Automatic improvements via Pandoc upgrades
- ✅ Community support via Pandoc docs/forums
- ✅ Handles format quirks we might miss (e.g., DOCX table formatting)

**Negative**:
- ❌ **External Dependency**: Pandoc must be installed, not pure Java solution
- ❌ **Version Sensitivity**: Different Pandoc versions may produce different output
- ❌ **Limited Control**: Can't easily customize Pandoc's conversion logic
- ❌ **Debugging**: Format issues may be Pandoc bugs, requires upstream fixes
- ❌ **Installation Friction**: Users must install Pandoc before building

### Risk Mitigation
- Version pinned to specific release (currently 3.7.0.2)
- Build script (build-arc42.sh) auto-installs correct version
- Docker/Gitpod images pre-install Pandoc
- Documentation clearly states Pandoc requirement

### Alternatives Considered

**Alternative 1: Custom Converters per Format**
```groovy
task convert2Docx {
    // 1000+ lines of DOCX generation code...
}
```
- **Rejected because**: Would require implementing and maintaining 15+ converters, each with hundreds/thousands of lines of code. Infeasible.

**Alternative 2: Format-Specific Tools**
- Use docx4j for DOCX, JLaTeXMath for LaTeX, etc.
- **Rejected because**: Fragmented toolchain, different APIs, inconsistent quality, higher maintenance burden

**Alternative 3: Cloud Conversion API**
- Use external service like CloudConvert, Aspose, etc.
- **Rejected because**: Requires internet connection, ongoing costs, less reproducible builds, vendor lock-in

**Alternative 4: Pure-Java Solution (JVM-based converters)**
- **Rejected because**: No comprehensive JVM library matches Pandoc's breadth, would still need multiple tools

### Why Acceptable to Have External Dependency
- Target users are developers (comfortable with tool installation)
- Build automation scripts handle installation
- Gain massive functionality for acceptable cost
- Industry-standard tool (many projects depend on Pandoc)

### Related ADRs
- [ADR-006: Pandoc as Universal Converter](09-decisions/ADR-006-pandoc-as-converter.md)
- [ADR-003: Two-Stage Conversion Pipeline](09-decisions/ADR-003-two-stage-conversion-pipeline.md)

---

## How These Decisions Work Together

```
┌─────────────────────────────────────────────────────────────┐
│                  Architectural Strategy                     │
└─────────────────────────────────────────────────────────────┘

   Golden Master Pattern (Decision 1)
           ↓
   Single source → multiple variants
           ↓
   Dynamic Subproject Generation (Decision 2)
           ↓
   One subproject per language/style combination
           ↓
   Submodule Architecture (Decision 4)
           ↓
   Content separate from build logic
           ↓
   Two-Stage Conversion (Decision 3)
           ↓
   AsciiDoc → DocBook → Target formats
           ↓
   Pandoc as Converter (Decision 5)
           ↓
   15+ output formats from single pipeline
```

These five decisions create a **multiplier effect**:
- 1 Golden Master source
- × 2 style variants (via feature flags)
- × 10 languages (via submodules)
- × 15 formats (via Pandoc)
- = 300 unique templates generated automatically

**The Core Insight**: This is a **compiler architecture** applied to documentation templates. The Golden Master is "source code," feature flags are "preprocessor directives," and the build system is the "compiler" generating deployable "artifacts."
