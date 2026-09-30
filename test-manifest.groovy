#!/usr/bin/env groovy

/**
 * Test script for the release manifest
 *
 * Builds the ZIPs of a small golden master and checks that manifest.json next to them
 * lists languages (with version and date from version.properties), styles, formats and
 * every ZIP with size and sha256, that release tag and template commit appear only when
 * set, and that the manifest is byte-identical across runs and between parallel and
 * sequential packaging.
 */

import groovy.json.JsonSlurper
import java.security.MessageDigest

def fixture = new File('build2/test-manifest/demo-project')
if (fixture.exists()) fixture.deleteDir()

new File(fixture, 'buildconfig.groovy').with {
    parentFile.mkdirs()
    write('''
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

formats = [
    'html': [imageFolder: true, label: 'HTML'],
    'docx': [imageFolder: true, label: 'Word (.docx)'],
    'rst' : [imageFolder: true],
]

distribution {
    targetPath = 'dist/'
    languageNames = [EN: 'English', DE: 'Deutsch']
}
''', 'utf-8')
}

// EN and DE have names in the config, ZH has none; DE uses a space instead of a hyphen in revnumber
[EN: ['9.0-EN', 'July 2025'], DE: ['9.1 DE', 'Dezember 2025'], ZH: ['9.0-ZH', '7月 2025']].each { lang, props ->
    new File(fixture, "${lang}/adoc").mkdirs()
    new File(fixture, "${lang}/images").mkdirs()
    new File(fixture, "${lang}/demo-template.adoc").write("= Demo ${lang}\n\ninclude::adoc/01_chapter.adoc[]\n", 'utf-8')
    new File(fixture, "${lang}/adoc/01_chapter.adoc").write("== Chapter\n\nCONTENT ${lang}\n", 'utf-8')
    new File(fixture, "${lang}/version.properties").write("revnumber=${props[0]}\nrevdate=${props[1]}\nrevremark=(test)\n", 'utf-8')
    new File(fixture, "${lang}/images/demo-logo.png").bytes = [1, 2, 3] as byte[]
}

def config = new ConfigSlurper().parse(new File(fixture, 'buildconfig.groovy').toURI().toURL())
def gcl = new GroovyClassLoader()
def templates = gcl.parseClass(new File('lib/Templates.groovy')).newInstance(config, fixture)
def discovery = gcl.parseClass(new File('lib/Discovery.groovy')).newInstance(config, fixture)
def packagerClass = gcl.parseClass(new File('lib/Packager.groovy'))

def sha256 = { File f -> MessageDigest.getInstance('SHA-256').digest(f.bytes).encodeHex().toString() }

