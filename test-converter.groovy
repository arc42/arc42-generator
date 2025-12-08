#!/usr/bin/env groovy

/**
 * Test script for Converter.groovy
 * Tests format conversion with AsciidoctorJ and Pandoc
 */

// Load buildconfig.groovy
def config = new ConfigSlurper().parse(new File('buildconfig.groovy').toURI().toURL())

// Load classes
def gcl = new GroovyClassLoader()
def discoveryClass = gcl.parseClass(new File('lib/Discovery.groovy'))
def converterClass = gcl.parseClass(new File('lib/Converter.groovy'))

// Create instances
def discovery = discoveryClass.newInstance(config)
def converter = converterClass.newInstance(config)

try {
    println "=== Test 1: Discover Templates ==="
    def templates = discovery.discoverTemplates()
    println "Found ${templates.size()} template(s)"
    assert templates.size() > 0, "Should have templates"
    println "✓ Test 1 passed\n"

    println "=== Test 2: Convert Single Template to HTML ==="
    def enPlain = templates.find { it.language == 'EN' && it.style == 'plain' }
    assert enPlain != null, "Should find EN:plain template"

    println "Converting EN:plain to HTML..."
    println "  Template source: ${enPlain.sourcePath}"
    println "  Main file: ${enPlain.mainFile}"
    def htmlResult = converter.convertToHTML(enPlain, 'build/test/EN/html/plain')
    println "  Result: ${htmlResult}"
    assert htmlResult != null, "HTML conversion should succeed"
    def htmlFile = new File(htmlResult)
    println "  File exists: ${htmlFile.exists()}"
    if (htmlFile.exists()) {
        println "  File size: ${htmlFile.length()} bytes"
    } else {
        println "  Parent dir exists: ${htmlFile.parentFile.exists()}"
        println "  Files in parent: ${htmlFile.parentFile?.list()?.join(', ')}"
    }
    assert htmlFile.exists(), "HTML file should exist"
    println "✓ HTML file created: ${htmlResult}"
    println "✓ Test 2 passed\n"

    println "=== Test 3: Convert Single Template to DocBook ==="
    println "Converting EN:plain to DocBook..."
    def docbookResult = converter.convertToDocBook(enPlain, 'build/test/EN/docbook/plain', false)
    assert docbookResult != null, "DocBook conversion should succeed"
    assert new File(docbookResult).exists(), "DocBook file should exist"
    println "✓ DocBook file created: ${docbookResult}"
    println "✓ Test 3 passed\n"

    println "=== Test 4: Check Pandoc Availability ==="
    def pandocCheck = ['pandoc', '--version'].execute()
    pandocCheck.waitFor()
    if (pandocCheck.exitValue() == 0) {
        def version = pandocCheck.text.split('\n')[0]
        println "✓ Pandoc available: ${version}"

        println "\n=== Test 5: Convert via Pandoc (Markdown) ==="
        println "Converting EN:plain to Markdown..."
        def markdownResult = converter.convertViaPandoc(enPlain, 'markdown', 'build/test/EN/markdown/plain')
        assert markdownResult != null, "Markdown conversion should succeed"
        assert new File(markdownResult).exists(), "Markdown file should exist"
        println "✓ Markdown file created: ${markdownResult}"
        println "✓ Test 5 passed\n"

        println "=== Test 6: Convert via Pandoc (DOCX) ==="
        println "Converting EN:plain to DOCX..."
        def docxResult = converter.convertViaPandoc(enPlain, 'docx', 'build/test/EN/docx/plain')
        assert docxResult != null, "DOCX conversion should succeed"
        assert new File(docxResult).exists(), "DOCX file should exist"
        println "✓ DOCX file created: ${docxResult}"
        println "✓ Test 6 passed\n"
    } else {
        println "⚠ Pandoc not available, skipping Pandoc tests"
    }

    println "=== Test 7: Convert Using High-Level API ==="
    println "Converting EN:plain to all formats (sequential)..."
    def enTemplate = [enPlain]
    def testFormats = ['html', 'asciidoc', 'docbook']
    converter.convertAll(enTemplate, testFormats, false)
    println "✓ Test 7 passed\n"

    println "=== All Tests Passed! ==="
    System.exit(0)

} catch (Exception e) {
    println "\n✗ Test failed with error:"
    println e.message
    e.printStackTrace()
    System.exit(1)
}
