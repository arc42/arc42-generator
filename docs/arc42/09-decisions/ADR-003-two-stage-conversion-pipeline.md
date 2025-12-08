# ADR-003: Two-Stage Conversion Pipeline

**Status**: Accepted
**Date**: 2014-2015 (estimated)
**Impact**: 🟡 High - Affects all format conversions

## Context

Need to convert AsciiDoc templates to 15+ output formats:
- Document formats: DOCX, PDF, ODT
- Markup formats: Markdown (5+ variants), RST, Textile
- Publishing formats: EPUB, LaTeX

**Tool landscape**:
- **Asciidoctor**: Best-in-class AsciiDoc processor, outputs HTML/DocBook/PDF
- **Pandoc**: Universal document converter, supports 40+ formats
- **Problem**: Pandoc's AsciiDoc support is limited; Asciidoctor doesn't support all target formats

## Decision

Use DocBook XML as intermediate format: AsciiDoc → (Asciidoctor) → DocBook → (Pandoc) → Target Formats

**Exception**: HTML uses direct conversion (AsciiDoc → HTML via Asciidoctor) for quality.

## Consequences

### Positive
- ✅ **Best-of-breed tools**: Asciidoctor for AsciiDoc, Pandoc for format conversion
- ✅ **High quality**: Asciidoctor captures all AsciiDoc semantics in DocBook
- ✅ **Proven pattern**: DocBook designed as interchange format for technical docs
- ✅ **Easy format addition**: Just add Pandoc output target

### Negative
- ❌ **Two-step process**: Slower than direct conversion
- ❌ **Disk space**: DocBook intermediate files
- ❌ **Potential information loss**: DocBook may not represent all AsciiDoc features
- ❌ **Debugging complexity**: Issues could be in either step

## Alternatives Considered

**Alternative 1**: Direct Pandoc (AsciiDoc → Target)
- **Rejected**: Pandoc's AsciiDoc parser is basic, misses features

**Alternative 2**: Asciidoctor converters for each format
- **Rejected**: Would require writing/maintaining 15+ custom converters

**Alternative 3**: Custom converter per format
- **Rejected**: Enormous effort, Pandoc is battle-tested

## Related

- [Solution Strategy](../04-solution-strategy.md#decision-3-two-stage-conversion-pipeline-asciidoc--docbook--target)
- [Concepts: Two-Stage Conversion](../08-concepts.md#concept-3-two-stage-conversion-pipeline-docbook-as-lingua-franca)
- [ADR-006: Pandoc as Universal Converter](ADR-006-pandoc-as-converter.md)
