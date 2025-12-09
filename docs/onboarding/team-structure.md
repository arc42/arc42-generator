# Team Structure and Knowledge Map

## Knowledge Map: Who Knows What

*Note: This section documents knowledge areas. Actual person assignments should be filled in by current team.*

### Build System Architecture
**Knowledge Area**: Gradle build system, dynamic subproject generation, task orchestration

**Key Skills**:
- Gradle DSL and build lifecycle
- Dynamic project structure
- Groovy scripting
- Settings.gradle mechanics

**Code Ownership**:
- `build.gradle`
- `settings.gradle`
- `subBuild.gradle`
- `buildconfig.groovy`

**Learning Resources**:
- [05-building-blocks.md](../arc42/05-building-blocks.md)
- [ADR-002](../arc42/09-decisions/ADR-002-dynamic-subproject-generation.md)
- [Concepts: Dynamic Subproject Generation](../arc42/08-concepts.md#concept-5-dynamic-subproject-generation)

---

### Template Content & Feature Flags
**Knowledge Area**: arc42 template structure, AsciiDoc authoring, feature flag system

**Key Skills**:
- arc42 framework knowledge
- AsciiDoc syntax
- Feature flag patterns
- Content localization

**Code Ownership**:
- `arc42-template/` submodule (separate repo)
- Feature flag regex patterns in `build.gradle`

**Learning Resources**:
- [08-concepts.md - Golden Master](../arc42/08-concepts.md#concept-1-the-golden-master-pattern)
- [ADR-001](../arc42/09-decisions/ADR-001-golden-master-pattern.md)
- [ADR-004](../arc42/09-decisions/ADR-004-feature-flag-system.md)

---

### Format Conversion Pipeline
**Knowledge Area**: Asciidoctor, Pandoc, DocBook, multi-format output

**Key Skills**:
- Asciidoctor plugin configuration
- Pandoc CLI and options
- DocBook XML format
- Format-specific quirks (DOCX, LaTeX, Markdown variants)

**Code Ownership**:
- Conversion tasks in `subBuild.gradle`
- Format definitions in `buildconfig.groovy`

**Learning Resources**:
- [ADR-003](../arc42/09-decisions/ADR-003-two-stage-conversion-pipeline.md)
- [ADR-006](../arc42/09-decisions/ADR-006-pandoc-as-converter.md)
- [Concepts: Two-Stage Conversion](../arc42/08-concepts.md#concept-3-two-stage-conversion-pipeline)

---

### Language Management
**Knowledge Area**: Multi-language support, version management, localization

**Key Skills**:
- Language code conventions
- version.properties structure
- Translation coordination

**Code Ownership**:
- Language list in `build.gradle`
- Version loading in `settings.gradle`
- Per-language version.properties files

**Learning Resources**:
- [ADR-007](../arc42/09-decisions/ADR-007-version-per-language.md)

---

### Distribution & Release
**Knowledge Area**: ZIP packaging, release process, distribution hosting

**Key Skills**:
- Gradle ZIP tasks
- Git submodule workflow
- Release coordination

**Code Ownership**:
- `createDistribution` task
- `publish/` subproject
- Distribution workflow

**Learning Resources**:
- [development-workflow.md#deployment-process](development-workflow.md#5-deployment-process)

---

## Code Ownership Model

### Primary Ownership
**Definition**: Person primarily responsible for area, reviews all changes.

### Secondary Ownership
**Definition**: Backup reviewer, can approve in primary owner's absence.

### Component Matrix

| Component | Primary | Secondary | Expertise Level Required |
|-----------|---------|-----------|-------------------------|
| build.gradle | TBD | TBD | High (Gradle, architecture) |
| settings.gradle | TBD | TBD | High (Gradle lifecycle) |
| subBuild.gradle | TBD | TBD | Medium (Gradle, Pandoc) |
| buildconfig.groovy | TBD | TBD | Low (config) |
| arc42-template/* | TBD | TBD | Medium (arc42, AsciiDoc) |
| publish/* | TBD | TBD | Low (rarely changes) |
| docs/* | ALL | ALL | Low (documentation) |

*TBD: To be filled in by current maintainers*

---

## Decision-Making Process

### Decision Levels

**Level 1: Individual Decisions** (no approval needed)
- Bug fixes
- Documentation improvements
- Code formatting
- Adding comments
- Test improvements

**Level 2: Component Decisions** (component owner approval)
- Adding new output format
- Changing conversion parameters
- Performance optimizations
- Refactoring within component

**Level 3: Architectural Decisions** (team discussion + ADR)
- Changing Golden Master approach
- New external dependencies
- Build system restructuring
- Breaking changes to public interface

### Decision Process

**For Level 3 (Architectural)**:
1. Open GitHub Issue describing problem
2. Propose solution with alternatives
3. Team discussion (async via Issue comments)
4. Consensus or vote if needed
5. Document in ADR
6. Implement
7. Update affected documentation

**Example Timeline**:
- Day 1: Issue opened
- Days 2-5: Discussion, alternatives explored
- Day 6: Decision made, ADR draft
- Day 7: Implementation begins

---

## Escalation Paths

### Build Failures
1. Check [common-issues.md](common-issues.md)
2. Ask in GitHub Issue/Discussion
3. Tag component owner
4. If urgent: Direct contact (if team has chat)

### Architectural Questions
1. Review existing ADRs
2. Check [08-concepts.md](../arc42/08-concepts.md)
3. Open GitHub Discussion
4. Request review from senior contributors

### Process Questions
1. Read [development-workflow.md](development-workflow.md)
2. Check recent PRs for examples
3. Ask in GitHub Discussion

---

## Contribution Tiers

### Tier 1: Observers
- Can view code
- Can comment on Issues/PRs
- Can fork and experiment

### Tier 2: Contributors
- Can submit PRs
- PRs reviewed by owners
- Changes merged after approval

### Tier 3: Component Owners
- Can review PRs in their component
- Can approve changes
- Responsible for component quality

### Tier 4: Maintainers
- Can approve architectural changes
- Can merge any PR
- Can create releases
- Write access to arc42-template submodule

**Progression Path**: Observer → Contributor (after 2-3 accepted PRs) → Component Owner (demonstrated expertise) → Maintainer (team decision)

---

## Communication Norms

### Response Times (aspirational)
- **Critical bugs**: 24 hours
- **PR reviews**: 3-5 days
- **Issue triage**: 1 week
- **Feature requests**: 2 weeks

### Communication Channels
- **GitHub Issues**: Bug reports, feature requests
- **GitHub PRs**: Code review, implementation discussion
- **GitHub Discussions**: Questions, general discussion (if enabled)
- **Commit messages**: Technical details, references

### Meeting Cadence
*If team has regular meetings, document here. Otherwise:*
- Asynchronous collaboration via GitHub
- Ad-hoc calls for major architectural decisions

---

## Onboarding Responsibilities

### For New Contributors
1. Complete [journey-map.md](journey-map.md) 4-week path
2. Shadow a PR review
3. Submit first PR (documentation or simple fix)
4. Get familiar with one component area

### For Component Owners
- Review PRs affecting your component within 3-5 days
- Maintain component documentation
- Update ADRs when decisions change
- Mentor new contributors

### For Maintainers
- Triage Issues weekly
- Coordinate releases
- Review architectural changes
- Update team structure doc

---

## Knowledge Transfer

### Departing Contributors
**Checklist**:
- [ ] Document tacit knowledge in appropriate docs
- [ ] Identify successor for owned components
- [ ] Transfer in-progress work or close
- [ ] Update team structure doc

### Onboarding Successors
- Pair on PRs for 2-3 weeks
- Grant component ownership
- Add to reviewer list

---

## Current Team Status

*To be filled in by maintainers:*

**Active Maintainers**:
- Name 1 (since YYYY)
- Name 2 (since YYYY)

**Component Owners**:
- Build System: TBD
- Format Conversion: TBD
- Template Content: TBD

**Regular Contributors**: (contributed in last 6 months)
- TBD

**Emeritus Contributors**: (significant past contributions)
- Gernot Starke (founder)
- Ralf D. Müller (early development)
- Others TBD

---

## How to Update This Document

This is a living document. Update when:
- Team composition changes
- New component ownership assigned
- Process changes
- Knowledge areas shift

**Process**:
1. Edit this file
2. Submit PR with "docs:" prefix
3. Any maintainer can approve
4. Merge and announce in team channels
