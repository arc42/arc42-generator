#!/usr/bin/env groovy

/**
 * Test script for the golden master lint in Templates.groovy
 *
 * Creates a small, clean golden master (EN + DE) with its own config file,
 * breaks it in one way per test and checks what validateGoldenMaster()
 * reports and how createFromGoldenMaster() reacts to errors and warnings.
 */

def fixture = new File('build/test-lint/demo-project')

// A help block as it appears in the golden master (six lines, the tests rely on the line numbers)
def helpBlock = { String text ->
    """ifdef::demohelp[]
[role="demohelp"]
****
${text}
****
endif::demohelp[]
"""
}

// (Re)create the clean fixture: two languages with the same chapters, help blocks and images
def resetFixture = {
    if (fixture.exists()) fixture.deleteDir()
    fixture.mkdirs()

    new File(fixture, 'buildconfig.groovy').write('''
project {
    name = 'demo-template'
    featurePrefix = 'demo'
    logo = 'demo-logo.png'
}

goldenMaster {
    sourcePath = './'
    targetPath = 'build/src_gen/'
    allFeatures = ['help', 'example']
    templateStyles = ['plain': [], 'with-help': ['help']]
}

formats = ['asciidoc': [imageFolder: true]]

distribution {
    targetPath = 'dist/'
}
''', 'utf-8')

    ['EN', 'DE'].each { lang ->
        new File(fixture, "${lang}/adoc").mkdirs()
        new File(fixture, "${lang}/images").mkdirs()

        new File(fixture, "${lang}/demo-template.adoc").write("""// header file for the demo template
include::adoc/config.adoc[]

= image:demo-logo.png[demo] Demo Template (${lang})

${helpBlock('Help for the whole document')}
include::adoc/01_chapter.adoc[]

include::adoc/02_chapter.adoc[]
""", 'utf-8')
        new File(fixture, "${lang}/adoc/config.adoc").write(":demohelp:\n", 'utf-8')

        // lines: 1 imagesdir, 3 heading, 5-10 help block, 12 content, 14 image
        new File(fixture, "${lang}/adoc/01_chapter.adoc").write("""ifndef::imagesdir[:imagesdir: ../images]

== Chapter one

${helpBlock('HELP ONE')}
CHAPTER ONE CONTENT

image::example.png["An example"]
""", 'utf-8')

        // lines: 5-10 first help block, 14-19 second help block, 21 content
        new File(fixture, "${lang}/adoc/02_chapter.adoc").write("""ifndef::imagesdir[:imagesdir: ../images]

== Chapter two

${helpBlock('HELP TWO A')}
=== Section

${helpBlock('HELP TWO B')}
CHAPTER TWO CONTENT
""", 'utf-8')

        new File(fixture, "${lang}/version.properties").write("revnumber=1.0-${lang}\nrevdate=2026\nrevremark=(test)\n", 'utf-8')
        new File(fixture, "${lang}/images/demo-logo.png").bytes = [1, 2, 3] as byte[]
        new File(fixture, "${lang}/images/example.png").bytes = [4, 5, 6] as byte[]
    }
}

resetFixture()

// paths inside the config are relative to the config file, as in build.groovy
def config = new ConfigSlurper().parse(new File(fixture, 'buildconfig.groovy').toURI().toURL())
def templatesClass = new GroovyClassLoader().parseClass(new File('lib/Templates.groovy'))
def newTemplates = { templatesClass.newInstance(config, fixture) }
def templates = newTemplates()

