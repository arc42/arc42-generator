#!/usr/bin/env groovy

/**
 * Test script for the verify phase: Checks.groovy, Verifier.groovy, Report.groovy
 *
 * Runs unit tests on synthetic inputs and one smoke test on the real dist if present.
 * Uses build/test-verifier/ as scratch directory.
 */

import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

def gcl = new GroovyClassLoader()
def checksClass = gcl.parseClass(new File('lib/Checks.groovy'))
def reportClass = gcl.parseClass(new File('lib/Report.groovy'))

def failures = []
def section = { String name, Closure body ->
    println "=== ${name} ==="
    try {
        body()
        println "✓ ${name}\n"
    } catch (Throwable t) {
        println "✗ ${name}: ${t.message}\n"
        failures << name
    }
}

// ---- helpers shared by all sections -----------------------------------------

def expectRules = { List findings, List ruleIds ->
    def got = findings*.ruleId.unique().sort()
    ruleIds.each { assert it in got, "expected rule ${it}, got ${got}: ${findings*.message}" }
}
def noRule = { List findings, String ruleId ->
    assert !(ruleId in findings*.ruleId), "rule ${ruleId} must not fire: ${findings.find { it.ruleId == ruleId }?.message}"
}
def baseConfig = { Map verifyOverrides = [:] ->
    def text = '''
project { name = 'demo-template'; featurePrefix = 'demo'; logo = 'demo-logo.png' }
goldenMaster { sourcePath = 'gm/'; targetPath = 'build/src_gen/'; allFeatures = ['help', 'example']
               templateStyles = ['plain': [], 'with-help': ['help']] }
formats = ['markdown': [imageFolder: true], 'markdownMP': [imageFolder: true], 'markdownStrict': [imageFolder: true],
           'html': [imageFolder: true], 'asciidoc': [imageFolder: true], 'docx': [imageFolder: true],
           'epub': [imageFolder: false], 'latex': [imageFolder: true], 'rst': [imageFolder: true],
           'textile': [imageFolder: true], 'docbook': [imageFolder: true]]
distribution { targetPath = 'dist/' }
verify { referenceLanguage = 'EN'; chapterCount = 2; reportDir = 'build/reports'
         allowedHtml = [markdownStrict: ['table', 'thead', 'tbody', 'tr', 'th', 'td', 'col', 'colgroup']]
         severity = [:] }
'''
    def cfg = new ConfigSlurper().parse(text)
    verifyOverrides.each { k, v -> cfg.verify[k] = v }
    return cfg
}
def bytesOf = { Map<String, Object> files ->
    // name -> String or byte[]
    files.collectEntries { k, v -> [(k): v instanceof byte[] ? v : v.toString().getBytes('UTF-8')] }
}
def ctxOf = { Map overrides ->
    def ctx = [language: 'EN', style: 'with-help', format: 'markdown', projectName: 'demo-template',
               formatConfig: [imageFolder: true], entries: [:], zipFile: null, revnumber: '1.0-EN',
               helpSentinel: 'HELP SENTINEL SENTENCE FOR TESTS', chapterCount: 2, referenceCounts: null]
    ctx.putAll(overrides)
    return ctx
}

// ---- Task 1 -----------------------------------------------------------------

section('severity: defaults, overrides, off') {
    def checks = checksClass.newInstance(baseConfig())
    assert checks.severityOf('md.rawHtml') == 'error'
    assert checks.severityOf('structure.referenceCounts') == 'warn'
    assert checks.severityOf('unknown.rule') == 'error', "unknown rules default to error"

    def overridden = checksClass.newInstance(baseConfig([severity: ['md.rawHtml': 'warn', 'md.emptyHeading': 'off']]))
    assert overridden.severityOf('md.rawHtml') == 'warn'
    assert overridden.finding('md.emptyHeading', 'x') == null, "off rules produce no finding"
    def f = overridden.finding('md.rawHtml', 'msg', [[location: 'a.md:1', text: '<div>'], [location: 'a.md:2', text: '<div>'],
                                                    [location: 'a.md:3', text: '<div>'], [location: 'a.md:4', text: '<div>']])
    assert f.severity == 'warn' && f.ruleId == 'md.rawHtml' && f.message == 'msg'
    assert f.examples.size() == 3, "examples are capped at three"
}

section('normalize collapses whitespace') {
    assert checksClass.normalize("  a \n\t b c  ") == 'a b c'
    assert checksClass.normalize(null) == ''
}

section('Report.statusOf') {
    assert reportClass.statusOf([]) == 'pass'
    assert reportClass.statusOf([[ruleId: 'x', severity: 'warn']]) == 'warn'
    assert reportClass.statusOf([[ruleId: 'x', severity: 'warn'], [ruleId: 'y', severity: 'error']]) == 'fail'
}

