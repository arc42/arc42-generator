#!/usr/bin/env groovy

@Grab('org.asciidoctor:asciidoctorj:2.5.10')
@Grab('org.asciidoctor:asciidoctorj-diagram:2.2.14')
@Grab('org.codehaus.gpars:gpars:1.2.1')

import org.asciidoctor.Asciidoctor
import org.asciidoctor.Options
import org.asciidoctor.Attributes
import org.asciidoctor.SafeMode
import groovyx.gpars.GParsPool

/**
 * Converter.groovy - Format conversion using AsciidoctorJ and Pandoc
 *
 * Responsibilities:
 * - Convert AsciiDoc to HTML using AsciidoctorJ
 * - Convert AsciiDoc to DocBook using AsciidoctorJ
 * - Convert DocBook to various formats using Pandoc (markdown, docx, epub, latex, etc.)
 * - Handle image copying for each format
 * - Support parallel execution for performance
 */

class Converter {

    def config
    def projectRoot
    def asciidoctor

    Converter(config, projectRoot = new File('.')) {
        this.config = config
        this.projectRoot = projectRoot
        this.asciidoctor = Asciidoctor.Factory.create()
    }

    /**
     * Convert a single template to a specific format
     *
     * @param template Template metadata from Discovery
     * @param format Target format (e.g., 'html', 'markdown', 'docx')
     * @param outputBase Base output directory (e.g., 'build')
     * @return Output file path or null if conversion failed
     */
    String convertTemplate(Map template, String format, String outputBase = 'build') {
        def language = template.language
        def style = template.style
        def outputDir = "${outputBase}/${language}/${format}/${style}"

        try {
            // Create output directory
            new File(projectRoot, outputDir).mkdirs()

            // Copy images if needed for this format
            if (config.formats[format]?.imageFolder) {
                copyImages(template, outputDir, format)
            }

            // Convert based on format
            def result = null
            if (format == 'html') {
                result = convertToHTML(template, outputDir)
            } else if (format == 'asciidoc') {
                result = copyAsciidoc(template, outputDir)
            } else if (format == 'docbook') {
                result = convertToDocBook(template, outputDir, false)
            } else {
                // All other formats go through DocBook + Pandoc
                result = convertViaPandoc(template, format, outputDir)
            }

            return result
        } catch (Exception e) {
            println "✗ Error converting ${language}/${style} to ${format}: ${e.message}"
            e.printStackTrace()
            return null
        }
    }

    /**
     * Convert AsciiDoc to HTML5 using AsciidoctorJ
     */
    String convertToHTML(Map template, String outputDir) {
        def language = template.language
        def mainFile = new File(template.mainFile).canonicalFile
        def outputFileDir = new File(projectRoot, outputDir).canonicalFile
        outputFileDir.mkdirs()
        def outputFile = new File(outputFileDir, "arc42-template.html")

        def attributes = createAttributes(template)
        attributes.put('backend', 'html5')

        // baseDir must be srcDir because includes are relative to arc42-template.adoc
        def baseDir = new File(template.srcDir).canonicalFile

        def options = Options.builder()
            .toFile(outputFile)
            .backend('html5')
            .safe(SafeMode.UNSAFE)
            .baseDir(baseDir)
            .mkDirs(true)
            .attributes(attributes)
            .build()

        asciidoctor.convertFile(mainFile, options)

        return outputFile.absolutePath
    }

    /**
     * Convert AsciiDoc to DocBook XML using AsciidoctorJ
     *
     * @param multiPage If true, creates multi-page structure (for markdownMP, etc.)
     */
    String convertToDocBook(Map template, String outputDir, boolean multiPage = false) {
        def language = template.language
        def mainFile = new File(template.mainFile).canonicalFile
        def outputSubDir = multiPage ? "${outputDir}MP" : outputDir
        def outputFileDir = new File(projectRoot, outputSubDir).canonicalFile
        outputFileDir.mkdirs()
        def outputFile = new File(outputFileDir, "arc42-template.xml")

        def attributes = createAttributes(template)
        attributes.put('backend', 'docbook')

        // baseDir must be srcDir because includes are relative to arc42-template.adoc
        def baseDir = new File(template.srcDir).canonicalFile

        def options = Options.builder()
            .toFile(outputFile)
            .backend('docbook')
            .safe(SafeMode.UNSAFE)
            .baseDir(baseDir)
            .mkDirs(true)
            .attributes(attributes)
            .build()

        asciidoctor.convertFile(mainFile, options)

        return outputFile.absolutePath
    }

