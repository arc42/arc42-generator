# Onboarding Journey: 4-Week Learning Path

This document provides a structured 4-week learning journey for new senior developers joining the arc42-generator project. Each week has specific goals, activities, and validation questions to ensure understanding.

---

## Week 1: Overview and First Build

**Goal**: Understand the problem space, run a successful build, see the system in action.

### Learning Objectives
- [ ] Understand why arc42-generator exists (the 300-file problem)
- [ ] Comprehend the Golden Master concept at high level
- [ ] Successfully run complete build pipeline
- [ ] Locate and understand key documentation

### Activities

#### Day 1: Context and Setup
1. **Read**: [01-introduction.md](../arc42/01-introduction.md)
   - Focus on "The Problem It Solves" section
   - Understand the 300+ files → 10 files transformation

2. **Environment Setup**:
   ```bash
   git clone https://github.com/arc42/arc42-generator.git
   cd arc42-generator
   git submodule init
   git submodule update
   cd arc42-template
   git checkout master
   cd ..
   ```

3. **Verify Prerequisites**:
   ```bash
   java -version  # Should be 1.7+
   ./gradlew --version
   ```

#### Day 2: First Build
1. **Run Automated Build**:
   ```bash
   ./build-arc42.sh
   ```

   Watch output carefully. Understand what each phase does.

2. **Explore Results**:
   ```bash
   ls -R build/src_gen/EN/asciidoc/
   ls -R build/EN/html/
   ls arc42-template/dist/
   ```

3. **Open Generated Templates**:
   - Open `build/EN/html/plain/arc42-template.html` in browser
   - Open `arc42-template/dist/arc42-template-EN-plain-docx.zip`, extract, view DOCX

#### Day 3: Understanding the Pipeline
1. **Read**: [05-building-blocks.md](../arc42/05-building-blocks.md)
   - Focus on "Build Phases (Temporal View)"

2. **Manual Build Steps** (to understand phases):
   ```bash
   rm -rf build/  # Clean slate
   ./gradlew createTemplatesFromGoldenMaster
   # Observe: build/src_gen/ created

   ./gradlew projects
   # Observe: Subprojects discovered

   ./gradlew :EN:plain:generateHTML
   # Observe: Single format for single language/style

   ./gradlew arc42
   # Observe: All formats generated

   ./gradlew createDistribution
   # Observe: ZIPs in arc42-template/dist/
   ```

#### Day 4: Architecture Overview
1. **Read**: [04-solution-strategy.md](../arc42/04-solution-strategy.md)
   - Don't try to memorize details yet
   - Get the big picture of the 5 key decisions

2. **Explore Golden Master**:
   ```bash
   cd arc42-template/EN/asciidoc/src/
   less 01_introduction_and_goals.adoc
   ```

   Look for:
   - `[role="arc42help"]` blocks
   - `****` delimiters
   - `ifdef::arc42help[]` conditionals

#### Day 5: Review and Questions
1. **Read**: [03-context.md](../arc42/03-context.md)
   - Understand what's IN vs OUT of scope

2. **Draw the Pipeline** (on paper or whiteboard):
   - Golden Master → Generated Variants → Converted Formats → Distributions
   - Show where Pandoc fits
   - Show where submodules fit

3. **List Questions**: Write down anything confusing for discussion with team.

### Validation Questions

Answer these to verify Week 1 understanding:

1. **Q**: Why does arc42-generator exist? What problem does it solve?
   <details><summary>Answer</summary>
   Before the generator, maintaining arc42 templates required managing 300+ files manually (10 languages × 15 formats × 2 styles). The generator reduces this to ~10 Golden Master files per language that automatically generate all variants.
   </details>

2. **Q**: What is the Golden Master?
   <details><summary>Answer</summary>
   A single source AsciiDoc file containing ALL variants (plain, with-help, with-examples) marked with feature flags. The build system removes unwanted content to create specific variants.
   </details>

3. **Q**: What are the 4 main build phases?
   <details><summary>Answer</summary>
   1. Template Generation (createTemplatesFromGoldenMaster)
   2. Subproject Discovery (settings.gradle)
   3. Format Conversion (arc42 task)
   4. Distribution Packaging (createDistribution)
   </details>