// ---- Task 2: Markdown purity ------------------------------------------------

def GOOD_MD = '''# Chapter One

HELP SENTINEL SENTENCE FOR TESTS. Template Version 1.0-EN.

![logo](images/demo-logo.png)

## Section

- item one
- item two

# Chapter Two

Text with an autolink <https://arc42.org> and escaped \\<placeholder\\>.
'''

section('markdown: clean file has no findings') {
    def checks = checksClass.newInstance(baseConfig())
    def ctx = ctxOf([entries: bytesOf(['demo-template-EN.md': GOOD_MD, 'images/demo-logo.png': [1, 2, 3] as byte[]])])
    def findings = checks.checkMarkdown(ctx)
    assert findings.isEmpty(), "expected no findings, got ${findings*.ruleId} ${findings*.message}"
}

section('markdown: pandoc fenced divs and heading attributes') {
    def checks = checksClass.newInstance(baseConfig())
    def md = '# Chapter One {#section-one}\n\n:::: note\n::: title\n:::\ntext\n::::\n\n# Chapter Two\n'
    def findings = checks.checkMarkdown(ctxOf([entries: bytesOf(['demo-template-EN.md': md])]))
    expectRules(findings, ['md.pandocSyntax'])
    def f = findings.find { it.ruleId == 'md.pandocSyntax' }
    assert f.message.contains('5'), "1 heading attribute + 4 fenced div lines = 5, got: ${f.message}"
    assert f.examples[0].location == 'demo-template-EN.md:1'
}

section('markdown: raw HTML outside the allow list') {
    def checks = checksClass.newInstance(baseConfig())
    def md = '# Chapter One\n\n<div class="paragraph">\n<p>text</p>\n</div>\n\n<img src="images/x.png" />\n\n# Chapter Two\n'
    def findings = checks.checkMarkdown(ctxOf([entries: bytesOf(['demo-template-EN.md': md])]))
    expectRules(findings, ['md.rawHtml', 'md.commonmark'])
    def f = findings.find { it.ruleId == 'md.rawHtml' }
    assert f.message.contains('div') && f.message.contains('img') && f.message.contains('p')
}

section('markdown: tables are allowed in markdownStrict only') {
    def checks = checksClass.newInstance(baseConfig())
    def md = '# Chapter One\n\n<table>\n<tr><td>a</td></tr>\n</table>\n\n# Chapter Two\n'
    noRule(checks.checkMarkdown(ctxOf([format: 'markdownStrict', entries: bytesOf(['demo-template-EN.md': md])])), 'md.rawHtml')
    noRule(checks.checkMarkdown(ctxOf([format: 'markdownStrict', entries: bytesOf(['demo-template-EN.md': md])])), 'md.commonmark')
    expectRules(checks.checkMarkdown(ctxOf([format: 'markdown', entries: bytesOf(['demo-template-EN.md': md])])), ['md.rawHtml'])
}

section('markdown: code fences are ignored') {
    def checks = checksClass.newInstance(baseConfig())
    def md = '# Chapter One\n\n```html\n<div>\n:::\n# not a heading\n```\n\n    <span>indented code</span>\n\n# Chapter Two\n'
    def findings = checks.checkMarkdown(ctxOf([entries: bytesOf(['demo-template-EN.md': md])]))
    noRule(findings, 'md.rawHtml'); noRule(findings, 'md.pandocSyntax'); noRule(findings, 'md.commonmark')
}

section('markdown: empty heading') {
    def checks = checksClass.newInstance(baseConfig())
    def md = '# \n\n# Chapter One\n\n# Chapter Two\n'
    def f = checks.checkMarkdown(ctxOf([entries: bytesOf(['demo-template-EN.md': md])])).find { it.ruleId == 'md.emptyHeading' }
    assert f != null && f.examples[0].location == 'demo-template-EN.md:1'
}

section('markdown: image path relative to entry directory') {
    def checks = checksClass.newInstance(baseConfig())
    def md = '# Chapter One\n\n![a](./images/ok.png)\n![b](images/missing.png)\n![c](https://example.org/remote.png)\n\n# Chapter Two\n'
    def findings = checks.checkMarkdown(ctxOf([entries: bytesOf(['docs/index.md': md, 'docs/images/ok.png': [1] as byte[]])]))
    def f = findings.find { it.ruleId == 'md.images' }
    assert f != null && f.message.contains('1'), "only missing.png is missing: ${f?.message}"
    assert f.examples[0].text.contains('docs/images/missing.png')
}

