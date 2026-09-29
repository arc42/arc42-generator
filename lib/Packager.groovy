#!/usr/bin/env groovy

@Grab('org.codehaus.gpars:gpars:1.2.1')

import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import groovyx.gpars.GParsPool
import groovy.json.JsonGenerator
import groovy.json.JsonOutput
import java.security.MessageDigest

/**
 * Packager.groovy - ZIP distribution creation
 *
 * Responsibilities:
 * - Create ZIP distributions for each (language, style, format) combination
 * - Package converted templates for distribution
 * - Copy ZIPs to distribution directory (arc42-template/dist/)
 * - Write manifest.json next to the ZIPs: languages, styles, formats and every ZIP with its sha256
 */

class Packager {

    def config
    def projectRoot

    /**
     * Unix timestamp (seconds) used as modification time of all ZIP entries, so that a ZIP of unchanged
     * content is byte-identical. Null uses the current time, as ZipOutputStream does by default.
     */
    Long sourceDateEpoch = null

    /** Tag of the release the ZIPs are built for (e.g. 2026.09.29); written to the manifest only if set. */
    String releaseTag = null

    /** Commit of the golden master the ZIPs are built from; written to the manifest only if set. */
    String templateCommit = null

    Packager(config, projectRoot = new File('.')) {
        this.config = config
        this.projectRoot = projectRoot
    }

    /**
     * Create a ZIP file from a directory
     *
     * @param sourceDir Directory to ZIP
     * @param zipFile Target ZIP file
     * @return true if successful
     */
    boolean createZip(File sourceDir, File zipFile) {
        if (!sourceDir.exists() || !sourceDir.isDirectory()) {
            return false
        }

        zipFile.parentFile.mkdirs()

        def zos = new ZipOutputStream(new FileOutputStream(zipFile))
        try {
            // sorted entries and a fixed timestamp make the ZIP reproducible
            def files = []
            sourceDir.eachFileRecurse { file -> if (file.isFile()) files << file }
            files.sort { it.absolutePath }.each { file ->
                def relativePath = file.absolutePath - sourceDir.absolutePath - File.separator
                def entry = new ZipEntry(relativePath.replace(File.separator, '/'))
                if (sourceDateEpoch != null) {
                    entry.time = sourceDateEpoch * 1000L
                }
                zos.putNextEntry(entry)
                file.withInputStream { zos << it }
                zos.closeEntry()
            }
            return true
        } catch (Exception e) {
            println "Error creating ZIP ${zipFile.name}: ${e.message}"
            return false
        } finally {
            zos.close()
        }
    }

    /**
     * Create distribution ZIP for a specific template and format
     *
     * @param language Language code (e.g., 'EN')
     * @param style Template style (e.g., 'plain', 'with-help')
     * @param format Output format (e.g., 'html', 'markdown')
     * @param buildDir Base build directory (default: 'build')
     * @return Path to created ZIP file or null if failed
     */
    String createDistribution(String language, String style, String format, String buildDir = 'build') {
        // Source: build/{LANG}/{FORMAT}/{STYLE}/
        def sourceDir = new File(projectRoot, "${buildDir}/${language}/${format}/${style}")

        if (!sourceDir.exists()) {
            return null
        }

        // Normalize style name for filename (remove hyphens)
        def shortStyle = styleShort(style)

        // Target: <distribution.targetPath>/<project name>-{LANG}-{STYLE}-{FORMAT}.zip
        def zipFileName = "${config.project.name}-${language}-${shortStyle}-${format}.zip"
        def distDir = new File(projectRoot, config.distribution.targetPath)
        distDir.mkdirs()
        def zipFile = new File(distDir, zipFileName)

        if (createZip(sourceDir, zipFile)) {
            return zipFile.absolutePath
        } else {
            return null
        }
    }

