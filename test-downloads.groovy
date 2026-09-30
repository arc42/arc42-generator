#!/usr/bin/env groovy

/**
 * Test script for the download check (lib/DownloadCheck.groovy)
 *
 * Serves a few files from a local HTTP server, one of them only behind a redirect (as GitHub
 * release downloads are), and checks that missing files and files whose SHA-256 differs from
 * the manifest are reported, with HEAD requests and with full downloads.
 */

import com.sun.net.httpserver.HttpServer
import groovy.json.JsonOutput
import java.security.MessageDigest

def sha256 = { byte[] b -> MessageDigest.getInstance('SHA-256').digest(b).encodeHex().toString() }
def served = ['a.zip': 'A' * 1000, 'b.zip': 'B changed']   // b.zip differs from the manifest
def manifestFiles = [
    [name: 'a.zip', sha256: sha256(served['a.zip'].bytes)],
    [name: 'b.zip', sha256: sha256('B original'.bytes)],
    [name: 'c.zip', sha256: sha256('C'.bytes)],              // not served
]

def server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
server.createContext('/files/') { ex ->
    def body = served[ex.requestURI.path.substring('/files/'.length())]?.bytes
    if (body == null) { ex.sendResponseHeaders(404, -1); ex.close(); return }
    ex.sendResponseHeaders(200, ex.requestMethod == 'HEAD' ? -1 : body.length)
    if (ex.requestMethod != 'HEAD') ex.responseBody.write(body)
    ex.close()
}
server.createContext('/latest/') { ex ->    // like releases/latest/download/: a redirect to the file
    ex.responseHeaders.add('Location', '/files/' + ex.requestURI.path.substring('/latest/'.length()))
    ex.sendResponseHeaders(302, -1)
    ex.close()
}
server.start()
def base = "http://127.0.0.1:${server.address.port}"

try {
    def checker = new GroovyClassLoader().parseClass(new File('lib/DownloadCheck.groovy')).newInstance()

    println "=== Test 1: the manifest lists the files with their sha256 ==="
    def manifest = new File('build2/test-downloads/manifest.json')
    manifest.parentFile.mkdirs()
    manifest.write(JsonOutput.toJson([project: 'demo', files: manifestFiles.collect { it + [size: 1] }]), 'utf-8')
    assert checker.manifestFiles(manifest) == manifestFiles
    println "✓ Test 1 passed\n"

    println "=== Test 2: HEAD requests find missing files, also behind a redirect ==="
    [base + '/files/', base + '/latest'].each { prefix ->
        def results = checker.check(manifestFiles, prefix, false)
        assert results*.name == ['a.zip', 'b.zip', 'c.zip']
        assert results.findAll { it.ok }*.name == ['a.zip', 'b.zip'], "a and b are served (${prefix}): ${results}"
        assert results.find { it.name == 'c.zip' }.problem == 'HTTP 404'
    }
    println "✓ Test 2 passed\n"

    println "=== Test 3: with checksums, a file that differs from the manifest is reported ==="
    def results = checker.check(manifestFiles, base + '/latest/', true)
    assert results.find { it.name == 'a.zip' }.ok, "a.zip matches: ${results}"
    assert results.find { it.name == 'b.zip' }.problem?.contains('differs from the manifest'), "b.zip differs: ${results}"
    assert results.find { it.name == 'c.zip' }.problem == 'HTTP 404'
    println "✓ Test 3 passed\n"

    println "=== Test 4: an unreachable host is a problem, not a crash ==="
    def unreachable = checker.check(manifestFiles.take(1), 'http://127.0.0.1:1/', false)
    assert !unreachable[0].ok && unreachable[0].problem
    println "✓ Test 4 passed\n"

    println "=== All Tests Passed! ==="
    server.stop(0)
    System.exit(0)
} catch (Throwable e) {
    println "\n✗ Test failed with error:"
    println e.message
    e.printStackTrace()
    server.stop(0)
    System.exit(1)
}
