#!/usr/bin/env groovy

/**
 * Checks that every file listed in manifest.json can be downloaded from a prefix.
 * Run through make: make check-downloads PREFIX=https://... [CHECKSUMS=1]
 *
 * Usage:
 *   groovy check-downloads.groovy <prefix> [--checksums] [--manifest=path] [--config=path]
 *
 * Default manifest: manifest.json in distribution.targetPath of the config (buildconfig.groovy).
 * Exits with 1 if a file is missing or (with --checksums) differs from the manifest.
 */

def cliArgs = args as List
def prefix = cliArgs.find { !it.startsWith('--') }
if (!prefix) {
    System.err.println "Usage: groovy check-downloads.groovy <prefix> [--checksums] [--manifest=path] [--config=path]"
    System.exit(2)
}
def checksums = cliArgs.contains('--checksums')
def manifestArg = cliArgs.find { it.startsWith('--manifest=') }?.substring('--manifest='.length())
def configPath = cliArgs.find { it.startsWith('--config=') }?.substring('--config='.length()) ?: 'buildconfig.groovy'

def manifestFile
if (manifestArg) {
    manifestFile = new File(manifestArg)
} else {
    def configFile = new File(configPath)
    def config = new ConfigSlurper().parse(configFile.toURI().toURL())
    manifestFile = new File(new File(configFile.absoluteFile.parentFile, config.distribution.targetPath.toString()), 'manifest.json')
}
if (!manifestFile.exists()) {
    System.err.println "✗ No manifest: ${manifestFile} (run make build first)"
    System.exit(2)
}

def checker = new GroovyClassLoader().parseClass(new File('lib/DownloadCheck.groovy')).newInstance()
def files = checker.manifestFiles(manifestFile)
println "==> ${checksums ? 'Downloading' : 'Checking'} ${files.size()} files of ${manifestFile.path}"
println "    from ${prefix}"
def start = System.currentTimeMillis()
def results = checker.check(files, prefix, checksums)
def failed = results.findAll { !it.ok }
failed.each { println "  ✗ ${it.name}: ${it.problem}" }
def seconds = String.format('%.1f', (System.currentTimeMillis() - start) / 1000.0)
if (failed) {
    println "✗ ${failed.size()} of ${files.size()} files ${checksums ? 'missing or different' : 'missing'} (${seconds}s)"
    System.exit(1)
}
println "✓ All ${files.size()} files ${checksums ? 'downloaded, SHA-256 as in the manifest' : 'available'} (${seconds}s)"
