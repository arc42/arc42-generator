#!/usr/bin/env groovy

/**
 * Test script for Converter.groovy
 * Tests format conversion with AsciidoctorJ and Pandoc
 *
 * Uses 'build2/test/' as output dir to avoid the root-owned 'build/' directory.
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
    def htmlResult = converter.convertToHTML(enPlain, 'build2/test/EN/html/plain')
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
    def docbookResult = converter.convertToDocBook(enPlain, 'build2/test/EN/docbook/plain', false)
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
        def markdownResult = converter.convertViaPandoc(enPlain, 'markdown', 'build2/test/EN/markdown/plain')
        assert markdownResult != null, "Markdown conversion should succeed"
        assert new File(markdownResult).exists(), "Markdown file should exist"
        println "✓ Markdown file created: ${markdownResult}"
        println "✓ Test 5 passed\n"

        println "=== Test 6: Convert via Pandoc (DOCX) ==="
        println "Converting EN:plain to DOCX..."
        def docxResult = converter.convertViaPandoc(enPlain, 'docx', 'build2/test/EN/docx/plain')
        assert docxResult != null, "DOCX conversion should succeed"
        assert new File(docxResult).exists(), "DOCX file should exist"
        println "✓ DOCX file created: ${docxResult}"
        println "✓ Test 6 passed\n"

        println "=== Test 8: Convert via Pandoc (markdownMP - multi-page) ==="
        println "Converting EN:plain to markdownMP..."
        def markdownMPResult = converter.convertViaPandocMP(enPlain, 'markdownMP', 'build2/test/EN/markdownMP/plain')
        assert markdownMPResult != null, "markdownMP conversion should succeed"
        def markdownMPDir = new File(markdownMPResult)
        assert markdownMPDir.exists(), "markdownMP output directory should exist"
        def mdFiles = markdownMPDir.listFiles((FilenameFilter) { dir, name -> name.endsWith('.md') })?.sort { it.name }
        assert mdFiles != null && mdFiles.size() > 1,
            "markdownMP should produce multiple .md files (one per chapter), got: ${mdFiles?.size()}"
        println "✓ markdownMP produced ${mdFiles.size()} .md files:"
        mdFiles.each { f -> println "  - ${f.name}" }
        println "✓ Test 8 passed\n"

    } else {
        println "⚠ Pandoc not available, skipping Pandoc tests"
    }

    println "=== Test 7: Convert Using High-Level API ==="
    println "Converting EN:plain to all formats (sequential)..."
    def enTemplate = [enPlain]
    def testFormats = ['html', 'asciidoc', 'docbook']
    converter.convertAll(enTemplate, testFormats, false)
    println "✓ Test 7 passed\n"

    println "=== Test 9: Multi-page with-help output keeps the help text ==="
    def enHelp = templates.find { it.language == 'EN' && it.style == 'with-help' }
    assert enHelp != null, "Should find EN:with-help template"
    def mpHelpDir = converter.convertTemplate(enHelp, 'markdownMP', 'build2/test')
    assert mpHelpDir != null, "markdownMP conversion of with-help should succeed"
    def mpPlainDir = converter.convertTemplate(enPlain, 'markdownMP', 'build2/test')
    assert mpPlainDir != null, "markdownMP conversion of plain should succeed"
    def helpChapter = new File(mpHelpDir, '01_introduction_and_goals.md')
    def plainChapter = new File(mpPlainDir, '01_introduction_and_goals.md')
    assert helpChapter.exists() && plainChapter.exists(), "chapter files should exist"
    def helpText = helpChapter.getText('utf-8')
    def plainText = plainChapter.getText('utf-8')
    assert helpText.contains('Describes the relevant requirements'), "with-help chapter must contain the help text"
    assert !plainText.contains('Describes the relevant requirements'), "plain chapter must not contain help text"
    assert helpText.length() > plainText.length(), "with-help chapter must be longer than the plain one"
    println "✓ Test 9 passed\n"

    println "=== Test 10: cleanOutputs removes previous output including DocBook intermediates ==="
    def stale = ['build2/test/EN/html/plain/stale.txt', 'build2/test/EN/docbook/plain/stale.txt',
                 'build2/test/EN/docbookMP/plain/stale.txt', 'build2/test/EN/markdownMP/plain/stale.txt'].collect { new File(it) }
    stale.each { it.parentFile.mkdirs(); it.write('stale', 'utf-8') }
    def deleted = converter.cleanOutputs([enPlain], ['html', 'markdown', 'markdownMP'], 'build2/test')
    assert deleted >= 4, "expected at least 4 directories to be deleted, got ${deleted}"
    stale.each { assert !it.exists(), "${it} should have been removed" }
    assert !new File('build2/test/EN/html/plain').exists(), "html output directory should be removed"
    println "✓ Test 10 passed\n"

    println "=== Test 11: Asciidoctor diagnostics are collected ==="
    def diagDir = new File('build2/test/diag/src')
    diagDir.mkdirs()
    new File(diagDir, 'broken.adoc').write("= Broken\n\ninclude::missing.adoc[]\n", 'utf-8')
    def brokenTemplate = [language: 'XX', style: 'plain', srcDir: diagDir.absolutePath,
                          mainFile: new File(diagDir, 'broken.adoc').absolutePath,
                          hasImages: false, imagesDir: null, versionProperties: [:]]
    def before = converter.diagnostics.size()
    converter.convertToHTML(brokenTemplate, 'build2/test/diag/html')
    def newRecords = converter.diagnostics.drop(before)
    assert newRecords.any { it.severity.toString() == 'ERROR' && it.message.contains('missing.adoc') },
        "a missing include must be reported as ERROR, got: ${newRecords*.message}"
    assert converter.diagnosticsAtOrAbove('error').size() >= 1, "diagnosticsAtOrAbove('error') should find the record"
    assert converter.diagnosticsAtOrAbove('fatal').isEmpty(), "no FATAL diagnostics expected"
    assert converter.diagnosticsAtOrAbove('none').isEmpty(), "'none' must select nothing"
    println "✓ Test 11 passed\n"

    println "=== Test 12: Pandoc warnings are collected as diagnostics ==="
    new File(diagDir, 'noimage.adoc').write("= No image\n\nimage::nope.png[]\n", 'utf-8')
    def noImageTemplate = brokenTemplate + [mainFile: new File(diagDir, 'noimage.adoc').absolutePath]
    before = converter.diagnostics.size()
    def noImageResult = converter.convertViaPandoc(noImageTemplate, 'docx', 'build2/test/diag/docx')
    assert noImageResult != null && new File(noImageResult).exists(), "conversion should still produce a file"
    newRecords = converter.diagnostics.drop(before)
    assert newRecords.any { it.severity.toString() == 'WARN' && it.message.contains('pandoc') && it.message.contains('nope.png') },
        "Pandoc's missing-image warning must be recorded, got: ${newRecords*.message}"
    println "✓ Test 12 passed\n"

    println "=== Test 13: DOCX output is reproducible with sourceDateEpoch ==="
    converter.sourceDateEpoch = 1751846400L
    def docxA = converter.convertViaPandoc(enPlain, 'docx', 'build2/test/EN/docx/plain')
    def bytesA = new File(docxA).bytes
    Thread.sleep(1100)  // DOCX dates have a resolution of seconds
    def docxB = converter.convertViaPandoc(enPlain, 'docx', 'build2/test/EN/docx/plain')
    assert Arrays.equals(bytesA, new File(docxB).bytes), "two DOCX conversions with the same sourceDateEpoch must be byte-identical"
    println "✓ Test 13 passed\n"

    println "=== Test 14: EPUB output is reproducible (fixed identifier) ==="
    def epubA = converter.convertViaPandoc(enPlain, 'epub', 'build2/test/EN/epub/plain')
    def epubBytesA = new File(epubA).bytes
    def epubB = converter.convertViaPandoc(enPlain, 'epub', 'build2/test/EN/epub/plain')
    assert Arrays.equals(epubBytesA, new File(epubB).bytes), "two EPUB conversions with the same sourceDateEpoch must be byte-identical"
    assert converter.epubIdentifier(enPlain) != converter.epubIdentifier(enHelp), "template variants need distinct EPUB identifiers"
    converter.sourceDateEpoch = null
    println "✓ Test 14 passed\n"

    println "=== All Tests Passed! ==="
    System.exit(0)

} catch (Exception e) {
    println "\n✗ Test failed with error:"
    println e.message
    e.printStackTrace()
    System.exit(1)
}
