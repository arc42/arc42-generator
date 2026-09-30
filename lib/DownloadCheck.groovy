#!/usr/bin/env groovy

@Grab('org.codehaus.gpars:gpars:1.2.1')

import groovy.json.JsonSlurper
import groovyx.gpars.GParsPool
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration

/**
 * DownloadCheck.groovy - check that every file of a manifest can be downloaded
 *
 * Requests <prefix><name> for every file listed in manifest.json (every language, style and
 * format of a release), following redirects. Without checksums a HEAD request per file; with
 * checksums every file is downloaded and its SHA-256 compared with the manifest. Used before and
 * after switching the download page to GitHub Releases: the old prefix (raw/master/dist/) and the
 * new one (releases/download/<tag>/ or releases/latest/download/) must serve the same files.
 */
class DownloadCheck {

    HttpClient client = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(20))
        .build()

    int poolSize = 8

    /** The file names of the manifest, with their sha256; manifest.json itself is not in its list. */
    static List<Map> manifestFiles(File manifestFile) {
        def manifest = new JsonSlurper().parse(manifestFile, 'utf-8')
        manifest.files.collect { [name: it.name, sha256: it.sha256] }
    }

    /**
     * Check every file. Returns one result per file: [name, url, ok, problem], problem null if ok.
     */
    List<Map> check(List<Map> files, String prefix, boolean checksums) {
        def base = prefix.endsWith('/') ? prefix : prefix + '/'
        List<Map> results = []
        GParsPool.withPool(poolSize) {
            results = files.collectParallel { f -> checkOne(f, base + f.name, checksums) }
        }
        results.sort { it.name }
    }

    Map checkOne(Map file, String url, boolean checksums) {
        def result = [name: file.name, url: url, ok: false, problem: null]
        try {
            def builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(120))
            if (checksums) {
                def response = client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofInputStream())
                if (response.statusCode() != 200) {
                    response.body().close()
                    result.problem = "HTTP ${response.statusCode()}"
                    return result
                }
                def digest = MessageDigest.getInstance('SHA-256')
                response.body().withCloseable { stream ->
                    def buffer = new byte[65536]
                    int n
                    while ((n = stream.read(buffer)) > 0) digest.update(buffer, 0, n)
                }
                def actual = digest.digest().encodeHex().toString()
                if (actual != file.sha256) {
                    result.problem = "sha256 ${actual.take(12)}… differs from the manifest (${file.sha256?.take(12)}…)"
                    return result
                }
            } else {
                def response = client.send(builder.method('HEAD', HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.discarding())
                if (response.statusCode() != 200) {
                    result.problem = "HTTP ${response.statusCode()}"
                    return result
                }
            }
            result.ok = true
        } catch (Exception e) {
            result.problem = "${e.class.simpleName}: ${e.message}"
        }
        return result
    }
}
