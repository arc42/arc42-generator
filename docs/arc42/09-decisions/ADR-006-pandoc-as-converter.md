# ADR-006: Pandoc as Universal Converter

**Status**: Accepted
**Date**: 2014-2015 (estimated)
**Impact**: 🔴 Critical - External dependency

## Context

Need to support 15+ output formats beyond what Asciidoctor provides directly. Each format has unique complexities (DOCX structure, LaTeX syntax, Markdown variants, EPUB packaging, etc.).

## Decision

Use Pandoc as external CLI tool for format conversions, rather than implementing custom converters.

**Implementation**:
```groovy
task convert2Docx (type: Exec) {
    executable = "pandoc"
    args = ['-r','docbook', '-t','docx', '-o', outputFile, inputFile]
}
```

**Version**: Pinned to 3.7.0.2 via build-arc42.sh installation.

**Rationale for Version Pinning** (confirmed by maintainer 2025-10-30):
> "All versions are pinned for stability"

This ensures reproducible builds - different Pandoc versions can produce slightly different output, so pinning guarantees consistent results across environments.

## Consequences

### Positive
- ✅ **Massive functionality**: 40+ formats for zero implementation cost
- ✅ **Production quality**: Battle-tested over 15+ years
- ✅ **Community maintained**: Bugs fixed upstream
- ✅ **Format quirks handled**: Pandoc handles edge cases we'd miss

### Negative
- ❌ **External dependency**: Must install Pandoc before build
- ❌ **Version sensitivity**: Different versions may produce different output
- ❌ **Limited control**: Can't easily customize Pandoc's conversion logic
- ❌ **Debugging**: Format issues may be Pandoc bugs, need upstream fix
- ❌ **Platform-specific**: Installation varies by OS

**Risk Mitigation**:
- Version pinned (3.7.0.2)
- build-arc42.sh auto-installs
- Docker/Gitpod pre-install
- Clear documentation

## Alternatives Considered

**Alternative 1**: Custom converters per format (docx4j, JLaTeXMath, etc.)
- **Rejected**: Would require 15+ tool integrations, inconsistent quality

**Alternative 2**: Cloud conversion API (CloudConvert, Aspose)
- **Rejected**: Requires internet, ongoing costs, vendor lock-in

**Alternative 3**: Pure Java/JVM converters
- **Rejected**: No comprehensive JVM library matches Pandoc's breadth

## Why External Dependency is Acceptable

- Target users are developers (comfortable installing tools)
- Automation handles installation
- Industry-standard tool (many projects depend on Pandoc)
- Gain far outweighs installation friction

## Related

- [Solution Strategy](../04-solution-strategy.md#decision-5-pandoc-as-universal-converter-vs-custom-implementation)
- [ADR-003: Two-Stage Conversion](ADR-003-two-stage-conversion-pipeline.md)
- [Building Blocks: Subproject Build Logic](../05-building-blocks.md#format-conversion-tasks)
