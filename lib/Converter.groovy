#!/usr/bin/env groovy

@Grab('org.asciidoctor:asciidoctorj:2.5.10')
@Grab('org.asciidoctor:asciidoctorj-diagram:2.2.14')
@Grab('org.asciidoctor:asciidoctorj-pdf:2.3.27')
@Grab('org.codehaus.gpars:gpars:1.2.1')

import org.asciidoctor.Asciidoctor
import org.asciidoctor.Options
import org.asciidoctor.Attributes
import org.asciidoctor.SafeMode
import groovyx.gpars.GParsPool
import org.asciidoctor.log.LogHandler
import org.asciidoctor.log.LogRecord
import org.asciidoctor.log.Severity

/**
 * Converter.groovy - Format conversion using AsciidoctorJ and Pandoc
 *
 * Responsibilities:
 * - Convert AsciiDoc to HTML and PDF using AsciidoctorJ (PDF: asciidoctorj-pdf)
 * - Convert AsciiDoc to DocBook using AsciidoctorJ
 * - Convert DocBook to various formats using Pandoc (markdown, docx, epub, latex, etc.)
 * - Handle image copying for each format
 * - Support parallel execution for performance
 */

class Converter {

    def config
    def projectRoot
    def asciidoctor

    /** Asciidoctor and Pandoc diagnostics (missing includes, missing images, ...) collected across all conversions */
    final List<LogRecord> diagnostics = Collections.synchronizedList([])

    /** Severities from least to most severe; anything unknown is treated like ERROR */
    static final List<Severity> SEVERITY_ORDER = [Severity.DEBUG, Severity.INFO, Severity.WARN, Severity.ERROR, Severity.FATAL]

    /** PDF theme (relative to the generator directory) and the directory of its fallback font (Chinese), installed in the Docker image */
    static final String PDF_THEME = 'lib/pdf-theme.yml'
    static final String PDF_FALLBACK_FONT_DIR = '/usr/share/fonts/droid-nonlatin'

    /**
     * Unix timestamp (seconds) that Pandoc uses for the dates inside DOCX and EPUB files instead of "now",
     * so that unchanged content produces identical files. Null keeps Pandoc's default (current time).
     */
    Long sourceDateEpoch = null

    Converter(config, projectRoot = new File('.')) {
        this.config = config
        this.projectRoot = projectRoot
        this.asciidoctor = Asciidoctor.Factory.create()
        // Asciidoctor reports content problems only through its logger; collect them so that the build can fail on them
        this.asciidoctor.registerLogHandler({ LogRecord record -> diagnostics << record } as LogHandler)
    }

