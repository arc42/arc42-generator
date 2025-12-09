# System Context and Scope

## Business Context

```
┌─────────────────────────────────────────────────────────────────┐
│                      arc42 Ecosystem                            │
│                                                                 │
│  ┌──────────────────┐         ┌─────────────────────────┐     │
│  │  arc42-template  │────────>│   arc42-generator       │     │
│  │  (Golden Master) │  input  │   (This System)         │     │
│  │                  │         │                         │     │
│  │ - DE/asciidoc/   │         │ ┌─────────────────────┐ │     │
│  │ - EN/asciidoc/   │         │ │ Feature Flag        │ │     │
│  │ - FR/asciidoc/   │         │ │ Processor           │ │     │
│  │ - ...            │         │ └─────────────────────┘ │     │
│  └──────────────────┘         │           ↓             │     │
│                               │ ┌─────────────────────┐ │     │
│  ┌──────────────────┐         │ │ Format Converter    │ │     │
│  │  req42-framework │────────>│ │ (via Pandoc)        │ │     │
│  │  (Alternative)   │  input  │ └─────────────────────┘ │     │
│  └──────────────────┘         │           ↓             │     │
│                               │ ┌─────────────────────┐ │     │
│        ┌──────────────────────│ │ Distribution Packager│ │     │
│        │                      │ └─────────────────────┘ │     │
│        ↓                      └───────────┬─────────────┘     │
│  ┌──────────────────┐                    │                   │
│  │  Pandoc          │                    ↓                   │
│  │  (External Tool) │         ┌─────────────────────────┐    │
│  └──────────────────┘         │  Distribution ZIPs      │    │
│                               │  arc42-template/dist/   │    │
│                               └─────────────┬───────────┘    │
│                                             │                │
└─────────────────────────────────────────────┼────────────────┘
                                              ↓
                                    ┌───────────────────┐
                                    │   End Users       │
                                    │ (Architects/Devs) │
                                    └───────────────────┘
```

## System Boundaries

### What's IN Scope

The arc42-generator is responsible for:

1. **Template Generation**
   - Reading Golden Master AsciiDoc files
   - Processing feature flags (`ifdef::arc42help[]`, `[role="arc42help"]`)
   - Creating template style variants (plain, with-help)
   - Copying appropriate assets (images, common files)

2. **Multi-Format Conversion**
   - Converting AsciiDoc to HTML (direct via Asciidoctor)
   - Converting AsciiDoc → DocBook → Other formats (via Pandoc)
   - Handling both single-file and multi-page outputs
   - Managing image references across formats

3. **Multi-Language Support**
   - Processing templates for 10+ languages
   - Managing per-language version metadata (version.properties)
   - Ensuring language-specific formatting (e.g., Russian LaTeX fonts)

4. **Build System Orchestration**
   - Dynamic Gradle subproject generation
   - Dependency management between build phases
   - Automated build pipeline (build-arc42.sh)

5. **Distribution Packaging**
   - Creating ZIP files for each language/style/format combination
   - Organizing distributions in standard structure
   - Managing version information in filenames

### What's OUT of Scope

The arc42-generator does NOT handle:

1. **Template Content**
   - Writing or editing arc42 template structure (maintained in arc42-template repo)
   - Translating help text between languages
   - Creating example diagrams or content
   - **Rationale**: Separation of concerns - content experts work in arc42-template, build experts work here

2. **User Documentation Generation**
   - Filling in template sections with project-specific content
   - Generating actual architecture documentation
   - Project-specific customizations
   - **Rationale**: This creates templates, users create documentation

3. **Template Content Validation**
   - Checking arc42 template structure correctness
   - Validating AsciiDoc syntax
   - Ensuring content quality
   - **Rationale**: Assumed that Golden Master is already validated

4. **Distribution Hosting**
   - Hosting ZIP files for download (that's GitHub's role)
   - Managing arc42.org website
   - **Rationale**: Infrastructure concern, not build concern

5. **End-User Tooling**
   - Providing editors for arc42 documentation
   - Template usage guidance
   - **Rationale**: Out of generator's scope

## External Interfaces

### Input Interfaces

#### 1. arc42-template Submodule (Git)
```
Interface: Git submodule
Protocol: Git
Location: github.com/arc42/arc42-template
Structure:
  /{LANG}/
    /asciidoc/
      arc42-template.adoc          # Main document
      /src/*.adoc                  # Individual sections
    version.properties             # Version metadata
  /common/
    /styles/*.adoc                 # Shared styling
  /images/*.png                    # Diagrams and logos
```

**Data Flow**:
- Generator reads AsciiDoc files
- Parses feature flag markers
- Extracts version properties
- Copies images selectively based on template style

**Dependencies**:
- Must run `git submodule update` before build
- Generator assumes specific directory structure
- Breaking changes in structure require generator updates