    /**
     * Convert via DocBook intermediate format using Pandoc
     */
    String convertViaPandoc(Map template, String format, String outputDir) {
        def language = template.language

        // First generate DocBook
        def docbookDir = "${projectRoot.path}/build/${language}/docbook/${template.style}"
        new File(docbookDir).mkdirs()
        def docbookFile = convertToDocBook(template, docbookDir.replace(projectRoot.path + '/', ''), false)

        // Copy images to DocBook directory so Pandoc can find them
        if (template.hasImages) {
            def sourceImagesDir = new File(template.imagesDir)
            def targetImagesDir = new File(docbookDir, 'images')
            targetImagesDir.mkdirs()
            
            sourceImagesDir.eachFileRecurse { file ->
                if (file.isFile()) {
                    def relativePath = file.absolutePath - sourceImagesDir.absolutePath
                    def targetFile = new File(targetImagesDir, relativePath)
                    targetFile.parentFile.mkdirs()
                    targetFile.bytes = file.bytes
                }
            }
        }

        // Determine output file extension and Pandoc target format
        def formatConfig = getPandocConfig(format)
        def outputFileName = "arc42-template-${language}.${formatConfig.extension}"
        def outputFileDir = new File(projectRoot, outputDir).canonicalFile
        outputFileDir.mkdirs()
        def outputFile = new File(outputFileDir, outputFileName)

        // Build Pandoc command - use relative path to DocBook file since we'll run from docbookDir
        def docbookFileName = "arc42-template.xml"
        def pandocArgs = [
            'pandoc',
            '-r', 'docbook',
            '-t', formatConfig.pandocFormat,
            '-o', outputFile.absolutePath,
            docbookFileName
        ]

        // Add format-specific arguments
        if (formatConfig.args) {
            pandocArgs.addAll(formatConfig.args)
        }

        // Special handling for Russian language (LaTeX)
        if (format == 'latex' && language == 'RU') {
            pandocArgs.addAll(['-V', 'fontenc=T1,T2A'])
        }

        // Add standalone flag for most formats
        if (format in ['latex', 'rst', 'markdown', 'markdownMP', 'markdownStrict',
                       'markdownMPStrict', 'gitHubMarkdown', 'gitHubMarkdownMP',
                       'mkdocs', 'mkdocsMP']) {
            pandocArgs.add(1, '-s')  // Insert after 'pandoc'
        }

        // Execute Pandoc with UTF-8 environment
        // IMPORTANT: Run from docbook directory so Pandoc can find relative image paths
        def processBuilder = new ProcessBuilder(pandocArgs)
        processBuilder.directory(new File(docbookDir))
        
        // Set UTF-8 encoding in environment
        def env = processBuilder.environment()
        env['LC_ALL'] = 'en_US.UTF-8'
        env['LANG'] = 'en_US.UTF-8'
        
        def process = processBuilder.start()
        process.waitFor()

        if (process.exitValue() != 0) {
            def error = process.err.text
            throw new RuntimeException("Pandoc conversion failed: ${error}")
        }

        // Post-processing for LaTeX (fix unicode characters)
        if (format == 'latex') {
            def content = outputFile.getText('utf-8')
            outputFile.write(content.replaceAll("\u2009", " "), 'utf-8')
        }
        
        // Ensure UTF-8 charset is properly set in HTML output
        if (format == 'html') {
            def content = outputFile.getText('utf-8')
            // Add UTF-8 charset meta tag if not present
            if (!content.contains('charset')) {
                content = content.replaceFirst('<head>', '<head>\n<meta charset="UTF-8">')
                outputFile.write(content, 'utf-8')
            }
        }

        return outputFile.absolutePath
    }

    /**
     * Copy AsciiDoc source to output directory
     */
    String copyAsciidoc(Map template, String outputDir) {
        def srcDir = new File(template.srcDir)
        def targetSrcDir = new File(projectRoot, "${outputDir}/src")
        targetSrcDir.mkdirs()

        // Copy all files except main template to src/
        srcDir.eachFile { file ->
            if (file.name != 'arc42-template.adoc') {
                def targetFile = new File(targetSrcDir, file.name)
                targetFile.write(file.getText('utf-8'), 'utf-8')
            }
        }

        // Copy main template to root of output, adjusting include paths
        def mainFile = new File(template.mainFile)
        def mainContent = mainFile.getText('utf-8')
        
        // Adjust include paths for AsciiDoc output:
        // - Files are in src/ subdirectory, so references need src/ prefix
        mainContent = mainContent.replaceAll('include::', 'include::src/')
        
        def targetMainFile = new File(projectRoot, "${outputDir}/arc42-template.adoc")
        targetMainFile.write(mainContent, 'utf-8')

        return targetMainFile.absolutePath
    }

