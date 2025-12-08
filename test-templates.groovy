#!/usr/bin/env groovy

/**
 * Test script for Templates.groovy
 * Tests language auto-discovery and Golden Master processing
 */

// Load buildconfig.groovy
def config = new ConfigSlurper().parse(new File('buildconfig.groovy').toURI().toURL())

// Load Templates class using GroovyClassLoader
def gcl = new GroovyClassLoader()
def templatesClass = gcl.parseClass(new File('lib/Templates.groovy'))

// Create Templates instance
def templates = templatesClass.newInstance(config)

try {
    // Test 1: Language auto-discovery
    println "=== Test 1: Language Auto-Discovery ==="
    def languages = templates.discoverLanguages()
    println "Languages: ${languages}"
    assert languages.size() > 0, "Should discover at least one language"
    println "✓ Test 1 passed\n"

    // Test 2: Feature removal
    println "=== Test 2: Feature Removal ==="
    def testTemplate = '''
[role="arc42help"]
****
This is help text that should be removed.
****

This text should remain.

ifdef::arc42help[]
This help content should also be removed.
endif::arc42help[]

This text should also remain.
'''

    def cleaned = templates.removeFeatures(testTemplate, ['help'])
    println "Original length: ${testTemplate.length()}"
    println "Cleaned length: ${cleaned.length()}"
    assert !cleaned.contains('[role="arc42help"]'), "Should remove role-based help blocks"
    assert !cleaned.contains('ifdef::arc42help'), "Should remove ifdef statements"
    assert cleaned.contains('This text should remain'), "Should keep non-help content"
    println "✓ Test 2 passed\n"

    // Test 3: Full template generation
    println "=== Test 3: Full Template Generation ==="
    println "Starting createFromGoldenMaster()...\n"
    templates.createFromGoldenMaster()
    println "\n✓ Test 3 passed\n"

    // Test 4: Verify output structure
    println "=== Test 4: Verify Output Structure ==="
    def buildSrcGen = new File('build/src_gen')
    assert buildSrcGen.exists(), "build/src_gen should exist"

    languages.each { lang ->
        def langDir = new File(buildSrcGen, lang)
        assert langDir.exists(), "${lang} directory should exist"

        ['plain', 'with-help'].each { style ->
            def styleDir = new File(langDir, "asciidoc/${style}/src")
            assert styleDir.exists(), "${lang}/${style} directory should exist"

            def adocFiles = styleDir.listFiles()?.findAll { it.name.endsWith('.adoc') }
            assert adocFiles.size() > 0, "${lang}/${style} should contain .adoc files"
            println "✓ ${lang}/${style}: ${adocFiles.size()} .adoc file(s)"
        }
    }
    println "\n✓ Test 4 passed\n"

    println "=== All Tests Passed! ==="
    System.exit(0)

} catch (Exception e) {
    println "\n✗ Test failed with error:"
    println e.message
    e.printStackTrace()
    System.exit(1)
}
