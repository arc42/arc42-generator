# Cross-Cutting Concepts and Mental Models

> **Peter Naur's Insight**: "The real program is the theory in the developers' minds, not the code itself."

This document captures the essential mental models you need to internalize to work effectively with the arc42-generator. These are the concepts that "everyone knows" but are rarely written down - the theory that makes the code make sense.

---

## Core Metaphor: Template Compiler System

**Think of arc42-generator as a compiler**, not just a build system:

```
Traditional Compiler          arc42-generator
────────────────────         ──────────────────
Source Code            ←→    Golden Master (AsciiDoc)
Preprocessor Directives ←→    Feature Flags (ifdef, role="arc42help")
Compiler Front-End     ←→    Asciidoctor (parsing & validation)
Intermediate Repr.     ←→    DocBook XML
Compiler Back-End      ←→    Pandoc (code generation)
Target Platforms       ←→    Output Formats (DOCX, HTML, Markdown, etc.)
Build Targets          ←→    Languages (EN, DE, FR, etc.)
Compiled Artifacts     ←→    Template ZIPs
Object Files           ←→    build/src_gen/ (generated intermediates)
Executables            ←→    Distributions in arc42-template/dist/
```

**Why this metaphor matters**:
- You don't edit compiled output → Don't edit build/ directory
- Changing source requires recompilation → Changing Golden Master requires rebuild
- Different optimization levels → Different template styles (plain vs. with-help)
- Compiler flags control features → Feature flags control content variants
- One source, many targets → One Golden Master, 300+ templates

**When this metaphor breaks down**:
- Unlike compilers, we commit "compiled" output (distributions) to Git
- Unlike compilers, intermediate files (DocBook) are part of the process, not hidden
- Unlike compilers, "compilation" is manual/triggered, not automatic on save

---

## Must-Understand Concepts

### Concept 1: The Golden Master Pattern

**Level**: 0-Fundamental
**Priority**: CRITICAL
**Learning Time**: 30 minutes

#### What It Is
A single source file contains ALL variants of content, marked with feature flags. The build system filters out unwanted content to create specific variants.

#### Why It Matters
This is the FOUNDATIONAL concept. If you don't understand Golden Master, nothing else makes sense.

**Before Golden Master**:
```
plain/01_introduction.adoc      (1,000 lines)
with-help/01_introduction.adoc  (1,800 lines - duplicates 1,000)
with-examples/01_introduction.adoc (2,500 lines - duplicates 1,800)
```

Change structure → Must manually sync 3 files. Miss one? Variants diverge.

**With Golden Master**:
```
golden-master/01_introduction.adoc (2,500 lines total)

[role="arc42help"]
****
This is help text, appears in with-help and with-examples.
****

[role="arc42example"]
****
This is an example, appears only in with-examples.
****
```

Change structure → ONE file update. Generator creates all 3 variants automatically.

#### How It Works
1. Author writes Golden Master with feature markers
2. Build system reads feature flags from config:
   ```groovy
   'plain': [],  // Remove ALL features
   'with-help': ['help'],  // Keep help, remove examples
   ```
3. Regex removes unwanted content:
   ```groovy
   template = template.replaceAll(
       /(?ms)\[role="arc42help"\][ \r\n]+[*]{4}.*?[*]{4}/,
       ''
   )
   ```
4. Filtered content written to `build/src_gen/{STYLE}/`

#### Common Mistakes Newcomers Make

**Mistake #1: Editing Generated Files**
```bash
# WRONG - editing generated file
vim build/src_gen/EN/asciidoc/plain/src/01_introduction.adoc
```
Next build → Your changes disappear. Build system regenerates from Golden Master.

**Correct**:
```bash
# RIGHT - edit source
vim arc42-template/EN/asciidoc/src/01_introduction.adoc
./gradlew createTemplatesFromGoldenMaster  # Regenerate
```

**Mistake #2: Adding Content Outside Feature Blocks**
Adding help text directly in Golden Master without `[role="arc42help"]`:
```asciidoc
=== Section

Here's some help text...  ← Shows in ALL variants (wrong!)
```

**Correct**:
```asciidoc
=== Section

[role="arc42help"]
****
Here's some help text...  ← Only in with-help variant
****
```

