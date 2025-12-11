#!/usr/bin/env groovy

/**
 * run-all-tests.groovy - Automated Test Suite
 *
 * Executes all test scripts and reports results
 * Exit code 0 = all tests passed
 * Exit code 1 = one or more tests failed
 */

println """
╔═══════════════════════════════════════════════════════════════════════════╗
║                    arc42-generator Test Suite                             ║
║                      Automated Integration Tests                          ║
╚═══════════════════════════════════════════════════════════════════════════╝
"""

def startTime = System.currentTimeMillis()

// Test configuration
def tests = [
    [
        name: "Template Generation",
        script: "test-templates.groovy",
        description: "Tests Golden Master processing, language discovery, and feature removal"
    ],
    [
        name: "Template Discovery",
        script: "test-discovery.groovy",
        description: "Tests template scanning and metadata extraction"
    ],
    [
        name: "Format Conversion",
        script: "test-converter.groovy",
        description: "Tests AsciidoctorJ and Pandoc integration"
    ]
]

def results = []
def totalTests = tests.size()
def passedTests = 0
def failedTests = 0

// Run each test
tests.eachWithIndex { test, index ->
    println "\n[${ index + 1}/${totalTests}] Running: ${test.name}"
    println "Description: ${test.description}"
    println "Script: ${test.script}"
    println "-" * 79

    def testStartTime = System.currentTimeMillis()

    try {
        // Execute test script
        def process = ["groovy", test.script].execute()

        // Capture output
        def output = new StringBuilder()
        def error = new StringBuilder()

        process.consumeProcessOutput(output, error)
        process.waitFor()

        def exitCode = process.exitValue()
        def testDuration = (System.currentTimeMillis() - testStartTime) / 1000.0

        if (exitCode == 0) {
            println "✅ PASSED (${String.format('%.1f', testDuration)}s)"
            passedTests++
            results << [
                name: test.name,
                status: "PASSED",
                duration: testDuration,
                exitCode: exitCode,
                output: output.toString()
            ]
        } else {
            println "❌ FAILED (${String.format('%.1f', testDuration)}s)"
            println "\nError Output:"
            println error.toString()
            failedTests++
            results << [
                name: test.name,
                status: "FAILED",
                duration: testDuration,
                exitCode: exitCode,
                output: output.toString(),
                error: error.toString()
            ]
        }
    } catch (Exception e) {
        def testDuration = (System.currentTimeMillis() - testStartTime) / 1000.0
        println "❌ EXCEPTION (${String.format('%.1f', testDuration)}s)"
        println "Error: ${e.message}"
        e.printStackTrace()
        failedTests++
        results << [
            name: test.name,
            status: "EXCEPTION",
            duration: testDuration,
            exception: e.message
        ]
    }
}

// Print summary
def totalDuration = (System.currentTimeMillis() - startTime) / 1000.0

println """

╔═══════════════════════════════════════════════════════════════════════════╗
║                           TEST SUMMARY                                    ║
╚═══════════════════════════════════════════════════════════════════════════╝
"""

println "Total Tests:  ${totalTests}"
println "Passed:       ${passedTests} ✅"
println "Failed:       ${failedTests} ❌"
println "Success Rate: ${String.format('%.1f', (passedTests / totalTests) * 100)}%"
println "Total Time:   ${String.format('%.1f', totalDuration)}s"
println ""

// Print detailed results
if (failedTests > 0) {
    println "Failed Tests:"
    results.findAll { it.status != "PASSED" }.each { result ->
        println "  ❌ ${result.name}"
        if (result.error) {
            println "     Error: ${result.error.split('\n')[0]}"
        }
        if (result.exception) {
            println "     Exception: ${result.exception}"
        }
    }
    println ""
}

// Print test details
println "Test Details:"
results.each { result ->
    def icon = result.status == "PASSED" ? "✅" : "❌"
    println "  ${icon} ${result.name}: ${result.status} (${String.format('%.1f', result.duration)}s)"
}

println ""

// Exit with appropriate code
if (failedTests > 0) {
    println "❌ TEST SUITE FAILED"
    System.exit(1)
} else {
    println "✅ ALL TESTS PASSED"
    System.exit(0)
}
