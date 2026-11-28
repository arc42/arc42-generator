#!/usr/bin/env groovy

/**
 * Test script for the output checks (lib/OutputCheck.groovy, make validate)
 *
 * Builds a small golden master (EN and DE, help blocks, images, an SVG with PNG fallback) into all formats and ZIPs, checks
 * that the untouched output passes, then damages the output in one way per test and checks that
 * exactly that damage is reported.
 */

import java.awt.image.BufferedImage
import javax.imageio.ImageIO

def fixture = new File('build2/test-outputcheck/demo-project')
if (fixture.exists()) fixture.deleteDir()
fixture.mkdirs()

new File(fixture, 'buildconfig.groovy').write('''
project {
    name = 'demo-template'
    featurePrefix = 'demo'
    logo = 'demo-logo.png'
}

goldenMaster {
    sourcePath = './'
    targetPath = 'build/src_gen/'
    allFeatures = ['help', 'example']
    templateStyles = ['plain': [], 'with-help': ['help']]
}

formats = [
    'asciidoc': [imageFolder: true],
    'html': [imageFolder: true],
    'epub': [imageFolder: false],
    'rst': [imageFolder: true],
    'markdown': [imageFolder: true],
    'markdownMP': [imageFolder: true],
    'markdownStrict': [imageFolder: true],
    'gitHubMarkdown': [imageFolder: true],
    'textile': [imageFolder: true],
    'docx': [imageFolder: false],
    'docbook': [imageFolder: true],
    'latex': [imageFolder: true],
    'pdf': [imageFolder: false],
]

distribution {
    targetPath = 'dist/'
}
''', 'utf-8')

def png = { File file, int rgb ->
    def image = new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB)
    def graphics = image.createGraphics()
    graphics.color = new java.awt.Color(rgb)
    graphics.fillRect(0, 0, 40, 20)
    graphics.dispose()
    ImageIO.write(image, 'png', file)
}
def helpBlock = { String text -> "ifdef::demohelp[]\n[role=\"demohelp\"]\n****\n${text}\n****\nendif::demohelp[]\n" }

['EN', 'DE'].each { lang ->
    new File(fixture, "${lang}/adoc").mkdirs()
    new File(fixture, "${lang}/images").mkdirs()
    new File(fixture, "${lang}/demo-template.adoc").write("""include::adoc/config.adoc[]

= image:demo-logo.png[demo] Demo Template (${lang})

${helpBlock('HELP FOR THE DOCUMENT')}
include::adoc/01_chapter.adoc[]

include::adoc/02_chapter.adoc[]
""", 'utf-8')
    new File(fixture, "${lang}/adoc/config.adoc").write(":imagesdir: ./images\n:demohelp:\n", 'utf-8')
    new File(fixture, "${lang}/adoc/01_chapter.adoc").write("""== Chapter one

${helpBlock('HELP ONE\n\nimage::diagram.png["A diagram"]')}
CHAPTER ONE CONTENT

=== Section one

SECTION ONE CONTENT
""", 'utf-8')
    new File(fixture, "${lang}/adoc/02_chapter.adoc").write("""== Chapter two

${helpBlock('HELP TWO\n\nimage::vector.svg["A vector image"]')}
=== Section two

SECTION TWO CONTENT

=== Section three

SECTION THREE CONTENT
""", 'utf-8')
    new File(fixture, "${lang}/version.properties").write("revnumber=1.0-${lang}\nrevdate=2026\nrevdate-iso=2026-01\nrevremark=(test)\n", 'utf-8')
    png(new File(fixture, "${lang}/images/demo-logo.png"), 0x336699)
    png(new File(fixture, "${lang}/images/diagram.png"), 0x993366)
    // an SVG with its PNG fallback: DOCX and LaTeX use the PNG, all other formats the SVG
    new File(fixture, "${lang}/images/vector.svg").write(
        '<svg xmlns="http://www.w3.org/2000/svg" width="40" height="20"><rect width="40" height="20" fill="#005CA9"/></svg>', 'utf-8')
    png(new File(fixture, "${lang}/images/vector.png"), 0x005CA9)
    // the terms of a translation: kept next to the template, never converted or published
    new File(fixture, "${lang}/translation").mkdirs()
    new File(fixture, "${lang}/translation/terms.adoc").write(
        "= Translation terms: ${lang}\n\n|===\n| EN term | ${lang} term | Why\n\n| Building block | UNPUBLISHED-TERM |\n|===\n", 'utf-8')
}

