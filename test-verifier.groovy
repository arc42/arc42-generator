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

// ---- summary ----------------------------------------------------------------

println failures ? "✗ ${failures.size()} section(s) failed: ${failures}" : "=== All Tests Passed! ==="
System.exit(failures ? 1 : 0)
