# Building Block View

## Whitebox: Overall System

```
┌────────────────────────────────────────────────────────────────┐
│                    arc42-generator                             │
│                                                                │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │  Root Build Orchestration (build.gradle)                 │ │
│  │  - Language detection                                    │ │
│  │  - createTemplatesFromGoldenMaster task                  │ │
│  │  - createDistribution task                               │ │
│  └────────────────┬─────────────────────────────────────────┘ │
│                   │                                            │
│  ┌────────────────▼─────────────────────────────────────────┐ │
│  │  Configuration (buildconfig.groovy)                      │ │
│  │  - Template styles definition                            │ │
│  │  - Output formats list                                   │ │
│  │  - Path configuration                                    │ │
│  └────────────────┬─────────────────────────────────────────┘ │
│                   │                                            │
│  ┌────────────────▼─────────────────────────────────────────┐ │
│  │  Dynamic Subproject Discovery (settings.gradle)          │ │
│  │  - Scans build/src_gen/                                  │ │
│  │  - Creates subprojects dynamically                       │ │
│  │  - Substitutes build templates                           │ │
│  └────────────────┬─────────────────────────────────────────┘ │
│                   │                                            │
│       ┌───────────┴───────────┐                                │
│       ▼                       ▼                                │
│  ┌─────────┐           ┌─────────┐                            │
│  │ EN:plain│  ...      │DE:with- │  (20+ subprojects)         │
│  │         │           │  help   │                            │
│  └────┬────┘           └────┬────┘                            │
│       │                     │                                  │
│       └──────────┬──────────┘                                  │
│                  ▼                                              │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │  Subproject Build Logic (subBuild.gradle template)       │ │
│  │  - Asciidoctor tasks (HTML, DocBook)                     │ │
│  │  - Pandoc conversion tasks                               │ │
│  │  - Image copying                                         │ │
│  └──────────────────────────────────────────────────────────┘ │
│                                                                │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │  Automation Script (build-arc42.sh)                      │ │
│  │  - Pandoc installation                                   │ │
│  │  - Submodule management                                  │ │
│  │  - Full build pipeline orchestration                     │ │
│  └──────────────────────────────────────────────────────────┘ │
│                                                                │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │  Publishing (publish/build.gradle)                       │ │
│  │  - GitHub Pages deployment                               │ │
│  │  - Distribution upload                                   │ │
│  └──────────────────────────────────────────────────────────┘ │
└────────────────────────────────────────────────────────────────┘

External Dependencies:
- arc42-template/ (Git submodule - template content)
- Pandoc (system tool - format conversion)
- Gradle wrapper (bundled - build execution)
```

### Responsibility
The arc42-generator system transforms Golden Master AsciiDoc templates into 300+ ready-to-use templates across languages, styles, and formats.

### Interfaces
- **Input**: Git submodule (arc42-template)
- **Output**: ZIP files in arc42-template/dist/
- **External Tool**: Pandoc CLI

---

## Component: Root Build Orchestration

**Location**: `build.gradle` (root)

### Responsibility
- Define the `createTemplatesFromGoldenMaster` task (Phase 1)
- Define the `createDistribution` task (Phase 4)
- Detect available languages from arc42-template submodule
- Configure global build settings

### Key Behavior

#### Language Detection
```groovy
project.ext.languages = []
new File(config.goldenMaster.sourcePath).eachDir { dir ->
    if (dir.name =~ /^[A-Z]{2}$/) {
        languages << dir.name
    }
}
languages=['DE','EN', 'FR', 'CZ']  // Currently hardcoded override
```

**Why hardcoded?** Open question - auto-discovery code exists but is overridden. Likely for quality control (not all languages in submodule are complete).

#### createTemplatesFromGoldenMaster Task
**Triggers**: Manual invocation (`./gradlew createTemplatesFromGoldenMaster`)

**Steps**:
1. For each language in `languages` list:
   - Copy common files (styles, version.properties)
   - For each template style (plain, with-help):
     - Read each .adoc file from Golden Master
     - Remove unwanted features using regex
     - Write filtered content to `build/src_gen/{LANG}/asciidoc/{STYLE}/`
   - Copy images (selectively based on style)

**Critical Regex**:
```groovy
// Remove help blocks
template = template.replaceAll(
    /(?ms)\[role="arc42help"\][ \r\n]+[*]{4}.*?[*]{4}/,
    ''
)

// Remove ifdef/endif markers
if ("help" in featuresToRemove) {
    template = template.replaceAll(/(?ms)ifdef::arc42help\[\]/, '')
    template = template.replaceAll(/(?ms)endif::arc42help\[\]/, '')
}
```