def config = new ConfigSlurper().parse(new File(fixture, 'buildconfig.groovy').toURI().toURL())
def gcl = new GroovyClassLoader()
def templates = gcl.parseClass(new File('lib/Templates.groovy')).newInstance(config, fixture)
def discovery = gcl.parseClass(new File('lib/Discovery.groovy')).newInstance(config, fixture)
def converter = gcl.parseClass(new File('lib/Converter.groovy')).newInstance(config, fixture)
def packager = gcl.parseClass(new File('lib/Packager.groovy')).newInstance(config, fixture)
def checkClass = gcl.parseClass(new File('lib/OutputCheck.groovy'))

def copyDir = { File from, File to ->
    if (to.exists()) to.deleteDir()
    from.eachFileRecurse { file ->
        if (file.isFile()) {
            def target = new File(to, from.toPath().relativize(file.toPath()).toString())
            target.parentFile.mkdirs()
            target.bytes = file.bytes
        }
    }
}
def build = new File(fixture, 'build')
def dist = new File(fixture, 'dist')
def pristine = new File(fixture.parentFile, 'pristine')
def restore = {
    ['EN', 'DE'].each { copyDir(new File(pristine, "build/${it}"), new File(build, it)) }
    copyDir(new File(pristine, 'dist'), dist)
}
def check = { List<String> formats = null ->
    def checker = checkClass.newInstance(config, fixture)
    checker.checkAll(formats)
    checker
}
// the errors of one damaged output: all of the given check, all at the given location
def expectErrors = { String name, String checkName, String location, String text ->
    def checker = check()
    def errors = checker.errors
    println "  ${name}:"
    errors.each { println "    reported: [${it.check}] ${it.location}: ${it.message}" }
    assert errors, "${name}: an error should be reported"
    assert errors.any { it.check == checkName && it.location.startsWith(location) && it.message.contains(text) },
        "${name}: expected a ${checkName} error at ${location} containing '${text}', got: ${errors}"
    errors
}