section('markdown: multi-page chapter files must start with a heading') {
    def checks = checksClass.newInstance(baseConfig())
    def entries = bytesOf(['01_intro.md': '# Chapter One\n\ntext\n', '02_second.md': 'text before heading\n\n# Chapter Two\n'])
    def findings = checks.checkMarkdown(ctxOf([format: 'markdownMP', entries: entries]))
    expectRules(findings, ['md.multiPageHeading'])
    assert findings.find { it.ruleId == 'md.multiPageHeading' }.examples[0].location == '02_second.md:1'
}

section('markdown: front matter title with image warns') {
    def checks = checksClass.newInstance(baseConfig())
    def md = '---\ndate: July 2025\ntitle: "![arc42](images/arc42-logo.png) Template"\n---\n\n# Chapter One\n\n# Chapter Two\n'
    def f = checks.checkMarkdown(ctxOf([entries: bytesOf(['demo-template-EN.md': md])])).find { it.ruleId == 'md.frontMatter' }
    assert f != null && f.severity == 'warn'
}

// ---- Task 3: heading counts and structure -----------------------------------

def zipBytes = { Map<String, Object> files ->
    def bos = new ByteArrayOutputStream()
    new ZipOutputStream(bos).withCloseable { zos ->
        bytesOf(files).each { String name, byte[] data ->
            zos.putNextEntry(new ZipEntry(name)); zos.write(data); zos.closeEntry()
        }
    }
    return bos.toByteArray()
}

section('unzip reads entries into memory') {
    def entries = checksClass.unzip(zipBytes(['a.md': 'hello', 'images/x.png': [1, 2] as byte[]]))
    assert entries.keySet() == ['a.md', 'images/x.png'] as Set
    assert new String(entries['a.md'], 'UTF-8') == 'hello'
}

section('headingCounts: markdown, asciidoc, textile, rst, latex, html, docbook') {
    def checks = checksClass.newInstance(baseConfig())
    def count = { String format, Map files -> checks.headingCounts(ctxOf([format: format, entries: bytesOf(files)])) }

    assert count('markdown', ['x.md': '# \n\n# One\n\n## Sub\n\n### Deep\n\n# Two\n\n```\n# code\n```\n']) == [1: 2, 2: 1, 3: 1]
    assert count('asciidoc', ['demo-template.adoc': '= Title\n\ninclude::src/01.adoc[]\n', 'src/01.adoc': '== One\n\n=== Sub\n', 'src/02.adoc': '== Two\n']) == [1: 2, 2: 1]
    assert count('textile', ['x.textile': 'h1. \n\nh1(#one). One\n\nh2(#sub). Sub\n\nh1. Two\n']) == [1: 2, 2: 1]
    assert count('rst', ['x.rst': '=====\nTitle\n=====\n\nOne\n===\n\nSub\n---\n\nTwo\n===\n']) == [1: 2, 2: 1]
    assert count('latex', ['x.tex': '\\section{}\n\\section{One}\\label{a}\n\\subsection{Sub}\n\\section{Two}\n']) == [1: 2, 2: 1]
    assert count('latex', ['x.tex': '\\chapter{One}\n\\section{Sub}\n\\chapter{Two}\n']) == [1: 2, 2: 1], "chapter present: chapter is level 1"
    assert count('html', ['demo-template.html': '<html><body><h1>T</h1><h2 id="a">One</h2><h3>Sub</h3><h2>Two</h2><h2></h2></body></html>']) == [1: 1, 2: 2, 3: 1]
    assert count('docbook', ['demo-template.xml': '<book><chapter><title>One</title><section><title>S</title></section></chapter><chapter><title>Two</title></chapter></book>']) == [1: 2, 2: 1]
}

def DOCX_XML = { List<List> paragraphs ->
    // paragraphs: [[style, text], ...]
    def body = paragraphs.collect { p ->
        def style = p[0] ? "<w:pPr><w:pStyle w:val=\"${p[0]}\"/></w:pPr>" : ''
        "<w:p>${style}<w:r><w:t>${p[1]}</w:t></w:r></w:p>"
    }.join('')
    return """<?xml version="1.0" encoding="UTF-8"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>${body}</w:body></w:document>"""
}

