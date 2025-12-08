# ADR-007: Version Per Language

**Status**: Accepted
**Date**: 2015-2016 (estimated)
**Impact**: 🟢 Medium - Version management strategy

## Context

Template content evolves over time. Different languages may be at different maturity levels:
- Some languages complete and stable (DE, EN)
- Some newly added, incomplete (e.g., new translation)
- Need to track version independently per language

Versioning options:
1. Single global version for all languages
2. Independent version per language
3. Separate versioning for structure vs. translations

## Decision

Each language maintains independent version metadata in `{LANG}/version.properties`:

```properties
revnumber=9.0-EN
revdate=July 2025
revremark=(based upon AsciiDoc version)
```

**Usage**:
- Substituted into generated document headers
- Included in distribution ZIP filenames
- Displayed in rendered templates

## Consequences

### Positive
- ✅ **Flexibility**: New languages can start at v1.0 while others at v9.0
- ✅ **Transparency**: Users see language-specific version
- ✅ **Independent evolution**: Translation updates don't require global version bump

### Negative
- ❌ **Inconsistency**: Different languages at different versions may confuse users
- ❌ **Coordination**: Structural changes should update all languages, easy to miss one

## Alternatives Considered

**Alternative 1**: Single global version
- **Rejected**: Forces all languages to same version even if translation incomplete

**Alternative 2**: Separate structure vs. content versioning
- **Rejected**: Over-complicated, users don't care about internal distinction

## Current Status

Works well. Most mature languages (DE, EN) at v9.0. Newer translations may be at earlier versions, signaling maturity level to users.

## Related

- [Building Blocks: Version Properties Loading](../05-building-blocks.md#version-properties-loading)
- Code: `settings.gradle:15-33`, `arc42-template/{LANG}/version.properties`
