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
}
