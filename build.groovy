#!/usr/bin/env groovy

/**
 * build.groovy - Main build orchestration script for arc42-generator
 *
 * This script replaces the Gradle build system with a standalone Groovy solution.
 *
 * Pipeline:
 * 1. Load configuration from buildconfig.groovy (or the file given by --config)
 * 2. Create templates from Golden Master (Templates.groovy)
 * 3. Discover generated templates (Discovery.groovy)
 * 4. Convert templates to all formats (Converter.groovy)
 * 5. Create ZIP distributions (Packager.groovy)
 *
 * Usage:
 *   groovy build.groovy                         # Full build (all steps)
 *   groovy build.groovy templates               # Only generate templates
 *   groovy build.groovy convert                 # Only convert (assumes templates exist)
 *   groovy build.groovy distribution            # Only create distributions
 *   groovy build.groovy convert --format=html   # Convert to specific format only
 *   groovy build.groovy --parallel=false        # Disable parallel execution
 *   groovy build.groovy --config=path/to/config.groovy   # Build another template (e.g. req42)
 *   groovy build.groovy --failure-level=error   # Fail only on Asciidoctor/Pandoc errors (default: warn)
 *   groovy build.groovy --lint=warn             # Report golden master problems but do not fail on them
 *   groovy build.groovy --release-tag=2026.09.29   # Tag written to manifest.json (only set by make release)
 *
 * Every run starts from a clean output: the templates phase removes build/src_gen/, the convert
 * phase removes the output directories of the formats it converts.
 */

// ============================================================================
// Configuration
// ============================================================================

def startTime = System.currentTimeMillis()

// Parse command-line arguments
def cliArgs = []
try {
    if (binding.hasVariable('args')) {
        cliArgs = binding.args as List
    }
} catch (Exception e) {
    cliArgs = []
}

def targetPhase = 'all'
def useParallel = true
def targetFormat = null
def configPath = 'buildconfig.groovy'
// Asciidoctor/Pandoc diagnostics at this level or above fail the build: warn, error, fatal or none
def failureLevel = 'warn'
// Golden master validation problems fail the build unless --lint=warn is given
def failOnLintErrors = true
// Tag of the release the ZIPs are built for, written to manifest.json
def releaseTag = null

if (cliArgs) {
    targetPhase = cliArgs.find { !it.startsWith('--') } ?: 'all'
    useParallel = !cliArgs.contains('--parallel=false')
    def formatArg = cliArgs.find { it.startsWith('--format=') }
    if (formatArg) {
        targetFormat = formatArg.split('=')[1]
    }
    def configArg = cliArgs.find { it.startsWith('--config=') }
    if (configArg) {
        configPath = configArg.substring('--config='.length())
    }
    def failureArg = cliArgs.find { it.startsWith('--failure-level=') }
    if (failureArg) {
        failureLevel = failureArg.substring('--failure-level='.length()).toLowerCase()
        if (!(failureLevel in ['warn', 'error', 'fatal', 'none'])) {
            println "✗ Unknown --failure-level '${failureLevel}', expected warn, error, fatal or none"
            System.exit(2)
        }
    }
    failOnLintErrors = !cliArgs.contains('--lint=warn')
    def releaseTagArg = cliArgs.find { it.startsWith('--release-tag=') }
    if (releaseTagArg) {
        releaseTag = releaseTagArg.substring('--release-tag='.length())
    }
}

println """
╔═══════════════════════════════════════════════════════════════════════════╗
║                        arc42 Template Generator                           ║
║                     Standalone Groovy Build System                        ║
╚═══════════════════════════════════════════════════════════════════════════╝
"""

println "Build started: ${new Date()}"
println "Target phase: ${targetPhase}"
println "Parallel execution: ${useParallel}"
if (targetFormat) {
    println "Target format: ${targetFormat}"
}
println "Failure level: ${failureLevel}"
println ""

// ============================================================================
// Load Configuration
// ============================================================================

