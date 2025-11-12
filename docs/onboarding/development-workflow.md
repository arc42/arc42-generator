# Development Workflow

## Feature Lifecycle

### 1. Design Phase
**When**: Adding new formats, languages, or architectural changes

**Activities**:
- Review existing ADRs and concepts
- Identify affected components
- Consider alternatives
- Document decision (create ADR if architectural)

**Checklist**:
- [ ] Read relevant ADRs and concepts
- [ ] Identify similar existing functionality
- [ ] List affected files/components
- [ ] Consider backward compatibility
- [ ] Draft implementation approach

---

### 2. Implementation Phase

**Golden Master Changes** (template content):
```bash
cd arc42-template
git checkout -b feature/update-section-05
# Edit template content
vim EN/asciidoc/src/05_building_block_view.adoc
# Test both variants
cd ..
./gradlew createTemplatesFromGoldenMaster
diff build/src_gen/EN/asciidoc/plain/src/05_building_block_view.adoc \
     build/src_gen/EN/asciidoc/with-help/src/05_building_block_view.adoc
# Verify feature flags worked correctly
cd arc42-template
git add EN/asciidoc/src/05_building_block_view.adoc
git commit -m "Update building block view guidance"
git push origin feature/update-section-05
```

**Generator Changes** (build system):
```bash
git checkout -b feature/add-rst-format
# Edit buildconfig.groovy or build.gradle
# Test build
./gradlew clean
./gradlew createTemplatesFromGoldenMaster
./gradlew :EN:plain:convert2Rst  # Test new format
# Verify output
ls build/EN/rst/plain/
git add buildconfig.groovy
git commit -m "Add RST output format"
git push origin feature/add-rst-format
```

---

### 3. Testing Phase

**Manual Testing Checklist**:
- [ ] Clean build from scratch: `rm -rf build/ && ./build-arc42.sh`
- [ ] Test at least 2 languages (e.g., EN, DE)
- [ ] Test both template styles (plain, with-help)
- [ ] Verify affected formats generate correctly
- [ ] Check distribution ZIPs contain expected files
- [ ] Validate no content leaked to wrong variants
- [ ] Test on fresh clone (verify submodule instructions)

**Format-Specific Testing**:
```bash
# HTML: Open in browser
open build/EN/html/plain/arc42-template.html

# DOCX: Extract and check structure
cd arc42-template/dist
unzip arc42-template-EN-plain-docx.zip
open arc42-template-EN.docx

# Markdown: Check syntax
cd ../../build/EN/markdown/plain
markdown-lint arc42-template-EN.md
```

---

### 4. Review Checklist

**Before PR submission**:
- [ ] Code follows existing patterns
- [ ] No hardcoded paths (use config or variables)
- [ ] Placeholders used correctly (%LANG%, %TYPE%, etc.)
- [ ] Golden Master uses correct feature flag syntax (4 asterisks)
- [ ] All affected languages tested
- [ ] Documentation updated if needed
- [ ] No debug output left in code
- [ ] Git submodule reference updated if template changed

**Code Review Focus Points**:
- Consistency with existing code style
- No duplication (DRY principle)
- Error handling for edge cases
- Performance impact (especially for large builds)
- Backward compatibility

---

### 5. Deployment Process

**Template Content Updates**:
```bash
# In arc42-template repo:
git checkout master
git pull
# Merge feature branch
git merge feature/update-section-05
# Tag if significant release
git tag v9.1-EN
git push origin master --tags
```

**Generator Updates**:
```bash
# In arc42-generator repo:
git checkout master
git pull
git merge feature/add-rst-format
# Update submodule reference if needed
git submodule update --remote arc42-template
git add arc42-template
git commit -m "Update arc42-template to v9.1"
git push origin master
```

**Distribution Updates**:
```bash
# Build new distributions
./build-arc42.sh
# Verify distributions
ls -lh arc42-template/dist/*.zip
# Commit distributions to arc42-template
cd arc42-template
git add dist/*.zip
git commit -m "Release v9.1 distributions"
git push origin master
```

---

