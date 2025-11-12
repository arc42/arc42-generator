# Technical Risks and Technical Debt

## Current Technical Risks

### Risk 1: Pandoc Version Dependency 🔴 HIGH

**Description**: System depends on specific Pandoc version (currently 3.7.0.2). Different versions may produce different output or break builds.

**Probability**: Medium (Pandoc updates regularly)
**Impact**: High (build failures or output quality degradation)

**Indicators**:
- Build fails with Pandoc errors
- Generated documents have formatting issues
- New Pandoc version available with breaking changes

**Mitigation Strategies**:
1. **Version Pinning**: build-arc42.sh installs specific version
2. **Docker/Gitpod**: Pre-configured environments with correct Pandoc version
3. **Testing**: Test all formats after Pandoc upgrade before committing
4. **Documentation**: Clear version requirements in README

**Current Status**: Mitigated. Version pinned, automated installation works.

**Future Actions**:
- Monitor Pandoc releases for critical security fixes
- Test newer Pandoc versions in separate branch
- Document migration path if upgrade needed

---

### Risk 2: Gradle API Changes 🟡 MEDIUM

**Description**: Gradle evolves, deprecates old APIs. Requires ongoing maintenance to stay current.

**Probability**: High (Gradle releases frequently)
**Impact**: Medium (code changes needed, but gradual deprecation path)

**Indicators**:
- Deprecation warnings during build
- New Gradle version breaks build
- IDE warnings about deprecated API usage

**Recent Examples**:
```groovy
// Old (deprecated):
task.leftShift { ... }

// New (current):
task.doLast { ... }
```

```groovy
// Old (deprecated):
archiveBaseName = "filename"

// New (current):
archiveBaseName.set("filename")
```

**Mitigation Strategies**:
1. **Regular Updates**: Address deprecations proactively
2. **Gradle Wrapper**: Version-lock Gradle for reproducibility
3. **Test Suite**: (Currently manual - risk!)
4. **Community**: Watch Gradle changelog for breaking changes

**Current Status**: Partially mitigated. Recent work updated to Gradle 7+ APIs, but more deprecations likely in future.

**Future Actions**:
- Add automated build testing (CI/CD)
- Document Gradle version requirements
- Plan migration to Gradle 8+

---

### Risk 3: Git Submodule Sync Issues 🟡 MEDIUM

**Description**: Users forget `git submodule update`, leading to stale content or build failures.

**Probability**: High (common user error with submodules)
**Impact**: Low (easy to fix once diagnosed)

**Indicators**:
- "File not found" errors from build
- Outdated template content in distributions
- Empty arc42-template/ directory

**Mitigation Strategies**:
1. **Automation**: build-arc42.sh handles submodule update
2. **Documentation**: Clear instructions in README
3. **Error Messages**: Gradle tasks could check submodule status
4. **Hooks**: Git hooks to remind about submodule updates (not implemented)

**Current Status**: Partially mitigated. Documentation exists, automation script handles it.

**Future Actions**:
- Add Gradle task to verify submodule is initialized
- Consider Git worktrees as alternative to submodules
- Improve error messages when submodule missing

---

### Risk 4: Regex Fragility in Feature Removal 🟡 MEDIUM

**Description**: Feature flag removal uses regex patterns. Incorrect syntax in Golden Master breaks removal, content leaks to wrong variants.

**Probability**: Medium (happens when content authors unfamiliar with syntax)
**Impact**: Medium (wrong content in variants, but catchable in testing)

**Indicators**:
- Help text appears in "plain" variant
- Inconsistent content between variants
- Build completes successfully but output wrong

**Vulnerable Pattern**:
```asciidoc
[role="arc42help"]
***  ← 3 asterisks instead of 4 - BREAKS
Help text
***
```

Regex expects exactly 4 asterisks: `[*]{4}`

**Mitigation Strategies**:
1. **Validation**: Could add regex pre-check before build
2. **Testing**: Manual review of both plain and with-help variants
3. **Documentation**: Clear syntax guidelines for content authors
4. **Linting**: AsciiDoc linter to check block delimiters (not implemented)

**Current Status**: Unmitigated. Relies on manual testing and content author discipline.