**Mistake #3: Incorrect Feature Block Syntax**
```asciidoc
[role="arc42help"]
***  ← WRONG - needs 4 asterisks
Help text
***
```

Regex won't match → Help text appears in plain variant (bug!).

**Correct**: Exactly 4 asterisks:
```asciidoc
[role="arc42help"]
****  ← RIGHT - 4 asterisks
Help text
****
```

#### Validation Question
**Q**: You want to add explanatory text that appears in "with-help" but not "plain". Where and how do you add it?

**A**: In arc42-template/{LANG}/asciidoc/src/{section}.adoc, wrapped in:
```asciidoc
[role="arc42help"]
****
Your explanatory text here
****
```

#### Code Locations
- Feature flag definitions: `buildconfig.groovy:8-14`
- Removal logic: `build.gradle:88-94`
- Golden Master sources: `arc42-template/{LANG}/asciidoc/src/*.adoc`

#### Prerequisites
- Understanding of AsciiDoc syntax
- Basic regex knowledge (helpful but not required)

#### Enables
- Understanding subproject generation (builds on variant concept)
- Understanding distribution packaging (packages filtered variants)

---

### Concept 2: The Chicken-and-Egg Problem (Gradle Build Lifecycle)

**Level**: 1-Architectural
**Priority**: HIGH
**Learning Time**: 20 minutes

#### What It Is
Gradle's build lifecycle has a critical ordering constraint: `settings.gradle` evaluates BEFORE any tasks run, but needs OUTPUT from `createTemplatesFromGoldenMaster` task.

```
Normal Build:              Our Requirement:
─────────────              ─────────────────
1. settings.gradle ───┐    1. createTemplatesFromGoldenMaster (creates structure)
2. build.gradle       │    2. settings.gradle (discovers structure)
3. Tasks execute      └────3. Tasks execute
   ↑                       ↑
   └─ Can't happen first   └─ MUST happen first!
```

#### Why It Matters
This is why you can't just run `./gradlew arc42` on a fresh clone. Understanding this prevents frustrating "task not found" errors.

#### How We Solve It
**Solution**: Conditional subproject discovery in settings.gradle:

```groovy
def target = file(config.goldenMaster.targetPath)  // build/src_gen/

if (target.exists()) {
    // Only discover subprojects if structure exists
    target.eachFileRecurse { ... }
} else {
    // Silent - no subprojects registered yet
}
```

**Required Workflow**:
```bash
# Fresh clone - build/src_gen/ doesn't exist
./gradlew arc42
# ERROR: Task ':EN:plain:arc42' not found

# Correct workflow:
./gradlew createTemplatesFromGoldenMaster  # Creates build/src_gen/
# Gradle re-evaluates settings.gradle automatically
./gradlew arc42  # NOW subprojects exist
```

**Or use the automation script**:
```bash
./build-arc42.sh  # Handles sequencing for you
```

#### Common Mistakes

**Mistake #1: Running arc42 First**
```bash
git clone ...
cd arc42-generator
./gradlew arc42  # FAILS - no subprojects yet
```

**Mistake #2: Expecting IDE to Show Subprojects Immediately**
IntelliJ/Eclipse won't show :EN:plain, :DE:with-help projects until after createTemplatesFromGoldenMaster runs.

**Mistake #3: Deleting build/ and Expecting It to Recover**
```bash
rm -rf build/
./gradlew arc42  # FAILS - subprojects gone
```

Must re-run createTemplatesFromGoldenMaster.

#### Why Can't Gradle Fix This?
Gradle's design: Settings phase → Configuration phase → Execution phase.

Tasks run in Execution phase. Settings phase happens BEFORE tasks can run. By design.

Our workaround (conditional discovery) is the idiomatic Gradle solution.

#### Validation Question
**Q**: You've deleted the build/ directory. What command must you run before `./gradlew arc42` will work?

**A**: `./gradlew createTemplatesFromGoldenMaster` to recreate build/src_gen/ so settings.gradle can discover subprojects.

#### Code Locations
- Conditional discovery: `settings.gradle:35-60`
- Automation handling: `build-arc42.sh:13-15`

