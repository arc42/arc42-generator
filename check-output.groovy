#!/usr/bin/env groovy

/**
 * Checks the converted output and the distribution ZIPs of a build (lib/OutputCheck.groovy):
 * leftover AsciiDoc markers, headings, help texts and images in every format, well-formed
 * DocBook/DOCX/EPUB, readable PDFs, and ZIPs that match manifest.json.
 * Run through make: make validate
 *
 * Usage:
 *   groovy check-output.groovy [--config=path] [--format=<format>]
 *
 * Exits with 1 if a check fails, with 2 on a wrong call.
 */

def cliArgs = args as List
def unknown = cliArgs.findAll { !it.startsWith('--config=') && !it.startsWith('--format=') }
if (unknown) {
    System.err.println "Unknown argument ${unknown.join(', ')}\nUsage: groovy check-output.groovy [--config=path] [--format=<format>]"
    System.exit(2)
}
def configPath = cliArgs.find { it.startsWith('--config=') }?.substring('--config='.length()) ?: 'buildconfig.groovy'
def format = cliArgs.find { it.startsWith('--format=') }?.substring('--format='.length())

def configFile = new File(configPath).absoluteFile
def config = new ConfigSlurper().parse(configFile.toURI().toURL())
if (format && !(format in config.formats.keySet())) {
    System.err.println "✗ Unknown --format '${format}', the config defines: ${config.formats.keySet().join(', ')}"
    System.exit(2)
}

println "==> Content of the output and the distribution ZIPs (fails the build)"
def start = System.currentTimeMillis()
def check = new GroovyClassLoader().parseClass(new File('lib/OutputCheck.groovy')).newInstance(config, configFile.parentFile)
check.checkAll(format ? [format] : null)

check.problems.sort { a, b -> (a.severity <=> b.severity) ?: (a.check <=> b.check) ?: (a.location <=> b.location) }.each { problem ->
    println "  ${problem.severity == 'error' ? '✗' : '⚠'} [${problem.check}] ${problem.location}: ${problem.message}"
}
def seconds = String.format('%.1f', (System.currentTimeMillis() - start) / 1000.0)
def scope = "${check.checked.outputs} output(s), ${check.checked.textFiles} text file(s), ${check.checked.pdfs} PDF(s), ${check.checked.zips} ZIP(s)"
if (check.errors) {
    println "✗ ${check.errors.size()} problem(s) in the output, ${check.warnings.size()} warning(s) (${scope}, ${seconds}s)"
    System.exit(1)
}
println "✓ Output checks passed: ${scope}, ${check.warnings.size()} warning(s) (${seconds}s)"