try {
    println "=== Test 1: A clean golden master has no problems ==="
    def problems = templates.validateGoldenMaster()
    assert problems.isEmpty(), "expected no problems, got: ${problems}"
    println "✓ Test 1 passed\n"

    println "=== Test 2: An ifdef without endif is an error with file and line ==="
    resetFixture()
    // the second help block of chapter two loses its endif (line 19)
    def chapterTwo = new File(fixture, 'EN/adoc/02_chapter.adoc')
    chapterTwo.write(chapterTwo.getText('utf-8').replace('endif::demohelp[]\n\nCHAPTER TWO', 'CHAPTER TWO'), 'utf-8')
    problems = templates.validateGoldenMaster()
    assert problems.size() == 1, "expected exactly one problem, got: ${problems}"
    def problem = problems[0]
    assert problem.severity == 'error'
    assert problem.language == 'EN'
    assert problem.file == 'EN/adoc/02_chapter.adoc', "unexpected file: ${problem.file}"
    assert problem.line == 14, "expected the line of the unclosed ifdef (14), got ${problem.line}"
    assert problem.message.contains('ifdef::demohelp[]') && problem.message.contains('endif::demohelp[]'), "unexpected message: ${problem.message}"
    println "  reported: ${templates.formatProblem(problem)}"
    println "✓ Test 2 passed\n"

    println "=== Test 3: A help block without ifdef/endif is an error ==="
    resetFixture()
    def chapterOne = new File(fixture, 'DE/adoc/01_chapter.adoc')
    chapterOne.write(chapterOne.getText('utf-8').replace('ifdef::demohelp[]\n', '').replace('endif::demohelp[]\n', ''), 'utf-8')
    problems = templates.validateGoldenMaster()
    assert problems.size() == 1, "expected exactly one problem, got: ${problems}"
    problem = problems[0]
    assert problem.severity == 'error' && problem.file == 'DE/adoc/01_chapter.adoc', "unexpected problem: ${problem}"
    assert problem.line == 5, "expected the line of the role marker (5), got ${problem.line}"
    assert problem.message.contains('[role="demohelp"]') && problem.message.contains('ifdef::demohelp[]'), "unexpected message: ${problem.message}"
    println "  reported: ${templates.formatProblem(problem)}"
    println "✓ Test 3 passed\n"

    println "=== Test 4: A missing image is an error naming the image ==="
    resetFixture()
    new File(fixture, 'EN/adoc/01_chapter.adoc').append('''
image::missing.png["Not there"]
image:https://example.org/remote.png[remote] and image:missing.png[twice] image:missing.png[on one line]
image::sub/dir/other.png[]
''', 'utf-8')
    problems = templates.validateGoldenMaster()
    assert problems.every { it.severity == 'error' && it.file == 'EN/adoc/01_chapter.adoc' }, "only image errors expected, got: ${problems}"
    assert problems.size() == 3, "missing.png (block), missing.png (inline, once per line) and sub/dir/other.png expected, got: ${problems}"
    assert problems.count { it.message.contains('missing.png') } == 2, "missing.png should be reported once per line: ${problems}"
    assert problems.any { it.message.contains('sub/dir/other.png') }, "targets with / should be checked: ${problems}"
    assert !problems.any { it.message.contains('remote.png') }, "URLs must not be checked: ${problems}"
    assert problems*.line == [16, 17, 18], "unexpected lines: ${problems*.line}"
    problems.each { println "  reported: ${templates.formatProblem(it)}" }
    println "✓ Test 4 passed\n"

    println "=== Test 5: version.properties must exist and be complete ==="
    resetFixture()
    new File(fixture, 'EN/version.properties').write("revnumber=1.0-EN\nrevremark=(test)\n", 'utf-8')
    assert new File(fixture, 'DE/version.properties').delete()
    problems = templates.validateGoldenMaster()
    assert problems.size() == 2 && problems.every { it.severity == 'error' && it.line == null }, "two errors without line expected, got: ${problems}"
    def en = problems.find { it.file == 'EN/version.properties' }
    def de = problems.find { it.file == 'DE/version.properties' }
    assert en?.message?.contains('revdate') && !en.message.contains('revnumber'), "EN should only lack revdate: ${en}"
    assert de?.message?.contains('missing'), "DE version.properties should be reported as missing: ${de}"
    problems.each { println "  reported: ${templates.formatProblem(it)}" }
    println "✓ Test 5 passed\n"

    println "=== Test 6: Translation drift is a warning, not an error ==="
    resetFixture()
    new File(fixture, 'DE/adoc/03_extra.adoc').write("== Extra chapter\n\nONLY IN DE\n", 'utf-8')
    // DE chapter two loses its second help block
    def deChapterTwo = new File(fixture, 'DE/adoc/02_chapter.adoc')
    deChapterTwo.write(deChapterTwo.getText('utf-8').replace(helpBlock('HELP TWO B'), ''), 'utf-8')
    problems = templates.validateGoldenMaster()
    assert problems.size() == 2 && problems.every { it.severity == 'warning' && it.language == 'DE' }, "two DE warnings expected, got: ${problems}"
    def files = problems.find { it.file == 'DE/adoc' }
    assert files?.message?.contains('03_extra.adoc'), "the extra chapter should be named: ${files}"
    def blocks = problems.find { it.file == 'DE/adoc/02_chapter.adoc' }
    assert blocks?.message?.contains('EN') && blocks.message ==~ /.*\b1\b.*\b2\b.*/, "both counts (1 and EN's 2) expected: ${blocks}"
    problems.each { println "  reported: ${templates.formatProblem(it)}" }
    // warnings never stop the generation
    templates.createFromGoldenMaster()
    assert new File(fixture, 'build/src_gen/DE/asciidoc/with-help/src/03_extra.adoc').exists(), "templates should be generated despite warnings"
    println "✓ Test 6 passed\n"

    println "=== Test 7: failOnLintErrors = false only reports errors ==="
    resetFixture()
    new File(fixture, 'EN/adoc/01_chapter.adoc').append('image::missing.png[]\n', 'utf-8')
    def lenient = newTemplates()
    lenient.failOnLintErrors = false
    def originalOut = System.out
    def captured = new ByteArrayOutputStream()
    System.setOut(new PrintStream(captured, true, 'UTF-8'))
    try {
        lenient.createFromGoldenMaster()
    } finally {
        System.setOut(originalOut)
    }
    def output = captured.toString('UTF-8')
    print output
    assert output.contains("⚠ (ignored) EN/adoc/01_chapter.adoc:15: image 'missing.png' not found in EN/images/"), "ignored errors should be printed as warnings"
    assert output.contains('1 error(s), 0 warning(s)'), "the summary should count the ignored error"
    assert new File(fixture, 'build/src_gen/EN/asciidoc/plain/src/demo-template.adoc').exists(), "templates should be generated despite lint errors"
    println "✓ Test 7 passed\n"

    println "=== Test 8: By default lint errors fail the template generation ==="
    resetFixture()
    new File(fixture, 'EN/adoc/01_chapter.adoc').append('image::missing.png[]\n', 'utf-8')
    new File(fixture, 'DE/version.properties').write("revnumber=1.0-DE\n", 'utf-8')
    def message = null
    try {
        newTemplates().createFromGoldenMaster()
    } catch (IllegalStateException e) {
        message = e.message
    }
    assert message != null, "createFromGoldenMaster() should throw on lint errors"
    assert message.startsWith('Golden master validation failed: 2 error(s)'), "unexpected message start: ${message.readLines()[0]}"
    assert message.contains("\n  ✗ EN/adoc/01_chapter.adoc:15: image 'missing.png' not found in EN/images/"), "the image error should be listed:\n${message}"
    assert message.contains("\n  ✗ DE/version.properties: ") && message.contains('revdate, revremark'), "the version error should be listed:\n${message}"
    assert !new File(fixture, 'build/src_gen').exists(), "nothing should be generated when the lint fails"
    println "  reported: ${message.readLines()[0]}"
    println "✓ Test 8 passed\n"

    println "=== Test 9: A feature used in ifdef but never set as attribute is an error ==="
    resetFixture()
    // without ':demohelp:' every ifdef::demohelp[] block is dropped, the with-help style equals plain
    new File(fixture, 'DE/adoc/config.adoc').write("// no feature attribute\n", 'utf-8')
    problems = templates.validateGoldenMaster()
    assert problems.size() == 1, "expected exactly one problem, got: ${problems}"
    problem = problems[0]
    assert problem.severity == 'error' && problem.language == 'DE' && problem.line == null, "unexpected problem: ${problem}"
    assert problem.file == 'DE', "the problem belongs to the language, got file ${problem.file}"
    assert problem.message.contains(':demohelp:') && problem.message.contains('with-help'), "unexpected message: ${problem.message}"
    println "  reported: ${templates.formatProblem(problem)}"
    println "✓ Test 9 passed\n"

    println "=== All Tests Passed! ==="
    System.exit(0)

} catch (Throwable e) {
    println "\n✗ Test failed with error:"
    println e.message
    e.printStackTrace()
    System.exit(1)
}
