#!/usr/bin/env groovy

import java.security.MessageDigest
import java.util.regex.Pattern
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler

/**
 * OutputCheck.groovy - Checks of the converted output and the distribution ZIPs
 *
 * The build must not distort, change or lose template content. These checks compare what was
 * built with what the golden master and the other formats say should be there, for every
 * language, style and format:
 *
 * - present:   every combination of language, style and format has output
 * - encoding:  every text file is valid UTF-8
 * - markers:   no AsciiDoc directive or unresolved attribute survives in a rendered file
 *              (ifdef::, endif::, [role=", "Unresolved directive", {revnumber}, ...)
 * - headings:  the numbers of chapter and section headings agree with the DocBook rendering, in every format
 * - features:  a style contains the feature blocks (help texts) of the golden master it should, and no others
 * - images:    every image the template references is referenced by the output and shipped with it
 *              (or embedded, for DOCX, EPUB and PDF)
 * - xml:       DocBook and the XML parts of DOCX and EPUB are well-formed
 * - pdf:       every PDF has pages and extractable text (pdfinfo and pdftotext of poppler-utils)
 * - zips:      manifest.json lists exactly one ZIP per language, style and format; every ZIP has the size
 *              and SHA-256 of the manifest, can be read to the end and holds the files of its output directory
 *
 * Every problem is a map: severity ('error' or 'warning'), check (the names above), location
 * (path relative to the project root) and message.
 */
class OutputCheck {

    def config
    File projectRoot

    /** Directory of the converted output, relative to the project root (as in Converter) */
    String buildDir = 'build'

    final List<Map> problems = []

    /** What was looked at, for the summary: a check that found nothing to check must not look like success */
    final Map<String, Integer> checked = [outputs: 0, textFiles: 0, pdfs: 0, zips: 0].withDefault { 0 }

    /** File extension of the main output file(s) of a format; a format not listed here gets the generic checks only */
    static final Map<String, String> EXTENSIONS = [
        html: 'html', pdf: 'pdf', docbook: 'xml', asciidoc: 'adoc', epub: 'epub', docx: 'docx',
        rst: 'rst', textile: 'textile', latex: 'tex',
        markdown: 'md', markdownStrict: 'md', gitHubMarkdown: 'md',
        markdownMP: 'md', markdownMPStrict: 'md', gitHubMarkdownMP: 'md',
    ]
    static final List<String> MARKDOWN_FORMATS = ['markdown', 'markdownStrict', 'gitHubMarkdown']
    static final List<String> MULTI_PAGE_FORMATS = ['markdownMP', 'markdownMPStrict', 'gitHubMarkdownMP']
    static final List<String> CONTAINER_FORMATS = ['docx', 'epub']
    /** Formats that get the PNG of the same name instead of an SVG (as Converter.RASTER_IMAGE_FORMATS) */
    static final List<String> RASTER_IMAGE_FORMATS = ['docx', 'latex']