section('headingCounts and plainText: docx and epub') {
    def checks = checksClass.newInstance(baseConfig())
    def docx = zipBytes(['word/document.xml': DOCX_XML([['Title', 'T'], ['Heading1', ''], ['Heading1', 'One'], ['Heading2', 'Sub'], ['BodyText', 'HELP SENTINEL SENTENCE FOR TESTS 1.0-EN'], ['Heading1', 'Two']])])
    def dctx = ctxOf([format: 'docx', entries: ['demo-template-EN.docx': docx]])
    assert checks.headingCounts(dctx) == [1: 2, 2: 1]
    assert checks.plainText(dctx).contains('HELP SENTINEL SENTENCE FOR TESTS 1.0-EN')

    def epub = zipBytes([
        'mimetype': 'application/epub+zip',
        'META-INF/container.xml': '<container><rootfiles><rootfile full-path="EPUB/content.opf"/></rootfiles></container>',
        'EPUB/content.opf': '<package><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/><item id="t" href="text/title_page.xhtml" media-type="application/xhtml+xml"/><item id="c1" href="text/ch001.xhtml" media-type="application/xhtml+xml"/><item id="c2" href="text/ch002.xhtml" media-type="application/xhtml+xml"/><item id="i" href="media/f.png" media-type="image/png"/></manifest></package>',
        'EPUB/nav.xhtml': '<html><body><h1>Contents</h1></body></html>',
        'EPUB/text/title_page.xhtml': '<html><body><h1>Title</h1></body></html>',
        'EPUB/text/ch001.xhtml': '<html><body><h1></h1><p>about</p></body></html>',
        'EPUB/text/ch002.xhtml': '<html><body><h1>One</h1><h2>Sub</h2><p>HELP SENTINEL SENTENCE FOR TESTS</p><h1>Two</h1></body></html>',
        'EPUB/media/f.png': [1] as byte[]])
    def ectx = ctxOf([format: 'epub', entries: ['demo-template-EN.epub': epub]])
    assert checks.headingCounts(ectx) == [1: 2, 2: 1], "nav and title page are excluded, empty h1 is not counted"
    assert checks.plainText(ectx).contains('HELP SENTINEL SENTENCE FOR TESTS')
}

section('structure: chapters, help text, revnumber') {
    def checks = checksClass.newInstance(baseConfig())
    def good = ctxOf([entries: bytesOf(['demo-template-EN.md': GOOD_MD])])
    assert checks.checkStructure(good).isEmpty(), checks.checkStructure(good)*.message.toString()

    def oneChapter = ctxOf([entries: bytesOf(['demo-template-EN.md': '# Only\n\nHELP SENTINEL SENTENCE FOR TESTS 1.0-EN\n'])])
    expectRules(checks.checkStructure(oneChapter), ['structure.chapters'])

    def plainWithHelp = ctxOf([style: 'plain', entries: bytesOf(['demo-template-EN.md': GOOD_MD])])
    expectRules(checks.checkStructure(plainWithHelp), ['structure.helpText'])

    def helpMissing = ctxOf([entries: bytesOf(['demo-template-EN.md': '# One\n\nVersion 1.0-EN\n\n# Two\n'])])
    expectRules(checks.checkStructure(helpMissing), ['structure.helpText'])

    def noRev = ctxOf([entries: bytesOf(['demo-template-EN.md': '# One\n\nHELP SENTINEL SENTENCE FOR TESTS\n\n# Two\n'])])
    expectRules(checks.checkStructure(noRev), ['structure.revnumber'])

    def noSentinelKnown = ctxOf([helpSentinel: null, entries: bytesOf(['demo-template-EN.md': GOOD_MD])])
    noRule(checks.checkStructure(noSentinelKnown), 'structure.helpText')
}

section('structure: revnumber with space matches after normalisation') {
    def checks = checksClass.newInstance(baseConfig())
    def md = '# One\n\nHELP SENTINEL SENTENCE FOR TESTS\nTemplate Version 8.2\nES\n\n# Two\n'
    noRule(checks.checkStructure(ctxOf([revnumber: '8.2 ES', entries: bytesOf(['x.md': md])])), 'structure.revnumber')
}

section('structure: reference counts warn on mismatch') {
    def checks = checksClass.newInstance(baseConfig())
    def ctx = ctxOf([entries: bytesOf(['x.md': GOOD_MD]), referenceCounts: [1: 2, 2: 3]])
    def f = checks.checkStructure(ctx).find { it.ruleId == 'structure.referenceCounts' }
    assert f != null && f.severity == 'warn' && f.message.contains('level 2')
    noRule(checks.checkStructure(ctxOf([entries: bytesOf(['x.md': GOOD_MD]), referenceCounts: [1: 2, 2: 1]])), 'structure.referenceCounts')
}

section('structure: html chapters are h2') {
    def checks = checksClass.newInstance(baseConfig())
    def html = '<html><head><title>T</title></head><body><h1>T</h1><h2>One</h2><p>HELP SENTINEL SENTENCE FOR TESTS 1.0-EN</p><h2>Two</h2></body></html>'
    assert checks.checkStructure(ctxOf([format: 'html', entries: bytesOf(['demo-template.html': html])])).isEmpty()
}

// ---- Task 4: completeness and dispatch ----------------------------------------

