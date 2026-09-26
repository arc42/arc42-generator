#!/usr/bin/env groovy

import groovy.xml.MarkupBuilder

/**
 * Report.groovy - Output of the verify phase
 *
 * Responsibilities:
 * - Derive a test case status from its findings
 * - Write JUnit XML (Surefire schema), one file per suite
 * - Write a self-contained HTML matrix
 * - Print a console summary
 */
class Report {

    def config
    def projectRoot

    Report(config, projectRoot = new File('.')) {
        this.config = config
        this.projectRoot = projectRoot
    }

    /** 'fail' if any error finding, else 'warn' if any finding, else 'pass' */
    static String statusOf(List findings) {
        if (!findings) return 'pass'
        if (findings.any { it.severity == 'error' }) return 'fail'
        return 'warn'
    }

    File reportDir() {
        return new File(projectRoot, (config.verify?.reportDir ?: 'build/reports').toString())
    }

    // ---- JUnit XML ---------------------------------------------------------------

    static String caseName(Map c) {
        def base = "${c.language}-${c.style}"
        return c.revnumber ? "${base} [${c.revnumber}]".toString() : base.toString()
    }

    private static String seconds(Long ms) { return String.format(Locale.ROOT, '%.3f', (ms ?: 0L) / 1000.0) }

    private static String describe(List findings) {
        return findings.collect { f ->
            def lines = ["${f.ruleId} [${f.severity}]: ${f.message}"]
            (f.examples ?: []).each { e -> lines << "    ${e.location}: ${e.text}" }
            lines.join('\n')
        }.join('\n')
    }

    /** One Surefire-style XML file per suite: TEST-<suite>.xml */
    List<File> writeJUnit(List suites, File dir) {
        dir.mkdirs()
        def classPrefix = "${config.project.featurePrefix ?: 'project'}.verify"
        return suites.collect { suite ->
            def file = new File(dir, "TEST-${suite.name}.xml")
            def cases = suite.cases
            def writer = new StringWriter()
            def xml = new MarkupBuilder(writer)
            xml.mkp.xmlDeclaration(version: '1.0', encoding: 'UTF-8')
            xml.testsuite(name: suite.name, tests: cases.size(),
                          failures: cases.count { it.status == 'fail' }, errors: 0,
                          skipped: cases.count { it.status == 'skipped' },
                          time: seconds(cases.sum { it.durationMs ?: 0L } as Long),
                          timestamp: new Date().format("yyyy-MM-dd'T'HH:mm:ss")) {
                properties {
                    cases.collect { it.language }.unique().each { lang ->
                        def c = cases.find { it.language == lang }
                        if (c.revnumber) property(name: "revnumber.${lang}", value: c.revnumber)
                        if (c.revdate) property(name: "revdate.${lang}", value: c.revdate)
                    }
                }
                cases.each { c ->
                    testcase(name: caseName(c), classname: "${classPrefix}.${suite.name}", time: seconds(c.durationMs)) {
                        properties {
                            property(name: 'language', value: c.language)
                            property(name: 'style', value: c.style)
                            property(name: 'format', value: c.format)
                            if (c.revnumber) property(name: 'revnumber', value: c.revnumber)
                            if (c.revdate) property(name: 'revdate', value: c.revdate)
                        }
                        def errors = c.findings.findAll { it.severity == 'error' }
                        def warns = c.findings.findAll { it.severity != 'error' }
                        if (c.status == 'skipped') {
                            skipped()
                        } else if (errors) {
                            failure(message: errors.collect { "${it.ruleId} (${it.count ?: (it.examples ? it.examples.size() : 1)})" }.join(', '), describe(errors))
                        }
                        if (warns) {
                            'system-out'(describe(warns))
                        }
                    }
                }
            }
            file.write(writer.toString(), 'UTF-8')
            return file
        }
    }

    // ---- HTML matrix ---------------------------------------------------------------

    static String esc(Object s) {
        return (s == null ? '' : s.toString()).replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;').replace('"', '&quot;')
    }

    boolean hasFailures(List suites) {
        return suites.any { s -> s.cases.any { it.status == 'fail' } }
    }

