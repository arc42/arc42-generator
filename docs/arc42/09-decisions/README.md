# Architecture Decision Records

This directory contains Architecture Decision Records (ADRs) documenting significant architectural choices made in the arc42-generator project.

## ADR Index

| ADR | Title | Status | Date | Impact |
|-----|-------|--------|------|--------|
| [ADR-001](ADR-001-golden-master-pattern.md) | Golden Master Pattern | Accepted | 2014-2015 (est.) | 🔴 Critical - Foundation of entire system |
| [ADR-002](ADR-002-dynamic-subproject-generation.md) | Dynamic Gradle Subproject Generation | Accepted | 2015-2016 (est.) | 🔴 Critical - Enables scalability |
| [ADR-003](ADR-003-two-stage-conversion-pipeline.md) | Two-Stage Conversion Pipeline | Accepted | 2014-2015 (est.) | 🟡 High - Affects all format conversions |
| [ADR-004](ADR-004-feature-flag-system.md) | Feature Flag System (Regex-based) | Accepted | 2014-2015 (est.) | 🟡 High - Implements Golden Master |
| [ADR-005](ADR-005-submodule-architecture.md) | Submodule Architecture | Accepted | 2014 (est.) | 🟡 High - Separation of concerns |
| [ADR-006](ADR-006-pandoc-as-converter.md) | Pandoc as Universal Converter | Accepted | 2014-2015 (est.) | 🔴 Critical - External dependency |
| [ADR-007](ADR-007-version-per-language.md) | Version Per Language | Accepted | 2015-2016 (est.) | 🟢 Medium - Version management |
| [ADR-008](ADR-008-template-style-variants.md) | Template Style Variants | Accepted | 2014-2015 (est.) | 🟡 High - User experience |

## Evolution Phases

### Phase 1: Foundation (2014-2015)
**Goal**: Automate multi-format template generation

**Decisions Made**:
- ADR-001: Golden Master Pattern
- ADR-003: Two-Stage Conversion Pipeline
- ADR-004: Feature Flag System
- ADR-005: Submodule Architecture
- ADR-006: Pandoc as Universal Converter
- ADR-008: Template Style Variants

**Outcome**: Basic automation working, supporting DE and EN, core formats (HTML, DOCX, PDF)

### Phase 2: Scaling (2015-2018)
**Goal**: Support 10+ languages without exponential complexity

**Decisions Made**:
- ADR-002: Dynamic Subproject Generation
- ADR-007: Version Per Language

**Outcome**: Successfully scaling to 10+ languages, 15+ formats

### Phase 3: Maintenance (2018-Present)
**Goal**: Keep system working as dependencies evolve

**Ongoing Work**:
- Gradle API modernization (removing deprecated APIs)
- Pandoc version management
- New format additions (mkdocs, variants of markdown)

## Decision Timeline

```
2014           2015           2016           2017           2018-Present
 │              │              │              │              │
 ├─ ADR-001    ├─ ADR-002     │              │              │
 │  (Golden    │  (Dynamic    │              │              │
 │   Master)   │   Subproj)   │              │              │
 │             │              │              │              │
 ├─ ADR-003    ├─ ADR-007     │              │              │
 │  (Two-      │  (Version    │              │              │
 │   Stage)    │   per Lang)  │              │              │
 │             │              │              │              │
 ├─ ADR-004    │              │              │              │
 │  (Feature   │              │              │              │
 │   Flags)    │              │              │              │
 │             │              │              │              │
 ├─ ADR-005    │              │              │              │
 │  (Submod)   │              │              │              │
 │             │              │              │              │
 ├─ ADR-006    │              │              │              │
 │  (Pandoc)   │              │              │              │
 │             │              │              │              │
 ├─ ADR-008    │              │              │              │
 │  (Variants) │              │              │              │
 │             │              │              │              │
 └─────────────┴──────────────┴──────────────┴──────────────┴──────────
 Initial        Scaling        Maturity       Refinement     Maintenance
 Automation     Languages                                    Gradle updates
```

Note: Exact dates are estimates based on git history analysis. Actual decisions may have evolved over time.

## Reading Guide

**New Contributors**: Start with these in order:
1. ADR-001 (Golden Master) - Foundation concept
2. ADR-006 (Pandoc) - Key external dependency
3. ADR-003 (Two-Stage) - How conversion works
4. ADR-002 (Dynamic Subprojects) - How build scales

**Debugging Build Issues**: Reference:
- ADR-002 for subproject discovery problems
- ADR-004 for feature flag/variant issues
- ADR-006 for format conversion problems

**Adding Features**: Consider:
- ADR-002 if adding languages
- ADR-006 if adding formats
- ADR-004 if adding new content variants

## ADR Template

All ADRs follow this structure:
- **Status**: Accepted/Deprecated/Superseded
- **Context**: Problem and constraints
- **Decision**: What we decided
- **Consequences**: Positive and negative outcomes
- **Alternatives Considered**: What we rejected and why

## Related Documentation

- [Solution Strategy](../04-solution-strategy.md) - High-level explanation of top 5 decisions
- [Concepts](../08-concepts.md) - Mental models for understanding the system
- [Building Blocks](../05-building-blocks.md) - Implementation details

## Open Questions

- **Exact decision dates**: Git history doesn't clearly show when each architectural choice was made
- **Decision makers**: Who made these decisions? (Likely Gernot Starke, Ralf D. Müller based on git history)
- **Rejected alternatives**: What else was tried? Limited evidence in git history
- **Evolution story**: How did the system evolve? Some ADRs may have been implicit initially, formalized later

## Contributing

When making significant architectural changes:
1. Create new ADR documenting the decision
2. Update existing ADRs if they're superseded
3. Update this README with the new ADR
4. Reference the ADR in code comments where appropriate