section('completeness: missing zip yields single finding') {
    def checks = checksClass.newInstance(baseConfig())
    def findings = checks.checkAll(ctxOf([entries: null, zipFile: new File('build/test-verifier/does-not-exist.zip')]))
    assert findings*.ruleId == ['zip.exists'], "got ${findings*.ruleId}"
}

section('completeness: empty zip, primary file, images') {
    def checks = checksClass.newInstance(baseConfig())
    expectRules(checks.checkCompleteness(ctxOf([entries: [:]])), ['zip.nonEmpty'])
    expectRules(checks.checkCompleteness(ctxOf([entries: bytesOf(['images/demo-logo.png': [1] as byte[]])])), ['zip.primaryFile'])
    // binary-only zip must not crash the text rules either
    def all = checks.checkAll(ctxOf([entries: bytesOf(['images/demo-logo.png': [1] as byte[]])]))
    expectRules(all, ['zip.primaryFile'])
    // with-help + imageFolder true needs images/
    expectRules(checks.checkCompleteness(ctxOf([entries: bytesOf(['demo-template-EN.md': GOOD_MD])])), ['zip.images'])
    noRule(checks.checkCompleteness(ctxOf([style: 'plain', entries: bytesOf(['demo-template-EN.md': GOOD_MD])])), 'zip.images')
    noRule(checks.checkCompleteness(ctxOf([format: 'epub', formatConfig: [imageFolder: false], entries: bytesOf(['demo-template-EN.epub': [1] as byte[]])])), 'zip.images')
    // mkdocs keeps images under docs/images
    noRule(checks.checkCompleteness(ctxOf([format: 'mkdocs', entries: bytesOf(['demo-template-EN.md': GOOD_MD, 'docs/images/l.png': [1] as byte[]])])), 'zip.images')
}

section('completeness: primary file names per family') {
    def checks = checksClass.newInstance(baseConfig())
    assert checks.primaryFiles(ctxOf([format: 'html', entries: bytesOf(['demo-template.html': 'x', 'images/a.png': [1] as byte[]])])) == ['demo-template.html']
    assert checks.primaryFiles(ctxOf([format: 'asciidoc', entries: bytesOf(['demo-template.adoc': 'x', 'src/01.adoc': 'y'])])) == ['demo-template.adoc', 'src/01.adoc']
    assert checks.primaryFiles(ctxOf([format: 'mkdocs', entries: bytesOf(['docs/index.md': 'x'])])) == ['docs/index.md'], "recursive search"
}

section('completeness: multi-page needs chapterCount chapter files and no config file') {
    def checks = checksClass.newInstance(baseConfig())
    def single = ctxOf([format: 'markdownMP', entries: bytesOf(['demo-template-EN.md': GOOD_MD, 'images/l.png': [1] as byte[]])])
    def f = checks.checkCompleteness(single).find { it.ruleId == 'zip.primaryFile' }
    assert f != null && f.message.contains('0 chapter file(s)'), f?.message
    def good = ctxOf([format: 'markdownMP', entries: bytesOf(['01_a.md': '# One\n', '02_b.md': '# Two\n', 'images/l.png': [1] as byte[]])])
    noRule(checks.checkCompleteness(good), 'zip.primaryFile')
    def withConfig = ctxOf([format: 'markdownMP', entries: bytesOf(['01_a.md': '# One\n', '02_b.md': '# Two\n', 'config.md': '', 'images/l.png': [1] as byte[]])])
    assert checks.checkCompleteness(withConfig).find { it.ruleId == 'zip.primaryFile' }.message.contains('config.md')
}

// ---- Task 5: HTML -----------------------------------------------------------

def GOOD_HTML = '''<!DOCTYPE html><html lang="en"><head><meta charset="UTF-8"><title>Template</title></head>
<body><h1>Template</h1><div id="toc"><a href="#one">One</a></div>
<h2 id="one">One</h2><p>HELP SENTINEL SENTENCE FOR TESTS 1.0-EN</p><img src="images/demo-logo.png" alt="logo">
<h2 id="two">Two</h2><a href="https://arc42.org">ext</a></body></html>'''

section('html: clean document') {
    def checks = checksClass.newInstance(baseConfig())
    def ctx = ctxOf([format: 'html', entries: bytesOf(['demo-template.html': GOOD_HTML, 'images/demo-logo.png': [1] as byte[]])])
    assert checks.checkAll(ctx).isEmpty(), checks.checkAll(ctx)*.message.toString()
}

