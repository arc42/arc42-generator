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
 * - Copy ZIPs to the distribution directory (distribution.targetPath, build/dist/ for arc42)
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
     * @return true if the ZIP was written, false if the source directory does not exist
     * @throws IOException if the ZIP could not be written; a partial file is deleted, so that
     *         no broken ZIP is left next to the good ones
     */
    boolean createZip(File sourceDir, File zipFile) {
        if (!sourceDir.exists() || !sourceDir.isDirectory()) {
            return false
        }

        zipFile.parentFile.mkdirs()

        try {
            new ZipOutputStream(new FileOutputStream(zipFile)).withCloseable { zos ->
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
            }
            return true
        } catch (Exception e) {
            zipFile.delete()
            throw new IOException("could not write ${zipFile.name}: ${e.message}", e)
        }
    }

    /**
     * Create distribution ZIP for a specific template and format
     *
     * @param language Language code (e.g., 'EN')
     * @param style Template style (e.g., 'plain', 'with-help')
     * @param format Output format (e.g., 'html', 'markdown')
     * @param buildDir Base build directory (default: 'build')
     * @return Path to created ZIP file, or null if there is no converted output for this combination
     * @throws IOException if the ZIP could not be written
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
     * @return [total, created, skipped, failed]: skipped combinations have no converted output,
     *         failed ones could not be written (their partial ZIP is deleted); the caller fails the build on them
     */
    Map createAllDistributions(List<Map> templates, List<String> formats = null, boolean parallel = true) {
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

        def lock = new Object()
        def packageOne = { combo ->
            def template = combo.template
            def format = combo.format
            def name = "${template.language}/${template.style}/${format}"
            def result = null
            def error = null
            try {
                result = createDistribution(template.language, template.style, format)
            } catch (Exception e) {
                error = e.message
            }

            synchronized (lock) {
                if (error) {
                    failed++
                    println "[${created + skipped + failed}/${totalZips}] ✗ ${name}: ${error}"
                } else if (result) {
                    created++
                    def zipFile = new File(result)
                    packaged << [template: template, format: format, zip: zipFile]
                    def sizeKB = String.format('%.1f', zipFile.length() / 1024.0)
                    println "[${created + skipped + failed}/${totalZips}] ✓ ${name} (${sizeKB} KB)"
                } else {
                    skipped++
                    println "[${created + skipped + failed}/${totalZips}] ⊘ ${name} (not found)"
                }
            }
        }

        if (parallel) {
            // Use GPars for parallel execution
            GParsPool.withPool(Runtime.runtime.availableProcessors()) {
                combinations.eachParallel(packageOne)
            }
        } else {
            combinations.each(packageOne)
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
            if (releaseTag) {
                def notes = writeReleaseInfo(manifest)
                println "Release notes and file list: ${notes.parentFile.path}"
            }
        }

        return [total: totalZips, created: created, skipped: skipped, failed: failed]
    }

    /**
     * The last commit of the golden master that changed anything outside the distribution directory,
     * as [commit: hash, time: commit time in seconds], or null without git. A merged release only adds
     * ZIPs; it must not change the timestamps or the templateCommit of the next build, otherwise every
     * release would rewrite all ZIPs again. First-parent history: the merge commit on main counts,
     * not a commit of the merged branch.
     */
    /**
     * Folders of the golden master that hold release output rather than template content: arc42-template
     * committed its ZIPs to dist/ until the downloads moved to GitHub Releases (arc42-template#248).
     */
    static final List<String> RELEASE_OUTPUT_PATHS = ['dist']

    Map lastTemplateCommit() {
        def goldenMasterDir = new File(projectRoot, config.goldenMaster.sourcePath.toString()).canonicalFile
        def distDir = new File(projectRoot, config.distribution.targetPath.toString()).canonicalFile
        def cmd = ['git', '-C', goldenMasterDir.path, 'log', '-1', '--first-parent', '--format=%H %ct', '--', '.']
        def excluded = RELEASE_OUTPUT_PATHS as Set
        if (distDir.path.startsWith(goldenMasterDir.path + File.separator)) {
            excluded << goldenMasterDir.toPath().relativize(distDir.toPath()).toString()
        }
        excluded.sort().each { cmd << ":(exclude)${it}".toString() }
        try {
            def git = cmd.execute()
            def output = git.text.trim()
            if (git.waitFor() == 0 && output ==~ /[0-9a-f]{40} \d+/) {
                def (commit, time) = output.tokenize(' ')
                return [commit: commit, time: time.toLong()]
            }
        } catch (Exception ignored) {
            // no git or not a repository
        }
        return null
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
            def entry = [code: t.language, name: languageNames[t.language] ?: t.language,
                         version: version, date: t.versionProperties?.revdate ?: '']
            // machine-readable month next to the localised revdate, only when version.properties has it
            def isoDate = t.versionProperties?.get('revdate-iso')?.toString()?.trim()
            if (isoDate ==~ /\d{4}-(0[1-9]|1[0-2])/) entry.isoDate = isoDate
            entry
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

    /**
     * For a release (releaseTag set): write build/release/notes.md, the release notes (one line per
     * language with name, version and date, plus the template commit), and build/release/files.txt,
     * the files to upload: every ZIP of the manifest and the manifest itself, one name per line.
     * Hand-made files in the distribution directory are not part of it.
     *
     * @return The release notes file
     */
    File writeReleaseInfo(File manifestFile) {
        def manifest = new groovy.json.JsonSlurper().parse(manifestFile, 'utf-8')
        def dir = new File(projectRoot, 'build/release')
        dir.mkdirs()

        def commit = manifest.templateCommit ? " from ${manifest.project} commit ${manifest.templateCommit.take(7)}" : ''
        def date = manifest.templateDate ? " (${manifest.templateDate})" : ''
        def notes = new StringBuilder()
        notes << "Built${commit}${date}.\n\n"
        notes << "| Language | Version | Date |\n|---|---|---|\n"
        manifest.languages.each { l -> notes << "| ${l.name} (${l.code}) | ${l.version} | ${l.date} |\n" }
        notes << "\n${manifest.formats.size()} formats: ${manifest.formats*.label.join(', ')}.\n"
        notes << "${manifest.files.size()} files, one per language, style (${manifest.styles.join(', ')}) and format; "
        notes << "sizes and SHA-256 checksums in `manifest.json`.\n"
        def notesFile = new File(dir, 'notes.md')
        notesFile.write(notes.toString(), 'utf-8')

        new File(dir, 'files.txt').write((manifest.files*.name + manifestFile.name).join('\n') + '\n', 'utf-8')
        return notesFile
    }

    /**
     * Compare the files of two manifests by name and sha256. Builds are reproducible, so two builds of
     * the same golden master commit differ only in the files a change of the generator really changed.
     *
     * @return [added: names only in current, removed: names only in base, changed: names whose sha256
     *         differs, unchanged: number of identical files, sameTemplate: both manifests name the same
     *         templateCommit (if not, every ZIP differs by its timestamps alone)]
     */
    static Map diffManifests(Map base, Map current) {
        def baseFiles = base.files.collectEntries { [(it.name): it.sha256] }
        def currentFiles = current.files.collectEntries { [(it.name): it.sha256] }
        def common = baseFiles.keySet().intersect(currentFiles.keySet())
        return [
            added       : (currentFiles.keySet() - baseFiles.keySet()).sort(),
            removed     : (baseFiles.keySet() - currentFiles.keySet()).sort(),
            changed     : common.findAll { baseFiles[it] != currentFiles[it] }.sort(),
            unchanged   : common.count { baseFiles[it] == currentFiles[it] },
            sameTemplate: base.templateCommit == current.templateCommit,
        ]
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