**Output**: `build/src_gen/{LANG}/asciidoc/{STYLE}/src/*.adoc`

#### createDistribution Task
**Triggers**: Manual invocation (after arc42 task completes)

**Behavior**:
- Dynamically creates ZIP tasks for each (language × style × format) combination
- Archives contents from `build/{LANG}/{FORMAT}/{STYLE}/`
- Names: `arc42-template-{LANG}-{STYLE}-{FORMAT}.zip`
- Output location: `arc42-template/dist/`

**Why commit to submodule?** Distribution location is public interface - users download from arc42-template repo.

### Dependencies
- **Reads**: buildconfig.groovy (configuration)
- **Reads**: arc42-template/ submodule (Golden Master sources)
- **Writes**: build/src_gen/ (generated templates)
- **Writes**: arc42-template/dist/ (distributions)

### Key Constraints
- Must run BEFORE settings.gradle can discover subprojects
- Language list hardcoded (despite auto-discovery code existing)
- Regex patterns must match exact AsciiDoc syntax

---

## Component: Configuration

**Location**: `buildconfig.groovy`

### Responsibility
Centralize all build configuration in one place using Groovy's ConfigSlurper.

### Configuration Structure

```groovy
goldenMaster {
    sourcePath = 'arc42-template/'
    targetPath = 'build/src_gen/'

    allFeatures = ['help', 'example']

    templateStyles = [
        'plain'    : [],              // No features
        'with-help': ['help'],        // Help text only
        // 'with-examples': ['help','example']  // Commented out
    ]
}

formats = [
    'asciidoc': [imageFolder: true],
    'html': [imageFolder: true],
    'docx': [imageFolder: false],    // Embeds images
    'markdown': [imageFolder: true],
    // ... 15+ total formats
]

distribution {
    targetPath = "arc42-template/dist/"
}
```

### Key Design Decisions

**Why ConfigSlurper?**
- Type-safe configuration (vs. properties files)
- Supports nested structures
- Allows comments and logic
- Native Groovy syntax

**imageFolder setting**:
- `true`: Format needs separate images/ directory
- `false`: Format embeds images (e.g., DOCX, EPUB)

### Dependencies
- **Read by**: build.gradle, settings.gradle, subBuild.gradle

---

## Component: Dynamic Subproject Discovery

**Location**: `settings.gradle`

### Responsibility
Scan `build/src_gen/` directory structure and dynamically create Gradle subprojects for each discovered language/style combination.

### Key Behavior

#### Discovery Process
```groovy
def target = file(config.goldenMaster.targetPath)

if (target.exists()) {
    target.eachFileRecurse { f ->
        if (f.name == 'src') {  // Found a template directory
            def parentFilePath = f.parentFile.path

            // Extract metadata from path
            def language = parentFilePath.split('[/\\\\]')[-3]  // e.g., "EN"
            def docFormat = parentFilePath.split('[/\\\\]')[-1] // e.g., "plain"

            // Load version metadata
            def versionProps = loadVersionProperties(goldenMaster, language)

            // Create build.gradle for this subproject
            new File(parentFilePath + "/build.gradle")
                .write(
                    new File("subBuild.gradle").text
                        .replaceAll('%LANG%', language)
                        .replaceAll('%TYPE%', docFormat)
                        .replaceAll('%REVNUMBER%', versionProps.revnumber)
                        .replaceAll('%REVDATE%', versionProps.revdate)
                        .replaceAll('%REVREMARK%', versionProps.revremark)
                )

            // Register subproject
            def projectIdentifier = "${language}:${docFormat}"
            include(projectIdentifier)
            project(":${projectIdentifier}").projectDir = new File(parentFilePath)
        }
    }
}
```

#### Version Properties Loading
```groovy
def loadVersionProperties(goldenMaster, language) {
    def props = new Properties()
    def propFile = file(goldenMaster+"/${language}/version.properties")
    propFile.withInputStream {
        props.load(new InputStreamReader(it, "UTF-8"))
    }
    return [
        'revnumber': props.revnumber ?: 'UNKNOWN',
        'revdate': props.revdate ?: 'UNKNOWN',
        'revremark': props.revremark ?: ''
    ]
}
```

Example version.properties:
```properties
revnumber=9.0-EN
revdate=July 2025
revremark=(based upon AsciiDoc version)
```

### The Chicken-and-Egg Problem

**Problem**: settings.gradle runs BEFORE any tasks, but needs output from createTemplatesFromGoldenMaster task.

**Solution**: Conditional discovery
```groovy
if (target.exists()) {
    // Only discover if build/src_gen/ exists
}
```

