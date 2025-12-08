#!/usr/bin/env groovy

/**
 * Templates.groovy - Golden Master processing and template generation
 *
 * Responsibilities:
 * - Auto-discover available languages from arc42-template/ directory
 * - Process Golden Master templates with feature flag removal
 * - Generate template variants (plain, with-help, etc.)
 * - Copy images and common files
 */

class Templates {

    def config
    def projectRoot

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

        // Remove role-based feature blocks: [role="arc42<feature>"] **** ... ****
        featuresToRemove.each { feature ->
            result = result.replaceAll(/(?ms)\[role="arc42${feature}"\][ \r\n]+[*]{4}.*?[*]{4}/, '')
        }

        // Remove ifdef/endif blocks for help feature
        if ('help' in featuresToRemove) {
            result = result.replaceAll(/(?ms)ifdef::arc42help\[\]/, '')
            result = result.replaceAll(/(?ms)endif::arc42help\[\]/, '')
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
            def logoSource = imageFiles?.find { it.name == 'arc42-logo.png' }

            if (logoSource) {
                def logoTarget = new File(imagesTarget, 'arc42-logo.png')
                logoTarget.bytes = logoSource.bytes
                println "  ✓ Copied arc42-logo.png"
            } else {
                println "  ⚠ Warning: arc42-logo.png not found"
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

    /**
     * Main method: Create templates from Golden Master
     *
     * New Structure (arc42-template):
     * - <LANG>/arc42-template.adoc (main template file)
     * - <LANG>/adoc/ (individual sections)
     * - <LANG>/images/ (images)
     * - <LANG>/version.properties
     *
     * Process:
     * 1. Auto-discover languages
     * 2. For each language:
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
                versionTarget.bytes = versionSource.bytes
                println "  ✓ Copied version.properties"
            }

            // Process each template style
            templateStyles.each { templateName, featuresWanted ->
                def featuresToRemove = allFeatures - featuresWanted
                def pathToTarget = config.goldenMaster.targetPath + '/' + language + '/asciidoc/' + templateName
                def targetSrc = new File(projectRoot, pathToTarget + '/src/.')
                targetSrc.mkdirs()

                println "  Style: ${templateName} (removing features: ${featuresToRemove ?: 'none'})"

                // Process main template file: <LANG>/arc42-template.adoc
                def mainTemplateSource = new File(goldenMasterLangDir, 'arc42-template.adoc')
                def processedCount = 0

                if (mainTemplateSource.exists()) {
                    def targetFile = new File(targetSrc, mainTemplateSource.name)
                    def template = mainTemplateSource.getText('utf-8')

                    // Remove unwanted features
                    template = removeFeatures(template, featuresToRemove)

                    // Fix include paths for new flat structure
                    template = adjustIncludePaths(template)

                    targetFile.write(template, 'utf-8')
                    processedCount++
                }

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