4. **Q**: Why must you run `createTemplatesFromGoldenMaster` before `./gradlew arc42`?
   <details><summary>Answer</summary>
   Because settings.gradle needs to scan build/src_gen/ to discover subprojects. If that directory doesn't exist, no subprojects are created, and the arc42 task has nothing to run.
   </details>

5. **Q**: Where do the final distribution ZIPs go, and why?
   <details><summary>Answer</summary>
   To arc42-template/dist/ (the submodule). This is the public interface - users download from arc42-template repo, not the generator repo.
   </details>

---

## Week 2: Fundamentals and First Modification

**Goal**: Understand core concepts deeply, make a small change to the Golden Master, verify it propagates.

### Learning Objectives
- [ ] Master the Golden Master pattern
- [ ] Understand feature flag syntax completely
- [ ] Make a change to Golden Master and see it in all outputs
- [ ] Comprehend the two-stage conversion pipeline

### Activities

#### Day 1-2: Deep Dive on Golden Master
1. **Read**: [08-concepts.md](../arc42/08-concepts.md) - Concept 1 (Golden Master)
   - Study the regex pattern in detail
   - Understand why exactly 4 asterisks matter
   - Learn common mistakes

2. **Read**: ADR-001 and ADR-004
   - [ADR-001: Golden Master Pattern](../arc42/09-decisions/ADR-001-golden-master-pattern.md)
   - [ADR-004: Feature Flag System](../arc42/09-decisions/ADR-004-feature-flag-system.md)

3. **Experiment**:
   ```bash
   cd arc42-template/EN/asciidoc/src/
   cp 01_introduction_and_goals.adoc 01_introduction_and_goals.adoc.backup

   # Add new help text block:
   vim 01_introduction_and_goals.adoc
   ```

   Add at end of file:
   ```asciidoc
   === Test Section

   [role="arc42help"]
   ****
   This is test help text. Should appear in with-help, not in plain.
   ****

   This content appears in ALL variants.
   ```

4. **Rebuild and Verify**:
   ```bash
   cd /workspaces/arc42-generator
   ./gradlew createTemplatesFromGoldenMaster

   # Check plain variant:
   grep -A5 "Test Section" build/src_gen/EN/asciidoc/plain/src/01_introduction_and_goals.adoc
   # Should NOT contain help text

   # Check with-help variant:
   grep -A5 "Test Section" build/src_gen/EN/asciidoc/with-help/src/01_introduction_and_goals.adoc
   # SHOULD contain help text
   ```

5. **Restore**:
   ```bash
   cd arc42-template/EN/asciidoc/src/
   mv 01_introduction_and_goals.adoc.backup 01_introduction_and_goals.adoc
   ```

#### Day 3: Two-Stage Conversion
1. **Read**: [08-concepts.md](../arc42/08-concepts.md) - Concept 3 (Two-Stage Conversion)

2. **Read**: [ADR-003](../arc42/09-decisions/ADR-003-two-stage-conversion-pipeline.md)

3. **Trace One Conversion**:
   ```bash
   # Generate DocBook intermediate:
   ./gradlew :EN:plain:generateDocbook

   # Examine DocBook:
   less build/EN/docbook/plain/arc42-template.xml

   # Now convert to DOCX:
   ./gradlew :EN:plain:convert2Docx

   # Result:
   ls -lh build/EN/docx/plain/arc42-template-EN.docx
   ```

4. **Compare Direct HTML**:
   ```bash
   ./gradlew :EN:plain:generateHTML
   less build/EN/html/plain/arc42-template.html
   ```

   Notice: HTML doesn't go through DocBook (direct Asciidoctor conversion).

#### Day 4-5: Single Language Build
**Challenge**: Build ONLY German (DE) templates, all formats.

1. **Understand Current Build**:
   ```bash
   ./gradlew projects
   # See all language/style subprojects
   ```

2. **Build Single Language**:
   ```bash
   # After createTemplatesFromGoldenMaster:
   ./gradlew :DE:plain:arc42
   ./gradlew :DE:with-help:arc42
   ```