**Future Actions**:
- Add validation step in createTemplatesFromGoldenMaster
- Automated comparison of variants (detect unexpected differences)
- Consider AST-based removal (more robust than regex)

---

### Risk 5: Language List Hardcoded 🟢 LOW

**Description**: Despite auto-discovery code, language list is hardcoded in build.gradle:41. New languages require code change.

**Probability**: Low (languages added infrequently)
**Impact**: Low (simple one-line change)

**Code**:
```groovy
// Auto-discovery (exists but unused):
new File(config.goldenMaster.sourcePath).eachDir { dir ->
    if (dir.name =~ /^[A-Z]{2}$/) {
        languages << dir.name
    }
}

// Hardcoded override:
languages=['DE','EN', 'FR', 'CZ']  // Why?
```

**Open Question**: Why is auto-discovery overridden? Likely:
- Quality control (not all language dirs complete)
- Explicit control over what gets built
- Testing incomplete translations

**Mitigation**: None needed. Works as designed.

**Future Actions**:
- Document the reason for hardcoding
- Consider feature flag per language (enabled/disabled)

---

### Risk 6: Multi-Page Format Complexity 🟢 LOW

**Description**: Multi-page formats (markdownMP, mkdocsMP) use dynamic task creation, harder to debug than static tasks.

**Probability**: Low (infrequent changes)
**Impact**: Medium (debugging is painful when it breaks)

**Challenge**:
```groovy
task convert2MarkdownMP {
    doLast {
        sourceFolder.eachFile { myFile ->
            def taskName = 'convert2Markdown_file' + i++
            tasks.create(name: taskName, type: Exec) {
                // Dynamic task created at config time
            }
        }
    }
}
```

Tasks don't exist in static configuration, only created when parent task runs.

**Mitigation**:
- Good documentation (this document!)
- Consistent pattern across all MP formats
- Manual testing of MP formats

**Future Actions**:
- Refactor to more Gradle-idiomatic approach
- Add integration tests for MP formats

---

## Technical Debt

### Debt 1: No Automated Testing 🔴 HIGH PRIORITY

**Description**: No unit tests, integration tests, or CI/CD pipeline. All testing is manual.

**Impact**:
- Regressions undetected until user reports
- Fear of refactoring (might break something)
- Long feedback loop (must run full build to test)

**Effort to Address**: Medium (weeks to set up proper testing)

**Proposed Solution**:
1. **Unit Tests**: Test regex patterns, version loading, path parsing
2. **Integration Tests**: Golden Master → Generated variants verification
3. **Format Tests**: Verify all formats can be generated
4. **CI/CD**: GitHub Actions to run on every PR

**Blockers**: None technical, just effort investment.

---

### Debt 2: Incomplete Error Handling 🟡 MEDIUM PRIORITY

**Description**: Many build tasks fail silently or with cryptic errors.

**Examples**:
- Pandoc not installed → generic "command not found"
- Submodule not initialized → "file not found" deep in build
- Wrong Pandoc version → mysterious formatting errors

**Effort to Address**: Small (incremental improvements)

**Proposed Solution**:
1. Pre-flight checks (Pandoc version, submodule status)
2. Better error messages with actionable guidance
3. Fail-fast principle (detect problems early)

---

### Debt 3: Limited Documentation for Contributors 🟡 MEDIUM PRIORITY

**Description**: Until now, no mental model documentation existed. Build system architecture undocumented.

**Impact**:
- High barrier for new contributors
- Tribal knowledge not captured
- Same questions asked repeatedly

**Effort to Address**: COMPLETED by this documentation project! 🎉

**This Documentation Provides**:
- Architecture decisions (ADRs)
- Mental models (08-concepts.md)
- Onboarding path (journey-map.md)
- Troubleshooting guide (common-issues.md)

---

### Debt 4: Publish Subproject Points to Fork 🟢 LOW PRIORITY

**Description**: publish/build.gradle points to `https://github.com/rdmueller/arc42-template.git` instead of arc42 organization.

**Impact**: Minimal (publishing not used regularly)

**Investigation Needed**: Why? Historical? Testing? Intentional?

**Effort to Address**: Trivial (change URL)

---

## Post-Mortems

### Incident 1: "Fix Common Folder" (Date Unknown)

**Evidence**: Git commit message "fix common folder"