#### Prerequisites
- Basic Gradle knowledge (build lifecycle phases)
- Understanding of file system operations

#### Enables
- Debugging "task not found" errors
- Understanding why build-arc42.sh sequences commands
- Contributing new subproject logic

---

### Concept 3: Two-Stage Conversion Pipeline (DocBook as Lingua Franca)

**Level**: 1-Architectural
**Priority**: CRITICAL
**Learning Time**: 15 minutes

#### What It Is
Instead of converting AsciiDoc directly to target formats, we use DocBook XML as an intermediate representation:

```
AsciiDoc  ──(Asciidoctor)──>  DocBook XML  ──(Pandoc)──>  DOCX/Markdown/EPUB/LaTeX/...
```

**Why not direct?**
```
AsciiDoc  ──(Pandoc)──X──>  DOCX  ← Pandoc's AsciiDoc support is basic
AsciiDoc  ──(Asciidoctor)──>  DOCX  ← Asciidoctor only supports HTML/DocBook/PDF directly
```

#### Why It Matters
This explains:
- Why DocBook files appear in build/ directory
- Why format conversion is slower (two steps instead of one)
- Why adding a new format doesn't require coding (just add Pandoc target)
- Where to look when output quality issues arise

#### How It Works
**Phase 1: AsciiDoc → DocBook**
```groovy
task generateDocbook (type: AsciidoctorTask) {
    backends 'docbook'
    outputDir = new File(localBuildDir.docbook)
}
```

Produces: `build/{LANG}/docbook/{STYLE}/arc42-template.xml`

**Phase 2: DocBook → Target Format**
```groovy
task convert2Docx (type: Exec) {
    executable = "pandoc"
    args = ['-r','docbook',           // Read DocBook
            '-t','docx',              // Write DOCX
            '-o', outputFile,
            inputDocBookFile]
}
```

**Exception: HTML**
Direct path for quality:
```
AsciiDoc  ──(Asciidoctor)──>  HTML5
```

Asciidoctor's HTML output is excellent, no need for DocBook intermediary.

#### Common Mistakes

**Mistake #1: Assuming Direct Conversion**
"I'll add Rust support by making Pandoc read AsciiDoc directly."

**Problem**: Pandoc's AsciiDoc support is limited. Will produce low-quality output.

**Correct**: Add Rust as Pandoc output target (reads DocBook):
```groovy
task convert2Rust (
    dependsOn: [generateDocbook],
    type: Exec
) {
    executable = "pandoc"
    args = ['-r','docbook', '-t','rust', ...]
}
```

**Mistake #2: Modifying DocBook Files**
Editing `build/{LANG}/docbook/arc42-template.xml` to fix output issues.

**Problem**: DocBook is regenerated on every build.

**Correct**: Fix the issue in Golden Master AsciiDoc or adjust Pandoc conversion args.

**Mistake #3: Expecting Fast Conversion**
"Why is conversion slow? Pandoc is fast."

**Reality**: Two-stage means two passes over content. This is the price of quality.

#### Why DocBook?
**Historical Context**: DocBook was designed as a semantic markup for technical documentation. It's perfect for:
- Preserving document structure
- Handling complex tables, lists, code blocks
- Supporting multiple output formats

**Benefits**:
- Asciidoctor's DocBook output captures ALL AsciiDoc semantics
- Pandoc's DocBook reader is mature and well-tested
- DocBook is a stable standard (changes rarely)

**Downsides**:
- Extra processing step
- Intermediate files consume disk space
- Debugging requires understanding DocBook structure

#### Validation Question
**Q**: You want to add support for exporting to reStructuredText (RST). Which tool(s) are involved and in what order?

**A**:
1. Asciidoctor converts AsciiDoc → DocBook (existing task: generateDocbook)
2. Pandoc converts DocBook → RST (new task: convert2Rst, dependsOn: generateDocbook)

No need to touch AsciiDoc parsing logic.

#### Code Locations
- DocBook generation: `subBuild.gradle:105-129`
- Pandoc conversions: `subBuild.gradle:174-512`
- HTML exception: `subBuild.gradle:91-103`

#### Prerequisites
- Understanding of document formats (HTML, XML, etc.)
- Basic command-line tool usage