section('html: missing title, charset, image, broken anchor, malformed') {
    def checks = checksClass.newInstance(baseConfig())
    def bad = '<html><head><title></title></head><body><h2>One</h2><a href="#nowhere">x</a><img src="images/gone.png"></body></html>'
    def findings = checks.checkHtml(ctxOf([format: 'html', entries: bytesOf(['demo-template.html': bad])]))
    expectRules(findings, ['html.title', 'html.charset', 'html.images', 'html.localLinks'])
    assert findings.find { it.ruleId == 'html.localLinks' }.severity == 'warn'
    noRule(findings, 'html.wellFormed')

    def twoBodies = '<html><head><meta charset="utf-8"><title>T</title></head><body></body><body></body></html>'
    expectRules(checks.checkHtml(ctxOf([format: 'html', entries: bytesOf(['demo-template.html': twoBodies])])), ['html.wellFormed'])
    def noHtmlTag = '<h2>One</h2>'
    expectRules(checks.checkHtml(ctxOf([format: 'html', entries: bytesOf(['demo-template.html': noHtmlTag])])), ['html.wellFormed'])
}

// ---- Task 6: DOCX and EPUB ----------------------------------------------------

section('docx: valid package with matching media') {
    def checks = checksClass.newInstance(baseConfig())
    def xml = DOCX_XML([['Heading1', 'One'], ['BodyText', 'HELP SENTINEL SENTENCE FOR TESTS 1.0-EN'], ['Heading1', 'Two']])
        .replace('</w:body>', '<w:p><w:r><w:drawing/></w:r></w:p></w:body>')
    def docx = zipBytes(['word/document.xml': xml, 'word/media/image1.png': [1] as byte[]])
    def ctx = ctxOf([format: 'docx', entries: ['demo-template-EN.docx': docx, 'images/l.png': [1] as byte[]]])
    assert checks.checkAll(ctx).isEmpty(), checks.checkAll(ctx)*.message.toString()
}

section('docx: unparsable xml, media mismatch, plain needs no media') {
    def checks = checksClass.newInstance(baseConfig())
    def broken = zipBytes(['word/document.xml': '<w:document><unclosed>'])
    expectRules(checks.checkDocx(ctxOf([format: 'docx', entries: ['demo-template-EN.docx': broken]])), ['docx.valid'])
    def notAZip = ctxOf([format: 'docx', entries: ['demo-template-EN.docx': 'plain text'.bytes]])
    expectRules(checks.checkDocx(notAZip), ['docx.valid'])

    def xml = DOCX_XML([['Heading1', 'One'], ['Heading1', 'Two']]).replace('</w:body>', '<w:p><w:r><w:drawing/></w:r></w:p></w:body>')
    def noMedia = zipBytes(['word/document.xml': xml])
    expectRules(checks.checkDocx(ctxOf([format: 'docx', entries: ['demo-template-EN.docx': noMedia]])), ['docx.media'])
    def plainXml = DOCX_XML([['Heading1', 'One'], ['Heading1', 'Two']])
    noRule(checks.checkDocx(ctxOf([format: 'docx', style: 'plain', entries: ['demo-template-EN.docx': zipBytes(['word/document.xml': plainXml])]])), 'docx.media')
}

def EPUB_FILES = { Map extra ->
    def files = [
        'mimetype': 'application/epub+zip',
        'META-INF/container.xml': '<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="EPUB/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>',
        'EPUB/content.opf': '<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0"><manifest><item id="c1" href="text/ch001.xhtml" media-type="application/xhtml+xml"/><item id="i" href="media/f.png" media-type="image/png"/></manifest></package>',
        'EPUB/text/ch001.xhtml': '<html><body><h1>One</h1><p>HELP SENTINEL SENTENCE FOR TESTS 1.0-EN</p><h1>Two</h1></body></html>',
        'EPUB/media/f.png': [1] as byte[]]
    files.putAll(extra)
    return files
}

section('epub: valid package') {
    def checks = checksClass.newInstance(baseConfig())
    def ctx = ctxOf([format: 'epub', formatConfig: [imageFolder: false], entries: ['demo-template-EN.epub': zipBytes(EPUB_FILES([:]))]])
    assert checks.checkAll(ctx).isEmpty(), checks.checkAll(ctx)*.message.toString()
}

section('epub: missing manifest item, missing container, no images in with-help') {
    def checks = checksClass.newInstance(baseConfig())
    def files = EPUB_FILES([:]); files.remove('EPUB/media/f.png')
    def findings = checks.checkEpub(ctxOf([format: 'epub', entries: ['demo-template-EN.epub': zipBytes(files)]]))
    expectRules(findings, ['epub.valid'])
    assert findings.find { it.ruleId == 'epub.valid' }.examples[0].text.contains('media/f.png')

    def noContainer = EPUB_FILES([:]); noContainer.remove('META-INF/container.xml')
    expectRules(checks.checkEpub(ctxOf([format: 'epub', entries: ['demo-template-EN.epub': zipBytes(noContainer)]])), ['epub.valid'])

    def noImages = EPUB_FILES(['EPUB/content.opf': '<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf"><manifest><item id="c1" href="text/ch001.xhtml" media-type="application/xhtml+xml"/></manifest></package>'])
    noImages.remove('EPUB/media/f.png')
    expectRules(checks.checkEpub(ctxOf([format: 'epub', entries: ['demo-template-EN.epub': zipBytes(noImages)]])), ['epub.media'])
    noRule(checks.checkEpub(ctxOf([format: 'epub', style: 'plain', entries: ['demo-template-EN.epub': zipBytes(noImages)]])), 'epub.media')
}