println "=== Loading Configuration ==="
def config
def configFile = new File(configPath).absoluteFile
// All paths in the config are relative to the directory of the config file
def projectRoot = configFile.parentFile
try {
    config = new ConfigSlurper().parse(configFile.toURI().toURL())
    println "✓ Configuration loaded from ${configFile}"

    def missing = ['name', 'featurePrefix', 'logo'].findAll { !config.project[it] }
    if (missing) {
        throw new IllegalStateException("missing settings: ${missing.collect { 'project.' + it }.join(', ')}")
    }
    println "  Project: ${config.project.name} (root: ${projectRoot})"

    def formats = config.formats.keySet() as List
    println "  Templates: ${config.goldenMaster.templateStyles.keySet().size()} styles"
    println "  Formats: ${formats.size()} (${formats.take(5).join(', ')}${formats.size() > 5 ? '...' : ''})"
    println ""
} catch (Exception e) {
    println "✗ Failed to load ${configPath}: ${e.message}"
    System.exit(1)
}

// ============================================================================
// Load Helper Classes
// ============================================================================

println "=== Loading Helper Classes ==="
def gcl = new GroovyClassLoader()

def templatesClass
def discoveryClass
def converterClass
def packagerClass

try {
    templatesClass = gcl.parseClass(new File('lib/Templates.groovy'))
    println "✓ Loaded Templates.groovy"

    discoveryClass = gcl.parseClass(new File('lib/Discovery.groovy'))
    println "✓ Loaded Discovery.groovy"

    converterClass = gcl.parseClass(new File('lib/Converter.groovy'))
    println "✓ Loaded Converter.groovy"

    packagerClass = gcl.parseClass(new File('lib/Packager.groovy'))
    println "✓ Loaded Packager.groovy"
    println ""
} catch (Exception e) {
    println "✗ Failed to load helper classes: ${e.message}"
    e.printStackTrace()
    System.exit(1)
}

// Create instances
def templates = templatesClass.newInstance(config, projectRoot)
def discovery = discoveryClass.newInstance(config, projectRoot)
def converter = converterClass.newInstance(config, projectRoot)
def packager = packagerClass.newInstance(config, projectRoot)

if (!failOnLintErrors && templates.hasProperty('failOnLintErrors')) {
    templates.failOnLintErrors = false
    println "⚠ --lint=warn: golden master problems are reported but do not fail the build"
}

// Reproducible output: dates inside DOCX/EPUB and the timestamps of the ZIP entries come from
// SOURCE_DATE_EPOCH or, if unset, from the last commit of the golden master, not from the build time.
// Commits that only touch the distribution directory (merged releases) do not count.
def lastTemplateCommit = packager.lastTemplateCommit()
def sourceDateEpoch = null
def sourceDateEpochEnv = System.getenv('SOURCE_DATE_EPOCH')
if (sourceDateEpochEnv?.isLong()) {
    sourceDateEpoch = sourceDateEpochEnv.toLong()
    println "✓ SOURCE_DATE_EPOCH from environment: ${sourceDateEpoch}"
} else if (lastTemplateCommit) {
    // without git, Pandoc and the packager fall back to the current time
    sourceDateEpoch = lastTemplateCommit.time
    println "✓ SOURCE_DATE_EPOCH from the golden master's last commit outside the distribution: ${sourceDateEpoch}"
}
if (sourceDateEpoch == null) {
    println "⚠ No SOURCE_DATE_EPOCH and no git history for the golden master: DOCX, EPUB and ZIP timestamps use the build time"
}
converter.sourceDateEpoch = sourceDateEpoch
packager.sourceDateEpoch = sourceDateEpoch

// manifest.json names the golden master commit (the same as above, no git: none) and, for a release, its tag
packager.releaseTag = releaseTag
packager.templateCommit = lastTemplateCommit?.commit

// ============================================================================
// Phase 1: Generate Templates from Golden Master
// ============================================================================

