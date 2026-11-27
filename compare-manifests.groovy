#!/usr/bin/env groovy

/**
 * Compares the files (name and SHA-256) of two manifest.json files and lists what differs.
 * Builds are reproducible: two builds of the same golden master commit differ only in the ZIPs
 * that a change of the generator really changed. Run through make: make manifest-diff
 *
 * Usage:
 *   groovy compare-manifests.groovy <base manifest.json> <current manifest.json>
 *
 * Exits with 0 if the files are identical, with 1 if they differ, with 2 on a wrong call.
 */

import groovy.json.JsonSlurper

if (args.size() != 2 || args.any { !new File(it).isFile() }) {
    System.err.println "Usage: groovy compare-manifests.groovy <base manifest.json> <current manifest.json>"
    args.findAll { !new File(it).isFile() }.each { System.err.println "✗ No such file: ${it}" }
    System.exit(2)
}
def (base, current) = args.collect { new JsonSlurper().parse(new File(it), 'utf-8') }
def packager = new GroovyClassLoader().parseClass(new File('lib/Packager.groovy'))
def diff = packager.diffManifests(base, current)

def report = new StringBuilder()
report << "Base:    ${args[0]} (${base.files.size()} files, template ${base.templateCommit?.take(7) ?: 'unknown'})\n"
report << "Current: ${args[1]} (${current.files.size()} files, template ${current.templateCommit?.take(7) ?: 'unknown'})\n"
if (!diff.sameTemplate) {
    report << "⚠ Different golden master commits: the timestamps inside every ZIP differ, so every ZIP differs.\n"
    report << "  Compare two builds of the same arc42-template commit to see what a generator change did.\n"
}
def differences = diff.changed.size() + diff.added.size() + diff.removed.size()
if (differences == 0) {
    report << "✓ All ${diff.unchanged} files are identical (name and SHA-256)\n"
} else {
    report << "✗ ${diff.changed.size()} changed, ${diff.added.size()} added, ${diff.removed.size()} removed, ${diff.unchanged} identical\n"
    // by format: a change of the generator usually touches whole formats
    def formatOf = { String name -> (base.files + current.files).find { it.name == name }?.format ?: '?' }
    [changed: diff.changed, added: diff.added, removed: diff.removed].each { kind, names ->
        names.groupBy(formatOf).sort().each { format, list ->
            report << "  ${kind} ${format} (${list.size()}): ${list.join(', ')}\n"
        }
    }
}
print report
System.exit(differences == 0 ? 0 : 1)