## When to Write ADRs

Create an ADR when:
- ✅ Adding/changing build system architecture
- ✅ Choosing between significant alternatives
- ✅ Introducing new external dependency
- ✅ Changing core patterns (Golden Master, conversion pipeline)
- ✅ Decision affects multiple components
- ✅ Future maintainers need to understand "why"

**Don't need ADR for**:
- ❌ Adding single new language (follows pattern)
- ❌ Fixing bugs (unless architectural implication)
- ❌ Minor config changes
- ❌ Documentation updates

---

## Git Workflow

**Branch Naming**:
- `feature/add-spanish-language`
- `feature/rst-format`
- `fix/regex-pattern-escaping`
- `docs/update-onboarding`

**Commit Messages**:
```
<type>: <subject>

<body>

<footer>
```

Types: `feat`, `fix`, `docs`, `refactor`, `test`, `chore`

Example:
```
feat: Add RST output format

- Added RST to buildconfig.groovy formats list
- Pandoc conversion task auto-generated
- Tested with EN and DE languages

Closes #123
```

---

## Emergency Fixes

**Broken Build**:
1. Identify which phase fails
2. Check recent commits (`git log --oneline -10`)
3. Test known-good commit
4. Bisect if needed (`git bisect`)
5. Fix or revert
6. Test full build
7. Push fix

**Corrupted Distribution**:
1. Re-run full build from clean state
2. Compare checksums if previous good version exists
3. Test extracted templates
4. If issue persists, check Pandoc version
5. Rebuild and re-upload

---

## Release Checklist

**Pre-Release**:
- [ ] All tests pass
- [ ] Documentation updated
- [ ] CHANGELOG.md updated (if exists)
- [ ] Version numbers bumped in version.properties
- [ ] Git tags created

**Release**:
- [ ] Full clean build: `./build-arc42.sh`
- [ ] Verify all 300+ distributions created
- [ ] Spot-check key distributions (EN/DE, DOCX/HTML)
- [ ] Commit distributions to arc42-template

**Post-Release**:
- [ ] Announce on project channels
- [ ] Update download instructions if needed
- [ ] Monitor for user-reported issues
- [ ] Archive old distributions if storage constrained

---

## Code Organization Principles

**Where to Put New Code**:
- **Format conversion logic** → `subBuild.gradle`
- **Global configuration** → `buildconfig.groovy`
- **Language detection** → `build.gradle`
- **Subproject discovery** → `settings.gradle`
- **Template content** → `arc42-template/` submodule
- **Documentation** → `docs/` or this repo's root

**Don't Put Code In**:
- `build/` (ephemeral)
- Generated `build.gradle` files in subprojects (regenerated)

---

## Communication Channels

**Before Making Changes**:
- Check GitHub Issues for existing discussions
- Search closed PRs for similar work
- Review open PRs to avoid conflicts

**Getting Help**:
- GitHub Issues for bugs/feature requests
- GitHub Discussions for questions (if enabled)
- Review ADRs and concepts docs first

**Proposing Major Changes**:
1. Open GitHub Issue describing problem
2. Propose solution with alternatives
3. Get feedback before implementing
4. Create ADR documenting decision
5. Implement with reference to ADR

---

## Tools and Environment

**Required**:
- Git (with submodule support)
- Java 1.7+ (recommend Java 11+)
- Pandoc 3.7.0.2 (or compatible)

**Recommended**:
- IDE with Gradle support (IntelliJ, VS Code)
- AsciiDoc preview plugin
- Markdown viewer
- Diff tool for comparing variants

**Helpful**:
- Docker (for consistent environment)
- GitHub CLI (for PR management)

---

## Troubleshooting Development Issues

See [common-issues.md](common-issues.md) for detailed troubleshooting guide.

Quick diagnostics:
```bash
# Verify environment
java -version
./gradlew --version
pandoc --version
git submodule status

# Clean rebuild
rm -rf build/
./build-arc42.sh

# Check subprojects discovered
./gradlew projects

# Verbose build for debugging
./gradlew createTemplatesFromGoldenMaster --info
```
