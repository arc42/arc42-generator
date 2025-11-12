# Antipatterns and Common Mistakes

This document catalogs common mistakes when working with arc42-generator, showing what NOT to do and why, with correct alternatives.

**Format**: Each antipattern includes:
- ❌ **Antipattern Name**: The mistake
- **Why This is Wrong**: Underlying reason
- ✅ **Correct Approach**: What to do instead
- **Code Example**: Side-by-side comparison when applicable
- **Related Concepts**: Links to relevant documentation

---

## Antipattern 1: Editing Generated Files

### ❌ Antipattern: Editing Files in `build/` Directory

**Scenario**: Developer edits `build/src_gen/EN/asciidoc/plain/src/01_introduction.adoc` to fix template content.

**Why This is Wrong**:
1. **Ephemeral location**: `build/` directory is recreated on clean
2. **Overwritten on rebuild**: Next `createTemplatesFromGoldenMaster` overwrites changes
3. **Not version controlled**: Changes lost, not shared with team
4. **Wrong source**: Golden Master is the source of truth, not generated files

**What Happens**:
```bash
# Developer edits generated file
vim build/src_gen/EN/asciidoc/plain/src/01_introduction.adoc
# Makes changes, seems to work

# Later, someone runs:
./gradlew clean createTemplatesFromGoldenMaster
# Changes are GONE! 💥
```

### ✅ Correct Approach

**Edit the Golden Master source**:

```bash
# Edit the source file in the submodule
vim arc42-template/EN/asciidoc/src/01_introduction_and_goals.adoc

# Regenerate from Golden Master
./gradlew createTemplatesFromGoldenMaster

# Verify changes in generated files
diff arc42-template/EN/asciidoc/src/01_introduction_and_goals.adoc \
     build/src_gen/EN/asciidoc/plain/src/01_introduction.adoc
```

**Commit in the correct place**:
```bash
cd arc42-template
git add EN/asciidoc/src/01_introduction_and_goals.adoc
git commit -m "Update introduction section"
git push

cd ..
git add arc42-template  # Update submodule reference
git commit -m "Update arc42-template to include introduction changes"
```