// ---- Task 7: Verifier ---------------------------------------------------------

def verifierClass = gcl.parseClass(new File('lib/Verifier.groovy'))

def fixtureRoot = new File('build/test-verifier')
def makeFixture = { Map<String, Object> zips ->
    if (fixtureRoot.exists()) fixtureRoot.deleteDir()
    new File(fixtureRoot, 'gm/EN/adoc').mkdirs()
    new File(fixtureRoot, 'gm/EN/images').mkdirs()
    new File(fixtureRoot, 'gm/EN/version.properties').write("revnumber=1.0-EN\nrevdate=September 2026\nrevremark=(test)\n", 'utf-8')
    new File(fixtureRoot, 'gm/EN/demo-template.adoc').write('= Demo\n\ninclude::adoc/config.adoc[]\n\ninclude::adoc/01_one.adoc[]\n\ninclude::adoc/02_two.adoc[]\n', 'utf-8')
    new File(fixtureRoot, 'gm/EN/adoc/config.adoc').write(':imagesdir: ./images\n:demohelp:\n', 'utf-8')
    new File(fixtureRoot, 'gm/EN/adoc/01_one.adoc').write('''== Chapter One

ifdef::demohelp[]
[role="demohelp"]
****
.Contents
* a bullet first
HELP SENTINEL SENTENCE FOR TESTS.
****
endif::demohelp[]

image::demo-logo.png[]
''', 'utf-8')
    new File(fixtureRoot, 'gm/EN/adoc/02_two.adoc').write('== Chapter Two\n\ntext\n', 'utf-8')
    new File(fixtureRoot, 'gm/EN/images/demo-logo.png').bytes = [1, 2, 3] as byte[]
    new File(fixtureRoot, 'dist').mkdirs()
    zips.each { String name, Object files -> new File(fixtureRoot, "dist/${name}").bytes = zipBytes(files as Map) }
    def cfg = baseConfig()
    cfg.formats = ['markdown': [imageFolder: true], 'markdownMP': [imageFolder: true]]
    return cfg
}

def GOOD_ZIP = ['demo-template-EN.md': GOOD_MD, 'images/demo-logo.png': [1] as byte[]]
def PLAIN_ZIP = ['demo-template-EN.md': '# Chapter One\n\nTemplate Version 1.0-EN\n\n# Chapter Two\n', 'images/demo-logo.png': [1] as byte[]]

section('verifier: languages, styles, version properties, sentinel skips label lines') {
    def cfg = makeFixture([:])
    def v = verifierClass.newInstance(cfg, fixtureRoot, checksClass.newInstance(cfg))
    assert v.languages() == ['EN']
    assert v.styles() == ['plain', 'with-help']
    assert v.versionProps('EN').revnumber == '1.0-EN' && v.versionProps('EN').revdate == 'September 2026'
    assert v.versionProps('XX') == [:]
    assert v.helpSentinel('EN') == 'HELP SENTINEL SENTENCE FOR TESTS.', "got '${v.helpSentinel('EN')}'"
    assert v.zipFile('EN', 'with-help', 'markdown').name == 'demo-template-EN-withhelp-markdown.zip'
}

section('verifier: buildContext and verifyCase on a good and a missing zip') {
    def cfg = makeFixture(['demo-template-EN-withhelp-markdown.zip': GOOD_ZIP])
    def v = verifierClass.newInstance(cfg, fixtureRoot, checksClass.newInstance(cfg))
    def ctx = v.buildContext('EN', 'with-help', 'markdown')
    assert ctx.entries.keySet() == GOOD_ZIP.keySet() && ctx.revnumber == '1.0-EN' && ctx.chapterCount == 2
    assert ctx.referenceCounts == null, "EN is the reference language"
    def good = v.verifyCase('EN', 'with-help', 'markdown')
    assert good.status == 'pass' && good.revnumber == '1.0-EN' && good.revdate == 'September 2026', good.findings*.message.toString()
    def missing = v.verifyCase('EN', 'plain', 'markdown')
    assert missing.status == 'fail' && missing.findings*.ruleId == ['zip.exists']
}