**Workflow**:
1. First run: `./gradlew createTemplatesFromGoldenMaster` creates structure
2. Gradle re-evaluates: settings.gradle discovers subprojects
3. Subsequent: All subproject tasks available

### Dependencies
- **Reads**: buildconfig.groovy
- **Reads**: build/src_gen/ (from createTemplatesFromGoldenMaster)
- **Reads**: arc42-template/{LANG}/version.properties
- **Writes**: build/src_gen/{LANG}/asciidoc/{STYLE}/build.gradle (per subproject)
- **Creates**: Subprojects (e.g., :EN:plain, :DE:with-help)

### Key Constraints
- Runs during Gradle's settings evaluation phase (very early)
- Must handle case where build/src_gen/ doesn't exist yet
- Path parsing relies on specific directory structure

---

## Component: Subproject Build Logic (Template)

**Location**: `subBuild.gradle` (template file, copied and substituted)

### Responsibility
Defines the build logic for a single language/style combination. Handles all format conversions for that combination.

### Placeholder Substitution
Template contains:
- `%LANG%` → Language code (e.g., "EN")
- `%TYPE%` → Template style (e.g., "plain")
- `%REVNUMBER%` → Version (e.g., "9.0-EN")
- `%REVDATE%` → Date (e.g., "July 2025")
- `%REVREMARK%` → Remark (e.g., "(based upon AsciiDoc version)")

### Key Tasks

#### copyAsciidoc
Copy generated AsciiDoc to build output directory (makes it available in distributions).

#### copyImages
Conditionally copy images/ directory based on format requirements:
```groovy
if (config.formats[format].imageFolder) {
    // Copy images for this format
}
```

Special case for mkdocs: images go to `docs/images` instead of `images`.

#### generateHTML
Direct AsciiDoc → HTML conversion using Asciidoctor plugin:
```groovy
task generateHTML (type: AsciidoctorTask) {
    backends "html5"
    attributes(
        toc: 'left',
        doctype: 'book',
        icons: 'font',
        // ... version metadata
    )
}
```

**Why direct?** Asciidoctor produces higher-quality HTML than DocBook → HTML route.

#### generateDocbook
AsciiDoc → DocBook XML for Pandoc pipeline:
```groovy
task generateDocbook (type: AsciidoctorTask) {
    backends 'docbook'
}
```

#### generateDocbookMP (Multi-Page)
Same as generateDocbook but splits sections into separate XML files for multi-page formats.

#### Format Conversion Tasks (convert2Markdown, convert2Docx, etc.)
Pattern for each format:
```groovy
task convert2Docx (
    dependsOn: [copyImages, generateDocbook],
    type: Exec
) {
    executable = "pandoc"
    args = ['-r','docbook',
            '-t','docx',
            '-o', localBuildDir.docx + '/arc42-template-' + language + '.docx',
            localBuildDir.docbook + '/arc42-template.xml']
}
```

**Multi-page variants** (markdownMP, mkdocsMP, etc.):
- Process each XML file from DocBookMP separately
- Create dynamic tasks at configuration time
- Use `mustRunAfter` to sequence execution

Special handling:
- **Russian language**: Extra LaTeX font encoding (`-V fontenc=T1,T2A`)
- **Mkdocs**: Creates mkdocs.yml config, uses markdown_phpextra variant

#### arc42 Task (Aggregator)
```groovy
task arc42(
    dependsOn: [
        copyImages, generateHTML, convert2Latex, convert2Docx,
        convert2Epub, convert2Rst, convert2Markdown, convert2MarkdownMP,
        // ... all format tasks
    ]
)
```

Runs all conversions for this language/style combination.

### Dependencies
- **Uses**: Asciidoctor Gradle plugin (org.asciidoctor.jvm.convert)
- **Executes**: Pandoc (external tool)
- **Reads**: Generated AsciiDoc from build/src_gen/
- **Writes**: build/{LANG}/{FORMAT}/{STYLE}/

### Key Constraints
- Pandoc must be in PATH
- DocBook must be generated before Pandoc conversions
- Multi-page tasks dynamically created (harder to debug)

---

## Component: Automation Script

**Location**: `build-arc42.sh`

### Responsibility
Provide one-command full build for users unfamiliar with the multi-step process.

### Script Steps
```bash
#!/bin/bash
echo "Building arc42 template"

# 1. Install Pandoc
echo "install pandoc"
wget https://github.com/jgm/pandoc/releases/download/3.7.0.2/pandoc-3.7.0.2-1-amd64.deb
sudo dpkg -i pandoc-3.7.0.2-1-amd64.deb

# 2. Initialize submodules
echo "init and update submodules"
git submodule init
git submodule update
cd arc42-template
git checkout master
git pull
cd ..

# 3. Full build pipeline
echo "build arc42 template"
./gradlew createTemplatesFromGoldenMaster
./gradlew arc42
./gradlew createDistribution

echo "please check the results in arc42-template/dist"
echo "and if ok, add, commit and push it"
```

