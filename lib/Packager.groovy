#!/usr/bin/env groovy

@Grab('org.codehaus.gpars:gpars:1.2.1')

import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import groovyx.gpars.GParsPool

/**
 * Packager.groovy - ZIP distribution creation
 *
 * Responsibilities:
 * - Create ZIP distributions for each (language, style, format) combination
 * - Package converted templates for distribution
 * - Copy ZIPs to distribution directory (arc42-template/dist/)
 */

class Packager {

    def config
    def projectRoot

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
            sourceDir.eachFileRecurse { file ->
                if (file.isFile()) {
                    def relativePath = file.absolutePath - sourceDir.absolutePath - File.separator
                    def entry = new ZipEntry(relativePath)
                    zos.putNextEntry(entry)
                    file.withInputStream { zos << it }
                    zos.closeEntry()
                }
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
        def styleShort = style.replaceAll("[^a-zA-Z]", "")

        // Target: arc42-template/dist/arc42-template-{LANG}-{STYLE}-{FORMAT}.zip
        def zipFileName = "arc42-template-${language}-${styleShort}-${format}.zip"
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
        }
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