    /** Self-contained HTML page: header, matrix (languages x formats), findings grouped by rule */
    File writeHtml(List suites, File file, Map meta) {
        file.parentFile.mkdirs()
        def formats = suites*.name
        def cases = suites.collectMany { s -> s.cases }
        def languages = cases*.language.unique().sort()
        def styles = cases.findAll { it.format != 'source' }*.style.unique().sort()
        def lookup = cases.collectEntries { [("${it.language}|${it.style}|${it.format}".toString()): it] }
        def version = { lang -> def c = cases.find { it.language == lang && it.revnumber }; c ? "${c.revnumber}<br><small>${esc(c.revdate)}</small>" : '' }

        def sb = new StringBuilder()
        sb << '<!DOCTYPE html>\n<html lang="en"><head><meta charset="UTF-8"><title>Verify report: ' << esc(meta.project) << '</title>\n<style>\n'
        sb << 'body{font-family:system-ui,sans-serif;margin:2em;color:#222}table{border-collapse:collapse}th,td{border:1px solid #ccc;padding:4px 8px;text-align:center;font-size:90%}'
        sb << 'th{background:#f3f3f3}td.lang{text-align:left;font-weight:bold}.cell,.tag{display:inline-block;min-width:1.6em;padding:2px 4px;margin:1px;border-radius:3px;color:#fff;font-size:80%}'
        sb << '.pass{background:#2e8b57}.warn{background:#d4a017}.fail{background:#c0392b}.missing{background:#999}'
        sb << 'details{margin:.4em 0}summary{cursor:pointer}pre{background:#f7f7f7;padding:.5em;overflow:auto}.legend span{margin-right:1em}\n</style></head><body>\n'
        sb << '<h1>Verify report: ' << esc(meta.project) << '</h1>\n'
        sb << '<p>Date: ' << esc(meta.date) << (meta.generatorCommit ? ' · generator ' + esc(meta.generatorCommit) : '') << (meta.goldenMasterCommit ? ' · golden master ' + esc(meta.goldenMasterCommit) : '') << '</p>\n'
        int pass = cases.count { it.status == 'pass' }, warn = cases.count { it.status == 'warn' }, fail = cases.count { it.status == 'fail' }
        sb << "<p class=\"legend\"><span class=\"tag pass\">${pass} pass</span><span class=\"tag warn\">${warn} warn</span><span class=\"tag fail\">${fail} fail</span> · cell order: ${styles.collect { esc(it) }.join(' / ')}</p>\n"

        sb << '<table>\n<tr><th>Language</th><th>Version</th>' << formats.collect { "<th>${esc(it)}</th>" }.join('') << '</tr>\n'
        languages.each { lang ->
            sb << '<tr><td class="lang">' << esc(lang) << '</td><td>' << version(lang) << '</td>'
            formats.each { fmt ->
                sb << '<td>'
                if (fmt == 'source') {
                    def c = lookup["${lang}|-|source".toString()]
                    sb << "<span class=\"cell ${c ? c.status : 'missing'}\" title=\"source\">${c ? c.status : '–'}</span>"
                } else {
                    styles.each { style ->
                        def c = lookup["${lang}|${style}|${fmt}".toString()]
                        sb << "<span class=\"cell ${c ? c.status : 'missing'}\" title=\"${esc(style)}\">${c ? esc(style[0]) : '–'}</span>"
                    }
                }
                sb << '</td>'
            }
            sb << '</tr>\n'
        }
        sb << '</table>\n'

        // findings grouped by rule id, errors first
        def byRule = [:]
        cases.each { c -> c.findings.each { f -> byRule.computeIfAbsent(f.ruleId) { [severity: f.severity, hits: []] }.hits << [c: c, f: f] } }
        def ordered = byRule.entrySet().sort { a, b -> (a.value.severity == 'error' ? 0 : 1) <=> (b.value.severity == 'error' ? 0 : 1) ?: a.key <=> b.key }
        if (ordered) sb << '<h2>Findings</h2>\n'
        ordered.each { e ->
            sb << "<details${e.value.severity == 'error' ? ' open' : ''}><summary><span class=\"tag ${e.value.severity == 'error' ? 'fail' : 'warn'}\">${e.value.severity}</span> <code>${esc(e.key)}</code> in ${e.value.hits.size()} output(s)</summary>\n<ul>\n"
            e.value.hits.each { h ->
                sb << '<li><b>' << esc(h.c.format) << ' / ' << esc(caseName(h.c)) << '</b>: ' << esc(h.f.message)
                if (h.f.examples) sb << '<pre>' << h.f.examples.collect { "${esc(it.location)}: ${esc(it.text)}" }.join('\n') << '</pre>'
                sb << '</li>\n'
            }
            sb << '</ul></details>\n'
        }
        sb << '</body></html>\n'
        file.write(sb.toString(), 'UTF-8')
        return file
    }

    // ---- console -------------------------------------------------------------------

    void printSummary(List suites, File junitDir, File htmlFile) {
        println "\n=== Verification Summary ==="
        suites.each { s ->
            def c = s.cases
            println String.format('  %-18s %3d pass  %3d warn  %3d fail', s.name, c.count { it.status == 'pass' }, c.count { it.status == 'warn' }, c.count { it.status == 'fail' })
        }
        def failed = suites.collectMany { s -> s.cases.findAll { it.status == 'fail' }.collect { [suite: s.name, c: it] } }
        if (failed) {
            println "\nFailed:"
            failed.each { println "  ✗ ${it.suite} ${caseName(it.c)}: ${it.c.findings.findAll { f -> f.severity == 'error' }*.ruleId.join(', ')}" }
        }
        println "\nReports:"
        println "  JUnit XML: ${junitDir}"
        println "  HTML:      ${htmlFile}"
        println ""
    }

    /**
     * Write both reports under reportDir() and print the summary.
     * A full run (no meta.formatFilter) first removes stale TEST-*.xml files so CI never ingests a mix;
     * a filtered run (--format=) keeps the other suites and writes verify-<format>.html next to verify.html.
     */
    List<File> writeAll(List suites, Map meta) {
        def junitDir = new File(reportDir(), 'junit')
        def filter = meta.formatFilter
        def htmlFile = new File(reportDir(), filter ? "verify-${filter}.html" : 'verify.html')
        if (!filter) junitDir.listFiles()?.findAll { it.name ==~ /TEST-.*\.xml/ }*.delete()
        def files = writeJUnit(suites, junitDir)
        files << writeHtml(suites, htmlFile, meta)
        printSummary(suites, junitDir, htmlFile)
        return files
    }
}
