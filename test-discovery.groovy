#!/usr/bin/env groovy

/**
 * Test script for Discovery.groovy
 * Tests template discovery and validation
 */

// Load buildconfig.groovy
def config = new ConfigSlurper().parse(new File('buildconfig.groovy').toURI().toURL())

// Load Discovery class
def gcl = new GroovyClassLoader()
def discoveryClass = gcl.parseClass(new File('lib/Discovery.groovy'))

// Create Discovery instance
def discovery = discoveryClass.newInstance(config)

try {
    // Test 1: Discover all templates
    println "=== Test 1: Discover All Templates ==="
    def templates = discovery.discoverTemplates()
    println "Found ${templates.size()} template(s)"
    assert templates.size() > 0, "Should discover at least one template"
    println "✓ Test 1 passed\n"

    // Test 2: Check template structure
    println "=== Test 2: Validate Template Metadata ==="
    def firstTemplate = templates[0]
    println "Sample template: ${firstTemplate.language}/${firstTemplate.style}"
    assert firstTemplate.language != null, "Template should have language"
    assert firstTemplate.style != null, "Template should have style"
    assert firstTemplate.sourcePath != null, "Template should have sourcePath"
    assert firstTemplate.mainFile != null, "Template should have mainFile"
    assert new File(firstTemplate.mainFile).exists(), "Main file should exist"
    println "✓ Test 2 passed\n"

    // Test 3: Get unique languages
    println "=== Test 3: Get Languages ==="
    def languages = discovery.getLanguages()
    println "Languages: ${languages}"
    assert languages.size() > 0, "Should have at least one language"
    println "✓ Test 3 passed\n"

    // Test 4: Get unique styles
    println "=== Test 4: Get Styles ==="
    def styles = discovery.getStyles()
    println "Styles: ${styles}"
    assert styles.size() > 0, "Should have at least one style"
    assert styles.contains('plain'), "Should have 'plain' style"
    assert styles.contains('with-help'), "Should have 'with-help' style"
    println "✓ Test 4 passed\n"

    // Test 5: Find by language
    println "=== Test 5: Find by Language ==="
    def enTemplates = discovery.findByLanguage('EN')
    println "EN templates: ${enTemplates*.style}"
    assert enTemplates.size() > 0, "Should find EN templates"
    println "✓ Test 5 passed\n"

    // Test 6: Find by style
    println "=== Test 6: Find by Style ==="
    def plainTemplates = discovery.findByStyle('plain')
    println "Plain templates: ${plainTemplates*.language}"
    assert plainTemplates.size() > 0, "Should find plain templates"
    println "✓ Test 6 passed\n"

    // Test 7: Find specific template
    println "=== Test 7: Find Specific Template ==="
    def template = discovery.findTemplate('EN', 'plain')
    assert template != null, "Should find EN:plain template"
    println "Found: ${template.language}/${template.style}"
    println "  Main file: ${template.mainFileName}"
    println "  File count: ${template.adocFileCount}"
    println "  Version: ${template.revnumber}"
    println "✓ Test 7 passed\n"

    // Test 8: Validate expected templates
    println "=== Test 8: Validate Expected Templates ==="
    discovery.validate(['EN', 'DE'], ['plain', 'with-help'])
    println "✓ Test 8 passed\n"

    println "=== All Tests Passed! ==="
    System.exit(0)

} catch (Exception e) {
    println "\n✗ Test failed with error:"
    println e.message
    e.printStackTrace()
    System.exit(1)
}