**What Happened**: (Details unknown - git commit message only)

**Hypothesis**: Common files (styles, images) not copied correctly during build, leading to broken templates.

**Resolution**: Fixed in commit, likely related to path handling in build.gradle

**Lesson Learned**: (To be documented if more information discovered)

**Prevention**: Test that common files present in all generated variants

---

### Incident 2: Multi-Page Format Implementation Struggles

**Evidence**: Multiple attempts to get multi-page Pandoc conversion working (comments in subBuild.gradle)

**What Happened**: Initial approach to multi-page formats didn't work with Gradle's task model.

**Challenge**:
```groovy
// FIXME remove this and keep the next block
def proc = ('pandoc ... ').execute()
proc.waitForOrKill(1000)

// was not working for me in any MP task
// FIXME keep this block and remove previous one
/*executable = "pandoc"
   args = [...]*/
```

**Resolution**: Workaround using shell execution instead of Gradle Exec task.

**Lesson Learned**: Gradle's Exec task doesn't play well with dynamically created tasks in some contexts.

**Technical Debt**: Marked with FIXME, but working. Consider refactoring when time permits.

---

## Why Certain Rules Exist

### Rule: Never Edit build/ Directory

**Reason**: Build directory is ephemeral, regenerated from Golden Master. Editing it:
- Loses changes on next build
- Breaks source-of-truth principle
- Creates impossible-to-track modifications

**Historical Context**: Early in project, someone likely edited generated files, lost work on rebuild, this rule was born.

---

### Rule: Always Run createTemplatesFromGoldenMaster First

**Reason**: Chicken-and-egg problem (see Concepts doc).

**Historical Context**: Initially, may have had static subproject list. When switched to dynamic discovery, this requirement emerged.

---

### Rule: Pandoc Version Pinned

**Reason**: Different Pandoc versions produce subtly different output (spacing, formatting, handling of edge cases).

**Historical Context**: Likely experienced output changes after Pandoc upgrade, decided consistency more important than latest version.

**Trade-off**: Security fixes delayed, but reproducible builds guaranteed.

---

## Risk Management Strategy

**Risk Assessment Frequency**: Ad-hoc (should be: quarterly)

**Risk Owners**: Maintainers (currently unclear who)

**Escalation Path**: GitHub issues for community discussion

**Risk Acceptance Criteria**:
- 🔴 HIGH: Address immediately or add mitigation
- 🟡 MEDIUM: Address in next release or accept with documentation
- 🟢 LOW: Monitor, address if becomes higher priority

---

## Technical Constraints

### Constraint 1: Pandoc Dependency

**Type**: External tool dependency
**Rationale**: See ADR-006
**Impact**: Users must install Pandoc before building
**Workaround**: Automation scripts, Docker images
**Cannot Remove**: Would require implementing 15+ custom converters

### Constraint 2: Gradle as Build System

**Type**: Platform choice
**Rationale**: Powerful task orchestration, plugin ecosystem
**Impact**: Users must have Java runtime
**Workaround**: Gradle wrapper bundles Gradle
**Cannot Remove**: Rewriting in another build system (Make, Maven) = months of effort

### Constraint 3: AsciiDoc as Source Format

**Type**: Content format
**Rationale**: arc42 chose AsciiDoc as official format
**Impact**: Tied to AsciiDoc ecosystem (Asciidoctor, etc.)
**Cannot Remove**: Would require rewriting all Golden Master content

### Constraint 4: Git Submodules

**Type**: Repository structure
**Rationale**: See ADR-005
**Impact**: Users must understand git submodules
**Alternative Considered**: Monorepo or separate repos
**Could Change**: Possible to migrate to Git worktrees or monorepo, but significant effort

---

## Future Risk Mitigation Roadmap

**Q1-Q2: Testing Infrastructure**
- Set up GitHub Actions CI/CD
- Add integration tests for format generation
- Automated variant comparison

**Q3-Q4: Robustness**
- Pre-flight checks (Pandoc version, submodule status)
- Better error messages
- Validation of Golden Master syntax before build

**Long-term: Architecture Evolution**
- Consider AST-based feature removal (replace regex)
- Evaluate Gradle 8+ migration
- Investigate alternatives to submodules if pain points grow
