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

        println "=== Test 8b: Multi-page output holds the chapters the main document includes, nothing else ==="
        def orphan = new File(enPlain.srcDir, 'zz_not_included.adoc')
        orphan.write("== Not included\n\nORPHAN\n", 'utf-8')
        try {
            def chapters = converter.chapterFiles(enPlain)*.name
            assert !chapters.contains(orphan.name) && !chapters.contains('config.adoc') && !chapters.contains("${config.project.name}.adoc".toString()),
                "only included chapter files are chapters: ${chapters}"
            assert chapters.contains('01_introduction_and_goals.adoc') && chapters == chapters.sort(false), "sorted chapter files: ${chapters}"
            converter.cleanOutputs([enPlain], ['markdownMP'], 'build2/test')
            def mpDir = new File(converter.convertTemplate(enPlain, 'markdownMP', 'build2/test'))
            def pages = (mpDir.list() as List).findAll { it.endsWith('.md') }.sort()
            assert pages == chapters.collect { it.replace('.adoc', '.md') }, "one page per included chapter: ${pages}"
        } finally {
            orphan.delete()
        }
        println "✓ Test 8b passed\n"

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

    println "=== Test 15: PDF output is reproducible, embeds the images and uses the fallback fonts for Chinese and Azerbaijani ==="
    def pdfA = converter.convertTemplate(enHelp, 'pdf', 'build2/test')
    assert pdfA != null && new File(pdfA).name == "${config.project.name}-EN.pdf", "PDF conversion should succeed: ${pdfA}"
    def pdfBytesA = new File(pdfA).bytes
    assert new String(pdfBytesA, 0, 5, 'ISO-8859-1') == '%PDF-', "output should be a PDF"
    assert new String(pdfBytesA, 'ISO-8859-1').contains('/Subtype /Image'), "the with-help PDF should embed the images"
    Thread.sleep(1100)
    def pdfB = converter.convertTemplate(enHelp, 'pdf', 'build2/test')
    assert Arrays.equals(pdfBytesA, new File(pdfB).bytes), "two PDF conversions must be byte-identical"
    def zhPlain = templates.find { it.language == 'ZH' && it.style == 'plain' }
    if (zhPlain) {
        def zhPdf = new File(converter.convertTemplate(zhPlain, 'pdf', 'build2/test'))
        assert new String(zhPdf.bytes, 'ISO-8859-1').contains('DroidSansFallback'),
            "Chinese text needs the fallback font, otherwise it renders as empty boxes"
    }
    def azPlain = templates.find { it.language == 'AZ' && it.style == 'plain' }
    if (azPlain) {
        assert converter.convertTemplate(azPlain, 'pdf', 'build2/test') != null, "AZ PDF conversion should succeed"
        // Azerbaijani Ə is not in the bundled Noto Serif subset; without the full Noto Serif as fallback it is left out
        assert !converter.diagnostics.any { it.message?.contains('Could not locate the character') },
            "no character of the PDF output may be missing from its fonts: ${converter.diagnostics*.message}"
    }
    println "✓ Test 15 passed\n"

    println "=== Test 16: DOCX and LaTeX use the PNG of an SVG, the other formats keep the SVG ==="
    def svgDir = new File('build2/test/svg/src')
    def svgImages = new File(svgDir, 'images')
    svgImages.mkdirs()
    def svgText = '<svg xmlns="http://www.w3.org/2000/svg" width="20" height="10"><rect width="20" height="10" fill="#005CA9"/></svg>'
    new File(svgImages, 'logo.svg').write(svgText, 'utf-8')
    new File(svgImages, 'only.svg').write(svgText, 'utf-8')    // no PNG of the same name: stays SVG
    javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(20, 10, java.awt.image.BufferedImage.TYPE_INT_RGB), 'png', new File(svgImages, 'logo.png'))
    new File(svgDir, 'svg.adoc').write("= SVG\n\nimage::logo.svg[logo]\n\nimage::only.svg[only]\n", 'utf-8')
    def svgTemplate = [language: 'XX', style: 'with-help', srcDir: svgDir.absolutePath,
                       mainFile: new File(svgDir, 'svg.adoc').absolutePath,
                       hasImages: true, imagesDir: svgImages.absolutePath, versionProperties: [:]]
    def docbookXml = new File("build2/test/svg/docbook/with-help/${config.project.name}.xml")

    def svgDocx = converter.convertViaPandoc(svgTemplate, 'docx', 'build2/test/svg/docx/with-help')
    def media = new java.util.zip.ZipFile(svgDocx).withCloseable { zip -> zip.entries().collect { it.name }.findAll { it.startsWith('word/media/') } }
    assert media.any { it.endsWith('.png') }, "DOCX should embed the PNG of logo.svg, got: ${media}"
    assert media.count { it.endsWith('.svg') } == 1, "DOCX should embed only.svg (no PNG) as SVG, got: ${media}"
    assert docbookXml.getText('utf-8').contains('fileref="images/logo.svg"'), "the DocBook file itself must keep the SVG"
    assert !docbookXml.parentFile.listFiles().any { it.name.endsWith('-docx.xml') }, "the DocBook copy for DOCX must be removed"

    def svgTex = new File(converter.convertViaPandoc(svgTemplate, 'latex', 'build2/test/svg/latex/with-help')).getText('utf-8')
    assert svgTex.contains('images/logo.png') && !svgTex.contains('images/logo.svg'), "LaTeX should include the PNG of logo.svg"
    assert svgTex.contains('images/only.svg'), "LaTeX should keep an SVG without PNG"

    def svgMd = new File(converter.convertViaPandoc(svgTemplate, 'markdown', 'build2/test/svg/markdown/with-help')).getText('utf-8')
    assert svgMd.contains('images/logo.svg') && !svgMd.contains('images/logo.png'), "Markdown should keep the SVG"
    println "✓ Test 16 passed\n"

    println "=== All Tests Passed! ==="
    System.exit(0)

} catch (Exception e) {
    println "\n✗ Test failed with error:"
    println e.message
    e.printStackTrace()
    System.exit(1)
}
