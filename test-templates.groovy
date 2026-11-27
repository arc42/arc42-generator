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

// This script tests template generation; golden master validation has its own tests (test-lint.groovy)
if (templates.hasProperty('failOnLintErrors')) {
    templates.failOnLintErrors = false
}

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
    assert !cleaned.contains('endif::arc42help'), "Should remove endif statements"
    assert !cleaned.contains('This help content should also be removed'), "Should remove everything between ifdef and endif"
    assert !cleaned.contains('This is help text that should be removed'), "Should remove a help block without ifdef"
    assert cleaned.contains('This text should remain'), "Should keep non-help content"
    assert cleaned.contains('This text should also remain'), "Should keep the content after the conditional"

    // what the golden master looks like: role block inside its conditional, between two paragraphs
    def chapter = 'BEFORE\n\nifdef::arc42help[]\n[role="arc42help"]\n****\nHELP with **** inside\n****\nendif::arc42help[]\n\nAFTER\n'
    def plain = templates.removeFeatures(chapter, ['help', 'example'])
    assert plain == 'BEFORE\n\n\n\n\nAFTER\n', "the block becomes two empty lines, nothing else changes: ${plain.inspect()}"
    assert templates.removeFeatures(chapter, ['example']) == chapter, "a kept feature must not change the text"
    assert templates.removeFeatures(chapter.replace('\n', '\r\n'), ['help']) == 'BEFORE\r\n\r\n\r\n\r\n\r\nAFTER\r\n',
        "CRLF line endings are kept"
    // a conditional that is never closed loses only its directive line (the lint reports it)
    assert templates.removeFeatures('A\nifdef::arc42help[]\nB\n', ['help']) == 'A\n\nB\n'
    // nested conditionals of the same feature are removed as one block
    assert templates.removeFeatures('A\nifdef::arc42help[]\nX\nifdef::arc42help[]\nY\nendif::arc42help[]\nZ\nendif::arc42help[]\nB\n', ['help']) == 'A\n\n\nB\n'
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
