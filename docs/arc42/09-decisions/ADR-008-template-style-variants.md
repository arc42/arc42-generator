# ADR-008: Template Style Variants

**Status**: Accepted
**Date**: 2014-2015 (estimated)
**Impact**: 🟡 High - User experience and usability

## Context

Different users have different needs:
- **Beginners**: Need structure + explanatory help text + examples
- **Experienced**: Want just structure, know arc42 well
- **Learning**: Want help text but not examples

Providing only one variant forces compromises:
- Too much help text → cluttered for experienced users
- No help text → steep learning curve for beginners

## Decision

Offer multiple template style variants generated from single Golden Master:

1. **plain**: Structure only, no help text or examples
2. **with-help**: Structure + embedded help text
3. **with-examples** (planned): Structure + help + examples

**Implementation**: Feature flag system (ADR-004) removes unwanted content.

**Configuration** (`buildconfig.groovy`):
```groovy
templateStyles = [
    'plain'    : [],              // Remove all features
    'with-help': ['help'],        // Keep help, remove examples
]
```

## Consequences

### Positive
- ✅ **User choice**: Each user gets appropriate complexity level
- ✅ **Learning path**: Beginners start with help, graduate to plain
- ✅ **No maintenance overhead**: Variants generated automatically from Golden Master
- ✅ **Consistent structure**: All variants guaranteed identical structure

### Negative
- ❌ **Choice overload**: Users must choose which variant (can be confusing)
- ❌ **Testing burden**: Must test all variants
- ❌ **Distribution size**: More variants = more ZIP files

## User Guidance

**Recommendation to users**:
- **New to arc42?** → Start with "with-help"
- **Know arc42?** → Use "plain"
- **Teaching arc42?** → Use "with-help" or "with-examples" (when available)

## Alternatives Considered

**Alternative 1**: Single variant with optional sections
User deletes sections they don't need.
- **Rejected**: Manual deletion, no automation benefit

**Alternative 2**: Interactive generator (ask questions, generate custom)
- **Rejected**: Complex implementation, would require web app

**Alternative 3**: Modular include system
Users include help modules they want.
- **Rejected**: Requires users to understand module system

## Future: with-examples Variant

Partially implemented in Golden Master (`[role="arc42example"]`), but:
- Not yet activated in `buildconfig.groovy`
- No example content finalized

**To activate**:
```groovy
templateStyles = [
    'plain'    : [],
    'with-help': ['help'],
    'with-examples': ['help', 'example'],  // Add this
]
```

## Related

- [ADR-001: Golden Master Pattern](ADR-001-golden-master-pattern.md)
- [ADR-004: Feature Flag System](ADR-004-feature-flag-system.md)
- [Introduction: Quality Goals](../01-introduction.md#2-easily-usable-priority-high)