try {
    templates.createFromGoldenMaster()
    def found = discovery.discoverTemplates()

    // stand-ins for converted output: html and docx for every template, rst for nobody (not converted)
    found.each { t ->
        ['html', 'docx'].each { fmt ->
            def dir = new File(fixture, "build/${t.language}/${fmt}/${t.style}")
            dir.mkdirs()
            new File(dir, "demo-template.${fmt}").write("${t.language} ${t.style} ${fmt}", 'utf-8')
        }
    }

    def distDir = new File(fixture, 'dist')
    def manifestFile = new File(distDir, 'manifest.json')

    def packageAll = { boolean parallel, Map props ->
        def packager = packagerClass.newInstance(config, fixture)
        packager.sourceDateEpoch = 1751328000L  // 2025-07-01
        props.each { k, v -> packager[k] = v }
        packager.createAllDistributions(found, null, parallel)
        manifestFile.bytes
    }

    println "=== Test 1: manifest.json is written next to the ZIPs ==="
    def first = packageAll(true, [releaseTag: '2026.09.29', templateCommit: 'abc123'])
    assert manifestFile.exists(), "manifest.json should be written to the dist directory"
    def m = new JsonSlurper().parse(manifestFile)
    assert m.project == 'demo-template'
    assert m.tag == '2026.09.29'
    assert m.templateCommit == 'abc123'
    assert m.templateDate == '2025-07-01'
    println "✓ Test 1 passed\n"

    println "=== Test 2: languages come from version.properties and the config ==="
    assert m.languages == [
        [code: 'DE', name: 'Deutsch', version: '9.1', date: 'Dezember 2025'],
        [code: 'EN', name: 'English', version: '9.0', date: 'July 2025'],
        [code: 'ZH', name: 'ZH', version: '9.0', date: '7月 2025'],
    ], "unexpected languages: ${m.languages}"
    assert manifestFile.getText('utf-8').contains('7月'), "non-ASCII text should be written as is, not \\u-escaped"
    println "✓ Test 2 passed\n"

    println "=== Test 3: styles and formats list only what was packaged ==="
    assert m.styles == ['plain', 'withhelp'], "unexpected styles: ${m.styles}"
    assert m.formats == [[id: 'html', label: 'HTML'], [id: 'docx', label: 'Word (.docx)']],
        "formats should keep config order, carry their label and leave out rst (no ZIPs): ${m.formats}"
    println "✓ Test 3 passed\n"

    println "=== Test 4: every ZIP is listed with size and sha256 ==="
    def zips = distDir.listFiles().findAll { it.name.endsWith('.zip') }.sort { it.name }
    assert zips.size() == 12, "expected 3 languages × 2 styles × 2 formats, got ${zips.size()}"
    assert m.files*.name == zips*.name, "files should list every ZIP, sorted by name"
    m.files.each { f ->
        def zip = new File(distDir, f.name)
        assert f.sha256 == sha256(zip), "sha256 mismatch for ${f.name}"
        assert f.size == zip.length(), "size mismatch for ${f.name}"
        assert f.name == "demo-template-${f.language}-${f.style}-${f.format}.zip"
    }
    println "✓ Test 4 passed\n"

    println "=== Test 5: the manifest is reproducible ==="
    def second = packageAll(false, [releaseTag: '2026.09.29', templateCommit: 'abc123'])
    assert first == second, "parallel and sequential runs should give a byte-identical manifest"
    println "✓ Test 5 passed\n"

    println "=== Test 6: tag and commit are left out when unknown ==="
    packageAll(true, [:])
    def plain = new JsonSlurper().parse(manifestFile)
    assert !plain.containsKey('tag') && !plain.containsKey('templateCommit'),
        "tag and templateCommit should be absent when not set: ${plain.keySet()}"
    println "✓ Test 6 passed\n"

    println "=== Test 7: template commit and timestamp ignore commits that only change the ZIPs ==="
    def git = { String date, String... args ->
        def cmd = ['git', '-C', fixture.path, '-c', 'user.name=test', '-c', 'user.email=test@example.org'] + (args as List)
        def pb = new ProcessBuilder(cmd).redirectErrorStream(true)
        pb.environment().putAll([GIT_AUTHOR_DATE: date, GIT_COMMITTER_DATE: date])
        def p = pb.start()
        def out = p.inputStream.text.trim()
        assert p.waitFor() == 0, "git ${args.join(' ')} failed: ${out}"
        out
    }
    def packager = packagerClass.newInstance(config, fixture)
    new File(fixture, '.git').deleteDir()
    git('@1751328000 +0000', 'init', '-q')
    git('@1751328000 +0000', 'add', '-A')
    git('@1751328000 +0000', 'commit', '-q', '-m', 'template')
    def templateHead = git('@1751328000 +0000', 'rev-parse', 'HEAD')
    manifestFile.write('{}', 'utf-8')
    git('@1751414400 +0000', 'commit', '-q', '-am', 'release: only dist/')
    assert packager.lastTemplateCommit() == [commit: templateHead, time: 1751328000L],
        "a dist-only commit must not count: ${packager.lastTemplateCommit()}"
    new File(fixture, 'EN/adoc/01_chapter.adoc').append('more\n', 'utf-8')
    git('@1751500800 +0000', 'commit', '-q', '-am', 'template change')
    assert packager.lastTemplateCommit() == [commit: git('@1751500800 +0000', 'rev-parse', 'HEAD'), time: 1751500800L],
        "a template change must count: ${packager.lastTemplateCommit()}"
    new File(fixture, '.git').deleteDir()
    assert packager.lastTemplateCommit() == null, "without git there is no template commit"
    println "✓ Test 7 passed\n"

    println "=== All Tests Passed! ==="
    System.exit(0)

} catch (Throwable e) {
    println "\n✗ Test failed with error:"
    println e.message
    e.printStackTrace()
    System.exit(1)
}
