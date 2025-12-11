# Introduction and Goals

## System Vision

The **arc42-generator** is a multi-format template compiler that transforms the arc42 architecture documentation framework from a single Golden Master into hundreds of ready-to-use templates across multiple languages and formats.

### The Problem It Solves

Before this generator existed, maintaining the arc42 template ecosystem required manually managing:
- **10+ languages** (DE, EN, FR, CZ, ES, IT, NL, PT, RU, UKR, etc.)
- **15+ output formats** (HTML, Markdown, DOCX, PDF, EPUB, LaTeX, etc.)
- **2+ style variants** (plain structure vs. with explanatory help text)

This resulted in **300+ individual files** that needed to be kept in sync whenever the arc42 template structure changed. A single update to the template structure would require propagating changes across all combinations manually - a maintenance nightmare prone to inconsistencies.

### The Solution

The arc42-generator reduces this to maintaining approximately **10 Golden Master files per language**. These master files contain all variants using feature flags, and the generator automatically:
1. Strips unwanted features to create style variants (plain, with-help)
2. Converts to all target formats using a two-stage pipeline (AsciiDoc → DocBook → Target)
3. Packages everything into downloadable ZIP distributions

**Impact**: From 300+ files maintained manually → 10 files per language that generate everything automatically.

## Project History

**Created**: 2014 by Ralf D. Müller (sole maintainer)

**Initial Motivation** (confirmed by maintainer):
> "We started out with word (.docx) as the only target format. Other formats were added over time and it soon made sense to automatically generate all formats from the main asciidoc master file."

**Key Milestones**:
- **2014**: Project created, Gradle chosen (maintainer wanted to deepen Gradle understanding)
- **~2023**: req42-framework added (same structure, reused generator)
- **2025**: Planning to remove Gradle, replace with standalone Groovy scripts for simplicity

**User Base**: arc42-team only (internal tool)

**Maintenance**: Updates only performed when needed

## Core Quality Goals

### 1. Agnostic (Priority: CRITICAL)
The system must remain neutral to:
- **Development processes**: Works in agile, waterfall, formal, or informal contexts
- **Technologies**: Independent of programming languages, frameworks, operating systems
- **Domains**: Applicable to any system domain (finance, healthcare, e-commerce, etc.)
- **System types**: Interactive, batch, server/backend, mobile, client/server
- **System sizes**: Supports small to large systems (tested up to ~1M LOC)
- **Lifecycle phases**: Usable for planning (a-priori) or describing (a-posteriori)

**Why this matters**: arc42 templates are used by thousands of teams worldwide with vastly different contexts. Any dependency on specific tools or processes would limit adoption.

### 2. Easily Usable (Priority: HIGH)
The generated templates must be:
- **Well-documented**: Clear help text and examples available
- **Multi-format**: Available in 15+ formats to match team preferences
- **Multi-language**: At least EN and DE, with community translations
- **Low-barrier**: Minimal toolchain requirements to start using

**Why this matters**: The value of arc42 comes from adoption. If templates are hard to use or require expensive tools, adoption suffers.

### 3. Flexible (Priority: HIGH)
The system enables:
- **Adaptability**: Users can modify templates to fit their needs
- **Toolchain choice**: Works with Word, Confluence, AsciiDoc, Markdown, etc.
- **Extension**: Adding new languages or formats should be straightforward

**Why this matters**: Every organization has existing documentation practices. The system must adapt to them, not force change.

## Stakeholders

| Role | Representatives | Expectations | Impact on Architecture |
|------|----------------|--------------|----------------------|
| **Primary User** | arc42-team (internal) | Reliable generation of templates for distribution | System optimized for internal use, not general public |
| **End Users** | Software architects, developers documenting systems | Easy-to-use templates in preferred format and language | Drives format diversity and quality of generated output |
| **Contributors** | Community members, translators | Ability to add languages, improve formats, fix bugs | Requires maintainable build system, clear contribution workflow |
| **Founders** | Peter Hruschka, Gernot Starke | Conceptual integrity of arc42 framework preserved | Ensures template structure remains faithful to arc42 principles |
| **Maintainer** | Ralf D. Müller (sole maintainer since 2014) | Manageable complexity, reliable automation | Drives decisions toward simplicity (e.g., Gradle removal) |
| **Downstream Users** | Organizations mirroring distributions | Stable ZIP file locations and formats | Requires predictable distribution structure |
| **Template Content Authors** | Those maintaining arc42-template submodule | Independence from build system changes | Drives separation of concerns (content vs. build) |

## Technical Roadmap (Next 12 Months)

*Note: This section documents known planned work. Actual roadmap should be verified with maintainers or GitHub issues/milestones.*

### Confirmed Roadmap Items

**Gradle Removal** (confirmed by maintainer 2025-10-30):
> "Gradle will be removed in the future"

The build system will be replaced with standalone Groovy scripts for:
- Simpler architecture (explicit flow vs. Gradle lifecycle)
- Lighter weight execution
- Same language (team already knows Groovy)
- Easier maintenance for single maintainer

**Language Auto-Discovery** (confirmed by maintainer 2025-10-30):
The hardcoded language list in build.gradle:41 was temporary for a deployment and will be removed, restoring automatic language discovery.

### Potential Areas for Evolution
1. **CI/CD Integration**: Automate builds on arc42-template changes
2. **Format Additions**: Additional format requests from community
3. **Documentation**: This mental model documentation being created
4. **Testing**: More automated validation of generated outputs

## Non-Goals

The arc42-generator explicitly does NOT:
- **Replace requirements documentation**: arc42 is for architecture, not detailed requirements
- **Generate project-specific content**: It creates empty templates, not filled documentation
- **Address safety-critical systems**: No certifiable documentation generation
- **Provide formal verification**: No mathematical proof of documentation correctness
- **Include template content**: Content lives in separate arc42-template repository
- **Manage user documentation**: Once generated, users manage their own documentation

## Success Criteria

The system is successful when:
1. ✅ A single Golden Master update generates all 300+ template variants automatically
2. ✅ Adding a new language requires minimal effort (10 source files + build.gradle entry)
3. ✅ Build completes in reasonable time (< 5 minutes for full generation)
4. ✅ Generated templates are indistinguishable from hand-crafted quality
5. ✅ Community can contribute new formats without deep system knowledge
6. ✅ Template content authors never need to understand the generator internals
