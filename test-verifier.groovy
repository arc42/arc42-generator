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

// ---- summary ----------------------------------------------------------------

println failures ? "✗ ${failures.size()} section(s) failed: ${failures}" : "=== All Tests Passed! ==="
System.exit(failures ? 1 : 0)