3. **Verify**:
   ```bash
   ls build/DE/
   # Should see all formats
   ```

4. **Create Distribution for DE only**:
   ```bash
   # Manually create ZIP (understand what createDistribution does):
   cd build/DE/html/plain/
   zip -r ../../../../arc42-template-DE-plain-html.zip .
   cd ../../../../
   ```

### Validation Questions

1. **Q**: What's the exact syntax for a feature flag block in the Golden Master?
   <details><summary>Answer</summary>
   <pre>
   [role="arc42FEATURE"]
   ****
   Content here
   ****
   </pre>
   Must be exactly 4 asterisks, specific spacing.
   </details>

2. **Q**: Why do we use DocBook as an intermediate format instead of converting AsciiDoc directly to DOCX/Markdown/etc.?
   <details><summary>Answer</summary>
   Because Asciidoctor produces excellent DocBook, and Pandoc excels at DocBook → many formats. Pandoc's AsciiDoc support is limited. This leverages the strengths of both tools.
   </details>

3. **Q**: What would happen if you used 3 asterisks instead of 4 in a feature block?
   <details><summary>Answer</summary>
   The regex wouldn't match, so the help text wouldn't be removed. It would appear in the plain variant (wrong!).
   </details>

4. **Q**: Which output format does NOT use the DocBook intermediate step?
   <details><summary>Answer</summary>
   HTML - it uses direct AsciiDoc → HTML conversion via Asciidoctor for higher quality.
   </details>

---

## Week 3: Deep Dive - Dynamic Systems

**Goal**: Master the dynamic subproject generation, understand Gradle build lifecycle, add a new output format.

### Learning Objectives
- [ ] Understand the chicken-and-egg problem completely
- [ ] Master dynamic subproject generation
- [ ] Add a new output format successfully
- [ ] Debug build issues independently

### Activities

#### Day 1-2: The Chicken-and-Egg Problem
1. **Read**: [08-concepts.md](../arc42/08-concepts.md) - Concept 2 (Chicken-and-Egg)

2. **Read**: [ADR-002](../arc42/09-decisions/ADR-002-dynamic-subproject-generation.md)

3. **Experiment - Break It**:
   ```bash
   rm -rf build/
   ./gradlew :EN:plain:generateHTML
   # ERROR: Task not found
   ```

   Why? Because settings.gradle hasn't discovered subprojects yet.

4. **Experiment - Fix It**:
   ```bash
   ./gradlew createTemplatesFromGoldenMaster
   ./gradlew projects
   # Now subprojects exist!
   ./gradlew :EN:plain:generateHTML
   # Works!
   ```

5. **Study settings.gradle**:
   ```bash
   less settings.gradle
   ```

   Focus on:
   - Line 35: `if (target.exists())`
   - Line 36-60: Directory scanning and subproject creation
   - Line 44-52: Placeholder substitution

#### Day 3-4: Add New Output Format (RST)
**Challenge**: Add reStructuredText (RST) as output format.

1. **Add to Configuration**:
   ```groovy
   // Edit buildconfig.groovy:
   formats = [
       // ... existing formats
       'rst': [imageFolder: true],  // Add this
   ]
   ```

2. **Verify Pandoc Supports RST**:
   ```bash
   pandoc --list-output-formats | grep rst
   # Should show 'rst'
   ```

3. **Rebuild**:
   ```bash
   ./gradlew createTemplatesFromGoldenMaster
   ./gradlew :EN:plain:convert2Rst
   ```

4. **Check Result**:
   ```bash
   ls build/EN/rst/plain/
   cat build/EN/rst/plain/arc42-template-EN.rst
   ```

5. **Create Distribution**:
   ```bash
   ./gradlew createDistribution
   ls arc42-template/dist/*rst.zip
   ```

6. **Revert**:
   ```bash
   git checkout buildconfig.groovy
   ```

#### Day 5: Pandoc Deep Dive
1. **Experiment with Pandoc Directly**:
   ```bash
   cd build/EN/docbook/plain/

   # Try different output formats:
   pandoc -f docbook -t markdown arc42-template.xml -o test.md
   pandoc -f docbook -t html arc42-template.xml -o test.html
   pandoc -f docbook -t latex arc42-template.xml -o test.tex

   # Compare quality
   less test.md
   ```