if (targetPhase in ['all', 'templates']) {
    try {
        // Start from scratch so that no file of an earlier run survives in the generated templates
        def srcGen = new File(projectRoot, config.goldenMaster.targetPath.toString()).canonicalFile
        if (srcGen.exists()) {
            // the target must be a subdirectory of the project, never the project itself or something outside
            if (!srcGen.path.startsWith(projectRoot.canonicalPath + File.separator)) {
                throw new IllegalStateException("goldenMaster.targetPath must be a subdirectory of ${projectRoot}: ${srcGen}")
            }
            srcGen.deleteDir()
            println "✓ Removed previous templates in ${config.goldenMaster.targetPath}"
        }

        templates.createFromGoldenMaster()
    } catch (IllegalStateException e) {
        // validation failures and missing files: the message says everything, no stack trace needed
        println "\n✗ Template generation failed: ${e.message}"
        System.exit(1)
    } catch (Exception e) {
        println "\n✗ Template generation failed: ${e.message}"
        e.printStackTrace()
        System.exit(1)
    }
}

// ============================================================================
// Phase 2: Discover Generated Templates
// ============================================================================

def discoveredTemplates = []

if (targetPhase in ['all', 'convert']) {
    println "=== Discovering Templates ==="
    try {
        discoveredTemplates = discovery.discoverTemplates()

        if (discoveredTemplates.isEmpty()) {
            println "✗ No templates found. Run 'groovy build.groovy templates' first."
            System.exit(1)
        }

    } catch (Exception e) {
        println "\n✗ Template discovery failed: ${e.message}"
        if (e.message?.contains('does not exist')) {
            println "\nHint: Run 'groovy build.groovy templates' first to generate templates."
        }
        System.exit(1)
    }
}

// ============================================================================
// Phase 3: Convert Templates to All Formats
// ============================================================================

if (targetPhase in ['all', 'convert']) {
    try {
        def formatsToConvert = targetFormat ? [targetFormat] : (config.formats.keySet() as List)

        // Start from scratch so that no file of an earlier run survives in the output (and in the ZIPs)
        converter.cleanOutputs(discoveredTemplates, formatsToConvert)

        def result = converter.convertAll(discoveredTemplates, formatsToConvert, useParallel)

        def failures = []
        if (result.failed > 0) {
            failures << "${result.failed} conversion(s) failed"
        }
        def diagnostics = converter.diagnosticsAtOrAbove(failureLevel)
        if (diagnostics) {
            failures << "${diagnostics.size()} diagnostic(s) at level ${failureLevel.toUpperCase()} or above (see 'Diagnostics' above)"
        }
        if (failures) {
            println "\n✗ Conversion failed: ${failures.join('; ')}"
            System.exit(1)
        }

    } catch (Exception e) {
        println "\n✗ Conversion failed: ${e.message}"
        e.printStackTrace()
        System.exit(1)
    }
}

// ============================================================================
// Phase 4: Create ZIP Distributions
// ============================================================================

if (targetPhase in ['all', 'distribution']) {
    try {
        // Discover templates if not already done
        if (discoveredTemplates.isEmpty()) {
            println "=== Discovering Templates ==="
            discoveredTemplates = discovery.discoverTemplates()
        }

        def formatsToPackage = targetFormat ? [targetFormat] : (config.formats.keySet() as List)

        packager.createAllDistributions(discoveredTemplates, formatsToPackage, useParallel)

    } catch (Exception e) {
        println "\n✗ Distribution creation failed: ${e.message}"
        e.printStackTrace()
        System.exit(1)
    }
}

// ============================================================================
// Summary
// ============================================================================

def endTime = System.currentTimeMillis()
def duration = (endTime - startTime) / 1000.0

println """
╔═══════════════════════════════════════════════════════════════════════════╗
║                            BUILD SUCCESSFUL                               ║
╚═══════════════════════════════════════════════════════════════════════════╝
"""

println "Duration: ${String.format('%.1f', duration)}s"

if (targetPhase in ['all', 'convert'] && discoveredTemplates) {
    def languages = discoveredTemplates*.language.unique().size()
    def styles = discoveredTemplates*.style.unique().size()
    def formats = targetFormat ? 1 : (config.formats.keySet().size())
    def totalOutputs = languages * styles * formats

    println """
Summary:
  Languages: ${languages}
  Styles: ${styles}
  Formats: ${formats}
  Total outputs: ${totalOutputs}
"""
}

println "Build completed: ${new Date()}"
println ""