try {
    println "=== Test 1: The untouched output of a full build passes all checks ==="
    templates.createFromGoldenMaster()
    def found = discovery.discoverTemplates()
    converter.sourceDateEpoch = 1751328000L
    packager.sourceDateEpoch = 1751328000L
    def result = converter.convertAll(found, null, false)
    assert result.failed == 0 && converter.diagnostics.isEmpty(), "the fixture should convert without problems: ${converter.diagnostics*.message}"
    packager.createAllDistributions(found, null, false)
    copyDir(build, new File(pristine, 'build'))
    copyDir(dist, new File(pristine, 'dist'))

    def clean = check()
    clean.problems.each { println "  reported: [${it.check}] ${it.location}: ${it.message}" }
    assert clean.problems.isEmpty(), "a correct build must have no problems"
    assert clean.checked.outputs == 2 * 2 * 13, "every language, style and format should be checked: ${clean.checked}"
    assert clean.checked.zips == 52 && clean.checked.pdfs == 4 && clean.checked.textFiles > 40, "unexpected scope: ${clean.checked}"
    println "✓ Test 1 passed (${clean.checked})\n"

    println "=== Test 1b: <LANG>/translation/ is not part of the template: it is in no output and in no ZIP ==="
    def leaked = []
    build.eachFileRecurse { file ->
        if (!file.isFile()) return
        if (file.path.contains('translation') || file.name == 'terms.adoc') leaked << file.path
        else if (new String(file.bytes, 'ISO-8859-1').contains('UNPUBLISHED-TERM')) leaked << "${file.path} (content)"
    }
    dist.listFiles().findAll { it.name.endsWith('.zip') }.each { zipFile ->
        new java.util.zip.ZipFile(zipFile).withCloseable { zip ->
            zip.entries().each { entry ->
                if (entry.name.contains('translation') || entry.name.endsWith('terms.adoc')) leaked << "${zipFile.name}!${entry.name}"
                else if (new String(zip.getInputStream(entry).readAllBytes(), 'ISO-8859-1').contains('UNPUBLISHED-TERM')) leaked << "${zipFile.name}!${entry.name} (content)"
            }
        }
    }
    assert leaked.isEmpty(), "the translation terms must not be generated, converted or packaged: ${leaked.take(5)}"
    assert new File(fixture, 'EN/translation/terms.adoc').isFile(), "the fixture should contain the translation terms"
    println "✓ Test 1b passed\n"

    println "=== Test 2: Leftover AsciiDoc and wrong encoding are reported ==="
    new File(build, 'EN/markdown/plain/demo-template-EN.md').append('\nifdef::demohelp[]\n', 'utf-8')
    expectErrors('conditional in Markdown', 'markers', 'build/EN/markdown/plain', 'conditional directive')
    restore()
    new File(build, 'DE/html/with-help/demo-template.html').append('<p>Unresolved directive in x.adoc - include::y.adoc[]</p>', 'utf-8')
    expectErrors('unresolved include in HTML', 'markers', 'build/DE/html/with-help', 'unresolved directive')
    restore()
    new File(build, 'EN/rst/plain/demo-template-EN.rst').append([0xC3, 0x28] as byte[])
    expectErrors('invalid UTF-8', 'encoding', 'build/EN/rst/plain', 'UTF-8')
    restore()
    println "✓ Test 2 passed\n"

    println "=== Test 3: A lost heading is reported in every text format, DOCX and multi-page ==="
    [textile: 'demo-template-EN.textile', markdownStrict: 'demo-template-EN.md', latex: 'demo-template-EN.tex',
     rst: 'demo-template-EN.rst', gitHubMarkdown: 'demo-template-EN.md', html: 'demo-template.html'].each { format, name ->
        def file = new File(build, "EN/${format}/plain/${name}")
        def text = file.getText('utf-8')
        def without = format == 'rst' ? text.replaceFirst(/(?m)^Section two\n-+\n/, 'Section two\n') :
            format == 'html' ? text.replaceFirst(/<h3 id="_section_two">.*?<\/h3>/, '') :
            text.readLines().findAll { !(it.contains('Section two') && !it.contains('SECTION')) }.join('\n')
        assert without != text, "${format}: the fixture should contain the heading 'Section two'"
        file.write(without, 'utf-8')
        expectErrors("heading removed from ${format}", 'headings', "build/EN/${format}/plain", '2 section heading(s)')
        restore()
    }
    new File(build, 'DE/markdownMP/with-help/02_chapter.md').delete()
    def mp = expectErrors('page removed from markdownMP', 'headings', 'build/DE/markdownMP/with-help', '1 file(s)')
    assert mp.any { it.check == 'zips' }, "the ZIP no longer holds the files of the output directory"
    restore()
    println "✓ Test 3 passed\n"

    println "=== Test 4: Missing, extra and misplaced help texts are reported ==="
    copyDir(new File(build, 'EN/docbook/plain'), new File(build, 'EN/docbook/with-help'))
    expectErrors('with-help DocBook equals plain', 'features', 'build/EN/docbook/with-help', '0 help block(s) in docbook, expected 3')
    restore()
    copyDir(new File(build, 'EN/html/with-help'), new File(build, 'EN/html/plain'))
    expectErrors('plain HTML contains help', 'features', 'build/EN/html/plain', '3 help block(s) in html, expected 0')
    restore()
    copyDir(new File(build, 'DE/textile/plain'), new File(build, 'DE/textile/with-help'))
    expectErrors('with-help Textile equals plain', 'features', 'build/DE/textile/with-help', 'not larger than the plain output')
    restore()
    new File(build, 'EN/asciidoc/plain/src/01_chapter.adoc').append('\n' + helpBlock('HELP'), 'utf-8')
    expectErrors('help block in the plain AsciiDoc', 'features', 'build/EN/asciidoc/plain', 'must not contain help blocks')
    restore()
    println "✓ Test 4 passed\n"

    println "=== Test 5: Missing and unknown images are reported ==="
    assert new File(build, 'EN/markdown/with-help/images/diagram.png').delete()
    expectErrors('image file missing', 'images', 'build/EN/markdown/with-help', 'images/diagram.png is referenced but not in the output')
    restore()
    def gfm = new File(build, 'DE/gitHubMarkdown/with-help/demo-template-DE.md')
    gfm.write(gfm.getText('utf-8').replace('images/diagram.png', 'images/other.png'), 'utf-8')
    def images = expectErrors('image reference changed', 'images', 'build/DE/gitHubMarkdown/with-help', 'does not reference diagram.png')
    assert images.any { it.message.contains('references other.png') }, "the unknown image should be reported as well"
    restore()
    assert new File(build, 'EN/asciidoc/with-help/images/diagram.png').delete()
    expectErrors('image missing in the AsciiDoc output', 'images', 'build/EN/asciidoc/with-help', 'images/diagram.png')
    restore()
    copyDir(new File(build, 'EN/docx/plain'), new File(build, 'EN/docx/with-help'))
    expectErrors('DOCX without the images of the help texts', 'images', 'build/EN/docx/with-help', '1 embedded image(s), the template references 3')
    restore()
    def tex = new File(build, 'EN/latex/with-help/demo-template-EN.tex')
    assert tex.getText('utf-8').contains('images/vector.png'), "LaTeX uses the PNG fallback of the SVG"
    assert new File(build, 'EN/markdown/with-help/demo-template-EN.md').getText('utf-8').contains('images/vector.svg'), "Markdown keeps the SVG"
    tex.write(tex.getText('utf-8').replace('images/vector.png', 'images/vector.svg'), 'utf-8')
    expectErrors('LaTeX with an SVG despite its PNG fallback', 'images', 'build/EN/latex/with-help', 'does not reference vector.png')
    restore()
    println "✓ Test 5 passed\n"

    println "=== Test 6: Broken DocBook, DOCX, EPUB and PDF files are reported ==="
    def docbook = new File(build, 'EN/docbook/plain/demo-template.xml')
    docbook.write(docbook.getText('utf-8').replaceFirst('</chapter>', ''), 'utf-8')
    expectErrors('DocBook not well-formed', 'xml', 'build/EN/docbook/plain', 'not well-formed')
    restore()
    def docx = new File(build, 'DE/docx/plain/demo-template-DE.docx')
    docx.bytes = docx.bytes[0..<(docx.length() / 2 as int)] as byte[]
    expectErrors('truncated DOCX', 'xml', 'build/DE/docx/plain', 'docx')
    restore()
    new File(build, 'EN/epub/plain/demo-template-EN.epub').write('not an epub', 'utf-8')
    expectErrors('EPUB that is no ZIP', 'xml', 'build/EN/epub/plain', 'epub')
    restore()
    def pdf = new File(build, 'EN/pdf/plain/demo-template-EN.pdf')
    pdf.bytes = pdf.bytes[0..<(pdf.length() / 2 as int)] as byte[]
    expectErrors('truncated PDF', 'pdf', 'build/EN/pdf/plain', 'not a complete PDF')
    restore()
    println "✓ Test 6 passed\n"

    println "=== Test 7: Missing output, missing and damaged ZIPs are reported ==="
    new File(build, 'DE/latex/plain').deleteDir()
    expectErrors('output directory missing', 'present', 'build/DE/latex/plain', 'no latex output for DE/plain')
    restore()
    assert new File(dist, 'demo-template-EN-withhelp-html.zip').delete()
    expectErrors('ZIP missing', 'zips', 'dist/demo-template-EN-withhelp-html.zip', 'does not exist')
    restore()
    def zip = new File(dist, 'demo-template-DE-plain-rst.zip')
    def bytes = zip.bytes
    bytes[bytes.length / 2 as int] ^= 0x55
    zip.bytes = bytes
    expectErrors('ZIP changed after the manifest was written', 'zips', 'dist/demo-template-DE-plain-rst.zip', 'differ from the manifest')
    restore()
    def manifest = new File(dist, 'manifest.json')
    def lines = manifest.readLines('utf-8')
    def start = lines.findIndexOf { it.contains('"name": "demo-template-EN-plain-pdf.zip"') } - 1
    manifest.write((lines[0..<start] + lines[(start + 8)..-1]).join('\n') + '\n', 'utf-8')
    new groovy.json.JsonSlurper().parse(manifest, 'utf-8')   // still valid JSON
    expectErrors('ZIP not in the manifest', 'zips', 'dist/manifest.json', 'demo-template-EN-plain-pdf.zip is not in the manifest')
    restore()
    new File(build, 'EN/html/plain/added-after-packaging.txt').write('x', 'utf-8')
    expectErrors('output changed after packaging', 'zips', 'dist/demo-template-EN-plain-html.zip', 'does not hold the files')
    restore()
    println "✓ Test 7 passed\n"

    println "=== Test 8: A build of one format checks that format only ==="
    new File(build, 'EN/latex/plain').deleteDir()
    def onlyHtml = check(['html'])
    assert onlyHtml.checked.outputs == 4, "only the four HTML outputs should be checked: ${onlyHtml.checked}"
    assert onlyHtml.checked.zips == 4 && onlyHtml.problems.isEmpty(),
        "the missing LaTeX output is none of its business: ${onlyHtml.checked} ${onlyHtml.problems.take(3)}"
    restore()
    assert check().problems.isEmpty(), "the restored output passes again"
    println "✓ Test 8 passed\n"

    println "=== All Tests Passed! ==="
    System.exit(0)

} catch (Throwable e) {
    println "\n✗ Test failed with error:"
    println e.message
    e.printStackTrace()
    System.exit(1)
}