section('verifier: reference counts come from the reference language') {
    def cfg = makeFixture(['demo-template-EN-withhelp-markdown.zip': GOOD_ZIP, 'demo-template-DE-withhelp-markdown.zip': ['demo-template-DE.md': '# Eins\n\nHELP SENTINEL SENTENCE FOR TESTS 1.0-DE\n\n## Extra\n\n## Extra2\n\n# Zwei\n', 'images/l.png': [1] as byte[]]])
    new File(fixtureRoot, 'gm/DE/adoc').mkdirs()
    new File(fixtureRoot, 'gm/DE/version.properties').write("revnumber=1.0-DE\nrevdate=2026\n", 'utf-8')
    new File(fixtureRoot, 'gm/DE/adoc/01_one.adoc').write('== Eins\n\n[role="demohelp"]\n****\nHELP SENTINEL SENTENCE FOR TESTS\n****\n', 'utf-8')
    def v = verifierClass.newInstance(cfg, fixtureRoot, checksClass.newInstance(cfg))
    def de = v.buildContext('DE', 'with-help', 'markdown')
    assert de.referenceCounts == [1: 2, 2: 1], "EN GOOD_MD has 2 H1 and 1 H2, got ${de.referenceCounts}"
    def result = v.verifyCase('DE', 'with-help', 'markdown')
    assert result.status == 'warn' && result.findings*.ruleId == ['structure.referenceCounts'], result.findings*.message.toString()
}

section('verifier: verifySuites builds one suite per format with every language x style') {
    def cfg = makeFixture(['demo-template-EN-withhelp-markdown.zip': GOOD_ZIP, 'demo-template-EN-plain-markdown.zip': PLAIN_ZIP])
    def v = verifierClass.newInstance(cfg, fixtureRoot, checksClass.newInstance(cfg))
    def suites = v.verifySuites(['markdown', 'markdownMP'])
    assert suites*.name == ['markdown', 'markdownMP']
    assert suites[0].cases*.status == ['pass', 'pass'], suites[0].cases.collect { "${it.style}: ${it.findings*.message}" }.toString()
    assert suites[1].cases*.status == ['fail', 'fail'], "markdownMP zips do not exist"
    assert suites[0].cases*.durationMs.every { it != null }
}

// ---- Task 8: source suite -----------------------------------------------------

section('source suite: clean golden master passes') {
    def cfg = makeFixture([:])
    def v = verifierClass.newInstance(cfg, fixtureRoot, checksClass.newInstance(cfg))
    def suite = v.verifySource()
    assert suite.name == 'source' && suite.cases.size() == 1
    assert suite.cases[0].status == 'pass', suite.cases[0].findings*.message.toString()
    assert suite.cases[0].language == 'EN' && suite.cases[0].revnumber == '1.0-EN'
}

section('source suite: missing revdate, broken include, missing image, wrong chapter count, missing main') {
    def cfg = makeFixture([:])
    new File(fixtureRoot, 'gm/EN/version.properties').write("revnumber=1.0-EN\n", 'utf-8')
    new File(fixtureRoot, 'gm/EN/demo-template.adoc').append('\ninclude::adoc/99_missing.adoc[]\n', 'utf-8')
    new File(fixtureRoot, 'gm/EN/adoc/02_two.adoc').append('\nimage::not-there.png[]\n', 'utf-8')
    new File(fixtureRoot, 'gm/EN/adoc/03_three.adoc').write('== Three\n', 'utf-8')
    def v = verifierClass.newInstance(cfg, fixtureRoot, checksClass.newInstance(cfg))
    def c = v.verifySource().cases[0]
    expectRules(c.findings, ['src.versionProperties', 'src.includes', 'src.images', 'src.chapters'])
    assert c.findings.find { it.ruleId == 'src.includes' }.examples[0].text.contains('99_missing.adoc')

    assert new File(fixtureRoot, 'gm/EN/demo-template.adoc').delete()
    expectRules(verifierClass.newInstance(cfg, fixtureRoot, checksClass.newInstance(cfg)).verifySource().cases[0].findings, ['src.mainFile'])
}

section('verifyAll puts the source suite first') {
    def cfg = makeFixture(['demo-template-EN-withhelp-markdown.zip': GOOD_ZIP])
    def v = verifierClass.newInstance(cfg, fixtureRoot, checksClass.newInstance(cfg))
    assert v.verifyAll(['markdown'])*.name == ['source', 'markdown']
}

// ---- summary ----------------------------------------------------------------

println failures ? "✗ ${failures.size()} section(s) failed: ${failures}" : "=== All Tests Passed! ==="
System.exit(failures ? 1 : 0)
