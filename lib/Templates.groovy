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

    /** Machine-readable version date (YYYY-MM) next to the localised revdate; manifest.json carries it as isoDate */
    static final String ISO_DATE_PROPERTY = 'revdate-iso'

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
     * Looks for directories matching pattern: /^[A-Z]{2,}(-[A-Z]{2,})?$/ (e.g. EN, UKR, ZH-TW)
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
            ?.findAll { it.isDirectory() && it.name ==~ /^[A-Z]{2,}(-[A-Z]{2,})?$/ }
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

    /**
     * Remove the features a template style does not want. For every feature:
     * - everything between ifdef::<prefix><feature>[] and its endif::<prefix><feature>[] is dropped,
     *   as Asciidoctor does when the attribute is not set; the two directive lines stay as empty
     *   lines, so that the text before and after the block stays separated
     * - a [role="<prefix><feature>"] sidebar outside such a conditional (a lint error) is removed as well
     *
     * @param template AsciiDoc source
     * @param featuresToRemove feature names without prefix, e.g. ['help', 'example']
     */
    String removeFeatures(String template, List<String> featuresToRemove) {
        def result = template
        def prefix = config.project.featurePrefix

        featuresToRemove.each { feature ->
            def name = "${prefix}${feature}".toString()
            result = removeConditionalBlocks(result, name)
            def marker = Pattern.quote(name)
            result = result.replaceAll(/(?ms)\[role="${marker}"\][ \r\n]+[*]{4}.*?[*]{4}/, '')
        }

        return result
    }

    /**
     * Drop the lines between ifdef::<attribute>[] and the matching endif::<attribute>[] (nested
     * conditionals of the same attribute are counted). A conditional that is never closed (a lint
     * error) loses only its directive line, the text after it is kept.
     */
    private String removeConditionalBlocks(String text, String attribute) {
        def open = "ifdef::${attribute}[]".toString()
        def close = "endif::${attribute}[]".toString()
        def lines = text.split('\n', -1) as List<String>
        // an emptied directive line keeps its carriage return, so that CRLF files stay CRLF files
        def emptied = { String line -> line.endsWith('\r') ? '\r' : '' }

        def kept = []
        int index = 0
        while (index < lines.size()) {
            def trimmed = lines[index].trim()
            if (trimmed == open) {
                int depth = 1
                int end = -1
                for (int i = index + 1; i < lines.size(); i++) {
                    def candidate = lines[i].trim()
                    if (candidate == open) {
                        depth++
                    } else if (candidate == close && --depth == 0) {
                        end = i
                        break
                    }
                }
                kept << emptied(lines[index])
                if (end > index) {
                    kept << emptied(lines[end])
                    index = end
                }
            } else if (trimmed == close) {
                kept << emptied(lines[index])
            } else {
                kept << lines[index]
            }
            index++
        }
        return kept.join('\n')
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
            // Only copy the logo, in all its formats (arc42-logo.png and arc42-logo.svg):
            // the template may use the SVG, with the PNG as fallback for DOCX and LaTeX
            String logo = config.project.logo
            def logoBase = logo.contains('.') ? logo.substring(0, logo.lastIndexOf('.')) : logo
            def logoSources = imageFiles?.findAll { it.name == logo || it.name.startsWith(logoBase + '.') } ?: []

            if (logoSources) {
                logoSources.each { source ->
                    new File(imagesTarget, source.name).bytes = source.bytes
                    println "  ✓ Copied ${source.name}"
                }
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
     * - error:   a [role="<prefix><feature>"] line that is not between an ifdef and its endif
     * - error:   an odd number of sidebar delimiters (****) in a file: a block that is never closed
     * - error:   an image::target[] or image:target[] whose target is not in <LANG>/images/
     * - error:   a language uses ifdef::<prefix><feature>[] for a feature that a style keeps,
     *            but none of its documents sets the attribute :<prefix><feature>: (the style
     *            would silently lose every block of that feature)
     * - error:   version.properties is missing or lacks revnumber, revdate or revremark
     * - error:   revdate-iso in version.properties is not a month in the form YYYY-MM
     * - warning: version.properties has no revdate-iso (the download page then shows the localised revdate)
     * - warning: the chapter files (adoc/*.adoc) differ from those of the EN language
     * - warning: a chapter has a different number of feature blocks than its EN counterpart
     * - warning: a chapter file (adoc/*.adoc) that no document of the language includes: it is in no
     *            single-file or multi-page output, only in the AsciiDoc ZIP
     * - warning: a file in <LANG>/images/ that no document references (it is shipped in every ZIP
     *            with an images folder)
     *
     * Lines inside //// comment blocks are not rendered and therefore not checked.
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

            def scans = []
            def mainDocument = new File(languageDir, "${config.project.name}.adoc")
            if (mainDocument.isFile()) {
                def scan = scanDocument(mainDocument)
                scans << scan
                problems.addAll(lintDocument(scan, language, language + '/' + mainDocument.name, imagesDir))
            }

            def chapters = scanChapters(languageDir)
            chapters.each { name, scan ->
                scans << scan
                problems.addAll(lintDocument(scan, language, language + '/adoc/' + name, imagesDir))
            }

            problems.addAll(lintFeatureAttributes(scans, language))

            problems.addAll(lintUnusedFiles(scans, chapters.keySet(), language, imagesDir))

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

        // In GitHub Actions: annotations on the lines of the pull request and a report in the job summary
        if (System.getenv('GITHUB_ACTIONS') == 'true') {
            problems.each { println githubAnnotation(it) }
            def summary = System.getenv('GITHUB_STEP_SUMMARY')
            if (summary) {
                new File(summary).append(lintReport(problems, languages ?: discoverLanguages()), 'utf-8')
            }
        }

        if (failed) {
            def details = errors.collect { "  ✗ ${formatProblem(it)}" }.join('\n')
            throw new IllegalStateException("Golden master validation failed: ${errors.size()} error(s)\n${details}")
        }
    }

    /**
     * A problem as GitHub Actions workflow command, shown as annotation on the file and line in a
     * pull request. A problem of a directory (e.g. a whole language) carries its location in the message.
     */
    String githubAnnotation(Map problem) {
        def escapeData = { String s -> s.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A') }
        def escapeProperty = { String s -> escapeData(s).replace(':', '%3A').replace(',', '%2C') }

        def sourceRoot = new File(projectRoot, config.goldenMaster.sourcePath)
        def properties = []
        def message = problem.message
        if (new File(sourceRoot, problem.file).isFile()) {
            properties << "file=${escapeProperty(problem.file)}"
            if (problem.line != null) properties << "line=${problem.line}"
        } else {
            message = "${problem.file}: ${message}"
        }
        properties << 'title=Golden master validation'
        return "::${problem.severity} ${properties.join(',')}::${escapeData(message)}".toString()
    }

    /** Markdown report of the lint problems: counts per language, then every problem */
    String lintReport(List<Map> problems, List<String> languages) {
        def errors = problems.count { it.severity == 'error' }
        def warnings = problems.count { it.severity == 'warning' }
        def cell = { String s -> s.replace('|', '\\|').replace('\n', ' ') }

        def report = new StringBuilder("## Golden master validation\n\n")
        if (!problems) {
            return report.append("✅ No problems found in ${languages.size()} language(s): ${languages.join(', ')}.\n").toString()
        }
        report << "${errors ? '❌' : '⚠️'} ${errors} error(s), ${warnings} warning(s). " <<
            "Errors fail the build; warnings point to differences from ${REFERENCE_LANGUAGE}.\n\n"

        report << "| Language | Errors | Warnings |\n|---|---|---|\n"
        languages.each { language ->
            def own = problems.findAll { it.language == language }
            report << "| ${language} | ${own.count { it.severity == 'error' }} | ${own.count { it.severity == 'warning' }} |\n"
        }

        report << "\n| Severity | Location | Problem |\n|---|---|---|\n"
        problems.sort { a, b -> (a.severity <=> b.severity) ?: (a.file <=> b.file) ?: ((a.line ?: 0) <=> (b.line ?: 0)) }.each { problem ->
            def severity = problem.severity == 'error' ? '❌ error' : '⚠️ warning'
            def location = problem.line != null ? "${problem.file}:${problem.line}" : problem.file
            report << "| ${severity} | `${location}` | ${cell(problem.message)} |\n"
        }
        return report.toString()
    }

    /** "<file>:<line>: <message>", without ":<line>" when the problem has no line */
    String formatProblem(Map problem) {
        def location = problem.line != null ? "${problem.file}:${problem.line}" : problem.file
        return "${location}: ${problem.message}"
    }

    /**
     * One pass over an AsciiDoc file: the line numbers of the ifdef, endif and role
     * marker lines per feature, the image targets with their lines, the include targets and
     * the lines of the sidebar delimiters. //// comment blocks are skipped.
     */
    private Map scanDocument(File file) {
        def prefix = config.project.featurePrefix
        def markers = [:]       // feature -> [ifdef: [lines], endif: [lines], role: [lines], define: [lines]]
        def markerLines = [:]   // marker line (trimmed) -> [feature, kind]
        def definitions = [:]   // attribute definition pattern (':<prefix><feature>:' with optional value) -> feature
        config.goldenMaster.allFeatures.each { feature ->
            markers[feature] = [ifdef: [], endif: [], role: [], define: []]
            definitions[Pattern.compile("^:${Pattern.quote(prefix + feature)}:(\\s.*)?\$")] = feature
            markerLines["ifdef::${prefix}${feature}[]".toString()] = [feature, 'ifdef']
            markerLines["endif::${prefix}${feature}[]".toString()] = [feature, 'endif']
            markerLines["[role=\"${prefix}${feature}\"]".toString()] = [feature, 'role']
        }

        def images = []
        def includes = []       // targets of include::target[] lines
        def sidebars = []       // lines of the sidebar delimiters (****)
        def inComment = false   // inside a //// comment block
        def lines = file.readLines('utf-8')
        lines.eachWithIndex { line, index ->
            def trimmed = line.trim()
            if (trimmed ==~ /\/{4,}/) {
                inComment = !inComment
                return
            }
            if (inComment) {
                return   // comment blocks are not rendered
            }
            def marker = markerLines[trimmed]
            def defined = definitions.find { pattern, feature -> pattern.matcher(trimmed).matches() }
            if (marker) {
                markers[marker[0]][marker[1]] << index + 1
            } else if (defined) {
                markers[defined.value].define << index + 1
            } else if (trimmed == '****') {
                sidebars << index + 1
            } else if (!trimmed.startsWith('//')) {   // comment lines are not rendered
                def include = (trimmed =~ /^include::([^\[]+)\[/)
                if (include.find()) {
                    includes << include.group(1)
                }
                def matcher = IMAGE_MACRO.matcher(line)
                while (matcher.find()) {
                    images << [line: index + 1, target: matcher.group(1)]
                }
            }
        }

        return [lineCount: lines.size(), markers: markers, images: images, includes: includes, sidebars: sidebars]
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
            } else if (found.ifdef.size() == found.endif.size()) {
                // the counts match: every role marker must also sit between an ifdef and its endif,
                // otherwise the block is not removed with its conditional
                def ranges = [found.ifdef.sort(false), found.endif.sort(false)].transpose()
                found.role.findAll { line -> !ranges.any { line > it[0] && line < it[1] } }.each { line ->
                    error(line, "[role=\"${name}\"] is not between ifdef::${name}[] and endif::${name}[]")
                }
            }
        }

        if (scan.sidebars.size() % 2 != 0) {
            error(scan.sidebars.last(), "${scan.sidebars.size()} sidebar delimiter(s) (****): a block is never closed")
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
     * A feature that a style keeps and that the language uses in ifdef::<prefix><feature>[]
     * must be set as attribute (usually in adoc/config.adoc) by one of the language's documents
     */
    private List<Map> lintFeatureAttributes(List<Map> scans, String language) {
        def prefix = config.project.featurePrefix
        def problems = []
        config.goldenMaster.templateStyles.each { style, features ->
            features.each { feature ->
                def used = scans.any { it.markers[feature]?.ifdef }
                def defined = scans.any { it.markers[feature]?.define }
                if (used && !defined && !problems.any { it.feature == feature }) {
                    def styles = config.goldenMaster.templateStyles.findAll { it.value.contains(feature) }.keySet()
                    problems << [severity: 'error', language: language, file: language, line: null, feature: feature,
                        message: "ifdef::${prefix}${feature}[] is used, but no document sets :${prefix}${feature}: " +
                            "(e.g. in adoc/config.adoc); the ${styles.join(', ')} style(s) would lose every ${feature} block"]
                }
            }
        }
        return problems
    }

    /**
     * Files of a language that nothing uses: chapter files that no document includes (they are in no
     * rendered output) and images that no document references (they are shipped in every ZIP with an
     * images folder). The logo is always used: it is the one image of the plain style.
     */
    private List<Map> lintUnusedFiles(List<Map> scans, Collection<String> chapterNames, String language, File imagesDir) {
        def problems = []
        def warning = { String path, String message ->
            problems << [severity: 'warning', language: language, file: path, line: null, message: message]
        }

        def included = scans.collectMany { it.includes }.collect { it.tokenize('/').last() } as Set
        (chapterNames - included).each { name ->
            warning("${language}/adoc/${name}".toString(),
                "no document includes this chapter file: it is in none of the rendered outputs")
        }

        def targets = scans.collectMany { it.images*.target }
        // an attribute reference could name any image: nothing can be said then
        if (!targets.any { it.contains('{') }) {
            def referenced = targets.collect { it.tokenize('/').last() } as Set
            // a PNG next to a referenced SVG is its fallback for DOCX and LaTeX, so it is used as well
            def isFallback = { String name ->
                name.toLowerCase().endsWith('.png') && (name[0..-5] + '.svg') in referenced
            }
            imagesDir.listFiles()?.findAll { it.isFile() && !it.name.startsWith('.') }?.sort { it.name }?.each { image ->
                if (image.name != config.project.logo && !(image.name in referenced) && !isFallback(image.name)) {
                    warning("${language}/images/${image.name}".toString(),
                        "no document references this image, yet it is shipped in every ZIP with an images folder")
                }
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

    /** version.properties must exist and define all VERSION_PROPERTIES; revdate-iso should be there, as YYYY-MM */
    private List<Map> lintVersionProperties(File languageDir, String language) {
        def file = new File(languageDir, 'version.properties')
        def path = language + '/version.properties'
        def error = { String message ->
            [severity: 'error', language: language, file: path, line: null, message: message]
        }
        def warning = { String message ->
            [severity: 'warning', language: language, file: path, line: null, message: message]
        }

        if (!file.isFile()) {
            return [error('version.properties is missing')]
        }
        def properties = new Properties()
        file.withReader('utf-8') { properties.load(it) }
        def missing = VERSION_PROPERTIES.findAll { !properties.getProperty(it)?.trim() }
        def problems = missing ? [error("version.properties lacks ${missing.join(', ')}")] : []
        def isoDate = properties.getProperty(ISO_DATE_PROPERTY)?.trim()
        if (!isoDate) {
            problems << warning("version.properties has no ${ISO_DATE_PROPERTY} (the month of revdate as YYYY-MM, e.g. 2025-07)")
        } else if (!(isoDate ==~ /\d{4}-(0[1-9]|1[0-2])/)) {
            problems << error("${ISO_DATE_PROPERTY} must be a month as YYYY-MM, found '${isoDate}'")
        }
        return problems
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
