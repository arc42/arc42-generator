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
                            failure(message: errors.collect { "${it.ruleId} (${it.message.find(/\d+/) ?: '1'})" }.join(', '), describe(errors))
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
}
