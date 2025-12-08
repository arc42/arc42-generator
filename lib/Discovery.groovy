#!/usr/bin/env groovy

/**
 * Discovery.groovy - Template structure discovery and validation
 *
 * Responsibilities:
 * - Scan build/src_gen/ directory for generated templates
 * - Discover all (language, style) combinations
 * - Validate directory structure
 * - Provide metadata for conversion process
 */

class Discovery {

    def config
    def projectRoot
    def cachedTemplates = null  // Cache for discovered templates

    Discovery(config, projectRoot = new File('.')) {
        this.config = config
        this.projectRoot = projectRoot
    }

    /**
     * Scan the source generation directory and discover all templates
     *
     * Structure expected (new structure from arc42-template):
     * build/src_gen/
     *   <LANG>/
     *     asciidoc/
     *       <STYLE>/
     *         src/
     *         images/
     *     common/
     *     version.properties
     *
     * This matches the output structure created by Templates.groovy from the
     * Golden Master, which generates the traditional nested structure.
     *
     * @return List of maps with template metadata:
     *         [language: 'EN', style: 'plain', sourcePath: '...', hasImages: true, ...]
     */
    List<Map> discoverTemplates() {
        // Return cached templates if available
        if (cachedTemplates != null) {
            return cachedTemplates
        }

        def srcGenPath = new File(projectRoot, config.goldenMaster.targetPath)

        if (!srcGenPath.exists()) {
            throw new IllegalStateException(
                "Source generation directory does not exist: ${srcGenPath.absolutePath}\n" +
                "Run Templates.createFromGoldenMaster() first!"
            )
        }

        def templates = []

        // Scan for language directories (e.g., EN, DE, FR, ZH, UKR)
        def languageDirs = srcGenPath.listFiles()
            ?.findAll { it.isDirectory() && it.name ==~ /^[A-Z]{2,}$/ }
            ?.sort { it.name }

        if (!languageDirs || languageDirs.isEmpty()) {
            throw new IllegalStateException("No language directories found in ${srcGenPath.absolutePath}")
        }

        println "\n=== Discovering Templates ==="
        println "Source: ${srcGenPath.absolutePath}"
        println "Found ${languageDirs.size()} language(s): ${languageDirs*.name.join(', ')}\n"

        languageDirs.each { langDir ->
            def language = langDir.name
            def asciidocDir = new File(langDir, 'asciidoc')

            if (!asciidocDir.exists() || !asciidocDir.isDirectory()) {
                println "⚠ Warning: ${language}/asciidoc/ not found, skipping"
                return
            }

            // Scan for style directories (e.g., plain, with-help)
            def styleDirs = asciidocDir.listFiles()?.findAll { it.isDirectory() }

            if (!styleDirs || styleDirs.isEmpty()) {
                println "⚠ Warning: No styles found in ${language}/asciidoc/, skipping"
                return
            }

            styleDirs.each { styleDir ->
                def style = styleDir.name
                def srcDir = new File(styleDir, 'src')
                def imagesDir = new File(styleDir, 'images')

                // Validate structure
                if (!srcDir.exists() || !srcDir.isDirectory()) {
                    println "⚠ Warning: ${language}/${style}/src/ not found, skipping"
                    return
                }

                def adocFiles = srcDir.listFiles()?.findAll { it.name.endsWith('.adoc') }
                if (!adocFiles || adocFiles.isEmpty()) {
                    println "⚠ Warning: No .adoc files in ${language}/${style}/src/, skipping"
                    return
                }

                // Find main template file (usually arc42-template.adoc or similar)
                def mainFile = adocFiles.find { it.name.toLowerCase().contains('arc42-template') }
                if (!mainFile) {
                    // Fallback: use first .adoc file
                    mainFile = adocFiles[0]
                }

                // Get version properties
                def versionFile = new File(langDir, 'version.properties')
                def versionProps = [:]
                if (versionFile.exists()) {
                    def props = new Properties()
                    versionFile.withInputStream { props.load(it) }
                    versionProps = props
                }

                // Create template metadata
                def template = [
                    language: language,
                    style: style,
                    sourcePath: styleDir.absolutePath,
                    srcDir: srcDir.absolutePath,
                    imagesDir: imagesDir.exists() ? imagesDir.absolutePath : null,
                    hasImages: imagesDir.exists(),
                    mainFile: mainFile.absolutePath,
                    mainFileName: mainFile.name,
                    adocFileCount: adocFiles.size(),
                    commonDir: new File(langDir, 'common').absolutePath,
                    versionProperties: versionProps,
                    revnumber: versionProps.revnumber ?: 'unknown',
                ]

                templates << template
                println "✓ Discovered: ${language}/${style} (${adocFiles.size()} .adoc file(s)${imagesDir.exists() ? ', with images' : ''})"
            }
        }

        println "\n=== Discovery Complete ==="
        println "Total templates: ${templates.size()}"
        println ""

        // Cache the discovered templates
        cachedTemplates = templates
        return templates
    }

    /**
     * Clear the cached templates to force a fresh discovery on next call
     */
    void clearCache() {
        cachedTemplates = null
    }

    /**
     * Get all unique languages from discovered templates
     *
     * @return List of language codes
     */
    List<String> getLanguages() {
        return discoverTemplates()*.language.unique().sort()
    }

    /**
     * Get all unique styles from discovered templates
     *
     * @return List of style names
     */
    List<String> getStyles() {
        return discoverTemplates()*.style.unique().sort()
    }

    /**
     * Find templates by language
     *
     * @param language Language code (e.g., 'EN')
     * @return List of template metadata maps for that language
     */
    List<Map> findByLanguage(String language) {
        return discoverTemplates().findAll { it.language == language }
    }

    /**
     * Find templates by style
     *
     * @param style Style name (e.g., 'plain')
     * @return List of template metadata maps for that style
     */
    List<Map> findByStyle(String style) {
        return discoverTemplates().findAll { it.style == style }
    }

    /**
     * Find a specific template
     *
     * @param language Language code
     * @param style Style name
     * @return Template metadata map or null if not found
     */
    Map findTemplate(String language, String style) {
        return discoverTemplates().find { it.language == language && it.style == style }
    }

    /**
     * Validate that all expected templates exist
     *
     * @param expectedLanguages List of expected language codes
     * @param expectedStyles List of expected style names
     * @throws IllegalStateException if any expected templates are missing
     */
    void validate(List<String> expectedLanguages, List<String> expectedStyles) {
        def templates = discoverTemplates()
        def found = templates.collect { "${it.language}:${it.style}" }

        def missing = []
        expectedLanguages.each { lang ->
            expectedStyles.each { style ->
                def key = "${lang}:${style}"
                if (!(key in found)) {
                    missing << key
                }
            }
        }

        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                "Missing expected templates: ${missing.join(', ')}\n" +
                "Found: ${found.sort().join(', ')}"
            )
        }

        println "✓ Validation successful: All expected templates exist"
    }
}