    /** Base name of the main document and of all generated files, e.g. 'arc42-template' */
    String getProjectName() {
        return config.project.name
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
            } else if (format == 'pdf') {
                result = convertToPDF(template, outputDir)
            } else if (format == 'asciidoc') {
                result = copyAsciidoc(template, outputDir)
            } else if (format == 'docbook') {
                result = convertToDocBook(template, outputDir, false)
            } else if (isMultiPage(format)) {
                result = convertViaPandocMP(template, format, outputDir)
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
        def outputFile = new File(outputFileDir, projectName + '.html')

        def attributes = createAttributes(template)
        attributes.put('backend', 'html5')

        // baseDir must be srcDir because includes are relative to the main document
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
     * Convert AsciiDoc to PDF using AsciidoctorJ PDF; the images are embedded, so the output is a single file
     */
    String convertToPDF(Map template, String outputDir) {
        def mainFile = new File(template.mainFile).canonicalFile
        def outputFileDir = new File(projectRoot, outputDir).canonicalFile
        outputFileDir.mkdirs()
        def outputFile = new File(outputFileDir, "${projectName}-${template.language}.pdf")

        def attributes = createAttributes(template)
        attributes.put('backend', 'pdf')
        // images are read from the template's own images directory instead of a copy next to the output
        if (template.imagesDir) attributes.put('imagesdir', new File(template.imagesDir).canonicalPath)
        attributes.put('pdf-theme', new File(PDF_THEME).canonicalPath)
        attributes.put('pdf-fontsdir', "GEM_FONTS_DIR;${PDF_FALLBACK_FONT_DIR}".toString())

        def options = Options.builder()
            .toFile(outputFile)
            .backend('pdf')
            .safe(SafeMode.UNSAFE)
            .baseDir(new File(template.srcDir).canonicalFile)
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
        def outputFile = new File(outputFileDir, projectName + '.xml')

        def attributes = createAttributes(template)
        attributes.put('backend', 'docbook')

        // baseDir must be srcDir because includes are relative to the main document
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

        // Derive docbook intermediate dir from outputDir (same base, 'docbook' format segment)
        def docbookRelDir = outputDir.replaceFirst("/${format}/", "/docbook/")
        def docbookDir = new File(projectRoot, docbookRelDir).canonicalFile.absolutePath
        new File(docbookDir).mkdirs()
        def docbookFile = convertToDocBook(template, docbookRelDir, false)

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
        def outputFileName = "${projectName}-${language}.${formatConfig.extension}"
        def outputFileDir = new File(projectRoot, outputDir).canonicalFile
        outputFileDir.mkdirs()
        def outputFile = new File(outputFileDir, outputFileName)

        // Build Pandoc command - use relative path to DocBook file since we'll run from docbookDir
        def docbookFileName = projectName + '.xml'
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

        // Without an identifier Pandoc puts a random UUID into every EPUB (even with SOURCE_DATE_EPOCH)
        if (format == 'epub') {
            pandocArgs.addAll(['--metadata', "identifier=${epubIdentifier(template)}".toString()])
        }

        // Special handling for Russian language (LaTeX)
        if (format == 'latex' && language == 'RU') {
            pandocArgs.addAll(['-V', 'fontenc=T1,T2A'])
        }

        // Add standalone flag for most formats
        if (format in ['latex', 'rst', 'markdown', 'markdownMP', 'markdownStrict',
                       'markdownMPStrict', 'gitHubMarkdown', 'gitHubMarkdownMP']) {
            pandocArgs.add(1, '-s')  // Insert after 'pandoc'
        }

        // Execute Pandoc with UTF-8 environment
        // IMPORTANT: Run from docbook directory so Pandoc can find relative image paths
        runPandoc(pandocArgs, new File(docbookDir), "${template.language}/${template.style} ${format}")

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
            if (file.name != projectName + '.adoc') {
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
        
        def targetMainFile = new File(projectRoot, "${outputDir}/${projectName}.adoc")
        targetMainFile.write(mainContent, 'utf-8')

        return targetMainFile.absolutePath
    }

    /**
     * Copy images to output directory
     */
    void copyImages(Map template, String outputDir, String format) {
        if (!template.hasImages) return

        def sourceImagesDir = new File(template.imagesDir)

        def targetImagesDir = new File(projectRoot, "${outputDir}/images")
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
     * Stable EPUB identifier of a template: a name-based UUID of project, language and style,
     * so that every template variant has its own identifier and unchanged content produces an identical EPUB.
     */
    String epubIdentifier(Map template) {
        def name = "${projectName}/${template.language}/${template.style}".toString()
        return "urn:uuid:${UUID.nameUUIDFromBytes(name.getBytes('UTF-8'))}".toString()
    }

    /** Returns true for formats that produce one output file per chapter */
    boolean isMultiPage(String format) {
        return format in ['markdownMP', 'markdownMPStrict', 'gitHubMarkdownMP']
    }

    /** Returns true for formats that Pandoc renders from the single-document DocBook intermediate */
    boolean usesDocBookIntermediate(String format) {
        return !(format in ['html', 'pdf', 'asciidoc', 'docbook']) && !isMultiPage(format)
    }

    /** Output directory of a template for a format, relative to the project root */
    String outputDirFor(Map template, String format, String outputBase = 'build') {
        return "${outputBase}/${template.language}/${format}/${template.style}"
    }

    /**
     * Delete the output directories, including the DocBook intermediates, that converting the given
     * templates to the given formats will write, so that no file of an earlier run survives in the output
     * (and ends up in a distribution ZIP).
     *
     * @return number of directories deleted
     */
    int cleanOutputs(List<Map> templates, List<String> formats, String outputBase = 'build') {
        def dirs = new LinkedHashSet<String>()
        templates.each { template ->
            formats.each { format ->
                dirs << outputDirFor(template, format, outputBase)
                if (usesDocBookIntermediate(format)) dirs << outputDirFor(template, 'docbook', outputBase)
                if (isMultiPage(format)) dirs << outputDirFor(template, 'docbookMP', outputBase)
            }
        }
        int deleted = 0
        dirs.each { relativeDir ->
            def dir = new File(projectRoot, relativeDir)
            if (dir.exists()) {
                dir.deleteDir()
                deleted++
            }
        }
        println "✓ Cleaned ${deleted} output director${deleted == 1 ? 'y' : 'ies'} under ${outputBase}/"
        return deleted
    }

    /**
     * Run Pandoc in the given directory. Everything Pandoc writes to stderr (e.g. a warning about an
     * image it could not find) is recorded as a WARN diagnostic; a non-zero exit code fails the conversion.
     */
    void runPandoc(List<String> args, File workingDir, String context) {
        def processBuilder = new ProcessBuilder(args)
        processBuilder.directory(workingDir)
        // Set UTF-8 encoding in environment
        processBuilder.environment().putAll([LC_ALL: 'en_US.UTF-8', LANG: 'en_US.UTF-8'])
        if (sourceDateEpoch != null) {
            processBuilder.environment().put('SOURCE_DATE_EPOCH', sourceDateEpoch.toString())
        }
        def stdout = new StringBuilder()
        def stderr = new StringBuilder()
        def process = processBuilder.start()
        process.consumeProcessOutput(stdout, stderr)
        process.waitFor()
        stderr.toString().readLines().findAll { it.trim() }.each { line ->
            diagnostics << new LogRecord(Severity.WARN, "pandoc (${context}): ${line.trim()}".toString())
        }
        if (process.exitValue() != 0) {
            throw new RuntimeException("Pandoc failed (${context}, exit code ${process.exitValue()}): ${stderr}")
        }
    }

    /**
     * Convert each chapter .adoc to a separate DocBook XML (multi-page step 1).
     *
     * The chapters are converted standalone, i.e. without the main document and without config.adoc.
     * config.adoc is where the golden master switches its features on (e.g. ':arc42help:'), so the
     * features of the template style have to be set as attributes here. Without them every
     * 'ifdef::arc42help[]' block is dropped and the with-help output equals the plain output.
     */
    void convertToDocBookMP(Map template, String docbookMPRelDir) {
        def outDir = new File(projectRoot, docbookMPRelDir).canonicalFile
        outDir.mkdirs()
        def attrs = createAttributes(template)
        featureAttributes(template).each { name -> attrs[name] = '' }
        def baseDir = new File(template.srcDir).canonicalFile
        new File(template.srcDir).listFiles().sort { it.name }.each { f ->
            if (!f.name.endsWith('.adoc') || f.name in [projectName + '.adoc', 'config.adoc']) return
            def opts = Options.builder().toFile(new File(outDir, f.name.replace('.adoc', '.xml')))
                .backend('docbook').safe(SafeMode.UNSAFE).baseDir(baseDir).mkDirs(true)
                .attributes(attrs).build()
            asciidoctor.convertFile(f, opts)
        }
    }

    /**
     * Names of the AsciiDoc attributes that switch on the features of the template's style,
     * e.g. ['arc42help'] for the with-help style of arc42 (feature prefix + feature name).
     */
    List<String> featureAttributes(Map template) {
        def features = config.goldenMaster.templateStyles[template.style] ?: []
        return features.collect { "${config.project.featurePrefix}${it}".toString() }
    }

    // Convert to multi-page output: one file per chapter via per-chapter DocBook XML + Pandoc
    String convertViaPandocMP(Map template, String format, String outputDir) {
        def docbookMPRelDir = outputDir.replaceFirst("/${format}/", "/docbookMP/")
        def docbookMPDir = new File(projectRoot, docbookMPRelDir).canonicalFile
        docbookMPDir.mkdirs()
        convertToDocBookMP(template, docbookMPRelDir)

        if (template.hasImages) {
            def src = new File(template.imagesDir)
            def tgt = new File(docbookMPDir, 'images')
            tgt.mkdirs()
            src.eachFileRecurse { f ->
                if (f.isFile()) { def t = new File(tgt, f.absolutePath - src.absolutePath); t.parentFile.mkdirs(); t.bytes = f.bytes }
            }
        }

        def formatConfig = getPandocConfig(format)
        def outputFileDir = new File(projectRoot, outputDir).canonicalFile
        outputFileDir.mkdirs()

        docbookMPDir.listFiles((FilenameFilter) { dir, name -> name.endsWith('.xml') })?.sort { it.name }?.each { xmlFile ->
            def outFile = new File(outputFileDir, xmlFile.name.replace('.xml', ".${formatConfig.extension}"))
            def args = ['pandoc', '-r', 'docbook', '-t', formatConfig.pandocFormat, '-o', outFile.absolutePath, xmlFile.name]
            if (formatConfig.args) args.addAll(formatConfig.args)
            runPandoc(args, docbookMPDir, "${template.language}/${template.style} ${format} ${xmlFile.name}")
        }

        return outputFileDir.absolutePath
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
            // no "Last updated <build time>" footer: the output of unchanged sources stays byte-identical
            'reproducible': '',
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
            'textile': [pandocFormat: 'textile', extension: 'textile', args: []],
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
    Map convertAll(List<Map> templates, List<String> formats = null, boolean parallel = true) {
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

        printDiagnostics()

        return [total: totalConversions, successful: completed - failed, failed: failed]
    }

    /**
     * Print all diagnostics collected so far, most severe first. The DocBook intermediate is rendered
     * once per Pandoc format, so identical records are printed once with their count.
     */
    void printDiagnostics() {
        def unique = uniqueDiagnostics()
        if (unique.isEmpty()) {
            println "✓ No Asciidoctor or Pandoc diagnostics"
            println ""
            return
        }
        println "=== Diagnostics: ${unique.size()} ==="
        unique.each { record ->
            def location = record.cursor?.file ? " ${relativePath(record.cursor.file)}:${record.cursor.lineNumber}" : ''
            def times = record.count > 1 ? " (x${record.count})" : ''
            println "  ${record.severity}${location}: ${record.message}${times}"
        }
        println ""
    }

    /**
     * Diagnostics whose severity is at least the given level, identical records counted once.
     *
     * @param level 'debug', 'info', 'warn', 'error' or 'fatal' (case-insensitive); 'none' selects nothing
     */
    List<Map> diagnosticsAtOrAbove(String level) {
        if (!level || level.equalsIgnoreCase('none')) return []
        def threshold = Severity.valueOf(level.toUpperCase())
        return uniqueDiagnostics().findAll { severityIndex(it.severity) >= severityIndex(threshold) }
    }

    /** Distinct diagnostics (severity, file, line, message) with their number of occurrences, most severe first */
    List<Map> uniqueDiagnostics() {
        def grouped = diagnostics.groupBy { [it.severity, it.cursor?.file, it.cursor?.lineNumber, it.message] }
        def unique = grouped.values().collect { records ->
            [severity: records[0].severity, cursor: records[0].cursor, message: records[0].message, count: records.size()]
        }
        return unique.toSorted { a, b -> severityIndex(b.severity) <=> severityIndex(a.severity) }
    }

    static int severityIndex(Severity severity) {
        int index = SEVERITY_ORDER.indexOf(severity)
        return index >= 0 ? index : SEVERITY_ORDER.indexOf(Severity.ERROR)
    }

    private String relativePath(String path) {
        def root = projectRoot.canonicalPath + File.separator
        return path?.startsWith(root) ? path.substring(root.length()) : path
    }
}