2. **Read**: [ADR-006](../arc42/09-decisions/ADR-006-pandoc-as-converter.md)

3. **Study Conversion Tasks**:
   ```bash
   less subBuild.gradle
   ```

   Find: `convert2Docx`, `convert2Markdown`, etc.
   - Notice pattern
   - See how args are constructed

### Validation Questions

1. **Q**: What is the "chicken-and-egg problem" in this build system?
   <details><summary>Answer</summary>
   settings.gradle needs to discover subprojects by scanning build/src_gen/, but that directory is created by the createTemplatesFromGoldenMaster task, which runs AFTER settings.gradle evaluates. Solution: Conditional discovery (only scan if directory exists).
   </details>

2. **Q**: How does settings.gradle determine which subprojects to create?
   <details><summary>Answer</summary>
   It scans build/src_gen/ recursively, looking for directories named "src/". When found, it extracts the language and style from the parent path, then creates a subproject named "{LANG}:{STYLE}".
   </details>

3. **Q**: What file gets copied and modified to create each subproject's build.gradle?
   <details><summary>Answer</summary>
   subBuild.gradle (the template). Placeholders like %LANG%, %TYPE%, %REVNUMBER% are replaced with actual values.
   </details>

4. **Q**: To add a new output format supported by Pandoc, what are the minimum changes needed?
   <details><summary>Answer</summary>
   1. Add format to buildconfig.groovy formats map
   2. Task already exists in subBuild.gradle (convert2{Format})
   3. Rebuild - that's it!
   (Some formats may need special args, but basic support is automatic)
   </details>

---

## Week 4: Independence and Contribution

**Goal**: Work independently, troubleshoot issues, prepare to contribute.

### Learning Objectives
- [ ] Add a new language completely independently
- [ ] Debug and fix a build issue
- [ ] Understand contribution workflow
- [ ] Identify areas for improvement

### Activities