**Related**: [Concepts: Golden Master Pattern](../arc42/08-concepts.md#concept-1-the-golden-master-pattern), [ADR-001](../arc42/09-decisions/ADR-001-golden-master-pattern.md)

---

## Antipattern 2: Wrong Feature Flag Syntax

### ❌ Antipattern: Using Wrong Number of Asterisks

**Scenario**: Developer wants to add help text visible only in "with-help" variant.

**Wrong Implementation**:
```asciidoc
[role="arc42help"]
***
This is help text that should only appear in with-help variant.
***
```

**Why This is Wrong**:
- **3 asterisks** are not recognized by the regex pattern
- Help text **leaks into "plain" variant** (should be stripped)
- Feature flag processing expects **exactly 4 asterisks**
- Subtle visual difference makes bug hard to spot

**What Happens**:
```bash
./gradlew createTemplatesFromGoldenMaster
# No error, silently fails

# Check plain variant:
cat build/src_gen/EN/asciidoc/plain/src/01_introduction.adoc
# 💥 Help text is there! Should have been removed!
```

### ✅ Correct Approach

**Use exactly 4 asterisks**:

```asciidoc
[role="arc42help"]
****
This is help text that ONLY appears in with-help variant.
It will be removed from the plain variant.
****
```

**Verify the regex pattern**:
```groovy
// From build.gradle:89-94
def regexArc42Help = /(?ms)\[role="arc42help"\]\s*?\*\*\*\*.*?\*\*\*\*/
//                                                   ^^^^      ^^^^
//                                         Exactly 4 asterisks on each side
```

**Test both variants**:
```bash
./gradlew createTemplatesFromGoldenMaster

# Plain should NOT have help text:
grep -c "arc42help" build/src_gen/EN/asciidoc/plain/src/01_introduction.adoc
# Should return: 0

# With-help SHOULD have help text:
grep -c "arc42help" build/src_gen/EN/asciidoc/with-help/src/01_introduction.adoc
# Should return: >0
```

**Related**: [Concepts: Feature Flag Processing](../arc42/08-concepts.md#concept-4-feature-flag-processing-regex-surgery), [Common Issue #2](../onboarding/common-issues.md#issue-2-help-text-appears-in-plain-variant)

---

## Antipattern 3: Running Tasks Out of Order

### ❌ Antipattern: Skipping `createTemplatesFromGoldenMaster`

**Scenario**: Developer runs format conversion tasks directly after `./gradlew clean`.

**Wrong Sequence**:
```bash
./gradlew clean
./gradlew :EN:plain:generateHTML
# ERROR: Project ':EN:plain' not found! 💥
```

**Why This is Wrong**:
1. **Chicken-and-egg problem**: `settings.gradle` scans `build/src_gen/` to discover subprojects
2. **No subprojects exist** until `createTemplatesFromGoldenMaster` runs
3. **Gradle evaluation phase** happens before task execution
4. **Subprojects not registered** in project hierarchy

### ✅ Correct Approach

**Always follow the correct sequence**:

```bash
# 1. Generate templates from Golden Master (creates build/src_gen/)
./gradlew createTemplatesFromGoldenMaster

# 2. Now subprojects exist, can run format tasks
./gradlew :EN:plain:generateHTML

# Or run all formats:
./gradlew arc42
```

**Use automation script**:
```bash
# Handles sequencing automatically:
./build-arc42.sh
```

**Verify subprojects discovered**:
```bash
./gradlew projects | grep -E ":(EN|DE):(plain|with-help)"
```

**Related**: [Concepts: Chicken-and-Egg Problem](../arc42/08-concepts.md#concept-2-the-chicken-and-egg-problem-gradle-build-lifecycle), [Common Issue #1](../onboarding/common-issues.md#issue-1-task-enplainarc42-not-found)

---

## Antipattern 4: Modifying Generated `build.gradle` in Subprojects

### ❌ Antipattern: Editing Subproject Build Files Directly

**Scenario**: Developer wants to change how DOCX conversion works for English templates.

**Wrong Approach**:
```bash
# Edit generated build file:
vim build/src_gen/EN/asciidoc/plain/build.gradle

# Add custom Pandoc options
task convert2Docx(type: Exec) {
    commandLine 'pandoc', '--my-custom-option', ...
}
```

**Why This is Wrong**:
1. **File is regenerated**: Next time `createTemplatesFromGoldenMaster` runs, **overwritten**
2. **Not applied to all languages**: Change only affects EN, not DE, FR, CZ
3. **Not version controlled**: Change lives in ephemeral `build/` directory
4. **Violates DRY**: Would need to edit 8 files (4 languages × 2 styles)

### ✅ Correct Approach

**Edit the template file**:

```bash
# Edit the source template that gets copied to all subprojects:
vim subBuild.gradle

# Find convert2Docx task (around line 280)
task convert2Docx(type: Exec) {
    dependsOn 'convert2Docbook'
    workingDir "$buildDir/%LANG%/docbook/$config.targetType"
    commandLine 'pandoc',
                '-f', 'docbook',
                '-t', 'docx',
                '--my-custom-option',  // ← Add option here
                ...
}
```

**Regenerate all subprojects**:
```bash
./gradlew createTemplatesFromGoldenMaster
# Applies change to ALL languages and styles
```

**Verify across all subprojects**:
```bash
grep "my-custom-option" build/src_gen/*/asciidoc/*/build.gradle
# Should show option in all generated build files
```

**Related**: [Building Blocks: Subproject Build Logic Template](../arc42/05-building-blocks.md#subproject-build-logic-template-subbuildgradle), [ADR-002](../arc42/09-decisions/ADR-002-dynamic-subproject-generation.md)

---

## Antipattern 5: Hardcoding Paths and Language Codes

### ❌ Antipattern: Hardcoding Values Instead of Using Placeholders

**Scenario**: Adding new conversion task to `subBuild.gradle`.

**Wrong Implementation**:
```groovy
task convert2CustomFormat(type: Exec) {
    workingDir "build/EN/docbook/plain"  // ❌ Hardcoded!
    commandLine 'myconverter',
                'arc42-template-EN.xml', // ❌ Hardcoded!
                '-o',
                'output-EN.txt'           // ❌ Hardcoded!
}
```

**Why This is Wrong**:
1. **Only works for EN**: Breaks for DE, FR, CZ
2. **Only works for plain**: Breaks for with-help
3. **Not portable**: Absolute paths break on different systems
4. **Placeholders not replaced**: %LANG%, %TYPE% exist for a reason

**What Happens**:
```bash
./gradlew createTemplatesFromGoldenMaster
./gradlew :DE:plain:convert2CustomFormat
# Tries to read build/EN/docbook/plain (doesn't exist for DE!) 💥
```

### ✅ Correct Approach

**Use placeholders and variables**:

```groovy
task convert2CustomFormat(type: Exec) {
    dependsOn 'convert2Docbook'
    workingDir "$buildDir/%LANG%/docbook/$config.targetType"  // ✅ Placeholders!
    commandLine 'myconverter',
                "arc42-template-%LANG%.xml",                   // ✅ Placeholders!
                '-o',
                "output-%LANG%-$config.targetType.txt"        // ✅ Placeholders!
}
```

**Understand placeholder replacement**:
```groovy
// From settings.gradle:93-112
subprojectBuildFile.text = subprojectBuildFile.text
    .replaceAll('%LANG%', dir.name)              // EN, DE, FR, CZ
    .replaceAll('%TYPE%', type)                  // plain, with-help
    .replaceAll('%VERSION%', versions[dir.name]) // 9.0, etc.
    .replaceAll('%SRC_VERSION%', srcVersion)
    .replaceAll('%CONFIG%', configContent)
```

**Test with multiple languages**:
```bash
./gradlew createTemplatesFromGoldenMaster
./gradlew :EN:plain:convert2CustomFormat    # ✅ Works
./gradlew :DE:with-help:convert2CustomFormat # ✅ Works
./gradlew :FR:plain:convert2CustomFormat     # ✅ Works
```

**Related**: [Concepts: Dynamic Subproject Generation](../arc42/08-concepts.md#concept-5-dynamic-subproject-generation)

---

## Antipattern 6: Adding Language Without Build Config Update

### ❌ Antipattern: Only Adding Template Files

**Scenario**: Team wants to add Spanish (ES) templates.

**Incomplete Approach**:
```bash
cd arc42-template
mkdir ES
cp -r EN/* ES/
# Translate content to Spanish...
git add ES/
git commit -m "Add Spanish templates"
git push

cd ..
git submodule update --remote
./gradlew createTemplatesFromGoldenMaster
./gradlew projects | grep ES
# 💥 No :ES:plain or :ES:with-help subprojects!
```

**Why This is Wrong**:
- **Hardcoded language list** in `build.gradle` overrides auto-discovery
- **Not built** because not in the list
- **Not distributed** in ZIPs
- **Auto-discovery code exists but is overridden** (see open-questions.md Q4)

### ✅ Correct Approach

**Update build configuration**:

```groovy
// Edit build.gradle:41
languages=['DE','EN', 'FR', 'CZ', 'ES']  // ← Add ES here
```

**Then build**:
```bash
./gradlew createTemplatesFromGoldenMaster
./gradlew projects | grep ES
# Now shows:
# :ES:plain
# :ES:with-help

./gradlew :ES:plain:arc42  # ✅ Works!
```

**Verify in distributions**:
```bash
./gradlew createDistribution
ls arc42-template/dist/ | grep ES
# Should show ES distributions:
# arc42-template-ES-plain-html.zip
# arc42-template-ES-with-help-docx.zip
# ...
```

**Related**: [Common Issue #8](../onboarding/common-issues.md#issue-8-language-added-but-not-building), [Open Questions Q4](../open-questions.md#q4-why-is-language-list-hardcoded-despite-auto-discovery-code)

---

## Antipattern 7: Committing to Wrong Submodule/Repo

### ❌ Antipattern: Mixing Template and Generator Changes

**Scenario**: Developer makes template content changes and build system changes in single commit.

**Wrong Workflow**:
```bash
# Edit template content
vim arc42-template/EN/asciidoc/src/05_building_block_view.adoc

# Edit build system
vim build.gradle

# Commit everything together (WRONG!)
git add .
git commit -m "Update templates and build system"
git push
# 💥 Submodule changes not pushed to arc42-template repo!
```

**Why This is Wrong**:
1. **Submodule reference broken**: Points to uncommitted submodule state
2. **Other developers can't clone**: Submodule commit doesn't exist on remote
3. **Violates separation of concerns**: Content vs. build logic mixed
4. **Two different repos**: arc42-template is separate repository

### ✅ Correct Approach

**Commit to submodule first**:

```bash
# 1. Commit template changes in submodule
cd arc42-template
git status  # Verify you're in submodule
git add EN/asciidoc/src/05_building_block_view.adoc
git commit -m "Update building block view section"
git push origin master  # ← Push to arc42-template repo
cd ..

# 2. Update submodule reference in main repo
git add arc42-template
git status  # Should show "modified: arc42-template (new commits)"
git commit -m "Update arc42-template to latest version"

# 3. Commit build system changes in main repo
git add build.gradle
git commit -m "Update build configuration"
git push origin master  # ← Push to arc42-generator repo
```

**Verify submodule state**:
```bash
git submodule status
# Should show space (not minus or plus):
#  a1b2c3d4 arc42-template (v9.0)
#  ^-- Space means clean, matches committed reference
```

**Related**: [ADR-005](../arc42/09-decisions/ADR-005-submodule-architecture.md), [Common Issue #4](../onboarding/common-issues.md#issue-4-submodule-is-empty-or-stale)

---

## Antipattern 8: Fragile Regex Modifications

### ❌ Antipattern: Modifying Feature Flag Regex Without Testing

**Scenario**: Developer wants to change how help text is detected.

**Hasty Change**:
```groovy
// build.gradle:89
// Changed from:
def regexArc42Help = /(?ms)\[role="arc42help"\]\s*?\*\*\*\*.*?\*\*\*\*/

// To (trying to support 3 or 4 asterisks):
def regexArc42Help = /(?ms)\[role="arc42help"\]\s*?\*\*\*+.*?\*\*\*+/
//                                                       ^^      ^^
//                                                    (greedy - BUG!)
```

**Why This is Wrong**:
1. **Greedy matching**: `*+` matches wrong blocks
2. **Nested blocks**: Captures too much content
3. **No validation**: Doesn't test against real templates
4. **Breaks silently**: Content leaks to wrong variant without error

**What Happens**:
```bash
./gradlew createTemplatesFromGoldenMaster
# No error message

# But plain variant now contains help text blocks! 💥
# And some help blocks in with-help are mangled
```

### ✅ Correct Approach

**Test regex changes carefully**:

```bash
# 1. Create test file with all edge cases
cat > /tmp/test-template.adoc << 'EOF'
Regular content before.

[role="arc42help"]
****
Help block 1 with 4 asterisks.
****

More content.

[role="arc42help"]
****
Help block 2 with:
- Nested lists
- **Bold text**
- Code `examples`
****

[role="arc42help"]
***
Wrong: 3 asterisks (should NOT match!)
***

Final content.
EOF

# 2. Test old regex (verify baseline)
grep -Pzo '(?ms)\[role="arc42help"\]\s*?\*\*\*\*.*?\*\*\*\*' /tmp/test-template.adoc

# 3. Test new regex
grep -Pzo 'YOUR_NEW_REGEX_HERE' /tmp/test-template.adoc

# 4. Verify both 4-asterisk blocks captured, 3-asterisk NOT captured
```

**Test with real templates**:
```bash
# Before changing code:
./gradlew createTemplatesFromGoldenMaster
cp -r build/src_gen build/src_gen.backup

# After changing regex:
./gradlew clean createTemplatesFromGoldenMaster

# Compare outputs:
diff -r build/src_gen.backup/EN/asciidoc/plain \
        build/src_gen/EN/asciidoc/plain
# Should ONLY show expected removals
```

**Document regex changes**:
```groovy
// If changing regex, add comment:
def regexArc42Help = /(?ms)\[role="arc42help"\]\s*?\*\*\*\*.*?\*\*\*\*/
// Changed 2024-01: Now supports nested asterisks in content
// Test file: test/regex-validation/help-blocks.adoc
```

**Related**: [Concepts: Feature Flag Processing](../arc42/08-concepts.md#concept-4-feature-flag-processing-regex-surgery), [Risks: Regex Fragility](../arc42/11-risks.md#risk-4-regex-fragility)

---

## Antipattern 9: Ignoring Pandoc Version

### ❌ Antipattern: Using System Pandoc Without Checking Version

**Scenario**: Developer has Pandoc installed via package manager.

**Unchecked Approach**:
```bash
which pandoc
# /usr/bin/pandoc ✓

./gradlew :EN:plain:convert2Docx
# Completes successfully

# Open generated DOCX file
# 💥 Formatting is broken! Tables mangled, images missing!
```

**Why This is Wrong**:
1. **Version sensitivity**: Different Pandoc versions produce different output
2. **Regression risk**: Output that worked with 3.7.0.2 breaks with 2.x or 4.x
3. **Inconsistent across team**: Each developer gets different results
4. **Distribution quality**: Released files don't match expected format

**Check version**:
```bash
pandoc --version
# pandoc 2.5
# ← Wrong version! Should be 3.7.0.2
```

### ✅ Correct Approach

**Use pinned version**:

```bash
# Check current version
pandoc --version | head -1
# If not 3.7.0.2, install correct version:

# Debian/Ubuntu:
wget https://github.com/jgm/pandoc/releases/download/3.7.0.2/pandoc-3.7.0.2-1-amd64.deb
sudo dpkg -i pandoc-3.7.0.2-1-amd64.deb

# macOS:
# Download DMG from pandoc releases, install 3.7.0.2 specifically

# Verify:
pandoc --version | head -1
# pandoc 3.7.0.2
```

**Or use build script** (handles installation):
```bash
./build-arc42.sh
# Installs correct Pandoc version automatically
```

**Document requirement**:
```markdown
# In README or CONTRIBUTING:
**Required**: Pandoc 3.7.0.2 (exactly)
- Other versions may produce different output
- Use ./build-arc42.sh to install correct version
```

**Related**: [ADR-006](../arc42/09-decisions/ADR-006-pandoc-as-converter.md), [Risks: Pandoc Version Dependency](../arc42/11-risks.md#risk-1-pandoc-version-dependency), [Common Issue #3](../onboarding/common-issues.md#issue-3-pandoc-command-not-found)

---

## Antipattern 10: Modifying Distribution Packaging Without Testing

### ❌ Antipattern: Changing ZIP Task Without Validation

**Scenario**: Developer wants to change distribution ZIP structure.

**Hasty Change**:
```groovy
// subBuild.gradle (around line 497)
task createDistribution(type: Zip) {
    archiveBaseName.set("arc42-template-%LANG%")
    destinationDirectory.set(file("$rootDir/arc42-template/dist"))

    // Changed from copy all formats:
    // from "$buildDir/%LANG%"

    // To only HTML (WRONG! Missing other formats):
    from "$buildDir/%LANG%/html"
}
```

**Why This is Wrong**:
1. **Silent partial failure**: Other formats still built but not packaged
2. **User expectation mismatch**: Downloads expect all formats
3. **No validation**: Build succeeds even with incomplete ZIPs
4. **Hard to detect**: File exists, but content is wrong

**What Happens**:
```bash
./gradlew createDistribution
# BUILD SUCCESSFUL (misleading!)

unzip -l arc42-template/dist/arc42-template-EN-plain-asciidoc.zip
# Only shows HTML files, missing DOCX, Markdown, etc. 💥
```

### ✅ Correct Approach

**Test distribution contents**:

```bash
# 1. Build distribution
./gradlew createDistribution

# 2. Verify ZIP contains expected formats
unzip -l arc42-template/dist/arc42-template-EN-plain-asciidoc.zip | \
  grep -E '\.(html|md|docx|pdf|epub|tex|adoc)$'
# Should show files in multiple formats

# 3. Extract and test a sample
cd /tmp
unzip arc42-template/dist/arc42-template-EN-plain-docx.zip
ls -lh *.docx
file arc42-template-EN.docx
# Should be: Microsoft Word 2007+ document

# 4. Spot-check critical formats
for format in html docx markdown pdf; do
    echo "Checking $format..."
    unzip -l arc42-template/dist/*-$format.zip | head -20
done
```

**Add validation task** (optional):
```groovy
task validateDistribution {
    dependsOn createDistribution
    doLast {
        def distDir = file("$rootDir/arc42-template/dist")
        def expectedZips = languages.size() * templateStyles.size() * formats.size()
        def actualZips = distDir.listFiles().findAll { it.name.endsWith('.zip') }.size()

        if (actualZips < expectedZips) {
            throw new GradleException(
                "Expected $expectedZips ZIPs, found $actualZips in $distDir"
            )
        }
        println "✓ Validation passed: $actualZips distribution ZIPs created"
    }
}
```

**Related**: [Common Issue #6](../onboarding/common-issues.md#issue-6-build-completes-but-distributions-emptycorrupt), [Building Blocks: Distribution](../arc42/05-building-blocks.md)

---

## Antipattern 11: Not Testing Both Template Styles

### ❌ Antipattern: Only Testing "plain" Variant

**Scenario**: Developer adds new section to Golden Master.

**Incomplete Testing**:
```bash
# Edit Golden Master
vim arc42-template/EN/asciidoc/src/12_glossary.adoc

# Add content with help text:
cat >> arc42-template/EN/asciidoc/src/12_glossary.adoc << 'EOF'
== Glossary

[role="arc42help"]
****
Explain your technical terms here.
****

|===
| Term | Definition
| Example | An example term
|===
EOF

# Rebuild and test ONLY plain variant:
./gradlew createTemplatesFromGoldenMaster
./gradlew :EN:plain:generateHTML
open build/EN/html/plain/arc42-template.html
# Looks good! ✓ (But incomplete testing!)

git commit -m "Add glossary section"
git push
```

**What Could Go Wrong**:
1. **Help text leaked to plain**: Wrong asterisk count (3 instead of 4)
2. **Content missing from with-help**: Regex too greedy, removed too much
3. **Format differences**: HTML looks fine, but DOCX broken
4. **Language-specific issues**: Works in EN, breaks in DE

### ✅ Correct Approach

**Test both variants systematically**:

```bash
# 1. Rebuild
./gradlew clean createTemplatesFromGoldenMaster

# 2. Test PLAIN variant (should NOT have help text)
echo "=== Testing PLAIN variant ==="
grep -c "arc42help" build/src_gen/EN/asciidoc/plain/src/12_glossary.adoc
# Should output: 0 (removed)

grep -A5 "Glossary" build/src_gen/EN/asciidoc/plain/src/12_glossary.adoc
# Should show table, NO help text block

# 3. Test WITH-HELP variant (SHOULD have help text)
echo "=== Testing WITH-HELP variant ==="
grep -c "arc42help" build/src_gen/EN/asciidoc/with-help/src/12_glossary.adoc
# Should output: 1 (present)

grep -A5 "Glossary" build/src_gen/EN/asciidoc/with-help/src/12_glossary.adoc
# Should show table AND help text block

# 4. Side-by-side diff
diff -u build/src_gen/EN/asciidoc/plain/src/12_glossary.adoc \
        build/src_gen/EN/asciidoc/with-help/src/12_glossary.adoc
# Should show ONLY the help block as difference

# 5. Test multiple formats
./gradlew :EN:plain:generateHTML :EN:plain:convert2Docx
./gradlew :EN:with-help:generateHTML :EN:with-help:convert2Docx

# 6. Visual inspection
open build/EN/html/plain/arc42-template.html
open build/EN/html/with-help/arc42-template.html
# Compare: with-help should have extra explanatory text
```

**Automated validation** (optional):
```bash
#!/bin/bash
# test-both-variants.sh

LANG="EN"
SECTION="12_glossary"

echo "Testing feature flag processing for $SECTION..."

# Check plain variant has NO help blocks
PLAIN_COUNT=$(grep -c "arc42help" "build/src_gen/$LANG/asciidoc/plain/src/$SECTION.adoc" || true)
if [ "$PLAIN_COUNT" -ne 0 ]; then
    echo "❌ FAIL: Plain variant has $PLAIN_COUNT help blocks (should be 0)"
    exit 1
fi

# Check with-help variant HAS help blocks
HELP_COUNT=$(grep -c "arc42help" "build/src_gen/$LANG/asciidoc/with-help/src/$SECTION.adoc" || true)
if [ "$HELP_COUNT" -eq 0 ]; then
    echo "❌ FAIL: With-help variant has no help blocks"
    exit 1
fi

echo "✅ PASS: Feature flags processed correctly"
echo "   Plain: $PLAIN_COUNT help blocks (correct)"
echo "   With-help: $HELP_COUNT help blocks (correct)"
```

**Related**: [Concepts: Feature Flag Processing](../arc42/08-concepts.md#concept-4-feature-flag-processing-regex-surgery), [Common Issue #2](../onboarding/common-issues.md#issue-2-help-text-appears-in-plain-variant)

---

## Antipattern 12: Debugging Without Understanding Build Phases

### ❌ Antipattern: Random Trial-and-Error Debugging

**Scenario**: Build fails with cryptic error.

**Ineffective Approach**:
```bash
./gradlew arc42
# Error: Some task failed

# Random attempts:
./gradlew clean
./gradlew arc42
# Still fails

./gradlew --refresh-dependencies arc42
# Still fails

rm -rf ~/.gradle/caches
# Still fails

# Give up, ask for help without diagnostics
```

**Why This is Wrong**:
1. **No hypothesis**: Random actions waste time
2. **Destroys evidence**: `clean` removes helpful error context
3. **No incremental testing**: Can't isolate which phase fails
4. **No logs**: Missing diagnostic information

### ✅ Correct Approach

**Systematic debugging by phase**:

```bash
# 1. Identify which PHASE fails
echo "=== Testing Phase 1: createTemplatesFromGoldenMaster ==="
./gradlew createTemplatesFromGoldenMaster --info --stacktrace 2>&1 | tee phase1.log
# If fails here: Golden Master or feature flag issue

echo "=== Verifying Phase 1 output ==="
ls -R build/src_gen/ | head -50
# Check if files generated correctly

echo "=== Testing Phase 2: Format conversion ==="
./gradlew :EN:plain:generateHTML --info --stacktrace 2>&1 | tee phase2.log
# If fails here: Conversion task issue

echo "=== Testing Phase 3: Distribution ==="
./gradlew createDistribution --info --stacktrace 2>&1 | tee phase3.log
# If fails here: Packaging issue

# 2. Analyze logs
grep -i "error\|exception\|failed" phase*.log

# 3. Check common issues
./gradlew --version  # Gradle version OK?
pandoc --version     # Pandoc installed? Right version?
git submodule status # Submodule initialized?
ls arc42-template/EN/asciidoc/src/ # Golden Master files exist?

# 4. Test in isolation
cd arc42-template/EN/asciidoc
asciidoctor -b html src/01_introduction_and_goals.adoc
# Does Asciidoctor work directly?

cd ../../../../build/EN/docbook/plain
pandoc -f docbook -t markdown arc42-template-EN.xml -o test.md
# Does Pandoc work directly?
```

**Understand build dependencies**:
```
createTemplatesFromGoldenMaster
    ↓
settings.gradle (discovers subprojects)
    ↓
subproject:generateDocbook
    ↓
subproject:convert2[Format] (depends on Docbook)
    ↓
subproject:arc42 (aggregates all formats)
    ↓
createDistribution (packages into ZIPs)
```

**Effective help request**:
```markdown
**Problem**: Build fails at format conversion phase

**Environment**:
- OS: Ubuntu 22.04
- Java: openjdk 11.0.16
- Gradle: 7.5.1 (from wrapper)
- Pandoc: 3.7.0.2

**Steps to reproduce**:
1. git clone ...
2. git submodule update --init
3. ./gradlew createTemplatesFromGoldenMaster  # ✓ SUCCESS
4. ./gradlew :EN:plain:convert2Docx          # ❌ FAILS

**Error message**:
```
pandoc: ./images/arc42-logo.png: openBinaryFile: does not exist
```

**What I've tried**:
- Verified images/ folder exists in arc42-template/EN/asciidoc/images/
- Checked subBuild.gradle copy task (seems correct)
- Tested Pandoc directly (works with absolute path)

**Logs**: (attached phase2.log)
```

**Related**: [Common Issues](../onboarding/common-issues.md), [Journey Map: Week 4 - Debug Issues](../onboarding/journey-map.md#week-4-independence-and-contribution)

---

## Quick Reference: Common Mistakes

| ❌ Antipattern | ✅ Correct Approach | Related Doc |
|----------------|---------------------|-------------|
| Edit `build/src_gen/` | Edit `arc42-template/` | [AP-1](#antipattern-1-editing-generated-files) |
| Use 3 asterisks `***` | Use 4 asterisks `****` | [AP-2](#antipattern-2-wrong-feature-flag-syntax) |
| Run `arc42` after `clean` | Run `createTemplatesFromGoldenMaster` first | [AP-3](#antipattern-3-running-tasks-out-of-order) |
| Edit generated `build.gradle` | Edit `subBuild.gradle` template | [AP-4](#antipattern-4-modifying-generated-buildgradle-in-subprojects) |
| Hardcode `EN` or `plain` | Use `%LANG%`, `%TYPE%` placeholders | [AP-5](#antipattern-5-hardcoding-paths-and-language-codes) |
| Add language folder only | Also update `build.gradle:41` | [AP-6](#antipattern-6-adding-language-without-build-config-update) |
| Commit submodule with main | Commit to submodule repo first | [AP-7](#antipattern-7-committing-to-wrong-submodulerepo) |
| Change regex hastily | Test with edge cases first | [AP-8](#antipattern-8-fragile-regex-modifications) |
| Use system Pandoc | Use Pandoc 3.7.0.2 exactly | [AP-9](#antipattern-9-ignoring-pandoc-version) |
| Change ZIP without testing | Validate distribution contents | [AP-10](#antipattern-10-modifying-distribution-packaging-without-testing) |
| Test only `plain` variant | Test both `plain` and `with-help` | [AP-11](#antipattern-11-not-testing-both-template-styles) |
| Random debugging | Systematic phase-by-phase testing | [AP-12](#antipattern-12-debugging-without-understanding-build-phases) |

---

## Using This Document

**For Code Reviewers**:
- Check PRs against these antipatterns
- Reference specific antipattern numbers in review comments
- Example: "This looks like AP-5 (hardcoding). Use %LANG% placeholder instead."

**For New Contributors**:
- Read alongside [journey-map.md](../onboarding/journey-map.md)
- When making first change, scan for relevant antipatterns
- Use as checklist before submitting PR

**For LLM Assistants**:
- Check code suggestions against antipatterns
- Proactively warn about common mistakes
- Suggest testing steps from correct approaches

**For Maintainers**:
- Add new antipatterns as discovered
- Update with recent issues from GitHub
- Reference in documentation and ADRs

---

## Contributing to This Document

Found a new antipattern? Add it with this structure:

```markdown
## Antipattern N: [Descriptive Name]

### ❌ Antipattern: [Short Description]

**Scenario**: [When this happens]

**Wrong Approach**:
```[code example]
```

**Why This is Wrong**:
1. [Reason 1]
2. [Reason 2]

### ✅ Correct Approach

**[Solution description]**:

```[code example]
```

**Related**: [Links to concepts, ADRs, common-issues]
```

---

**Last Updated**: 2024 (part of comprehensive mental model documentation)
**Related Documents**: [concepts.md](../arc42/08-concepts.md), [common-issues.md](../onboarding/common-issues.md), [journey-map.md](../onboarding/journey-map.md)