#### Enables
- Adding new output formats
- Debugging format conversion issues
- Understanding performance characteristics

---

### Concept 4: Feature Flag Processing (Regex Surgery)

**Level**: 2-Implementation
**Priority**: MEDIUM
**Learning Time**: 20 minutes

#### What It Is
Feature flags in Golden Master are removed using regular expressions, not AsciiDoc preprocessing. This is "string surgery" - cutting out unwanted text blocks.

#### Why It Matters
Understanding the regex patterns is essential for:
- Adding new feature types
- Debugging why content appears in wrong variants
- Understanding fragility and edge cases

#### The Regex Pattern
```groovy
def featuresToRemove = allFeatures - featuresWanted
// For "plain": remove=['help', 'example']
// For "with-help": remove=['example']

featuresToRemove.each { feature ->
    template = template.replaceAll(
        /(?ms)\[role="arc42${feature}"\][ \r\n]+[*]{4}.*?[*]{4}/,
        ''
    )
}
```

**Breaking it down**:
- `(?ms)`: Multiline mode, dot matches newlines
- `\[role="arc42${feature}"\]`: Match `[role="arc42help"]` literally
- `[ \r\n]+`: Match whitespace/newlines after role attribute
- `[*]{4}`: Match exactly 4 asterisks (AsciiDoc block delimiter)
- `.*?`: Non-greedy match of ANY content
- `[*]{4}`: Match closing 4 asterisks

**Special case for ifdef/endif**:
```groovy
if ("help" in featuresToRemove) {
    template = template.replaceAll(/(?ms)ifdef::arc42help\[\]/, '')
    template = template.replaceAll(/(?ms)endif::arc42help\[\]/, '')
}
```

#### Why Regex Instead of AsciiDoc Preprocessing?

**Alternative**: Use AsciiDoc's attribute system:
```asciidoc
ifdef::with-help[]
Help text here
endif::[]
```

Then build with: `asciidoctor -a with-help template.adoc`

**Why NOT chosen**:
1. Would require running Asciidoctor twice (once per variant)
2. Less control over exact feature combinations
3. Harder to implement "plain" (which is absence of features)
4. Regex gives us complete control

**Downside of Regex**:
- Fragile: Must match exact syntax
- No semantic understanding (just string matching)
- Edge cases can break matching

#### Common Mistakes

**Mistake #1: Inconsistent Asterisk Count**
```asciidoc
[role="arc42help"]
***  ← 3 asterisks, regex expects 4
Help text
***
```

**Result**: Regex doesn't match → Help text appears in plain variant.

**Detection**: Test both variants, compare content.

**Mistake #2: Whitespace Inside Block Delimiter**
```asciidoc
[role="arc42help"]
* * * *  ← Spaces between asterisks
Help text
* * * *
```

**Result**: Regex doesn't match `[*]{4}` → Help text leaks.

**Mistake #3: Nested Blocks**
```asciidoc
[role="arc42help"]
****
Outer help
****
Inner content
****
More help
****
```

**Result**: Non-greedy match stops at first `****` → Partial removal, syntax errors.

**Solution**: Never nest blocks. Use single block per feature.

#### Validation Question
**Q**: You've added help text with this syntax:
```asciidoc
[role="arc42help"]
*****  ← 5 asterisks
Help text
*****
```

Will it be correctly removed from the "plain" variant?

**A**: NO. Regex expects exactly `[*]{4}`. This won't match, help text will appear in plain variant.

#### Code Locations
- Regex patterns: `build.gradle:88-94`
- Feature definitions: `buildconfig.groovy:7`
- Template styles: `buildconfig.groovy:9-14`

#### Prerequisites
- Golden Master Pattern (Concept 1)
- Basic regex knowledge (helpful but pattern is provided)

#### Enables
- Adding new feature types (e.g., "arc42advanced")
- Debugging variant content issues
- Contributing to feature flag logic

---

### Concept 5: Dynamic Subproject Generation (Convention Over Configuration)

**Level**: 1-Architectural
**Priority**: HIGH
**Learning Time**: 25 minutes

#### What It Is
Gradle subprojects are NOT statically defined in settings.gradle. Instead, they're discovered by scanning the directory structure created by createTemplatesFromGoldenMaster.

