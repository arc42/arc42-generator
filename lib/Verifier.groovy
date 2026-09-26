#!/usr/bin/env groovy

/**
 * Verifier.groovy - Orchestration of the verify phase
 *
 * Responsibilities:
 * - Derive the expected matrix (languages x styles x formats) from the config and the golden master
 * - Read each distribution ZIP into memory and build the rule context
 * - Provide reference data: version.properties, help sentinel, reference heading counts
 * - Run Checks and collect suite results; the Report class renders them
 */
class Verifier {

    def config
    def projectRoot
    def checks
    private Map<String, Map<Integer, Integer>> referenceCache = [:]

    Verifier(config, projectRoot, checks) {
        this.config = config
        this.projectRoot = projectRoot
        this.checks = checks
    }

    String getProjectName() { return config.project.name }
    String getReferenceLanguage() { return (config.verify?.referenceLanguage ?: 'EN').toString() }
    int getChapterCount() { return ((config.verify?.chapterCount ?: 12) as int) }
    File getGoldenMasterDir() { return new File(projectRoot, config.goldenMaster.sourcePath.toString()) }
    File getDistDir() { return new File(projectRoot, config.distribution.targetPath.toString()) }

    /** Language directories of the golden master, same pattern as Templates.discoverLanguages */
    List<String> languages() {
        return (goldenMasterDir.listFiles()?.findAll { it.isDirectory() && it.name ==~ /^[A-Z]{2,}$/ }*.name ?: []).sort()
    }

    List<String> styles() {
        return (config.goldenMaster.templateStyles.keySet() as List).sort()
    }

    /** version.properties of a language as a map; empty when missing */
    Map versionProps(String lang) {
        def file = new File(goldenMasterDir, "${lang}/version.properties")
        if (!file.exists()) return [:]
        def props = new Properties()
        file.withInputStream { props.load(new InputStreamReader(it, 'UTF-8')) }
        return props.collectEntries { k, v -> [(k.toString()): v.toString().trim()] }
    }

    /**
     * First real sentence of the first help block of chapter 01: skips block titles (.Contents),
     * bullets and blank lines; needs at least 20 characters; capped at 120.
     */
    String helpSentinel(String lang) {
        def adocDir = new File(goldenMasterDir, "${lang}/adoc")
        def chapter = adocDir.listFiles()?.findAll { it.name ==~ /^01_.*\.adoc$/ }?.sort()?.find { true }
        if (!chapter) return null
        def prefix = config.project.featurePrefix
        def m = (~/(?s)\[role="${java.util.regex.Pattern.quote(prefix + 'help')}"\]\s*\*{4}\s*\n(.*?)\n\*{4}/).matcher(chapter.getText('UTF-8'))
        if (!m.find()) return null
        def line = m.group(1).readLines().collect { it.trim() }.find { it && !it.startsWith('.') && !it.startsWith('*') && !it.startsWith('//') && !it.startsWith('image:') && it.length() >= 20 }
        if (!line) return null
        return checks.normalize(line.take(120))
    }

    String styleShort(String style) { return style.replaceAll("[^a-zA-Z]", "") }

    File zipFile(String lang, String style, String format) {
        return new File(distDir, "${projectName}-${lang}-${styleShort(style)}-${format}.zip")
    }

    /** Heading counts of the reference language for the same style and format; null when unavailable */
    Map<Integer, Integer> referenceCounts(String style, String format) {
        def key = "${style}/${format}".toString()
        if (!referenceCache.containsKey(key)) {
            def zip = zipFile(referenceLanguage, style, format)
            referenceCache[key] = zip.exists() ? checks.headingCounts(baseContext(referenceLanguage, style, format, checks.unzip(zip.bytes))) : null
        }
        return referenceCache[key]
    }

    private Map baseContext(String lang, String style, String format, Map entries) {
        return [language: lang, style: style, format: format, projectName: projectName,
                formatConfig: config.formats[format] ?: [:], entries: entries, zipFile: zipFile(lang, style, format),
                revnumber: versionProps(lang).revnumber, helpSentinel: helpSentinel(lang),
                chapterCount: chapterCount, referenceCounts: null]
    }

    /** Full rule context; entries is null when the ZIP is missing or unreadable */
    Map buildContext(String lang, String style, String format) {
        def zip = zipFile(lang, style, format)
        Map entries = null
        if (zip.exists()) {
            try { entries = checks.unzip(zip.bytes) } catch (Exception e) { entries = null }
        }
        def ctx = baseContext(lang, style, format, entries)
        if (lang != referenceLanguage) ctx.referenceCounts = referenceCounts(style, format)
        return ctx
    }

