# ADR-004: Feature Flag System (Regex-based)

**Status**: Accepted
**Date**: 2014-2015 (estimated)
**Impact**: 🟡 High - Implements Golden Master pattern

## Context

Golden Master Pattern (ADR-001) requires removing unwanted content to create variants. Need mechanism to:
- Mark content as conditional (feature flags)
- Remove marked content reliably
- Support nested structures (AsciiDoc blocks)
- Work with any AsciiDoc syntax

## Decision

Use regular expressions to remove feature-flagged content blocks, rather than AsciiDoc native preprocessing.

**Flag Syntax** (in Golden Master):
```asciidoc
[role="arc42help"]
****
Help text here - removed for "plain" variant
****
```

**Removal Pattern**:
```groovy
template = template.replaceAll(
    /(?ms)\[role="arc42${feature}"\][ \r\n]+[*]{4}.*?[*]{4}/,
    ''
)
```

**Regex breakdown**:
- `(?ms)`: Multiline mode, dot matches newlines
- `\[role="arc42help"\]`: Match role attribute literally
- `[ \r\n]+`: Whitespace after attribute
- `[*]{4}`: Exactly 4 asterisks (AsciiDoc delimiter)
- `.*?`: Non-greedy content match
- `[*]{4}`: Closing delimiter

## Consequences

### Positive
- ✅ **Complete control**: Can remove any pattern
- ✅ **No AsciiDoc limitations**: Works with any content
- ✅ **Simple config**: Just list features to remove

### Negative
- ❌ **Fragile**: Must match exact syntax (4 asterisks, specific spacing)
- ❌ **No semantic understanding**: Pure string matching
- ❌ **Edge cases**: Nested blocks, unusual formatting can break
- ❌ **Debugging**: If regex fails, content leaks to wrong variant

## Alternatives Considered

**Alternative 1**: AsciiDoc `ifdef` preprocessing
```asciidoc
ifdef::with-help[]
Help text
endif::[]
```

**Rejected because**:
- Requires multiple Asciidoctor runs with different attributes
- Less control over feature combinations
- Harder to implement "plain" (absence of features)

**Historical Context from Maintainer** (confirmed 2025-10-30):
> "We wanted to be able to have clean docs without the help texts. Later we added the ifdef-AsciiDoc statement, so that you can hide the help text when you use the template with help-text."

This explains the evolution: Started with regex for clean variants, later added `ifdef` for hiding help text when *using* templates (not generating them).

**Alternative 2**: Programmatic AST manipulation
Parse AsciiDoc to AST, remove nodes, regenerate.

**Rejected because**: Complex, no good AsciiDoc AST library in Groovy/Java ecosystem.

## Current Status

Works reliably for 10+ years. Main risk: Content authors must follow exact syntax (exactly 4 asterisks, proper spacing).

**Best Practice**: Test both plain and with-help variants after Golden Master changes.

## Related

- [ADR-001: Golden Master Pattern](ADR-001-golden-master-pattern.md)
- [Concepts: Feature Flag Processing](../08-concepts.md#concept-4-feature-flag-processing-regex-surgery)
- Code: `build.gradle:88-94`