**Convention**:
```
build/src_gen/
  └── {LANG}/
      └── asciidoc/
          └── {STYLE}/
              └── src/  ← Presence of src/ triggers subproject creation
```

**Result**: Subproject named `:{LANG}:{STYLE}` (e.g., `:EN:plain`)

#### Why It Matters
This is why:
- Adding a new language requires minimal code changes
- All languages get identical build logic
- You don't see hardcoded lists of languages everywhere

#### How It Works
**Discovery Loop** (settings.gradle):
```groovy
target.eachFileRecurse { f ->
    if (f.name == 'src') {  // Found a template directory!
        // Extract language/style from path
        def parentFilePath = f.parentFile.path
        def language = parentFilePath.split('[/\\\\]')[-3]  // e.g., "EN"
        def docFormat = parentFilePath.split('[/\\\\]')[-1]  // e.g., "plain"

        // Create build.gradle for this subproject
        // (copy subBuild.gradle template, substitute placeholders)

        // Register subproject
        include("${language}:${docFormat}")
    }
}
```

**Path Parsing**:
```
/workspaces/arc42-generator/build/src_gen/EN/asciidoc/plain/src
                                           │           │
                                           │           └─ [-1] = "plain"
                                           └─────────────── [-3] = "EN"
```

Relies on consistent directory structure.

#### Why Convention Over Configuration?

**Alternative - Static Configuration**:
```groovy
// Would need to list every combination:
['DE','EN','FR','CZ'].each { lang ->
    ['plain','with-help'].each { style ->
        include("${lang}:${style}")
    }
}
```

**Problems**:
- Must update when adding language
- Doesn't enforce that files exist
- Can't incorporate per-language metadata easily

**Our Approach**:
- Directory structure IS the configuration
- Self-documenting (directory layout shows what exists)
- Adding language: Just add files, rebuild

#### The Placeholder Substitution Mechanism

Once a subproject is discovered, its build.gradle is created:

```groovy
new File(parentFilePath + "/build.gradle")
    .write(
        new File("subBuild.gradle").text  // Read template
            .replaceAll('%LANG%', language)
            .replaceAll('%TYPE%', docFormat)
            .replaceAll('%REVNUMBER%', versionProps.revnumber)
            // ... more substitutions
    )
```

**subBuild.gradle template snippet**:
```groovy
def language = '%LANG%'  ← Replaced with "EN"
def projectName = '%TYPE%'  ← Replaced with "plain"

attributes(
    revnumber: '%REVNUMBER%',  ← Replaced with "9.0-EN"
    // ...
)
```

**Result**: Each subproject gets language-specific values.

#### Common Mistakes

**Mistake #1: Assuming Subprojects Exist Immediately**
```bash
git clone ...
cd arc42-generator
./gradlew :EN:plain:generateHTML  # FAILS - subproject doesn't exist yet
```

**Correct**: Run createTemplatesFromGoldenMaster first.

**Mistake #2: Manually Creating Subproject Directories**
```bash
mkdir -p build/src_gen/ES/asciidoc/plain/src
./gradlew :ES:plain:arc42  # FAILS - no content, no build.gradle
```

**Correct**: Add ES to Golden Master, run createTemplatesFromGoldenMaster.

**Mistake #3: Modifying Generated build.gradle**
```bash
vim build/src_gen/EN/asciidoc/plain/build.gradle
# Add custom task...
./gradlew createTemplatesFromGoldenMaster  # Your changes lost!
```

**Correct**: Modify subBuild.gradle template (affects ALL subprojects).

#### Validation Question
**Q**: You've added Spanish (ES) Golden Master files to arc42-template/ES/. What additional code changes are needed in the build system to generate ES templates?

**A**: Currently, must add 'ES' to the hardcoded language list in build.gradle:41:
```groovy
languages=['DE','EN', 'FR', 'CZ', 'ES']
```

(Ideally none would be needed - open question why auto-discovery is overridden)

#### Code Locations
- Discovery loop: `settings.gradle:36-60`
- Placeholder substitution: `settings.gradle:44-52`
- Template: `subBuild.gradle` (entire file)

