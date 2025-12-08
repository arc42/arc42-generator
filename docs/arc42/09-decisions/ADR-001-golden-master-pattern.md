# ADR-001: Golden Master Pattern

**Status**: Accepted
**Date**: 2014-2015 (estimated)
**Impact**: 🔴 Critical - Foundation of entire system

## Context

The arc42 template is offered in multiple style variants:
- **Plain**: Structure only, no help text
- **With-Help**: Structure + embedded help text
- **With-Examples** (planned): Structure + help + examples

Maintaining separate source files for each variant leads to:
- Massive content duplication (structure identical, only help text differs)
- Synchronization nightmares (change structure in one file, must manually sync to others)
- High risk of variants drifting apart (missed sync = inconsistent templates)
- Linear scaling: 2 variants = 2× files, 3 variants = 3× files

With 12 sections per template, 10+ languages, 3 variants = 360+ source files to keep synchronized manually.

## Decision

Maintain a single "Golden Master" source file containing ALL variants, using feature flags to mark conditional content. Build system removes unwanted features via regex to create specific variants.

**Implementation**:
```asciidoc
=== Quality Goals

[role="arc42help"]
****
.Contents
The top three (max five) quality goals...
****

[Content here appears in ALL variants]
```

**Feature flag configuration** (`buildconfig.groovy`):
```groovy
allFeatures = ['help', 'example']

templateStyles = [
    'plain'    : [],              // Remove all features
    'with-help': ['help'],        // Keep help, remove examples
]
```

**Removal logic** (`build.gradle`):
```groovy
def featuresToRemove = allFeatures - featuresWanted
featuresToRemove.each { feature ->
    template = template.replaceAll(
        /(?ms)\[role="arc42${feature}"\][ \r\n]+[*]{4}.*?[*]{4}/,
        ''
    )
}
```

## Consequences

### Positive
- ✅ **DRY Principle**: Structure maintained in ONE place, changes propagate automatically
- ✅ **Impossible to drift**: Variants generated from same source, guaranteed structural consistency
- ✅ **Easy variant addition**: New variant = new feature flag configuration, no new files
- ✅ **Reduced maintenance**: ~70% reduction in source files to maintain
- ✅ **Single workflow**: Content authors work in one place

### Negative
- ❌ **Increased complexity**: Golden Master files contain all variants (more complex than single variant)
- ❌ **Learning curve**: Content authors must understand feature flag syntax
- ❌ **Fragile removal**: Regex-based removal depends on exact syntax matching
- ❌ **No direct preview**: Can't preview final variant without running generator
- ❌ **Debugging harder**: If feature appears in wrong variant, must check both Golden Master and regex

## Alternatives Considered

### Alternative 1: Separate Source Files per Variant
Maintain `plain/01_introduction.adoc` and `with-help/01_introduction.adoc` separately.

**Rejected because**:
- Requires manual synchronization of structural changes across all variant files
- High risk of human error (forgetting to sync a file)
- Already experienced this pain before generator existed
- Linear scaling in maintenance burden

### Alternative 2: Programmatic Content Assembly
Embed template content as data structures in code:
```groovy
def section1 = [
    title: "Introduction",
    help: "This is help text...",
    content: "..."
]
```

**Rejected because**:
- Terrible separation of concerns (content mixed with code)
- Harder for content authors (requires programming knowledge)
- Loses AsciiDoc tooling support (syntax highlighting, preview, etc.)
- Makes content harder to review

### Alternative 3: AsciiDoc Native Preprocessing
Use AsciiDoc's built-in `ifdef::attribute[]` system:
```asciidoc
ifdef::with-help[]
Help text here
endif::[]
```

Then build with different attribute sets.

**Rejected because**:
- Would require running Asciidoctor multiple times with different attributes
- Less control over feature combinations (AsciiDoc's ifdef is binary)
- Harder to implement "plain" variant (which is absence of all features)
- Regex approach gives complete control over what's removed

## Current Status

Successfully implemented and proven over 10+ years. The Golden Master pattern is now the foundation of the entire system. Adding the "with-examples" variant would be trivial (just define `example` feature flags in content, add to templateStyles configuration).

## Related

- [ADR-004: Feature Flag System](ADR-004-feature-flag-system.md) - Implementation details
- [Solution Strategy](../04-solution-strategy.md#decision-1-golden-master-pattern) - Detailed explanation
- [Concepts: Golden Master Pattern](../08-concepts.md#concept-1-the-golden-master-pattern) - Mental model