#### 2. req42-framework Submodule (Git)
```
Interface: Git submodule (alternative template)
Protocol: Git
Location: github.com/arc42/req42-framework
Usage: Activated via switchToreq42.sh script
```

**Rationale for Separation**: req42 is a requirements-focused variant. Keeping it separate allows independent evolution.

#### 3. Pandoc (External Tool)
```
Interface: Command-line execution
Required Version: ≥ 1.12.4.2 (currently pinned to 3.7.0.2)
Installation: System package or downloaded .deb
Commands Used:
  pandoc -r docbook -t {FORMAT} -o {OUTPUT} {INPUT}
```

**Critical Dependency**:
- Generator assumes Pandoc is in PATH
- Version compatibility matters (thus pinned)
- Different versions may produce different output

### Output Interfaces

#### 1. Build Directory Structure
```
build/
├── src_gen/                    # Generated AsciiDoc variants
│   └── {LANG}/
│       └── asciidoc/
│           ├── plain/
│           │   └── src/*.adoc
│           └── with-help/
│               └── src/*.adoc
└── {LANG}/
    └── {FORMAT}/
        └── {STYLE}/
            └── arc42-template-{LANG}.{ext}
```

**Contract**:
- Ephemeral (never committed)
- Regenerated on each build
- settings.gradle scans this to discover subprojects

#### 2. Distribution ZIPs
```
arc42-template/dist/
  arc42-template-{LANG}-{STYLE}-{FORMAT}.zip

Example:
  arc42-template-EN-plain-docx.zip
  arc42-template-DE-withhelp-html.zip
```

**Contract**:
- Committed to arc42-template submodule (not generator repo)
- Naming convention must remain stable (downstream dependencies)
- Each ZIP is self-contained (includes images if needed)

## Neighbor Systems

### 1. arc42-template Repository
**Relationship**: Content Provider
**Interface**: Git submodule
**Why Separate?**:
- Different stakeholders (content authors vs. build engineers)
- Different change frequency (content changes more often)
- Content can be used independently (users can fork just templates)

**Communication Pattern**:
- One-way: arc42-template → arc42-generator
- Generator reads, never writes to source templates
- Distribution ZIPs written back to arc42-template/dist/

### 2. Pandoc Project
**Relationship**: Format Conversion Engine
**Interface**: CLI tool execution
**Why External?**:
- Pandoc is a mature, well-tested universal document converter
- Building custom converters for 15+ formats would be enormous effort
- Community maintenance and bug fixes

**Risk**:
- Pandoc API changes could break builds (mitigated by version pinning)
- Pandoc bugs affect our output quality

### 3. Gradle Ecosystem
**Relationship**: Build Framework
**Interface**: Gradle plugins and APIs
**Why Gradle?**:
- Powerful task orchestration
- Dynamic project structure support
- Large plugin ecosystem (Asciidoctor, git-publish)

**Evolution**:
- Gradle APIs change between versions
- Requires ongoing maintenance to stay current
- Recent work: migrating from old APIs to property-based APIs

### 4. GitHub (Infrastructure)
**Relationship**: Hosting and Distribution
**Interface**: Git protocol, GitHub Pages
**Role**:
- Hosts source repositories
- Hosts distribution ZIPs
- Potentially hosts generated docs (via publish/ subproject)

### 5. Asciidoctor Gradle Plugin
**Relationship**: AsciiDoc Processor
**Interface**: Gradle plugin
**Why Used?**:
- Converts AsciiDoc → HTML directly (high quality)
- Converts AsciiDoc → DocBook (for Pandoc pipeline)

## Technical Context

### Development Environment
- **Language**: Groovy (Gradle build scripts)
- **Build Tool**: Gradle (with wrapper for version consistency)
- **VCS**: Git with submodules
- **CI/CD**: (Currently manual, potential for GitHub Actions)
- **Containerization**: Docker/Gitpod support for consistent environments

### Runtime Environment
- **Execution**: Gradle JVM runtime
- **External Tools**: Pandoc (system-installed)
- **Operating Systems**: Linux (primary), macOS, Windows (with caveats)
- **Cloud**: GitHub Codespaces supported

### Data Flows

**Build Pipeline Flow**:
```
1. Git Submodule → AsciiDoc Sources
2. Feature Flag Processor → Filtered AsciiDoc Variants
3. Asciidoctor → HTML + DocBook XML
4. Pandoc (DocBook XML) → Target Formats
5. ZIP Packager → Distribution Archives
6. Git Commit → Distribution ZIPs to arc42-template/dist/
```

**Critical Ordering**:
- createTemplatesFromGoldenMaster MUST run before settings.gradle discovery
- settings.gradle discovery MUST happen before any subproject tasks
- DocBook generation MUST precede Pandoc conversion
- All format generation MUST complete before createDistribution
