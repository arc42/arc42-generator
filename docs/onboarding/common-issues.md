# Common Issues and Troubleshooting

## Format: Symptom → Cause → Debug → Fix → Prevention

---

## Issue 1: "Task ':EN:plain:arc42' not found"

### Symptom
```bash
./gradlew arc42
# or
./gradlew :EN:plain:arc42

ERROR: Task ':EN:plain:arc42' not found in project ':EN:plain'.
```

### Common Causes
1. **Chicken-and-egg problem**: build/src_gen/ doesn't exist yet
2. **Deleted build directory**: Subprojects lost
3. **Failed createTemplatesFromGoldenMaster**: Incomplete structure

### Debugging Steps
```bash
# 1. Check if structure exists
ls build/src_gen/

# 2. Check if subprojects discovered
./gradlew projects

# 3. Check if specific language/style exists
ls build/src_gen/EN/asciidoc/plain/
```

### Fix
```bash
# Always run this first:
./gradlew createTemplatesFromGoldenMaster

# Then run:
./gradlew arc42
```

### Prevention
- Use `./build-arc42.sh` which handles sequencing automatically
- Document: Never delete build/ without re-running createTemplatesFromGoldenMaster
- Add check in documentation/scripts

**Reference**: [Concepts: Chicken-and-Egg Problem](../arc42/08-concepts.md#concept-2-the-chicken-and-egg-problem-gradle-build-lifecycle)

---

## Issue 2: Help Text Appears in "plain" Variant

### Symptom
Opening `build/src_gen/EN/asciidoc/plain/src/01_introduction.adoc` shows help text that should only be in "with-help" variant.

### Common Causes
1. **Wrong asterisk count**: Used 3 or 5 instead of 4
2. **Whitespace in delimiters**: `* * * *` instead of `****`
3. **Missing role attribute**: Forgot `[role="arc42help"]`
4. **Nested blocks**: Inner block delimiter matched first

### Debugging Steps
```bash
# 1. Check Golden Master syntax
cd arc42-template/EN/asciidoc/src/
grep -A3 -B1 "arc42help" 01_introduction.adoc

# 2. Manually inspect problematic block
vim 01_introduction.adoc
# Search for the help text that leaked

# 3. Count asterisks
grep "^\*\*\*\*" 01_introduction.adoc
# Should show pairs of exactly 4 asterisks
```

### Fix
Correct Golden Master syntax:
```asciidoc
[role="arc42help"]
****
Help text here - exactly 4 asterisks
****
```

Then rebuild:
```bash
./gradlew createTemplatesFromGoldenMaster
```

### Prevention
- Template validation script (check for 4 asterisks)
- Code review checklist includes syntax verification
- Test both variants after Golden Master changes

**Reference**: [Concepts: Feature Flag Processing](../arc42/08-concepts.md#concept-4-feature-flag-processing-regex-surgery)

---

## Issue 3: "pandoc: command not found"

### Symptom
```bash
./gradlew :EN:plain:convert2Docx

> Task :EN:plain:convert2Docx FAILED
pandoc: command not found
```

### Common Cause
Pandoc not installed or not in PATH.

### Debugging Steps
```bash
# Check if Pandoc installed
which pandoc

# Check Pandoc version
pandoc --version

# Check PATH
echo $PATH
```

### Fix

**Option 1: Use build-arc42.sh** (installs Pandoc):
```bash
./build-arc42.sh
```

**Option 2: Manual install** (Debian/Ubuntu):
```bash
wget https://github.com/jgm/pandoc/releases/download/3.7.0.2/pandoc-3.7.0.2-1-amd64.deb
sudo dpkg -i pandoc-3.7.0.2-1-amd64.deb
```

**Option 3: macOS**:
```bash
brew install pandoc
```

**Option 4: Windows**:
Download installer from https://pandoc.org/installing.html

### Prevention
- Document Pandoc requirement prominently
- build-arc42.sh handles installation
- Consider Docker/Gitpod for consistent environment

**Reference**: [ADR-006: Pandoc as Converter](../arc42/09-decisions/ADR-006-pandoc-as-converter.md)

---

## Issue 4: Submodule is Empty or Stale

### Symptom
```bash
ls arc42-template/
# Empty or very few files

./gradlew createTemplatesFromGoldenMaster
# ERROR: File not found
```

### Common Causes
1. **Forgot `git submodule update`** after clone
2. **Submodule not initialized**
3. **Submodule on old commit**

### Debugging Steps
```bash
# 1. Check submodule status
git submodule status

# If shows minus sign (-), not initialized
# If shows plus sign (+), different commit than expected
# If shows space, OK

# 2. Check submodule content
ls -la arc42-template/

# 3. Check submodule commit
cd arc42-template
git log -1
```

### Fix
```bash
# Initialize and update:
git submodule init
git submodule update

# Or combined:
git submodule update --init

# Update to latest:
cd arc42-template
git checkout master
git pull
cd ..
```

### Prevention
- Document submodule commands in README
- build-arc42.sh handles submodule update
- Post-clone checklist includes submodule update

**Reference**: [Concepts: Git Submodules](../llm/knowledge-graph.md) (search "Git Submodules")

---

## Issue 5: Wrong Pandoc Version Produces Different Output

### Symptom
Generated DOCX/Markdown looks different than expected. Formatting issues, missing elements.

### Common Cause
Pandoc version mismatch. System has different version than tested (3.7.0.2).

### Debugging Steps
```bash
# Check installed version
pandoc --version

# Expected: 3.7.0.2
```

### Fix
Install pinned version:
```bash
# Debian/Ubuntu:
wget https://github.com/jgm/pandoc/releases/download/3.7.0.2/pandoc-3.7.0.2-1-amd64.deb
sudo dpkg -i pandoc-3.7.0.2-1-amd64.deb

# Or use build-arc42.sh
./build-arc42.sh
```

### Prevention
- Pin Pandoc version in documentation
- Docker/Gitpod images with correct version
- CI/CD to catch version mismatches

**Reference**: [11-risks.md - Pandoc Version Dependency](../arc42/11-risks.md#risk-1-pandoc-version-dependency)

---

## Issue 6: Build Completes But Distributions Empty/Corrupt

### Symptom
```bash
./gradlew createDistribution
# SUCCESS

ls arc42-template/dist/*.zip
# Files exist

unzip arc42-template/dist/arc42-template-EN-plain-html.zip
# Empty or missing files
```

### Common Causes
1. **Format conversion failed silently**
2. **Images not copied**
3. **Path issues in ZIP task**

### Debugging Steps
```bash
# 1. Check if format was generated
ls build/EN/html/plain/
# Should contain arc42-template.html

# 2. Check ZIP contents without extracting
unzip -l arc42-template/dist/arc42-template-EN-plain-html.zip

# 3. Rebuild single format with verbose output
./gradlew :EN:plain:generateHTML --info

# 4. Check for errors in build log
./gradlew createDistribution 2>&1 | grep -i error
```

### Fix
```bash
# Full clean rebuild:
rm -rf build/
rm arc42-template/dist/*.zip
./build-arc42.sh
```

### Prevention
- Add validation step after createDistribution (check ZIP sizes, file counts)
- Test sample of distributions before release

---

## Issue 7: Gradle Deprecation Warnings

### Symptom
```bash
./gradlew arc42

Some problems were found with the configuration of task ':EN:plain:arc42'.
  - In plugin 'org.gradle.api.plugins.BasePlugin' type 'org.gradle.api.tasks.bundling.Zip' property 'archiveBaseName' has @Input annotation used on property of type 'Property<String>'.

Deprecated Gradle features were used in this build, making it incompatible with Gradle 8.0.
```

### Common Cause
Using old Gradle API patterns (direct assignment instead of .set() method).

### Debugging Steps
```bash
# Check Gradle version
./gradlew --version

# Run with --warning-mode all for details
./gradlew arc42 --warning-mode all
```

### Fix
Update code to use property API:
```groovy
// Old (deprecated):
archiveBaseName = "filename"

// New:
archiveBaseName.set("filename")
```

This is ongoing maintenance work. Log as issue if you find deprecated usage.

### Prevention
- Regular Gradle upgrades
- Address deprecations proactively
- CI/CD with --warning-mode all

**Reference**: [11-risks.md - Gradle API Changes](../arc42/11-risks.md#risk-2-gradle-api-changes)

---

## Issue 8: Language Added But Not Building

### Symptom
```bash
# Added ES (Spanish) to arc42-template/
ls arc42-template/ES/
# Files exist

./gradlew createTemplatesFromGoldenMaster
./gradlew projects | grep ES
# No :ES:plain or :ES:with-help subprojects
```

### Common Cause
Language not added to hardcoded list in build.gradle.

### Debugging Steps
```bash
# Check language list
grep "languages=" build.gradle
# See if ES included
```

### Fix
```groovy
// Edit build.gradle:41
languages=['DE','EN', 'FR', 'CZ', 'ES']  // Add ES
```

Then rebuild:
```bash
./gradlew createTemplatesFromGoldenMaster
./gradlew projects | grep ES  # Should now appear
```

### Prevention
- Document language addition requires build.gradle update
- **NOTE** (2025-10-30): Hardcoded list will be removed in future refactoring. It was temporary for a deployment. Auto-discovery will be restored.

**Reference**: [Development Plan - Decision 2](../../.vibe/development-plan-refactor-implement.md#decision-2-restore-auto-discovery-of-languages)

---

## Issue 11: Common Folder Includes Not Working (Historical)

### Symptom
```bash
# Templates fail to build
# Missing includes from common/ folder
ERROR: include file not found: {include}common/...
```

### Historical Context
**Context from Maintainer** (2025-10-30):
> "The templates introduced a common folder from which things are included. The original generator didn't implement this."

This was a past issue where the arc42 templates started using a `common/` folder for shared content (CSS, shared adoc includes, etc.), but the generator wasn't copying these files.

### Fix
The generator was updated to copy common files:

In `build.gradle:58-62`:
```groovy
copy {
    from config.goldenMaster.sourcePath + '/common/.'
    into config.goldenMaster.targetPath + language + '/common/.'
}
```

### Current Status
**RESOLVED** - Common folder copying is now implemented and working.

### Lessons Learned
- Generator must stay in sync with template structure changes
- When templates add new folder conventions, generator needs updates
- Communication between template and generator teams is critical

---

## Issue 9: Multi-Page Format Fails Mysteriously

### Symptom
```bash
./gradlew :EN:plain:convert2MarkdownMP

> Task :EN:plain:convert2MarkdownMP FAILED
# Cryptic error or silent failure
```

### Common Cause
Dynamic task creation in MP formats is complex, hard to debug.

### Debugging Steps
```bash
# 1. Check if DocBook MP generated
ls build/EN/docbookMP/

# 2. Run with --stacktrace
./gradlew :EN:plain:convert2MarkdownMP --stacktrace

# 3. Check Pandoc works on individual files
cd build/EN/docbookMP/
pandoc -f docbook -t markdown arc42-template.xml -o test.md
```

### Fix
Usually requires looking at subBuild.gradle implementation. If standard Pandoc conversion works but Gradle task fails, it's likely task configuration issue.

Consider filing GitHub Issue with details.

### Prevention
- Test MP formats regularly
- Refactor to more Gradle-idiomatic approach (future work)

**Reference**: [11-risks.md - Multi-Page Format Complexity](../arc42/11-risks.md#risk-6-multi-page-format-complexity)

---

## Issue 10: Changes to Golden Master Not Showing Up

### Symptom
```bash
# Edited arc42-template/EN/asciidoc/src/01_introduction.adoc
./gradlew createTemplatesFromGoldenMaster
./gradlew :EN:plain:generateHTML

# Open build/EN/html/plain/arc42-template.html
# Old content still there!
```

### Common Causes
1. **Edited wrong file** (edited build/src_gen/ instead of arc42-template/)
2. **Caching** (Gradle incremental build)
3. **Submodule not updated** (changes in fork not submodule)

### Debugging Steps
```bash
# 1. Verify you edited the right file
less arc42-template/EN/asciidoc/src/01_introduction.adoc
# Search for your change

# 2. Check generated file
less build/src_gen/EN/asciidoc/plain/src/01_introduction.adoc
# Should have your change (if in plain variant)

# 3. Clean rebuild
rm -rf build/
./gradlew createTemplatesFromGoldenMaster
```

### Fix
- Always edit arc42-template/ (the submodule), never build/
- Clean rebuild if caching suspected

### Prevention
- Never edit files in build/ directory
- IDE: Mark build/ as excluded to prevent accidental edits

**Reference**: [Concepts: Golden Master Pattern](../arc42/08-concepts.md#concept-1-the-golden-master-pattern)

---

## Quick Diagnostic Commands

### Full System Check
```bash
# Environment
java -version
./gradlew --version
pandoc --version
git --version

# Submodule status
git submodule status

# Clean rebuild
rm -rf build/
./build-arc42.sh

# Verify subprojects
./gradlew projects

# Test single conversion
./gradlew :EN:plain:generateHTML

# Check output
ls -lh build/EN/html/plain/
```

### Detailed Debug Build
```bash
./gradlew createTemplatesFromGoldenMaster --info --stacktrace
./gradlew :EN:plain:arc42 --debug --stacktrace > build.log 2>&1
less build.log
```

---

## Emergency Reset Procedure

If build completely broken and you need to start fresh:

```bash
# 1. Clean everything
rm -rf build/
rm -rf arc42-template/dist/*.zip

# 2. Reset submodule
cd arc42-template
git reset --hard origin/master
git clean -fd
cd ..

# 3. Update submodule
git submodule update --init --force

# 4. Full rebuild
./build-arc42.sh

# 5. Verify
./gradlew projects
ls -lh arc42-template/dist/ | head
```

**WARNING**: This loses uncommitted changes in arc42-template submodule!

---

## Getting Further Help

If issue not covered here:

1. **Search GitHub Issues**: https://github.com/arc42/arc42-generator/issues
2. **Check recent PRs**: May have encountered similar issue
3. **Review ADRs**: Architectural context may explain behavior
4. **Open GitHub Issue**: Provide:
   - Symptom (exact error message)
   - Steps to reproduce
   - Environment (OS, Java version, Pandoc version)
   - Relevant logs
   - What you've tried

Template for bug reports:
```markdown
**Symptom**: [exact error]

**Environment**:
- OS: [Ubuntu 20.04, macOS 12, etc.]
- Java: [version]
- Pandoc: [version]
- Gradle: [version from ./gradlew --version]

**Steps to Reproduce**:
1. [step 1]
2. [step 2]
...

**Expected**: [what should happen]

**Actual**: [what actually happened]

**Logs**: [paste relevant output]

**What I've Tried**: [attempted fixes]
```