    /**
     * Copy images to output directory
     */
    void copyImages(Map template, String outputDir, String format) {
        if (!template.hasImages) return

        def sourceImagesDir = new File(template.imagesDir)

        // mkdocs uses docs/images, others use images
        def targetImagesPath = (format == 'mkdocs') ? "${outputDir}/docs/images" : "${outputDir}/images"
        def targetImagesDir = new File(projectRoot, targetImagesPath)
        targetImagesDir.mkdirs()

        // Copy all image files
        sourceImagesDir.eachFileRecurse { file ->
            if (file.isFile()) {
                def relativePath = file.absolutePath - sourceImagesDir.absolutePath
                def targetFile = new File(targetImagesDir, relativePath)
                targetFile.parentFile.mkdirs()
                targetFile.bytes = file.bytes
            }
        }
    }

    /**
     * Create AsciiDoc attributes map from template metadata
     */
    Map<String, Object> createAttributes(Map template) {
        def attrs = [
            'toc': 'left',
            'doctype': 'book',
            'icons': 'font',
            'sectlink': true,
            'sectanchors': true,
            'numbered': true,
            'imagesdir': 'images',
        ]

        // Add version information if available
        def versionProps = template.versionProperties
        if (versionProps) {
            if (versionProps.revnumber) attrs['revnumber'] = versionProps.revnumber
            if (versionProps.revdate) attrs['revdate'] = versionProps.revdate
            if (versionProps.revremark) attrs['revremark'] = versionProps.revremark
        }

        return attrs
    }

    /**
     * Get Pandoc configuration for a specific format
     */
    Map getPandocConfig(String format) {
        def configs = [
            'html': [pandocFormat: 'html5', extension: 'html', args: ['-M', 'charset=utf-8']],
            'markdown': [pandocFormat: 'markdown', extension: 'md', args: []],
            'markdownMP': [pandocFormat: 'markdown', extension: 'md', args: []],
            'markdownStrict': [pandocFormat: 'markdown_strict', extension: 'md', args: []],
            'markdownMPStrict': [pandocFormat: 'markdown_strict', extension: 'md', args: []],
            'gitHubMarkdown': [pandocFormat: 'gfm', extension: 'md', args: []],
            'gitHubMarkdownMP': [pandocFormat: 'gfm', extension: 'md', args: []],
            'mkdocs': [pandocFormat: 'markdown', extension: 'md', args: []],
            'mkdocsMP': [pandocFormat: 'markdown', extension: 'md', args: []],
            'textile': [pandocFormat: 'textile', extension: 'textile', args: []],
            'textile2': [pandocFormat: 'textile', extension: 'textile', args: []],
            'docx': [pandocFormat: 'docx', extension: 'docx', args: []],
            'epub': [pandocFormat: 'epub', extension: 'epub', args: []],
            'latex': [pandocFormat: 'latex', extension: 'tex', args: []],
            'rst': [pandocFormat: 'rst', extension: 'rst', args: []],
        ]

        return configs[format] ?: [pandocFormat: format, extension: format, args: []]
    }

    /**
     * Convert all discovered templates to all configured formats
     *
     * @param templates List of template metadata from Discovery
     * @param formats List of format names (defaults to all configured formats)
     * @param parallel Enable parallel execution (default: true)
     */
    void convertAll(List<Map> templates, List<String> formats = null, boolean parallel = true) {
        if (!formats) {
            formats = config.formats.keySet() as List
        }

        println "\n=== Converting Templates ==="
        println "Templates: ${templates.size()}"
        println "Formats: ${formats.size()} (${formats.join(', ')})"
        println "Parallel: ${parallel}"
        println ""

        def totalConversions = templates.size() * formats.size()
        def completed = 0
        def failed = 0

        def startTime = System.currentTimeMillis()

        if (parallel) {
            // Use GPars for parallel execution
            GParsPool.withPool(Runtime.runtime.availableProcessors()) {
                templates.eachParallel { template ->
                    formats.each { format ->
                        def result = convertTemplate(template, format)
                        synchronized(this) {
                            completed++
                            if (result) {
                                println "[${completed}/${totalConversions}] ✓ ${template.language}/${template.style} → ${format}"
                            } else {
                                failed++
                                println "[${completed}/${totalConversions}] ✗ ${template.language}/${template.style} → ${format}"
                            }
                        }
                    }
                }
            }
        }

        if (!parallel) {
            // Sequential execution
            templates.each { template ->
                formats.each { format ->
                    def result = convertTemplate(template, format)
                    completed++
                    if (result) {
                        println "[${completed}/${totalConversions}] ✓ ${template.language}/${template.style} → ${format}"
                    } else {
                        failed++
                        println "[${completed}/${totalConversions}] ✗ ${template.language}/${template.style} → ${format}"
                    }
                }
            }
        }

        def endTime = System.currentTimeMillis()
        def duration = (endTime - startTime) / 1000.0

        println "\n=== Conversion Complete ==="
        println "Total: ${totalConversions}"
        println "Successful: ${completed - failed}"
        println "Failed: ${failed}"
        println "Duration: ${String.format('%.1f', duration)}s"
        println ""
    }
}
