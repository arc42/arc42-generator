# ADR-005: Submodule Architecture

**Status**: Accepted
**Date**: 2014 (estimated)
**Impact**: 🟡 High - Separation of concerns

## Context

Two distinct concerns:
1. **Template Content**: arc42 structure, help text, translations (maintained by content experts)
2. **Build System**: Gradle, conversion logic, distribution (maintained by build engineers)

Different stakeholders, different change frequencies, different expertise required.

## Decision

Maintain template content in separate repository (arc42-template), included as Git submodule.

**Structure**:
```
arc42-generator/              (build system repo)
├── build.gradle
├── buildconfig.groovy
└── arc42-template/           (git submodule)
    ├── DE/asciidoc/
    ├── EN/asciidoc/
    └── dist/                 (distributions written here)
```

## Consequences

### Positive
- ✅ **Clean separation**: Content authors don't need Gradle knowledge
- ✅ **Independent evolution**: Content changes don't require build changes
- ✅ **Reusability**: Users can fork arc42-template without build system
- ✅ **Independent versioning**: Template content at v9.0, build system separate
- ✅ **Easier review**: Content PRs separate from build PRs

### Negative
- ❌ **Submodule complexity**: Many developers unfamiliar with git submodules
- ❌ **Sync issues**: Easy to forget `git submodule update`
- ❌ **Cross-repo coordination**: Breaking changes need coordination
- ❌ **Unusual pattern**: Generated ZIPs committed to submodule

**The Distribution Dilemma**: ZIPs committed to arc42-template/dist/ because users download from that repo (public interface), even though they're generated files.

## Alternatives Considered

**Alternative 1**: Monorepo (everything together)
- **Rejected**: Mixes concerns, higher barrier for content contributions

**Rationale from Maintainer** (confirmed 2025-10-30):
> "Just a separation of concerns. We have the template repository and the generator."

This confirms the primary driver was keeping template content separate from build logic, allowing each to evolve independently.

**Alternative 2**: Two independent repos (no submodule link)
- **Rejected**: Must manually sync, loses build-time connection

**Alternative 3**: Template content as Maven/npm dependency
- **Rejected**: Adds packaging overhead, submodule simpler

## Related

- [Solution Strategy](../04-solution-strategy.md#decision-4-submodule-architecture-content-vs-build-separation)
- [Context](../03-context.md#neighbor-systems)
