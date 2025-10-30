#!/usr/bin/env groovy

/**
 * build.groovy - Main build orchestration script for arc42-generator
 *
 * This script replaces the Gradle build system with a standalone Groovy solution.
 *
 * Pipeline:
 * 1. Load configuration from buildconfig.groovy
 * 2. Create templates from Golden Master (Templates.groovy)
 * 3. Discover generated templates (Discovery.groovy)
 * 4. Convert templates to all formats (Converter.groovy)
 *
 * Usage:
 *   groovy build.groovy                    # Full build (all steps)
 *   groovy build.groovy templates          # Only generate templates
 *   groovy build.groovy convert            # Only convert (assumes templates exist)
 *   groovy build.groovy convert --format=html  # Convert to specific format only
 *   groovy build.groovy --parallel=false   # Disable parallel execution
 */

// ============================================================================
// Configuration
// ============================================================================

def startTime = System.currentTimeMillis()
def projectRoot = new File('.')

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

if (cliArgs) {
    targetPhase = cliArgs.find { !it.startsWith('--') } ?: 'all'
    useParallel = !cliArgs.contains('--parallel=false')
    def formatArg = cliArgs.find { it.startsWith('--format=') }
    if (formatArg) {
        targetFormat = formatArg.split('=')[1]
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
println ""

// ============================================================================
// Load Configuration
// ============================================================================

println "=== Loading Configuration ==="
def config
try {
    config = new ConfigSlurper().parse(new File('buildconfig.groovy').toURI().toURL())
    println "✓ Configuration loaded from buildconfig.groovy"

    def formats = config.formats.keySet() as List
    println "  Templates: ${config.goldenMaster.templateStyles.keySet().size()} styles"
    println "  Formats: ${formats.size()} (${formats.take(5).join(', ')}${formats.size() > 5 ? '...' : ''})"
    println ""
} catch (Exception e) {
    println "✗ Failed to load buildconfig.groovy: ${e.message}"
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

try {
    templatesClass = gcl.parseClass(new File('lib/Templates.groovy'))
    println "✓ Loaded Templates.groovy"

    discoveryClass = gcl.parseClass(new File('lib/Discovery.groovy'))
    println "✓ Loaded Discovery.groovy"

    converterClass = gcl.parseClass(new File('lib/Converter.groovy'))
    println "✓ Loaded Converter.groovy"
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

// ============================================================================
// Phase 1: Generate Templates from Golden Master
// ============================================================================

if (targetPhase in ['all', 'templates']) {
    try {
        templates.createFromGoldenMaster()
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

        converter.convertAll(discoveredTemplates, formatsToConvert, useParallel)

    } catch (Exception e) {
        println "\n✗ Conversion failed: ${e.message}"
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