    Map verifyCase(String lang, String style, String format) {
        long start = System.currentTimeMillis()
        def ctx = buildContext(lang, style, format)
        def findings
        try {
            findings = checks.checkAll(ctx)
        } catch (Exception e) {
            findings = [[ruleId: 'verifier.exception', severity: 'error', message: "check crashed: ${e}".toString(), examples: [[location: ctx.zipFile.name, text: e.stackTrace.take(3).join(' | ')]]]]
        }
        def props = versionProps(lang)
        return [language: lang, style: style, format: format, revnumber: props.revnumber, revdate: props.revdate,
                durationMs: System.currentTimeMillis() - start, findings: findings, status: statusOf(findings)]
    }

    static String statusOf(List findings) {
        if (!findings) return 'pass'
        return findings.any { it.severity == 'error' } ? 'fail' : 'warn'
    }

    /** One suite per format, cases ordered by language then style */
    List<Map> verifySuites(List<String> formats) {
        def langs = languages()
        def sts = styles()
        return formats.collect { String format ->
            def cases = []
            langs.each { lang -> sts.each { style -> cases << verifyCase(lang, style, format) } }
            [name: format, cases: cases]
        }
    }

    // ---- source suite ----------------------------------------------------------

    private static final java.util.regex.Pattern INCLUDE = ~/^include::([^\[]+)\[/
    private static final java.util.regex.Pattern IMAGE = ~/image::?([^\[\s]+)\[/

    Map verifySourceCase(String lang) {
        long start = System.currentTimeMillis()
        def findings = []
        def langDir = new File(goldenMasterDir, lang)
        def props = versionProps(lang)

        if (!props.revnumber || !props.revdate) {
            findings << checks.finding('src.versionProperties', "${lang}/version.properties missing revnumber or revdate", [[location: "${lang}/version.properties".toString(), text: props.toString()]])
        }

        def mainFile = new File(langDir, "${projectName}.adoc")
        if (!mainFile.exists()) {
            findings << checks.finding('src.mainFile', "${lang}/${projectName}.adoc not found", [[location: "${lang}/${projectName}.adoc".toString(), text: 'missing']])
        }

        def adocDir = new File(langDir, 'adoc')
        def chapterFiles = adocDir.listFiles()?.findAll { it.name ==~ /^\d\d_.*\.adoc$/ }?.sort() ?: []
        if (chapterFiles.size() != chapterCount) {
            findings << checks.finding('src.chapters', "${lang}/adoc has ${chapterFiles.size()} chapter files, expected ${chapterCount}", chapterFiles.take(3).collect { [location: "${lang}/adoc/${it.name}".toString(), text: 'present'] })
        }

        def missingIncludes = [], missingImages = []
        def sources = (mainFile.exists() ? [mainFile] : []) + (adocDir.listFiles()?.findAll { it.name.endsWith('.adoc') }?.sort() ?: [])
        sources.each { File src ->
            src.getText('UTF-8').readLines().eachWithIndex { String line, int i ->
                def im = INCLUDE.matcher(line.trim())
                if (im.find()) {
                    def target = new File(src.parentFile, im.group(1))
                    if (!target.exists()) missingIncludes << [location: "${lang}/${src.name}:${i + 1}".toString(), text: im.group(1)]
                }
                def gm = IMAGE.matcher(line)
                while (gm.find()) {
                    def ref = gm.group(1)
                    if (ref.contains('{') || ref ==~ /(?i)^https?:.*/) continue   // attribute references and URLs are not checked
                    def target = new File(langDir, "images/${ref}")
                    if (!target.exists()) missingImages << [location: "${lang}/${src.name}:${i + 1}".toString(), text: ref]
                }
            }
        }
        if (missingIncludes) findings << checks.finding('src.includes', "${missingIncludes.size()} include target(s) missing", missingIncludes)
        if (missingImages) findings << checks.finding('src.images', "${missingImages.size()} image(s) missing in ${lang}/images", missingImages)

        findings = findings.findAll { it != null }
        return [language: lang, style: '-', format: 'source', revnumber: props.revnumber, revdate: props.revdate,
                durationMs: System.currentTimeMillis() - start, findings: findings, status: statusOf(findings)]
    }

    Map verifySource() {
        return [name: 'source', cases: languages().collect { verifySourceCase(it) }]
    }

    /** Source suite first, then one suite per format */
    List<Map> verifyAll(List<String> formats) {
        return [verifySource()] + verifySuites(formats)
    }
}
