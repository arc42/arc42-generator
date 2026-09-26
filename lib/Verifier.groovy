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
}
