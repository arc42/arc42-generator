#!/usr/bin/env groovy

/**
 * Test script for the command line of build.groovy
 *
 * A typo in a phase, an option or a format must end the build with exit code 2 before anything
 * is built, instead of running a different build (or none) and reporting success.
 */

def run = { String... arguments ->
    def process = new ProcessBuilder(['groovy', 'build.groovy'] + (arguments as List)).redirectErrorStream(true).start()
    def output = process.inputStream.getText('UTF-8')
    [exit: process.waitFor(), output: output]
}

try {
    println "=== Test 1: An unknown phase is rejected ==="
    def result = run('template')
    assert result.exit == 2, "exit code 2 expected, got ${result.exit}:\n${result.output}"
    assert result.output.contains("Unknown phase 'template'") && result.output.contains('templates'), result.output
    assert !result.output.contains('BUILD SUCCESSFUL'), "a wrong phase must not look like a successful build"
    println "✓ Test 1 passed\n"

    println "=== Test 2: Unknown options and options without a value are rejected ==="
    ['--formt=html', '--lint=off', '--paralle=false', '-x'].each { option ->
        result = run('templates', option)
        assert result.exit == 2 && result.output.contains("Unknown option ${option}"), "${option}: exit ${result.exit}\n${result.output}"
    }
    result = run('convert', '--format=')
    assert result.exit == 2 && result.output.contains('--format= needs a value'), result.output
    result = run('templates', 'convert')
    assert result.exit == 2 && result.output.contains('More than one phase'), result.output
    result = run('--failure-level=loud')
    assert result.exit == 2 && result.output.contains("Unknown --failure-level 'loud'"), result.output
    println "✓ Test 2 passed\n"

    println "=== Test 3: A format the config does not define is rejected ==="
    result = run('convert', '--format=md')
    assert result.exit == 2, "exit code 2 expected, got ${result.exit}:\n${result.output}"
    assert result.output.contains("Unknown --format 'md'") && result.output.contains('markdown'), result.output
    assert !result.output.contains('Loading Helper Classes'), "nothing must be loaded or built for an unknown format"
    println "✓ Test 3 passed\n"

    println "=== Test 4: --help prints the phases and options ==="
    result = run('--help')
    assert result.exit == 0, "exit code 0 expected, got ${result.exit}"
    assert result.output.contains('Usage: groovy build.groovy') && result.output.contains('--release-tag='), result.output
    println "✓ Test 4 passed\n"

    println "=== All Tests Passed! ==="
    System.exit(0)

} catch (Throwable e) {
    println "\n✗ Test failed with error:"
    println e.message
    e.printStackTrace()
    System.exit(1)
}