#### Day 1-2: Add New Language (Simulation)
**Challenge**: Add Spanish (ES) support (simulation - don't commit).

1. **Preparation**:
   ```bash
   cd arc42-template/
   # In real scenario: Copy EN/ to ES/, translate content
   # For simulation: Just copy EN/ to ES/
   cp -r EN/ ES/

   # Create version.properties:
   echo "revnumber=1.0-ES" > ES/version.properties
   echo "revdate=October 2025" >> ES/version.properties
   echo "revremark=(test version)" >> ES/version.properties
   ```

2. **Update Build Configuration**:
   ```groovy
   // Edit build.gradle:41
   languages=['DE','EN', 'FR', 'CZ', 'ES']  // Add ES
   ```

3. **Build**:
   ```bash
   cd ..
   ./gradlew createTemplatesFromGoldenMaster
   ./gradlew projects | grep ES
   # Should see :ES:plain and :ES:with-help

   ./gradlew :ES:plain:generateHTML
   # Should work!
   ```

4. **Verify**:
   ```bash
   ls build/ES/html/plain/
   ls build/src_gen/ES/asciidoc/
   ```

5. **Create Distribution**:
   ```bash
   ./gradlew createDistribution
   ls arc42-template/dist/*ES*.zip
   ```

6. **Cleanup** (don't commit simulation):
   ```bash
   git checkout build.gradle
   rm -rf arc42-template/ES/
   ```

#### Day 3: Troubleshooting Practice
**Scenarios**: Deliberately break things, then fix them.

**Scenario 1**: Missing Pandoc
```bash
# Rename pandoc temporarily:
sudo mv /usr/bin/pandoc /usr/bin/pandoc.bak

./gradlew :EN:plain:convert2Docx
# ERROR: pandoc: command not found

# Diagnose: Check if Pandoc installed
which pandoc  # Not found

# Fix:
sudo mv /usr/bin/pandoc.bak /usr/bin/pandoc
```

**Scenario 2**: Stale Submodule
```bash
cd arc42-template
git checkout HEAD~5  # Go back 5 commits
cd ..

./gradlew createTemplatesFromGoldenMaster
# Old content!

# Diagnose:
cd arc42-template
git log -1  # See it's old

# Fix:
git checkout master
git pull
cd ..
```

**Scenario 3**: Wrong Feature Flag Syntax
```bash
# Edit Golden Master with wrong syntax:
cd arc42-template/EN/asciidoc/src/
vim 01_introduction_and_goals.adoc

# Add this (WRONG - 3 asterisks):
[role="arc42help"]
***
Bad syntax
***

cd ../../../../
./gradlew createTemplatesFromGoldenMaster

# Diagnose: Check if help text in plain variant
grep -A2 "Bad syntax" build/src_gen/EN/asciidoc/plain/src/01_introduction_and_goals.adoc
# It's there! (Wrong)

# Fix: Use 4 asterisks
# Revert changes
```

#### Day 4: Contribution Workflow
1. **Read**: CONTRIBUTING.md (if exists) or infer from git history

2. **Study Recent PRs**:
   ```bash
   # Look at recent pull requests on GitHub
   # Understand what changes are typical
   ```

3. **Identify Improvement**:
   - Read [11-risks.md](../arc42/11-risks.md)
   - Pick a "Future Actions" item
   - Plan how you'd implement it

4. **Practice Git Workflow**:
   ```bash
   git checkout -b feature/validation-check
   # Make changes
   git add .
   git commit -m "Add pre-flight check for Pandoc version"
   # Don't push (practice only)
   git checkout master
   git branch -D feature/validation-check
   ```

#### Day 5: Review and Reflection
1. **Self-Assessment**:
   - Can you explain the system to someone else?
   - Can you debug build issues independently?
   - Can you identify where to make changes for new features?

2. **Documentation Review**:
   - Read any docs you skipped
   - Note any gaps or confusing parts

3. **Next Steps Planning**:
   - What do you want to contribute?
   - What areas need deeper understanding?

### Validation Questions

1. **Q**: What are ALL the steps to add a new language to the system?
   <details><summary>Answer</summary>
   1. Add Golden Master content in arc42-template/{LANG}/asciidoc/
   2. Create arc42-template/{LANG}/version.properties
   3. Add language code to build.gradle:41 languages list
   4. Run ./gradlew createTemplatesFromGoldenMaster
   5. Run ./gradlew arc42
   6. Run ./gradlew createDistribution
   7. Test generated templates
   8. Commit distributions to arc42-template submodule
   </details>

2. **Q**: If a user reports "Task ':EN:plain:arc42' not found", what's the first thing you check?
   <details><summary>Answer</summary>
   Check if build/src_gen/ exists. If not, they forgot to run createTemplatesFromGoldenMaster first (chicken-and-egg problem).
   </details>

3. **Q**: You've made a change to the Golden Master. What's the MINIMUM you must rebuild to see it in HTML output?
   <details><summary>Answer</summary>
   ./gradlew createTemplatesFromGoldenMaster (regenerate variants)
   ./gradlew :EN:plain:generateHTML (or whichever language/style you want)
   </details>

4. **Q**: Where would you look to understand why German LaTeX output looks different from other languages?
   <details><summary>Answer</summary>
   Check subBuild.gradle:163-165 - there's special handling for Russian (language=='RU') that adds font encoding. Similar language-specific logic would be added there.
   </details>

---

## Graduation Criteria

You're ready to work independently when you can:

- ✅ Explain the Golden Master pattern to a colleague
- ✅ Trace data flow from Golden Master → Distribution ZIP
- ✅ Add a new output format without help
- ✅ Debug common build issues using the mental models
- ✅ Identify which ADR/concept to reference for a given question
- ✅ Make a change to Golden Master and verify it in all variants
- ✅ Understand the trade-offs in major architectural decisions

## Continued Learning

After these 4 weeks:
- **Read**: All ADRs in detail
- **Contribute**: Pick an item from [11-risks.md](../arc42/11-risks.md) Technical Debt
- **Teach**: Explain a concept to a new team member
- **Improve**: Submit PR to improve this documentation based on your experience

Welcome to the team! 🎉