    /**
     * Create all distributions for discovered templates
     *
     * @param templates List of template metadata from Discovery
     * @param formats List of format names (defaults to all configured formats)
     * @param parallel Enable parallel execution (default: true)
     */
    void createAllDistributions(List<Map> templates, List<String> formats = null, boolean parallel = true) {
        if (!formats) {
            formats = config.formats.keySet() as List
        }

        println "\n=== Creating Distributions ==="
        println "Templates: ${templates.size()}"
        println "Formats: ${formats.size()}"
        println "Parallel: ${parallel}"
        println ""

        def totalZips = templates.size() * formats.size()
        def created = 0
        def skipped = 0
        def failed = 0

        def startTime = System.currentTimeMillis()

        // Build list of all (template, format) combinations
        def packaged = []
        def combinations = []
        templates.each { template ->
            formats.each { format ->
                combinations << [template: template, format: format]
            }
        }

        if (parallel) {
            // Use GPars for parallel execution
            GParsPool.withPool(Runtime.runtime.availableProcessors()) {
                combinations.eachParallel { combo ->
                    def template = combo.template
                    def format = combo.format
                    def result = createDistribution(template.language, template.style, format)

                    synchronized(this) {
                        if (result) {
                            created++
                            packaged << [template: template, format: format, zip: new File(result)]
                            def zipFile = new File(result)
                            def sizeKB = String.format('%.1f', zipFile.length() / 1024.0)
                            println "[${created + skipped}/${totalZips}] ✓ ${template.language}/${template.style}/${format} (${sizeKB} KB)"
                        } else {
                            skipped++
                            println "[${created + skipped}/${totalZips}] ⊘ ${template.language}/${template.style}/${format} (not found)"
                        }
                    }
                }
            }
        }

        if (!parallel) {
            // Sequential execution
            combinations.each { combo ->
                def template = combo.template
                def format = combo.format
                def result = createDistribution(template.language, template.style, format)

                if (result) {
                    created++
                    packaged << [template: template, format: format, zip: new File(result)]
                    def zipFile = new File(result)
                    def sizeKB = String.format('%.1f', zipFile.length() / 1024.0)
                    println "[${created + skipped}/${totalZips}] ✓ ${template.language}/${template.style}/${format} (${sizeKB} KB)"
                } else {
                    skipped++
                    println "[${created + skipped}/${totalZips}] ⊘ ${template.language}/${template.style}/${format} (not found)"
                }
            }
        }

        def endTime = System.currentTimeMillis()
        def duration = (endTime - startTime) / 1000.0

        println "\n=== Distribution Complete ==="
        println "Total: ${totalZips}"
        println "Created: ${created}"
        println "Skipped: ${skipped}"
        println "Failed: ${failed}"
        println "Duration: ${String.format('%.1f', duration)}s"
        println ""

        if (created > 0) {
            def distPath = new File(projectRoot, config.distribution.targetPath).canonicalPath
            println "Distribution files: ${distPath}"
            def manifest = writeManifest(packaged)
            println "Manifest: ${manifest.name} (${packaged.size()} files)"
        }
    }

    /**
     * Write manifest.json into the distribution directory: the machine-readable list of what one
     * release contains. The same input gives a byte-identical file: everything is sorted, and the
     * only date is derived from sourceDateEpoch.
     *
     * @param packaged List of [template: Map, format: String, zip: File], one entry per created ZIP
     * @return The manifest file
     */
    File writeManifest(List<Map> packaged) {
        def languageNames = config.distribution.languageNames ?: [:]
        def formatOrder = config.formats.keySet() as List

        def languages = packaged*.template.unique { it.language }.sort { it.language }.collect { t ->
            def revnumber = t.versionProperties?.revnumber ?: ''
            def version = (revnumber =~ /^\d+(\.\d+)*/).with { it.find() ? it.group() : revnumber }
            [code: t.language, name: languageNames[t.language] ?: t.language,
             version: version, date: t.versionProperties?.revdate ?: '']
        }
        def styles = packaged.collect { styleShort(it.template.style) }.unique().sort()
        def formats = packaged*.format.unique().sort { formatOrder.indexOf(it) }.collect { id ->
            [id: id, label: config.formats[id]?.label ?: id]
        }
        def files = packaged.sort { it.zip.name }.collect { p ->
            [name: p.zip.name, language: p.template.language, style: styleShort(p.template.style),
             format: p.format, size: p.zip.length(), sha256: sha256(p.zip)]
        }

        def manifest = [project: config.project.name.toString()]
        if (releaseTag) manifest.tag = releaseTag
        if (templateCommit) manifest.templateCommit = templateCommit
        if (sourceDateEpoch != null) {
            manifest.templateDate = java.time.Instant.ofEpochSecond(sourceDateEpoch).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString()
        }
        manifest.languages = languages
        manifest.styles = styles
        manifest.formats = formats
        manifest.files = files

        def json = new JsonGenerator.Options().disableUnicodeEscaping().build().toJson(manifest)
        def manifestFile = new File(new File(projectRoot, config.distribution.targetPath), 'manifest.json')
        manifestFile.write(JsonOutput.prettyPrint(json, true) + '\n', 'utf-8')
        return manifestFile
    }

    /** Style as it appears in file names: 'with-help' becomes 'withhelp'. */
    static String styleShort(String style) {
        style.replaceAll("[^a-zA-Z]", "")
    }

    static String sha256(File file) {
        def digest = MessageDigest.getInstance('SHA-256')
        file.withInputStream { stream ->
            def buffer = new byte[65536]
            int n
            while ((n = stream.read(buffer)) > 0) digest.update(buffer, 0, n)
        }
        digest.digest().encodeHex().toString()
    }

    /**
     * Clean distribution directory
     */
    void cleanDistributions() {
        def distDir = new File(projectRoot, config.distribution.targetPath)
        if (distDir.exists()) {
            distDir.listFiles()?.each { file ->
                if (file.name.endsWith('.zip')) {
                    file.delete()
                }
            }
            println "✓ Cleaned distribution directory"
        }
    }
}
