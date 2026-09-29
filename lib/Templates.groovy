#!/usr/bin/env groovy

import java.util.regex.Pattern

/**
 * Templates.groovy - Golden Master processing and template generation
 *
 * Responsibilities:
 * - Auto-discover available languages from arc42-template/ directory
 * - Process Golden Master templates with feature flag removal
 * - Generate template variants (plain, with-help, etc.)
 * - Copy images and common files
 * - Lint the Golden Master before generating anything from it (validateGoldenMaster)
 */

class Templates {

    /** Language whose chapter files and help blocks the translations are compared with */
    static final String REFERENCE_LANGUAGE = 'EN'

    /** Properties every <LANG>/version.properties must define */
    static final List<String> VERSION_PROPERTIES = ['revnumber', 'revdate', 'revremark']

    /** image::target[...] (block) and image:target[...] (inline) macros; the target must not start with ':' */
    static final Pattern IMAGE_MACRO = ~/image::?([^\[\s:][^\[\s]*)\[/

    def config
    def projectRoot

    /** Abort createFromGoldenMaster() when validateGoldenMaster() reports errors (false: only report them) */
    boolean failOnLintErrors = true

    Templates(config, projectRoot = new File('.')) {
        this.config = config
        this.projectRoot = projectRoot
    }

    /**
     * Auto-discover available languages by scanning arc42-template/ directory
     * Looks for directories matching pattern: /^[A-Z]{2,}$/
     * (Matches 2 or more uppercase letters, e.g., DE, EN, ZH, UKR)
     *
     * @return List of language codes (e.g., ['CZ', 'DE', 'EN', 'ES', 'FR', 'IT', 'NL', 'PT', 'RU', 'UKR', 'ZH'])
     */
    List<String> discoverLanguages() {
        def sourcePath = new File(projectRoot, config.goldenMaster.sourcePath)

        if (!sourcePath.exists()) {
            throw new IllegalStateException("Golden Master source path does not exist: ${sourcePath.absolutePath}")
        }

        def languages = sourcePath.listFiles()
            ?.findAll { it.isDirectory() && it.name ==~ /^[A-Z]{2,}$/ }
            *.name
            .sort()

        if (!languages || languages.isEmpty()) {
            throw new IllegalStateException("No language directories found in ${sourcePath.absolutePath}")
        }

        println "✓ Discovered languages: ${languages.join(', ')}"
        return languages
    }

    /**
     * Adjust include paths in main template
     * The main template contains includes like:
     *   include::adoc/config.adoc[]
     *   include::../common/styles/arc42-help-style.adoc[]
     *
     * When copied to build/src_gen/<LANG>/asciidoc/<STYLE>/src/, these become relative to that location.
     * We need to replace:
     *   adoc/ → ./  (sections are in the same src/ directory after copy)
     *   ../common/ → ../../../common/  (go up from src/ → style/ → asciidoc/ → LANG/)
     *
     * @param template The template content
     * @return Modified template with corrected include paths
     */
    String adjustIncludePaths(String template) {
        def result = template

        // Fix adoc/ includes to reference current directory (all files in src/)
        result = result.replaceAll('include::adoc/', 'include::')

        // Fix ../common/ includes to reference the language common directory
        result = result.replaceAll('include::../common/', 'include::../../../common/')

        return result
    }
    String removeFeatures(String template, List<String> featuresToRemove) {
        def result = template
        def prefix = config.project.featurePrefix

        // Remove role-based feature blocks: [role="<prefix><feature>"] **** ... ****
        featuresToRemove.each { feature ->
            def marker = Pattern.quote("${prefix}${feature}")
            result = result.replaceAll(/(?ms)\[role="${marker}"\][ \r\n]+[*]{4}.*?[*]{4}/, '')
        }

        // Remove ifdef/endif blocks for help feature
        if ('help' in featuresToRemove) {
            def helpMarker = Pattern.quote("${prefix}help")
            result = result.replaceAll(/(?ms)ifdef::${helpMarker}\[\]/, '')
            result = result.replaceAll(/(?ms)endif::${helpMarker}\[\]/, '')
        }

        return result
    }

    /**
     * Copy images from language directory
     * New structure: arc42-template/<LANG>/images/
     *
     * @param language Language code (e.g., 'EN')
     * @param templateName Template style (e.g., 'plain', 'with-help')
     * @param languagePath Path to the language directory in Golden Master
     * @param targetPath Target directory path
     */
    void copyImages(String language, String templateName, String languagePath, String targetPath) {
        def imagesSource = new File(projectRoot, languagePath + '/images')
        def imagesTarget = new File(projectRoot, targetPath + '/images')

        imagesTarget.mkdirs()

        if (!imagesSource.exists()) {
            println "  ⚠ Warning: No images directory found at ${imagesSource.absolutePath}"
            return
        }

        def imageFiles = imagesSource.listFiles()?.findAll { it.isFile() }

        if (templateName == 'plain') {
            // Only copy the logo
            String logo = config.project.logo
            def logoSource = imageFiles?.find { it.name == logo }

            if (logoSource) {
                def logoTarget = new File(imagesTarget, logo)
                logoTarget.bytes = logoSource.bytes
                println "  ✓ Copied ${logo}"
            } else {
                println "  ⚠ Warning: ${logo} not found"
            }
        } else {
            // Copy all images for with-help style
            imageFiles?.each { sourceFile ->
                def targetFile = new File(imagesTarget, sourceFile.name)
                targetFile.bytes = sourceFile.bytes
            }

            println "  ✓ Copied ${imageFiles?.size() ?: 0} image(s)"
        }
    }

    // ========================================================================
    // Golden Master lint
    // ========================================================================

    /**
     * Validate the golden master before anything is generated from it.
     *
     * Checked per language in <LANG>/<project name>.adoc and <LANG>/adoc/*.adoc,
     * for every feature in goldenMaster.allFeatures (marker prefix: project.featurePrefix):
     * - error:   the numbers of ifdef::<prefix><feature>[] and endif::<prefix><feature>[]
     *            lines in a file differ (unbalanced conditional)
     * - error:   the numbers of [role="<prefix><feature>"] and ifdef::<prefix><feature>[]
     *            lines in a file differ (a feature block that is not wrapped in its conditional)
     * - error:   an image::target[] or image:target[] whose target is not in <LANG>/images/
     * - error:   version.properties is missing or lacks revnumber, revdate or revremark
     * - warning: the chapter files (adoc/*.adoc) differ from those of the EN language
     * - warning: a chapter has a different number of feature blocks than its EN counterpart
     *
     * @param languages Language codes to check; auto-discovered when null
     * @return One map per problem: severity ('error' or 'warning'), language,
     *         file (relative to the golden master root), line (Integer or null), message
     */
    List<Map> validateGoldenMaster(List<String> languages = null) {
        languages = languages ?: discoverLanguages()
        def sourceRoot = new File(projectRoot, config.goldenMaster.sourcePath)
        def problems = []

        // The reference language's chapters, for the translation drift warnings
        def referenceDir = new File(sourceRoot, REFERENCE_LANGUAGE)
        def referenceChapters = referenceDir.isDirectory() ? scanChapters(referenceDir) : null

        languages.each { language ->
            def languageDir = new File(sourceRoot, language)
            def imagesDir = new File(languageDir, 'images')

            def mainDocument = new File(languageDir, "${config.project.name}.adoc")
            if (mainDocument.isFile()) {
                problems.addAll(lintDocument(scanDocument(mainDocument), language, language + '/' + mainDocument.name, imagesDir))
            }

            def chapters = scanChapters(languageDir)
            chapters.each { name, scan ->
                problems.addAll(lintDocument(scan, language, language + '/adoc/' + name, imagesDir))
            }

            problems.addAll(lintVersionProperties(languageDir, language))

            if (referenceChapters != null && language != REFERENCE_LANGUAGE) {
                problems.addAll(compareWithReference(chapters, referenceChapters, language))
            }
        }

        return problems
    }

    /**
     * Validate the golden master, print every problem and fail on errors
     * (unless failOnLintErrors is false, then errors are only reported).
     */
    void lintGoldenMaster(List<String> languages) {
        def problems = validateGoldenMaster(languages)
        def errors = problems.findAll { it.severity == 'error' }
        def warnings = problems.findAll { it.severity == 'warning' }

        problems.each { problem ->
            def marker = problem.severity == 'warning' ? '⚠' : (failOnLintErrors ? '✗' : '⚠ (ignored)')
            println "  ${marker} ${formatProblem(problem)}"
        }
        def failed = errors && failOnLintErrors
        println "${failed ? '✗' : '✓'} Golden master validation: ${errors.size()} error(s), ${warnings.size()} warning(s)"

        if (failed) {
            def details = errors.collect { "  ✗ ${formatProblem(it)}" }.join('\n')
            throw new IllegalStateException("Golden master validation failed: ${errors.size()} error(s)\n${details}")
        }
    }

    /** "<file>:<line>: <message>", without ":<line>" when the problem has no line */
    String formatProblem(Map problem) {
        def location = problem.line != null ? "${problem.file}:${problem.line}" : problem.file
        return "${location}: ${problem.message}"
    }

    /**
     * One pass over an AsciiDoc file: the line numbers of the ifdef, endif and role
     * marker lines per feature, and the image targets with their lines.
     */
    private Map scanDocument(File file) {
        def prefix = config.project.featurePrefix
        def markers = [:]       // feature -> [ifdef: [lines], endif: [lines], role: [lines]]
        def markerLines = [:]   // marker line (trimmed) -> [feature, kind]
        config.goldenMaster.allFeatures.each { feature ->
            markers[feature] = [ifdef: [], endif: [], role: []]
            markerLines["ifdef::${prefix}${feature}[]".toString()] = [feature, 'ifdef']
            markerLines["endif::${prefix}${feature}[]".toString()] = [feature, 'endif']
            markerLines["[role=\"${prefix}${feature}\"]".toString()] = [feature, 'role']
        }

        def images = []
        def lines = file.readLines('utf-8')
        lines.eachWithIndex { line, index ->
            def trimmed = line.trim()
            def marker = markerLines[trimmed]
            if (marker) {
                markers[marker[0]][marker[1]] << index + 1
            } else if (!trimmed.startsWith('//')) {   // comment lines are not rendered
                def matcher = IMAGE_MACRO.matcher(line)
                while (matcher.find()) {
                    images << [line: index + 1, target: matcher.group(1)]
                }
            }
        }

        return [lineCount: lines.size(), markers: markers, images: images]
    }

    /** Scan every <LANG>/adoc/*.adoc file, keyed by file name and sorted */
    private Map<String, Map> scanChapters(File languageDir) {
        def chapters = new TreeMap<String, Map>()
        new File(languageDir, 'adoc').listFiles()?.each { file ->
            if (file.isFile() && file.name.endsWith('.adoc')) {
                chapters[file.name] = scanDocument(file)
            }
        }
        return chapters
    }

    /** The marker and image problems of one scanned document */
    private List<Map> lintDocument(Map scan, String language, String path, File imagesDir) {
        def prefix = config.project.featurePrefix
        def problems = []
        def error = { Integer line, String message ->
            problems << [severity: 'error', language: language, file: path, line: line, message: message]
        }

        scan.markers.each { feature, found ->
            def name = "${prefix}${feature}"
            if (found.ifdef.size() != found.endif.size()) {
                error(firstUnmatched(found.ifdef, found.endif) ?: scan.lineCount,
                    "unbalanced conditional: ${found.ifdef.size()} × ifdef::${name}[] but ${found.endif.size()} × endif::${name}[]")
            }
            if (found.role.size() != found.ifdef.size()) {
                error(found.role ? found.role[0] : found.ifdef[0],
                    "${found.role.size()} × [role=\"${name}\"] but ${found.ifdef.size()} × ifdef::${name}[] " +
                    "(every [role=\"${name}\"] block needs its own ifdef::${name}[] ... endif::${name}[])")
            }
        }

        scan.images.unique().each { image ->
            def target = image.target
            def external = ['http://', 'https://', 'data:'].any { target.startsWith(it) }
            if (external || target.contains('{')) {
                return   // URLs and attribute references cannot be checked
            }
            if (!new File(imagesDir, target).isFile()) {
                error(image.line, "image '${target}' not found in ${language}/images/")
            }
        }

        return problems
    }

    /**
     * Line of the first ifdef that is not closed by an endif (or of the first endif
     * without an open ifdef); null when all conditionals are closed
     */
    private Integer firstUnmatched(List<Integer> ifdefLines, List<Integer> endifLines) {
        def open = []
        def events = ifdefLines.collect { [line: it, opens: true] } + endifLines.collect { [line: it, opens: false] }
        for (event in events.sort { it.line }) {
            if (event.opens) {
                open << event.line
            } else if (open) {
                open.removeLast()
            } else {
                return event.line
            }
        }
        return open ? open[0] : null
    }

    /** version.properties must exist and define all VERSION_PROPERTIES */
    private List<Map> lintVersionProperties(File languageDir, String language) {
        def file = new File(languageDir, 'version.properties')
        def path = language + '/version.properties'
        def error = { String message ->
            [severity: 'error', language: language, file: path, line: null, message: message]
        }

        if (!file.isFile()) {
            return [error('version.properties is missing')]
        }
        def properties = new Properties()
        file.withReader('utf-8') { properties.load(it) }
        def missing = VERSION_PROPERTIES.findAll { !properties.getProperty(it)?.trim() }
        return missing ? [error("version.properties lacks ${missing.join(', ')}")] : []
    }

    /** Translation drift warnings: chapter files and feature block counts compared with the reference language */
    private List<Map> compareWithReference(Map<String, Map> chapters, Map<String, Map> reference, String language) {
        def prefix = config.project.featurePrefix
        def problems = []
        def warning = { String path, String message ->
            problems << [severity: 'warning', language: language, file: path, line: null, message: message]
        }

        def extra = chapters.keySet() - reference.keySet()
        def missing = reference.keySet() - chapters.keySet()
        if (extra || missing) {
            def details = []
            if (extra) details << "extra: ${extra.join(', ')}"
            if (missing) details << "missing: ${missing.join(', ')}"
            warning(language + '/adoc', "chapter files differ from ${REFERENCE_LANGUAGE} (${details.join('; ')})")
        }

        chapters.each { name, scan ->
            def counterpart = reference[name]
            if (counterpart == null) {
                return
            }
            scan.markers.each { feature, found ->
                def referenceCount = counterpart.markers[feature].role.size()
                if (found.role.size() != referenceCount) {
                    warning(language + '/adoc/' + name,
                        "${found.role.size()} [role=\"${prefix}${feature}\"] block(s), ${REFERENCE_LANGUAGE} has ${referenceCount}")
                }
            }
        }

        return problems
    }

    /**
     * Main method: Create templates from Golden Master
     *
     * New Structure (arc42-template):
     * - <LANG>/<project name>.adoc (main template file)
     * - <LANG>/adoc/ (individual sections)
     * - <LANG>/images/ (images)
     * - <LANG>/version.properties
     *
     * Process:
     * 1. Auto-discover languages
     * 2. Validate the golden master (errors abort unless failOnLintErrors is false)
     * 3. For each language:
     *    - Copy common files
     *    - Copy version.properties
     *    - For each template style:
     *      - Process main template and section files
     *      - Remove unwanted features
     *      - Copy images
     */
    void createFromGoldenMaster() {
        println "\n=== Creating Templates from Golden Master ==="
        println "Source: ${config.goldenMaster.sourcePath}"
        println "Target: ${config.goldenMaster.targetPath}"

        // Auto-discover languages
        def languages = discoverLanguages()

        // Lint the golden master before generating anything from it
        lintGoldenMaster(languages)

        // Get configuration
        def allFeatures = config.goldenMaster.allFeatures
        def templateStyles = config.goldenMaster.templateStyles

        println "\nProcessing ${languages.size()} language(s) × ${templateStyles.size()} style(s) = ${languages.size() * templateStyles.size()} template(s)"
        println ""

        languages.each { language ->
            println "Language: ${language}"

            def pathToGoldenMasterLang = config.goldenMaster.sourcePath + '/' + language
            def goldenMasterLangDir = new File(projectRoot, pathToGoldenMasterLang)

            // Copy common files
            def commonSource = new File(projectRoot, config.goldenMaster.sourcePath + '/common/.')
            def commonTarget = new File(projectRoot, config.goldenMaster.targetPath + language + '/common/.')

            if (commonSource.exists()) {
                commonTarget.mkdirs()
                commonSource.eachFileRecurse { file ->
                    if (file.isFile()) {
                        def relativePath = file.absolutePath - commonSource.absolutePath
                        def targetFile = new File(commonTarget, relativePath)
                        targetFile.parentFile.mkdirs()
                        targetFile.bytes = file.bytes
                    }
                }
                println "  ✓ Copied common files"
            }

            // Copy version.properties
            def versionSource = new File(goldenMasterLangDir, 'version.properties')
            def versionTarget = new File(projectRoot, config.goldenMaster.targetPath + language + '/version.properties')

            if (versionSource.exists()) {
                versionTarget.parentFile.mkdirs()
                versionTarget.write(versionSource.getText('utf-8'), 'utf-8')
                println "  ✓ Copied version.properties"
            }

            // Process each template style
            templateStyles.each { templateName, featuresWanted ->
                def featuresToRemove = allFeatures - featuresWanted
                def pathToTarget = config.goldenMaster.targetPath + '/' + language + '/asciidoc/' + templateName
                def targetSrc = new File(projectRoot, pathToTarget + '/src/.')
                targetSrc.mkdirs()

                println "  Style: ${templateName} (removing features: ${featuresToRemove ?: 'none'})"

                // Process main template file: <LANG>/<project name>.adoc
                def mainTemplateSource = new File(goldenMasterLangDir, "${config.project.name}.adoc")
                def processedCount = 0

                if (!mainTemplateSource.exists()) {
                    throw new IllegalStateException(
                        "Main document not found: ${mainTemplateSource.path}\n" +
                        "project.name must match <LANG>/<name>.adoc in the golden master")
                }

                def mainTargetFile = new File(targetSrc, mainTemplateSource.name)
                def template = mainTemplateSource.getText('utf-8')

                // Remove unwanted features
                template = removeFeatures(template, featuresToRemove)

                // Fix include paths for new flat structure
                template = adjustIncludePaths(template)

                mainTargetFile.write(template, 'utf-8')
                processedCount++

                // Process section files from <LANG>/adoc/ directory
                def adocDir = new File(goldenMasterLangDir, 'adoc')
                if (adocDir.exists() && adocDir.isDirectory()) {
                    adocDir.eachFile { sourceFile ->
                        if (sourceFile.name.endsWith('.adoc')) {
                            def targetFile = new File(targetSrc, sourceFile.name)
                            def content = sourceFile.getText('utf-8')

                            // Remove unwanted features
                            content = removeFeatures(content, featuresToRemove)

                            targetFile.write(content, 'utf-8')
                            processedCount++
                        }
                    }
                }

                println "    ✓ Processed ${processedCount} file(s)"

                // Copy images
                copyImages(language, templateName, pathToGoldenMasterLang, pathToTarget)
            }

            println ""
        }

        println "=== Template generation complete! ==="
    }
}
