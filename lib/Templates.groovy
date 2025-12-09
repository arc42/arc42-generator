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
     * Looks for directories matching pattern: /^[A-Z]{2}$/
     *
     * @return List of language codes (e.g., ['DE', 'EN', 'FR', 'CZ'])
     */
    List<String> discoverLanguages() {
        def sourcePath = new File(projectRoot, config.goldenMaster.sourcePath)

        if (!sourcePath.exists()) {
            throw new IllegalStateException("Golden Master source path does not exist: ${sourcePath.absolutePath}")
        }

        def languages = sourcePath.listFiles()
            ?.findAll { it.isDirectory() && it.name ==~ /^[A-Z]{2}$/ }
            *.name
            .sort()

        if (!languages || languages.isEmpty()) {
            throw new IllegalStateException("No language directories found in ${sourcePath.absolutePath}")
        }

        println "✓ Discovered languages: ${languages.join(', ')}"
        return languages
    }

    /**
     * Remove feature flags from template content using regex patterns
     * Ported from build.gradle:88-94
     *
     * @param template The template content
     * @param featuresToRemove List of features to remove (e.g., ['help', 'example'])
     * @return Modified template with features removed
     */
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
     * Copy images based on template style
     * - plain: only arc42-logo.png
     * - with-help: logo + language-specific example images
     *
     * @param language Language code (e.g., 'EN')
     * @param templateName Template style (e.g., 'plain', 'with-help')
     * @param targetPath Target directory path
     */
    void copyImages(String language, String templateName, String targetPath) {
        def pathToDEGoldenMaster = config.goldenMaster.sourcePath + '/DE/asciidoc/'
        def imagesSource = new File(projectRoot, pathToDEGoldenMaster + '/../../images')
        def imagesTarget = new File(projectRoot, targetPath + '/images')

        imagesTarget.mkdirs()

        if (templateName == 'plain') {
            // Only copy the logo
            def logoSource = new File(imagesSource, 'arc42-logo.png')
            def logoTarget = new File(imagesTarget, 'arc42-logo.png')

            if (logoSource.exists()) {
                logoTarget.bytes = logoSource.bytes
                println "  ✓ Copied arc42-logo.png"
            } else {
                println "  ⚠ Warning: arc42-logo.png not found at ${logoSource.absolutePath}"
            }
        } else {
            // Copy logo plus example images
            def imageFiles = imagesSource.listFiles()?.findAll { file ->
                file.name == 'arc42-logo.png' ||
                file.name.contains("-${language}.") ||
                file.name.contains("-EN.")
            }

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
     * Process:
     * 1. Auto-discover languages
     * 2. For each language:
     *    - Copy common files
     *    - Copy version.properties
     *    - For each template style:
     *      - Process .adoc and .config files
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

            def pathToGoldenMaster = config.goldenMaster.sourcePath + '/' + language + '/asciidoc/'

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
            def versionSource = new File(projectRoot, config.goldenMaster.sourcePath + language + '/version.properties')
            def versionTarget = new File(projectRoot, config.goldenMaster.targetPath + language + '/version.properties')

            if (versionSource.exists()) {
                versionTarget.parentFile.mkdirs()
                versionTarget.bytes = versionSource.bytes
                println "  ✓ Copied version.properties"
            }

            // Process each template style
            def sourceSub = new File(projectRoot, pathToGoldenMaster + 'src/.')
            def sourceMain = new File(projectRoot, pathToGoldenMaster)

            templateStyles.each { templateName, featuresWanted ->
                def featuresToRemove = allFeatures - featuresWanted
                def pathToTarget = config.goldenMaster.targetPath + '/' + language + '/asciidoc/' + templateName
                def targetSrc = new File(projectRoot, pathToTarget + '/src/.')
                targetSrc.mkdirs()

                println "  Style: ${templateName} (removing features: ${featuresToRemove ?: 'none'})"

                // Process files from both sourceMain and sourceSub
                def processedCount = 0
                [sourceMain, sourceSub].each { source ->
                    source.eachFile { sourceFile ->
                        if (sourceFile.name.endsWith('.adoc') || sourceFile.name.endsWith('.config')) {
                            def targetFile = new File(targetSrc, sourceFile.name)
                            def template = sourceFile.getText('utf-8')

                            // Remove unwanted features
                            template = removeFeatures(template, featuresToRemove)

                            targetFile.write(template, 'utf-8')
                            processedCount++
                        }
                    }
                }

                println "    ✓ Processed ${processedCount} file(s)"

                // Copy images
                copyImages(language, templateName, pathToTarget)
            }

            println ""
        }

        println "=== Template generation complete! ==="
    }
}
