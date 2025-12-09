# ADR-002: Dynamic Gradle Subproject Generation

**Status**: Accepted
**Date**: 2015-2016 (estimated)
**Impact**: 🔴 Critical - Enables scalability to 10+ languages

## Context

With 10+ languages and 2+ template styles, we have 20+ combinations. Each needs:
- Independent build configuration
- Language-specific version metadata
- Parallel execution capability
- Identical build logic (consistency)

Traditional Gradle approach: Manually list each subproject in `settings.gradle`:
```groovy
include 'DE:plain'
include 'DE:with-help'
include 'EN:plain'
// ... 20+ entries
```

**Problem**: Adding a new language requires manually updating configuration, defeating automation goals.

## Decision

Generate Gradle subprojects dynamically by scanning the directory structure created by `createTemplatesFromGoldenMaster`.

**Convention**: Presence of `build/src_gen/{LANG}/asciidoc/{STYLE}/src/` directory triggers subproject creation.

**Implementation** (`settings.gradle`):
```groovy
target.eachFileRecurse { f ->
    if (f.name == 'src') {
        def language = parentFilePath.split('[/\\\\]')[-3]  // e.g., "EN"
        def docFormat = parentFilePath.split('[/\\\\]')[-1] // e.g., "plain"

        // Copy subBuild.gradle template, substitute placeholders
        new File(parentFilePath + "/build.gradle")
            .write(new File("subBuild.gradle").text
                .replaceAll('%LANG%', language)
                .replaceAll('%TYPE%', docFormat)
                .replaceAll('%REVNUMBER%', versionProps.revnumber))

        include("${language}:${docFormat}")
    }
}
```

## Consequences

### Positive
- ✅ **Convention Over Configuration**: Directory structure IS the configuration
- ✅ **Zero-config language addition**: Add Golden Master files → rebuild → works
- ✅ **Consistency**: All subprojects get identical build logic via template
- ✅ **Scalability**: Tested with 10+ languages, can handle any number
- ✅ **Self-documenting**: Directory layout shows what exists

### Negative
- ❌ **"Chicken-and-Egg" problem**: settings.gradle runs before tasks, but needs task output
- ❌ **IDE confusion**: IntelliJ/Eclipse don't see subprojects until after first build
- ❌ **Debugging harder**: Subprojects created dynamically, not visible in static config
- ❌ **Path parsing fragility**: Relies on specific directory structure

**Chicken-and-Egg Solution**:
```groovy
if (target.exists()) {
    // Only discover if build/src_gen/ exists
}
```

Required workflow: `./gradlew createTemplatesFromGoldenMaster` first, then other tasks.

## Alternatives Considered

### Alternative 1: Static Subproject List
```groovy
['DE','EN','FR','CZ'].each { lang ->
    ['plain','with-help'].each { style ->
        include("${lang}:${style}")
    }
}
```

**Rejected because**: Requires manual updates when adding languages, defeats automation.

### Alternative 2: Gradle Plugin with Custom DSL
Create custom Gradle plugin to manage subproject creation.

**Rejected because**: Over-engineering for this use case, higher maintenance burden, harder for community contributions.

### Alternative 3: Multi-Module Maven
Use Maven's multi-module structure.

**Rejected because**: Maven less flexible for dynamic structures, Gradle ecosystem better fit.

## Current Status

Successfully proven with 10+ languages. The main limitation is the hardcoded language list override in `build.gradle:41` (see Open Questions).

## Related

- [Solution Strategy](../04-solution-strategy.md#decision-2-dynamic-gradle-subproject-generation)
- [Concepts: Dynamic Subproject Generation](../08-concepts.md#concept-5-dynamic-subproject-generation-convention-over-configuration)
- [Concepts: Chicken-and-Egg Problem](../08-concepts.md#concept-2-the-chicken-and-egg-problem-gradle-build-lifecycle)