    /** AsciiDoc that must never reach a rendered file */
    static final Map<String, Pattern> LEFTOVER_MARKERS = [
        'a conditional directive (ifdef::, ifndef::, endif::, ifeval::)': ~/\b(?:ifdef|ifndef|endif|ifeval)::/,
        'a role marker ([role=")'                                       : ~/\[role=(?:"|&quot;)/,
        'an unresolved directive'                                       : ~/Unresolved directive/,
        'an unresolved attribute reference'                             : ~/\{(?:revnumber|revdate|revremark|imagesdir|[a-z]+-caption)\}/,
    ]

    /** A reference to a file in the images folder, in any markup: src="images/x.png", ](images/x.png), !images/x.png!, ... */
    static final Pattern IMAGE_REFERENCE = ~/(?i)\bimages\/([A-Za-z0-9._~%+-]+\.(?:png|jpe?g|gif|svg|webp|bmp))/
    /** image::target[...] and image:target[...] in AsciiDoc (as in Templates.IMAGE_MACRO) */
    static final Pattern IMAGE_MACRO = ~/image::?([^\[\s:][^\[\s]*)\[/
    static final Pattern IMAGE_FILE = ~/(?i).+\.(?:png|jpe?g|gif|svg|webp|bmp)/

    OutputCheck(config, projectRoot = new File('.')) {
        this.config = config
        this.projectRoot = projectRoot
    }

    // ========================================================================
    // Entry point
    // ========================================================================

    /**
     * Run all checks.
     *
     * @param onlyFormats check these formats only (a build with --format=...); null: all formats of the config
     * @return the problems found (also in the problems property)
     */
    List<Map> checkAll(List<String> onlyFormats = null) {
        def formats = (config.formats.keySet() as List<String>).findAll { !onlyFormats || it in onlyFormats }
        def styles = config.goldenMaster.templateStyles.keySet() as List<String>
        def languages = builtLanguages()
        if (!languages) {
            error('present', relative(sourceRoot()), 'no generated templates: nothing to check (run the templates and convert phases first)')
            return problems
        }

        languages.each { language ->
            def styleData = [:]
            styles.each { style ->
                def data = [:]
                formats.each { format ->
                    def dir = outputDir(language, style, format)
                    if (!dir.isDirectory() || !dir.list()) {
                        error('present', relative(dir), "no ${format} output for ${language}/${style}")
                        return
                    }
                    checked.outputs++
                    data[format] = checkOutput(language, style, format, dir)
                }
                checkHeadings(language, style, data)
                styleData[style] = data
            }
            checkFeatures(language, styles, formats, styleData)
        }

        checkZips(languages, styles, formats)
        return problems
    }

    List<Map> getErrors() { problems.findAll { it.severity == 'error' } }
    List<Map> getWarnings() { problems.findAll { it.severity == 'warning' } }

    // ========================================================================
    // One output directory: encoding, markers, images, XML, PDF
    // ========================================================================

    /** Check one output directory and return what the cross-format checks need: [headings: [l1, l2], size, sidebars] */
    private Map checkOutput(String language, String style, String format, File dir) {
        def extension = EXTENSIONS[format]
        def images = referencedImages(language, style)
        def data = [size: directorySize(dir, format)]

        if (format == 'asciidoc') {
            checkAsciidoc(language, style, dir)
            return data
        }
        if (format == 'pdf') {
            mainFiles(dir, 'pdf').each { checkPdf(it, images, data) }
            return data
        }
        if (format in CONTAINER_FORMATS) {
            mainFiles(dir, extension).each { checkContainer(it, format, images, data) }
            return data
        }
        if (!extension) {
            return data   // a format this class does not know
        }

        def files = mainFiles(dir, extension)
        if (!files) {
            error('present', relative(dir), "no .${extension} file in the ${format} output")
            return data
        }
        def referenced = new TreeSet<String>()
        int level1 = 0
        int level2 = 0
        files.each { file ->
            def text = readText(file)
            if (text == null) return
            checked.textFiles++
            checkMarkers(text, relative(file))
            def matcher = IMAGE_REFERENCE.matcher(text)
            while (matcher.find()) {
                referenced << matcher.group(1)
            }
            def counts = countHeadings(format, text)
            if (counts) {
                level1 += counts[0]
                level2 += counts[1]
            }
            if (format == 'docbook') {
                wellFormed(file.newInputStream(), relative(file))
                data.reference = docbookStructure(file)
                data.sidebars = featureMarkers().collectEntries { feature, marker ->
                    [(feature): text.count("<sidebar role=\"${marker}\"")]
                }
            }
            if (format == 'html') {
                data.sidebars = featureMarkers().collectEntries { feature, marker ->
                    [(feature): (text =~ /class="sidebarblock[^"]*\b${Pattern.quote(marker)}\b[^"]*"/).count]
                }
            }
        }
        if (countHeadings(format, '') != null) {
            data.headings = [level1, level2]
        }
        if (format in MULTI_PAGE_FORMATS) {
            data.pages = files.size()
        }

        // every image of the template is referenced, and every referenced image is there;
        // the multi-page formats have no main document, only its chapters
        def required = format in MULTI_PAGE_FORMATS ? images.chapters : images.body
        def allowed = images.all
        if (format in RASTER_IMAGE_FORMATS) {
            // these formats use the PNG next to an SVG, if there is one (Converter.writeWithRasterImages)
            def imagesDir = new File(templateSrc(language, style).parentFile, 'images')
            def raster = { Set<String> names ->
                names.collect { name ->
                    def png = name.replaceFirst(/(?i)\.svg$/, '.png')
                    name.toLowerCase().endsWith('.svg') && new File(imagesDir, png).isFile() ? png : name
                } as TreeSet
            }
            required = raster(required)
            allowed = raster(allowed)
        }
        def missing = required - referenced
        def unexpected = referenced - allowed
        if (missing) {
            error('images', relative(dir), "the ${format} output does not reference ${missing.join(', ')} (referenced by the template)")
        }
        if (unexpected) {
            error('images', relative(dir), "the ${format} output references ${unexpected.join(', ')}, which the template does not")
        }
        if (config.formats[format]?.imageFolder) {
            referenced.findAll { !new File(dir, "images/${it}").isFile() }.each { name ->
                error('images', relative(dir), "images/${name} is referenced but not in the output")
            }
        }
        return data
    }

    /** The AsciiDoc output is source: removed features must be gone, and its images must be there */
    private void checkAsciidoc(String language, String style, File dir) {
        def removed = featureMarkers().findAll { feature, marker -> !(feature in styleFeatures(style)) }
        def referenced = new TreeSet<String>()
        dir.eachFileRecurse { file ->
            if (!file.isFile() || !file.name.endsWith('.adoc')) return
            def text = readText(file)
            if (text == null) return
            checked.textFiles++
            removed.each { feature, marker ->
                if (text.contains("ifdef::${marker}[]") || text.contains("[role=\"${marker}\"]")) {
                    error('features', relative(file), "the ${style} style must not contain ${feature} blocks (${marker})")
                }
            }
            def matcher = IMAGE_MACRO.matcher(text)
            while (matcher.find()) {
                referenced << matcher.group(1).tokenize('/').last()
            }
        }
        referenced.findAll { !it.contains('{') && !new File(dir, "images/${it}").isFile() }.each { name ->
            error('images', relative(dir), "images/${name} is referenced but not in the output")
        }
    }

    private void checkMarkers(String text, String location) {
        LEFTOVER_MARKERS.each { description, pattern ->
            def matcher = pattern.matcher(text)
            if (matcher.find()) {
                def from = Math.max(0, matcher.start() - 30)
                def excerpt = text.substring(from, Math.min(text.length(), matcher.end() + 30)).replaceAll(/\s+/, ' ')
                error('markers', location, "contains ${description}: …${excerpt}…")
            }
        }
    }

    /** DOCX and EPUB: a readable ZIP with well-formed XML parts, the expected parts and all images embedded */
    private void checkContainer(File file, String format, Map<String, Set<String>> images, Map data) {
        def location = relative(file)
        def entries = [:]   // name -> bytes
        try {
            new ZipFile(file).close()   // a truncated file has no central directory
            new ZipInputStream(file.newInputStream()).withCloseable { zip ->
                def entry
                while ((entry = zip.nextEntry) != null) {
                    entries[entry.name] = zip.readAllBytes()
                }
            }
        } catch (Exception e) {
            error('xml', location, "not a readable ${format} file: ${e.message}")
            return
        }

        def required = format == 'docx' ? ['[Content_Types].xml', 'word/document.xml'] : ['mimetype', 'META-INF/container.xml']
        (required - entries.keySet()).each { error('xml', location, "the ${format} file has no ${it}") }
        if (format == 'epub' && entries.keySet()[0] != 'mimetype') {
            error('xml', location, "the first entry of an EPUB must be 'mimetype', found '${entries.keySet()[0]}'")
        }

        def text = new StringBuilder()
        entries.each { name, bytes ->
            if (name ==~ /(?i).+\.(xml|rels|xhtml|opf|ncx)/) {
                wellFormed(new ByteArrayInputStream(bytes), "${location}!${name}")
                if (name == 'word/document.xml' || name ==~ /.+\.xhtml/) {
                    text << new String(bytes, 'UTF-8')
                }
            }
        }
        checkMarkers(text.toString(), location)

        def media = entries.keySet().findAll { it.contains('/media/') && it ==~ IMAGE_FILE }
        // DOCX and EPUB show the document title, so its logo is embedded as well
        if (media.size() != images.all.size()) {
            error('images', location, "${media.size()} embedded image(s), the template references ${images.all.size()} (${images.all.join(', ') ?: 'none'})")
        }
        if (format == 'docx') {
            def document = text.toString()
            data.headings = [document.count('w:pStyle w:val="Heading1"'), document.count('w:pStyle w:val="Heading2"')]
        }
    }

    /** A PDF has pages and text; the images of the template are embedded */
    private void checkPdf(File file, Map<String, Set<String>> images, Map data) {
        def location = relative(file)
        checked.pdfs++
        def bytes = file.bytes
        def raw = new String(bytes, 'ISO-8859-1')
        if (!raw.startsWith('%PDF-') || !raw.substring(Math.max(0, raw.length() - 1024)).contains('%%EOF')) {
            error('pdf', location, 'not a complete PDF file (no %PDF- header or no %%EOF at the end)')
            return
        }
        // an SVG is drawn as vector graphics, only raster images are embedded as image objects
        def rasterImages = images.body.findAll { !it.toLowerCase().endsWith('.svg') }
        if (raw.count('/Subtype /Image') < rasterImages.size()) {
            error('images', location, "${raw.count('/Subtype /Image')} embedded image(s), the template references ${rasterImages.size()} (${rasterImages.join(', ')})")
        }

        def info = run(['pdfinfo', file.path])
        def extracted = run(['pdftotext', '-enc', 'UTF-8', file.path, '-'])
        if (info == null || extracted == null) {
            error('pdf', location, 'pdfinfo/pdftotext (poppler-utils) not available or failed: the PDF could not be read')
            return
        }
        def pages = (info =~ /(?m)^Pages:\s+(\d+)/).with { it.find() ? it.group(1).toInteger() : 0 }
        def characters = extracted.replaceAll(/\s+/, '').length()
        data.pages = pages
        data.characters = characters
        if (pages < 1 || characters < 200) {
            error('pdf', location, "${pages} page(s) with ${characters} character(s) of text: the PDF is empty or its text cannot be extracted")
        }
        if (extracted.contains('�')) {
            error('pdf', location, 'the text contains replacement characters (�): characters without a glyph in the fonts')
        }
    }

    // ========================================================================
    // Across formats and styles: headings, features
    // ========================================================================

    /**
     * The DocBook rendering is the reference for the structure: its top-level divisions (preface,
     * chapters, appendices) and their sections. HTML comes from Asciidoctor directly and shows only
     * divisions with a title; the Pandoc formats come from the DocBook and show every division.
     */
    private void checkHeadings(String language, String style, Map<String, Map> data) {
        def reference = data.docbook?.reference
        if (!reference) {
            return   // no DocBook output in this run (--format=...)
        }
        data.each { format, values ->
            if (format == 'docbook' || !values?.headings) return
            def (level1, level2) = values.headings
            def location = relative(outputDir(language, style, format))
            def allowed = format == 'html' ? [reference.titled] : [reference.divisions, reference.titled]
            if (!(level1 in allowed)) {
                error('headings', location, "${level1} chapter heading(s) in ${format}, the DocBook rendering has ${allowed.unique().join(' or ')}")
            }
            if (level2 != reference.sections) {
                error('headings', location, "${level2} section heading(s) in ${format}, the DocBook rendering has ${reference.sections}")
            }
        }
        // multi-page: one file per chapter file that the main document includes
        def chapters = includedChapters(language, style)
        MULTI_PAGE_FORMATS.findAll { data[it]?.pages != null }.each { format ->
            if (data[format].pages != chapters.size()) {
                error('headings', relative(outputDir(language, style, format)),
                    "${data[format].pages} file(s) in ${format}, the main document includes ${chapters.size()} chapter file(s)")
            }
        }
    }

    /**
     * A style contains exactly the feature blocks of its features: as many sidebars as the golden
     * master has blocks of a feature the style keeps, none of a feature it removes. Formats in which
     * the blocks cannot be counted must at least be larger with a feature than without it.
     */
    private void checkFeatures(String language, List<String> styles, List<String> formats, Map<String, Map> styleData) {
        def blocks = featureBlocksInGoldenMaster(language)
        styles.each { style ->
            ['docbook', 'html'].each { format ->
                def sidebars = styleData[style]?.get(format)?.sidebars
                if (sidebars == null) return
                featureMarkers().each { feature, marker ->
                    def expected = feature in styleFeatures(style) ? blocks[feature] : 0
                    if (sidebars[feature] != expected) {
                        error('features', relative(outputDir(language, style, format)),
                            "${sidebars[feature]} ${feature} block(s) in ${format}, expected ${expected} " +
                            "(the golden master has ${blocks[feature]}, the ${style} style ${feature in styleFeatures(style) ? 'keeps' : 'removes'} them)")
                    }
                }
            }
        }
        // a style with more features than another one must have more content, in every format
        styles.each { richer ->
            styles.each { poorer ->
                def extra = styleFeatures(richer) - styleFeatures(poorer)
                if (!styleFeatures(richer).containsAll(styleFeatures(poorer)) || !extra.any { blocks[it] > 0 }) return
                formats.each { format ->
                    def more = styleData[richer]?.get(format)?.size
                    def less = styleData[poorer]?.get(format)?.size
                    if (more != null && less != null && more <= less) {
                        error('features', relative(outputDir(language, richer, format)),
                            "the ${richer} output (${more} bytes) is not larger than the ${poorer} output (${less} bytes): the ${extra.join(', ')} texts are missing")
                    }
                }
            }
        }
    }

    /** [level 1, level 2] heading counts of a text in a format; null for a format without countable headings */
    static List<Integer> countHeadings(String format, String text) {
        def count = { String pattern -> (text =~ pattern).count as int }
        switch (format) {
            case 'html':
                return [count(/<h2[ >]/), count(/<h3[ >]/)]
            case MARKDOWN_FORMATS + MULTI_PAGE_FORMATS:
                def body = text.replaceAll(/(?ms)^(`{3,}|~{3,}).*?^\1\s*$/, '')   // without fenced code blocks
                return [(body =~ /(?m)^# /).count as int, (body =~ /(?m)^## /).count as int]
            case 'textile':
                return [count(/(?m)^h1[.(]/), count(/(?m)^h2[.(]/)]
            case 'latex':
                return [count(/\\section\{/), count(/\\subsection\{/)]
            case 'rst':
                return [rstHeadings(text, '='), rstHeadings(text, '-')]
            default:
                return null
        }
    }

    /**
     * reStructuredText headings underlined with the given character: a text line between blank lines
     * with a line of that character below it. The document title (with an overline) does not count.
     * Pandoc measures the underline in display columns, so it can be one shorter than the title in
     * characters (a title of '…' and a zero-width space gets a single '-').
     */
    private static int rstHeadings(String text, String character) {
        def lines = text.readLines()
        def isLine = { String line -> line.length() >= 1 && line.chars.every { it == character as char } }
        int headings = 0
        for (int i = 1; i < lines.size(); i++) {
            if (!isLine(lines[i])) continue
            def title = lines[i - 1]
            if (!title.trim() || isLine(title) || title.startsWith(' ')) continue   // a table border or a transition
            if (lines[i].length() < title.length() - 1) continue                    // too short for an underline
            if (i + 1 < lines.size() && lines[i + 1].trim()) continue               // text goes on: no heading
            if (i >= 2 && isLine(lines[i - 2])) continue                            // overline: the document title
            if (i >= 2 && lines[i - 2].trim()) continue                             // the title is the end of a paragraph
            headings++
        }
        return headings
    }

    /** [divisions, titled, sections] of a DocBook file: top-level divisions, those with a title, and their direct sections */
    private Map docbookStructure(File file) {
        try {
            def root = new groovy.xml.XmlSlurper(false, false).parse(file)
            def divisions = root.name() == 'book' ?
                root.children().findAll { it.name() in ['preface', 'chapter', 'appendix', 'part'] }.collect { it } :
                root.children().findAll { it.name() == 'section' }.collect { it }
            return [
                divisions: divisions.size(),
                titled   : divisions.count { it.title.text().trim() },
                sections : divisions.sum(0) { division -> division.children().count { it.name() == 'section' } },
            ]
        } catch (Exception e) {
            return null   // not well-formed: reported by the xml check
        }
    }

    // ========================================================================
    // Distribution ZIPs and manifest
    // ========================================================================

    private void checkZips(List<String> languages, List<String> styles, List<String> formats) {
        def distDir = new File(projectRoot, config.distribution.targetPath.toString())
        def manifestFile = new File(distDir, 'manifest.json')
        if (!manifestFile.isFile()) {
            warning('zips', relative(manifestFile), 'no manifest.json: the distribution ZIPs were not checked (run the distribution phase)')
            return
        }
        def manifest = new groovy.json.JsonSlurper().parse(manifestFile, 'utf-8')
        // a check of some formats only (--format=...) looks at the ZIPs of these formats only
        def listed = manifest.files.findAll { it.format in formats }.collectEntries { [(it.name): it] }

        def expected = [:]   // ZIP name -> output directory
        languages.each { language ->
            styles.each { style ->
                formats.each { format ->
                    def name = "${config.project.name}-${language}-${style.replaceAll('[^a-zA-Z]', '')}-${format}.zip".toString()
                    expected[name] = outputDir(language, style, format)
                }
            }
        }
        (expected.keySet() - listed.keySet()).each { error('zips', relative(manifestFile), "${it} is not in the manifest") }
        (listed.keySet() - expected.keySet()).each { error('zips', relative(manifestFile), "${it} is in the manifest, but is no combination of the built languages, styles and formats") }

        listed.each { name, entry ->
            def zip = new File(distDir, name)
            def location = relative(zip)
            if (!zip.isFile()) {
                error('zips', location, 'listed in the manifest, but the file does not exist')
                return
            }
            checked.zips++
            if (zip.length() != entry.size || sha256(zip) != entry.sha256) {
                error('zips', location, 'size or SHA-256 differ from the manifest')
                return
            }
            def contents = [:]   // entry name -> size
            try {
                // reading every entry to its end verifies its CRC
                new ZipInputStream(zip.newInputStream()).withCloseable { stream ->
                    def zipEntry
                    while ((zipEntry = stream.nextEntry) != null) {
                        if (!zipEntry.isDirectory()) contents[zipEntry.name] = (long) stream.readAllBytes().length
                    }
                }
                new ZipFile(zip).close()   // the central directory is readable as well
            } catch (Exception e) {
                error('zips', location, "the ZIP cannot be read: ${e.message}")
                return
            }
            def dir = expected[name]
            if (dir?.isDirectory()) {
                def files = [:]
                dir.eachFileRecurse { if (it.isFile()) files[dir.toPath().relativize(it.toPath()).toString().replace(File.separator, '/')] = it.length() }
                if (files != contents) {
                    def missing = files.keySet() - contents.keySet()
                    def extra = contents.keySet() - files.keySet()
                    def details = []
                    if (missing) details << "missing: ${missing.take(5).join(', ')}"
                    if (extra) details << "not in the output: ${extra.take(5).join(', ')}"
                    if (!details) details << 'file sizes differ'
                    error('zips', location, "the ZIP does not hold the files of ${relative(dir)} (${details.join('; ')})")
                }
            }
            if (!contents) {
                error('zips', location, 'the ZIP is empty')
            }
        }
        def inManifest = manifest.files*.name as Set
        distDir.listFiles()?.findAll { it.name.endsWith('.zip') && !(it.name in inManifest) }?.sort { it.name }?.each {
            warning('zips', relative(it), 'not in the manifest: a ZIP of an earlier build (it is not released)')
        }
    }

    // ========================================================================
    // Golden master and generated templates
    // ========================================================================

    private File sourceRoot() { new File(projectRoot, config.goldenMaster.targetPath.toString()) }

    private File outputDir(String language, String style, String format) {
        new File(projectRoot, "${buildDir}/${language}/${format}/${style}")
    }

    /** Languages with generated templates (build/src_gen/<LANG>/) */
    private List<String> builtLanguages() {
        sourceRoot().listFiles()?.findAll { it.isDirectory() && it.name ==~ /^[A-Z]{2,}(-[A-Z]{2,})?$/ }*.name?.sort() ?: []
    }

    private List<String> styleFeatures(String style) {
        (config.goldenMaster.templateStyles[style] ?: []) as List<String>
    }

    /** feature -> marker, e.g. help -> arc42help */
    private Map<String, String> featureMarkers() {
        config.goldenMaster.allFeatures.collectEntries { [(it.toString()): "${config.project.featurePrefix}${it}".toString()] }
    }

    private File templateSrc(String language, String style) {
        new File(sourceRoot(), "${language}/asciidoc/${style}/src")
    }

    /** Chapter files the main document of a generated template includes, without the settings file config.adoc */
    private List<String> includedChapters(String language, String style) {
        def src = templateSrc(language, style)
        def main = new File(src, "${config.project.name}.adoc")
        if (!main.isFile()) return []
        def names = []
        main.getText('utf-8').eachLine { line ->
            def matcher = (line =~ /^include::([^\[]+)\[/)
            if (matcher.find()) {
                def name = matcher.group(1)
                if (!name.contains('/') && name != 'config.adoc' && new File(src, name).isFile()) names << name
            }
        }
        return names.unique()
    }

    /**
     * File names of the images a generated template references:
     * - all:      in the main document and the chapters it includes
     * - body:     the same without the images of the document title (formats without a title block,
     *             such as strict Markdown and Textile, do not show the title and its logo)
     * - chapters: in the included chapters only (the multi-page formats have no main document)
     */
    private Map<String, Set<String>> referencedImages(String language, String style) {
        def src = templateSrc(language, style)
        def result = [all: new TreeSet<String>(), body: new TreeSet<String>(), chapters: new TreeSet<String>()]
        def mainName = "${config.project.name}.adoc".toString()
        ([mainName] + includedChapters(language, style)).each { name ->
            def file = new File(src, name.toString())
            if (!file.isFile()) return
            def inComment = false
            file.getText('utf-8').eachLine { line ->
                def trimmed = line.trim()
                if (trimmed ==~ /\/{4,}/) { inComment = !inComment; return }
                if (inComment || trimmed.startsWith('//')) return
                def isTitle = name == mainName && line.startsWith('= ')
                def matcher = IMAGE_MACRO.matcher(line)
                while (matcher.find()) {
                    def target = matcher.group(1)
                    if (target.contains('{') || target ==~ /^(https?:|data:).*/) continue
                    def image = target.tokenize('/').last()
                    result.all << image
                    if (!isTitle) result.body << image
                    if (name != mainName) result.chapters << image
                }
            }
        }
        return result
    }

    /** feature -> number of [role="<marker>"] blocks in the golden master documents of a language that end up in the output */
    private Map<String, Integer> featureBlocksInGoldenMaster(String language) {
        def languageDir = new File(new File(projectRoot, config.goldenMaster.sourcePath.toString()), language)
        def main = new File(languageDir, "${config.project.name}.adoc")
        def files = main.isFile() ? [main] : []
        if (main.isFile()) {
            main.getText('utf-8').eachLine { line ->
                def matcher = (line =~ /^include::([^\[]+)\[/)
                if (matcher.find()) {
                    def included = new File(languageDir, matcher.group(1))
                    if (included.isFile() && included.canonicalPath.startsWith(languageDir.canonicalPath)) files << included
                }
            }
        }
        return featureMarkers().collectEntries { feature, marker ->
            [(feature): files.sum(0) { file -> file.getText('utf-8').readLines().count { it.trim() == "[role=\"${marker}\"]" } }]
        }
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private List<File> mainFiles(File dir, String extension) {
        dir.listFiles()?.findAll { it.isFile() && it.name.endsWith(".${extension}") }?.sort { it.name } ?: []
    }

    /** Bytes of the content of an output directory; the images folder is the same for all formats and does not count */
    private long directorySize(File dir, String format) {
        long size = 0
        dir.eachFileRecurse { file ->
            if (file.isFile() && !relative(file).contains('/images/')) size += file.length()
        }
        return size
    }

    /** The text of a file, or null (and an error) if it is not valid UTF-8 */
    private String readText(File file) {
        try {
            def decoder = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
            return decoder.decode(java.nio.ByteBuffer.wrap(file.bytes)).toString()
        } catch (java.nio.charset.CharacterCodingException e) {
            error('encoding', relative(file), 'not valid UTF-8')
            return null
        }
    }

    private void wellFormed(InputStream stream, String location) {
        try {
            def factory = SAXParserFactory.newInstance()
            factory.namespaceAware = true
            factory.setFeature('http://apache.org/xml/features/nonvalidating/load-external-dtd', false)
            def source = new InputSource(stream)
            stream.withCloseable { factory.newSAXParser().parse(source, new DefaultHandler()) }
        } catch (Exception e) {
            error('xml', location, "not well-formed XML: ${e.message}")
        }
    }

    /** Standard output of a command, or null if it is not installed or failed */
    private String run(List<String> command) {
        try {
            def process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            def output = new String(process.inputStream.bytes, 'UTF-8')
            return process.waitFor() == 0 ? output : null
        } catch (IOException e) {
            return null
        }
    }

    static String sha256(File file) {
        def digest = MessageDigest.getInstance('SHA-256')
        file.withInputStream { stream ->
            def buffer = new byte[65536]
            int n
            while ((n = stream.read(buffer)) > 0) digest.update(buffer, 0, n)
        }
        return digest.digest().encodeHex().toString()
    }

    private String relative(File file) {
        def root = projectRoot.canonicalPath + File.separator
        def path = file.canonicalPath
        return path.startsWith(root) ? path.substring(root.length()) : path
    }

    private void error(String check, String location, String message) {
        problems << [severity: 'error', check: check, location: location, message: message]
    }

    private void warning(String check, String location, String message) {
        problems << [severity: 'warning', check: check, location: location, message: message]
    }
}