### Why This Matters
- Handles Pandoc installation automatically
- Ensures submodules are up-to-date
- Executes build phases in correct order
- Provides feedback at each step

### Dependencies
- **Requires**: wget, sudo (for Pandoc installation)
- **Assumes**: Debian/Ubuntu Linux (uses .deb package)

### Limitations
- Linux-specific (Pandoc .deb installation)
- Requires sudo access
- Hardcoded Pandoc version

**Alternative for other OS**: Manual Pandoc installation, then run Gradle commands.

---

## Component: Publishing

**Location**: `publish/build.gradle`

### Responsibility
Deploy generated distributions to GitHub Pages (or other hosting).

### Configuration
```groovy
apply plugin: 'github-pages'

githubPages {
    repoUri = 'https://github.com/rdmueller/arc42-template.git'
    targetBranch = 'gh-pages'
    pages {
        from('build/dist')
    }
    credentials {
        username = System.getenv('GH_TOKEN')
        password = ''
    }
}
```

**Note**: Currently points to rdmueller's fork, not arc42 organization. This may be historical or for testing.

### Usage
```bash
./gradlew publish:publishGhPages
```

### Dependencies
- **Plugin**: org.ajoberstar:gradle-git
- **Requires**: GH_TOKEN environment variable
- **Publishes**: build/dist contents to gh-pages branch

### Current Status
Separate subproject, not integrated into main build flow. Likely manual/optional step.

---

## Component Dependencies

```
build-arc42.sh
    ↓
    ├─→ Pandoc (install)
    ├─→ Git Submodule (update)
    └─→ Gradle Tasks:
            ↓
        build.gradle (root)
            ↓
            ├─→ buildconfig.groovy (config)
            ├─→ createTemplatesFromGoldenMaster
            │       ↓
            │   [creates build/src_gen/]
            │       ↓
            └─→ settings.gradle (discovers subprojects)
                    ↓
                ┌───┴───┬───────┬────────┐
                ↓       ↓       ↓        ↓
            EN:plain  EN:with-help  DE:plain  ...
                │
                └─→ subBuild.gradle (copied & substituted)
                        ↓
                    ├─→ Asciidoctor Plugin
                    └─→ Pandoc (exec tasks)
                            ↓
                        [outputs to build/{LANG}/{FORMAT}/]
                            ↓
        createDistribution
            ↓
        [creates arc42-template/dist/*.zip]
```

---

## Key Interfaces Between Components

| From Component | To Component | Interface | Data |
|----------------|--------------|-----------|------|
| build.gradle | buildconfig.groovy | Read config | Template styles, formats, paths |
| build.gradle | arc42-template/ | Read files | AsciiDoc sources, images |
| build.gradle | build/src_gen/ | Write files | Filtered AsciiDoc variants |
| settings.gradle | build/src_gen/ | Scan directory | Discover languages/styles |
| settings.gradle | subBuild.gradle | Copy & substitute | Create subproject builds |
| settings.gradle | version.properties | Read file | Version metadata per language |
| subBuild.gradle | Asciidoctor | Plugin API | Convert AsciiDoc → HTML/DocBook |
| subBuild.gradle | Pandoc | CLI exec | Convert DocBook → formats |
| subBuild.gradle | build/{LANG}/ | Write files | Converted formats |
| createDistribution | build/{LANG}/ | Read files | Source for ZIP archives |
| createDistribution | arc42-template/dist/ | Write ZIPs | Final distributions |

---

## Build Phases (Temporal View)

```
Phase 1: Template Generation
  build.gradle:createTemplatesFromGoldenMaster
    └─→ Outputs: build/src_gen/

Phase 2: Subproject Discovery
  settings.gradle (automatic Gradle evaluation)
    └─→ Creates: Subprojects :EN:plain, :DE:with-help, etc.

Phase 3: Format Conversion
  Each subproject:subBuild.gradle:arc42
    ├─→ Asciidoctor → HTML, DocBook
    └─→ Pandoc → DOCX, Markdown, EPUB, etc.
    └─→ Outputs: build/{LANG}/{FORMAT}/{STYLE}/

Phase 4: Distribution
  build.gradle:createDistribution
    └─→ Outputs: arc42-template/dist/*.zip

Phase 5: Publishing (optional)
  publish/build.gradle:publishGhPages
    └─→ Uploads to GitHub Pages
```

Each phase must complete before the next begins (enforced by Gradle dependencies).
