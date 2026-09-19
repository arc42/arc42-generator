#!/usr/bin/env groovy

/**
 * Test script for building a project other than arc42
 *
 * Creates a small golden master with its own config file and checks that
 * project.name, project.featurePrefix and project.logo are honoured, that all
 * paths are resolved relative to the config file, and that a missing main
 * document fails the build.
 */

def fixture = new File('build/test-config/demo-project')
if (fixture.exists()) fixture.deleteDir()
new File(fixture, 'EN/adoc').mkdirs()
new File(fixture, 'EN/images').mkdirs()

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

new File(fixture, 'EN/demo-template.adoc').write('''= Demo Template

include::adoc/config.adoc[]

include::adoc/01_chapter.adoc[]
''', 'utf-8')
new File(fixture, 'EN/adoc/config.adoc').write(":imagesdir: ./images\n:demohelp:\n", 'utf-8')
new File(fixture, 'EN/adoc/01_chapter.adoc').write('''== Chapter

ifdef::demohelp[]
[role="demohelp"]
****
HELP TEXT
****
endif::demohelp[]

CHAPTER CONTENT
''', 'utf-8')
new File(fixture, 'EN/adoc/about-security.adoc').write("== About security\n\nA REAL CHAPTER\n", 'utf-8')
new File(fixture, 'EN/version.properties').write("revnumber=1.0-EN\nrevdate=2026\nrevremark=(test)\n", 'utf-8')
new File(fixture, 'EN/images/demo-logo.png').bytes = [1, 2, 3] as byte[]
new File(fixture, 'EN/images/example.png').bytes = [4, 5, 6] as byte[]

// paths inside the config are relative to the config file, as in build.groovy
def config = new ConfigSlurper().parse(new File(fixture, 'buildconfig.groovy').toURI().toURL())

def gcl = new GroovyClassLoader()
def templates = gcl.parseClass(new File('lib/Templates.groovy')).newInstance(config, fixture)
def discovery = gcl.parseClass(new File('lib/Discovery.groovy')).newInstance(config, fixture)
def packager = gcl.parseClass(new File('lib/Packager.groovy')).newInstance(config, fixture)

try {
    println "=== Test 1: Generate templates from the demo golden master ==="
    templates.createFromGoldenMaster()

    def plain = new File(fixture, 'build/src_gen/EN/asciidoc/plain/src')
    def withHelp = new File(fixture, 'build/src_gen/EN/asciidoc/with-help/src')
    assert new File(plain, 'demo-template.adoc').exists(), "main document should keep the configured name"

    def plainChapter = new File(plain, '01_chapter.adoc').getText('utf-8')
    assert !plainChapter.contains('HELP TEXT'), "plain must not contain help"
    assert !plainChapter.contains('ifdef::demohelp'), "plain must not contain ifdef markers"
    assert plainChapter.contains('CHAPTER CONTENT'), "plain must keep the content"
    assert new File(withHelp, '01_chapter.adoc').getText('utf-8').contains('HELP TEXT'), "with-help must contain help"
    println "✓ Test 1 passed\n"

    println "=== Test 2: Only the configured logo ends up in plain ==="
    def plainImages = (new File(fixture, 'build/src_gen/EN/asciidoc/plain/images').list() as List).sort()
    def withHelpImages = (new File(fixture, 'build/src_gen/EN/asciidoc/with-help/images').list() as List).sort()
    assert plainImages == ['demo-logo.png'], "plain should only contain the logo, got ${plainImages}"
    assert withHelpImages == ['demo-logo.png', 'example.png'], "with-help should contain all images, got ${withHelpImages}"
    println "✓ Test 2 passed\n"

    println "=== Test 3: Output goes next to the config file, not into the generator ==="
    assert !new File('build/src_gen/EN/asciidoc/plain/src/demo-template.adoc').exists(),
        "templates must not be written relative to the current directory"
    println "✓ Test 3 passed\n"

    println "=== Test 4: Discovery picks the configured main document ==="
    def found = discovery.discoverTemplates()
    assert found.size() == 2, "should discover both styles, got ${found.size()}"
    found.each {
        assert new File(it.mainFile).name == 'demo-template.adoc', "main file should be demo-template.adoc, got ${it.mainFileName}"
    }
    println "✓ Test 4 passed\n"

    println "=== Test 5: ZIP is named after the project ==="
    def converted = new File(fixture, 'build/EN/asciidoc/plain')
    converted.mkdirs()
    new File(converted, 'demo-template.adoc').write('content', 'utf-8')
    def zip = packager.createDistribution('EN', 'plain', 'asciidoc')
    assert zip != null, "distribution should be created"
    assert new File(zip).name == 'demo-template-EN-plain-asciidoc.zip', "unexpected ZIP name: ${new File(zip).name}"
    assert new File(fixture, 'dist/demo-template-EN-plain-asciidoc.zip').exists(), "ZIP should be written to the configured dist directory"
    println "✓ Test 5 passed\n"

    println "=== Test 6: A missing main document fails the build ==="
    assert new File(fixture, 'EN/demo-template.adoc').delete()
    def failed = false
    try {
        templates.createFromGoldenMaster()
    } catch (Exception e) {
        failed = true
        println "  reported: ${e.message.readLines()[0]}"
    }
    assert failed, "template generation should fail when the main document is missing"
    println "✓ Test 6 passed\n"

    println "=== All Tests Passed! ==="
    System.exit(0)

} catch (Throwable e) {
    println "\n✗ Test failed with error:"
    println e.message
    e.printStackTrace()
    System.exit(1)
}
