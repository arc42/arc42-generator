# Open Questions and Assumptions

This document captures information that is missing, unclear, or based on assumptions during the creation of this mental model documentation. Questions are removed once answered and incorporated into the permanent documentation.

---

## Resolved Questions

**Recently Resolved** (2025-10-30):
- ~~Q1: Project creation~~ → Documented in docs/arc42/01-introduction.md
- ~~Q2: Manual to generator shift~~ → Documented in docs/arc42/01-introduction.md
- ~~Q3: req42-framework addition~~ → Documented in docs/arc42/01-introduction.md
- ~~Q4: Hardcoded language list~~ → Documented in .vibe/development-plan-refactor-implement.md (Decision 2)
- ~~Q5: Gradle choice~~ → Documented in .vibe/development-plan-refactor-implement.md (Decision 1)
- ~~Q9: Maintenance status~~ → Documented in docs/arc42/01-introduction.md
- ~~Q18: Gradle version~~ → Documented in docs/arc42/01-introduction.md (roadmap)
- ~~A1-A5: Assumptions~~ → Confirmed and documented where relevant

---

## Current State & Roadmap

### Q8: What is the current version numbering scheme?

**Answer**: The generator is not versioned since it will always use only the most current version.

**TODO**: Document in project README or contributing guide

---

### Q10: Why does publish/ subproject point to rdmueller's fork?

**Context**: Publish task points to personal fork, not arc42 organization.

**Code**: `publish/build.gradle:23`
```groovy
repoUri = 'https://github.com/rdmueller/arc42-template.git'
```

**Status**: Unanswered - may be intentional (Ralf's personal fork for testing)

---

## Decision Rationale

### Q13: How are new language contributions reviewed?

**Answer**: "This is a question for the template itself, not the generator"

**Resolution**: Out of scope for arc42-generator documentation. Refer to arc42-template repository.

---

## Recently Incorporated Into Documentation

**The following questions have been answered and incorporated** (2025-10-30):

- ~~Q6: Regex-based feature removal~~ → Documented in docs/arc42/09-decisions/ADR-004-feature-flag-system.md
- ~~Q7: Submodule separation~~ → Documented in docs/arc42/09-decisions/ADR-005-submodule-architecture.md
- ~~Q17: Pandoc version pinning~~ → Documented in docs/arc42/09-decisions/ADR-006-pandoc-as-converter.md
- ~~Q21: Common folder fix~~ → Documented in docs/onboarding/common-issues.md (Issue 11)

---