#### Prerequisites
- Understanding of Gradle subprojects concept
- File path manipulation
- Chicken-and-Egg Problem (Concept 2)

#### Enables
- Adding new languages
- Understanding build parallelization
- Customizing per-language build logic

---

## Unwritten Rules

These are the conventions that "everyone knows" but are never explicitly documented.

### Rule 1: Never Edit build/ Directory
**What**: Anything in `build/` is ephemeral and regenerated.
**Why**: Build output is derived from sources. Editing it breaks the source-of-truth principle.
**Exception**: None. Even for "quick fixes."

### Rule 2: Always Run createTemplatesFromGoldenMaster First
**What**: On fresh clone or after deleting build/, run this task before any others.
**Why**: Chicken-and-Egg problem - other tasks depend on build/src_gen/ existing.
**Shortcut**: Use `./build-arc42.sh` which handles sequencing.

### Rule 3: Common Files Copied Per Language
**What**: `arc42-template/common/` gets copied to each language's build directory.
**Why**: Each language build must be self-contained (can build independently).
**Impact**: Changes to common files require full rebuild of all languages.

### Rule 4: Distribution ZIPs Committed to Submodule
**What**: Generated arc42-template/dist/*.zip files are committed to arc42-template repo, not generator repo.
**Why**: Historical - users download from arc42-template, that's the public interface.
**Unusual**: Most projects don't commit generated files.

### Rule 5: Version Properties Are Per-Language
**What**: Each language has `version.properties` with revnumber, revdate, revremark.
**Why**: Languages may be at different versions (e.g., new language added at 9.0, older ones at 8.5).
**Location**: `arc42-template/{LANG}/version.properties`

### Rule 6: Pandoc Version Pinned
**What**: build-arc42.sh installs specific Pandoc version (3.7.0.2).
**Why**: Different Pandoc versions can produce different output. Pinning ensures consistency.
**Risk**: If system Pandoc is different version, output may vary.

### Rule 7: Image Handling Varies by Format
**What**: Some formats need separate images/ directory, others embed images.
**Where Defined**: `buildconfig.groovy` `imageFolder` flag per format.
**Impact**: Can't assume images are always copied.

### Rule 8: Multi-Page Formats Use Dynamic Task Creation
**What**: markdownMP, mkdocsMP, etc. create tasks at configuration time, not hardcoded.
**Why**: Number of files varies (each section becomes a file).
**Debug**: Harder to troubleshoot (tasks don't exist until config time).

---

## Failed Experiments

Learning from what DIDN'T work is as important as understanding what did.

### Experiment 1: Direct Pandoc Conversion (AsciiDoc → Formats)
**Tried**: Using Pandoc's AsciiDoc reader directly:
```bash
pandoc -f asciidoc -t docx template.adoc
```

**Result**: Low quality output, lost AsciiDoc features.
**Why Failed**: Pandoc's AsciiDoc support is basic compared to Asciidoctor.
**Lesson**: Use right tool for each step - Asciidoctor for AsciiDoc, Pandoc for format conversion.
**Evidence**: Two-stage pipeline (Decision 3) is our solution.

### Experiment 2: Pure Auto-Discovery of Languages
**Tried**: Let code auto-detect languages from arc42-template/:
```groovy
new File(config.goldenMaster.sourcePath).eachDir { dir ->
    if (dir.name =~ /^[A-Z]{2}$/) {
        languages << dir.name
    }
}
```

**Result**: Code still exists, but is overridden by hardcoded list:
```groovy
languages=['DE','EN', 'FR', 'CZ']  // Override
```

**Why Failed?**: Open question. Likely:
- Quality control (not all language dirs are complete)
- Explicit control over which languages to build
- Testing incomplete translations

**Lesson**: Sometimes explicit configuration beats auto-magic.
**Evidence**: `build.gradle:36-41`

### Experiment 3: [To be discovered from git history]
**Open Question**: What other approaches were tried? Check git history for:
- "fix common folder" commits
- Reverted changes
- Commented-out code

---

## Cross-Cutting Concerns

### Error Handling
**Strategy**: Fail fast with clear messages.

**Example**:
```groovy
if (!propFile.exists()) {
    throw new GradleException(
        "Version properties file not found for language ${language}: ${propFile}"
    )
}
```

**Why**: Better to stop immediately than continue with bad data.

**Task Dependencies**: Gradle's dependency system prevents running tasks out of order:
```groovy
task convert2Docx (dependsOn: [copyImages, generateDocbook]) {
    // Can't run without DocBook existing
}
```

### Logging
**Levels**:
- `logger.lifecycle`: User-facing messages (visible by default)
- `logger.info`: Detailed progress (only with `--info`)
- `logger.debug`: Diagnostic details (only with `--debug`)

**Example**:
```groovy
logger.lifecycle "create %buildDir%${target.path - buildDirectory}"
```

**Why Lifecycle?**: Users need to see what's being generated.

### Version Management
**Each Language Independent**:
```properties
# arc42-template/EN/version.properties
revnumber=9.0-EN
revdate=July 2025
```

**Substituted Into**:
- AsciiDoc document attributes
- Distribution ZIP filenames
- Generated document metadata

**Rationale**: Different languages may be at different maturity levels.

### Image Management
**Strategy**: Conditional copy based on format:

```groovy
if (config.formats[format].imageFolder) {
    copy {
        from file('images')
        into file(dir + '/images')
    }
}
```

**Why Conditional?**:
- DOCX, EPUB embed images (don't need folder)
- HTML, Markdown need external image files
- Saves space and time by not copying unnecessarily

**Plain vs. With-Help**:
- Plain: Only arc42 logo
- With-Help: Logo + example images

### Language-Specific Handling
**Russian LaTeX**: Special font encoding:
```groovy
if (language=='RU') {
    args += ['-V','fontenc=T1,T2A']
}
```

**Why**: Cyrillic characters need specific LaTeX font encoding.

**Pattern**: Check language code, add format-specific args.

---

## Thinking Like the System

To truly internalize the system, practice these mental models:

### When Reading Code:
1. **Ask "Which Phase?"**: Is this Phase 1 (generation), Phase 2 (discovery), Phase 3 (conversion), or Phase 4 (packaging)?
2. **Follow the Data**: What's the input? What's the output? Where does it go next?
3. **Check the Convention**: Is this following "convention over configuration" or explicit config?

### When Debugging:
1. **Did createTemplatesFromGoldenMaster Run?**: Check if build/src_gen/ exists and has content.
2. **Are Subprojects Registered?**: Run `./gradlew projects` to see what Gradle sees.
3. **Is Pandoc Available?**: Run `pandoc --version` to verify.
4. **Check the Regex**: Does the feature block syntax match the pattern exactly?

### When Adding Features:
1. **Which Layer?**: Root build? Subproject build? Configuration?
2. **Which Phase?**: Does this affect generation, conversion, or packaging?
3. **Convention or Config?**: Can it be discovered or must it be explicit?

### When Reviewing Changes:
1. **Source of Truth**: Is the change in the right place (Golden Master vs. generator)?
2. **All Variants**: Will this work for ALL languages? ALL formats?
3. **Build Sequence**: Does the change respect the phase ordering?

---

## Mental Model Validation Checklist

Test your understanding:

- [ ] Can you explain why editing build/src_gen/ is always wrong?
- [ ] Can you draw the data flow from Golden Master to Distribution?
- [ ] Can you explain the chicken-and-egg problem in your own words?
- [ ] Can you identify which features would be in a "plain" variant?
- [ ] Can you predict what happens if you run `./gradlew arc42` before `createTemplatesFromGoldenMaster`?
- [ ] Can you explain why we use DocBook as intermediate format?
- [ ] Can you locate where language-specific version info is stored?
- [ ] Can you describe what "convention over configuration" means in this system?

If you answered all of these confidently, you've internalized the theory of the system.

---

## Next Steps for Learning

1. **Week 1**: Read this document completely. Run a full build. Observe each phase.
2. **Week 2**: Make a small change to Golden Master. Rebuild. Verify all variants updated.
3. **Week 3**: Add a new output format (e.g., RST). Understand the conversion pipeline.
4. **Week 4**: Debug a build issue using these mental models. Teach someone else.

The system will make sense when you stop thinking about "what the code does" and start thinking about "what problem this architecture solves."

That's the theory. That's what makes it a program, not just code.
