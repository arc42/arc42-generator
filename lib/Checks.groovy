#!/usr/bin/env groovy

@Grab('org.commonmark:commonmark:0.24.0')
@Grab('org.jsoup:jsoup:1.18.3')

import org.commonmark.parser.Parser
import org.commonmark.node.*
import org.jsoup.Jsoup
import java.util.regex.Pattern

/**
 * Checks.groovy - Rule catalogue for the verify phase
 *
 * Every public check method takes a context map (see Verifier.buildContext) and returns
 * a list of findings: [ruleId, severity, message, examples]. Rules never throw for bad
 * content; bad content is a finding. Nothing here touches the file system.
 */
class Checks {

    def config

    static final Map<String, String> DEFAULT_SEVERITY = [
        'zip.exists': 'error', 'zip.nonEmpty': 'error', 'zip.primaryFile': 'error', 'zip.images': 'error',
        'structure.chapters': 'error', 'structure.referenceCounts': 'warn',
        'structure.helpText': 'error', 'structure.revnumber': 'error',
        'md.pandocSyntax': 'error', 'md.rawHtml': 'error', 'md.emptyHeading': 'error', 'md.images': 'error',
        'md.commonmark': 'error', 'md.multiPageHeading': 'error', 'md.frontMatter': 'warn',
        'html.wellFormed': 'error', 'html.title': 'error', 'html.images': 'error',
        'html.localLinks': 'warn', 'html.charset': 'error',
        'docx.valid': 'error', 'docx.media': 'error', 'epub.valid': 'error', 'epub.media': 'error',
        'src.versionProperties': 'error', 'src.mainFile': 'error', 'src.chapters': 'error',
        'src.includes': 'error', 'src.images': 'error',
    ]

    static final List<String> MD_FORMATS = ['markdown', 'markdownMP', 'markdownStrict', 'markdownMPStrict',
                                            'gitHubMarkdown', 'gitHubMarkdownMP', 'mkdocs', 'mkdocsMP']
    static final List<String> MP_FORMATS = ['markdownMP', 'mkdocsMP', 'markdownMPStrict', 'gitHubMarkdownMP']

    /** Output file extension per format; mirrors Converter.getPandocConfig without pulling in AsciidoctorJ */
    static final Map<String, String> EXTENSIONS = [
        html: 'html', asciidoc: 'adoc', docbook: 'xml',
        markdown: 'md', markdownMP: 'md', markdownStrict: 'md', markdownMPStrict: 'md',
        gitHubMarkdown: 'md', gitHubMarkdownMP: 'md', mkdocs: 'md', mkdocsMP: 'md',
        textile: 'textile', textile2: 'textile', docx: 'docx', epub: 'epub', latex: 'tex', rst: 'rst',
    ]

    Checks(config) {
        this.config = config
    }

    // ---- severity and findings ----------------------------------------------

    String severityOf(String ruleId) {
        def override = config.verify?.severity?.get(ruleId)
        if (override in ['error', 'warn', 'off']) return override
        return DEFAULT_SEVERITY[ruleId] ?: 'error'
    }

    /** Build a finding, or null when the rule is switched off. Examples are capped at three. */
    Map finding(String ruleId, String message, List examples = []) {
        def severity = severityOf(ruleId)
        if (severity == 'off') return null
        return [ruleId: ruleId, severity: severity, message: message, examples: examples.take(3)]
    }

    /** Collapse all whitespace (including NBSP) to single spaces and trim. */
    static String normalize(String s) {
        if (s == null) return ''
        return s.replaceAll(/[\s ]+/, ' ').trim()
    }

    String extensionOf(String format) {
        return EXTENSIONS[format] ?: format
    }

    // ---- text access ---------------------------------------------------------

    /** Known HTML element names; anything else in angle brackets is treated as text (e.g. <Name black box 1>) */
    static final Set<String> HTML_TAGS = ['a', 'abbr', 'b', 'blockquote', 'br', 'caption', 'center', 'cite', 'code', 'col',
        'colgroup', 'dd', 'del', 'details', 'div', 'dl', 'dt', 'em', 'figcaption', 'figure', 'font', 'h1', 'h2', 'h3', 'h4',
        'h5', 'h6', 'hr', 'i', 'iframe', 'img', 'ins', 'kbd', 'li', 'mark', 'ol', 'p', 'picture', 'pre', 'q', 's', 'script',
        'section', 'small', 'source', 'span', 'strong', 'style', 'sub', 'summary', 'sup', 'table', 'tbody', 'td', 'tfoot',
        'th', 'thead', 'tr', 'u', 'ul', 'video'] as Set

    static final Pattern HTML_TAG = ~/<\/?([a-zA-Z][a-zA-Z0-9]*)(?:\s[^<>]*)?\/?>/
    static final Pattern MD_HEADING = ~/^(#{1,6})[ \t]+(.*?)\s*$/
    static final Pattern MD_EMPTY_HEADING = ~/^#{1,6}[ \t]*$/
    static final Pattern MD_HEADING_ATTR = ~/^#{1,6}\s.*\{[#.][^}]*\}\s*$/
    static final Pattern MD_FENCED_DIV = ~/^:{3,}(\s.*)?$/
    static final Pattern MD_IMAGE = ~/!\[[^\]]*\]\(\s*<?([^)\s>]+)>?(?:\s+"[^"]*")?\s*\)/

    /** Entries of this format's extension, decoded as UTF-8, keyed by entry name, sorted. */
    Map<String, String> textEntries(Map ctx) {
        def ext = '.' + extensionOf(ctx.format)
        def result = new TreeMap<String, String>()
        (ctx.entries ?: [:]).each { String name, byte[] bytes ->
            if (name.toLowerCase().endsWith(ext) && !name.endsWith('/')) {
                result[name] = new String(bytes, 'UTF-8')
            }
        }
        return result
    }

    /**
     * Split text into [no, text, inFence] maps. inFence is true inside ``` or ~~~ fences and for
     * indented code (4 spaces / tab after a blank line), so rules can skip code.
     */
    List<Map> lines(String text) {
        def result = []
        String fence = null
        boolean prevBlank = true
        text.split(/\r?\n/, -1).eachWithIndex { String line, int i ->
            def m = line =~ /^\s{0,3}(`{3,}|~{3,})/
            if (fence == null && m.find()) {
                fence = m.group(1)[0]
                result << [no: i + 1, text: line, inFence: true]
            } else if (fence != null && (line =~ /^\s{0,3}${fence}{3,}\s*$/).find()) {
                fence = null
                result << [no: i + 1, text: line, inFence: true]
            } else if (fence != null) {
                result << [no: i + 1, text: line, inFence: true]
            } else {
                boolean indentedCode = prevBlank && (line.startsWith('    ') || line.startsWith('\t')) && line.trim()
                result << [no: i + 1, text: line, inFence: indentedCode]
            }
            prevBlank = line.trim().isEmpty()
        }
        return result
    }

    /** Resolve a relative reference against the directory of a ZIP entry; strips ./ and collapses ../ */
    static String resolvePath(String entryName, String ref) {
        def dir = entryName.contains('/') ? entryName.substring(0, entryName.lastIndexOf('/') + 1) : ''
        def parts = (dir + ref).split('/').toList()
        def stack = []
        parts.each { p ->
            if (p == '' || p == '.') return
            if (p == '..') { if (stack) stack.removeLast() } else stack << p
        }
        return stack.join('/')
    }

    private boolean isRemote(String ref) {
        return ref ==~ /(?i)^(https?:|data:|mailto:)\S*/
    }

    // ---- Markdown -------------------------------------------------------------

    List<Map> checkMarkdown(Map ctx) {
        def findings = []
        def allowed = ((config.verify?.allowedHtml?.get(ctx.format)) ?: []).collect { it.toString().toLowerCase() } as Set
        def texts = textEntries(ctx)

        def pandoc = [], rawHtml = [], rawTagNames = [] as Set, empty = [], missingImages = [], commonmarkHtml = [], mpNoHeading = [], frontMatter = []

        texts.each { String name, String text ->
            lines(text).each { l ->
                if (l.inFence) return
                String line = l.text
                if (MD_FENCED_DIV.matcher(line).matches() || MD_HEADING_ATTR.matcher(line).matches()) {
                    pandoc << [location: "${name}:${l.no}".toString(), text: line.trim()]
                }
                if (MD_EMPTY_HEADING.matcher(line).matches()) {
                    empty << [location: "${name}:${l.no}".toString(), text: line]
                }
                // escaped angle brackets are text, drop them before tag matching
                def unescaped = line.replaceAll(/\\[<>]/, '')
                def tm = HTML_TAG.matcher(unescaped)
                while (tm.find()) {
                    def tag = tm.group(1).toLowerCase()
                    if (tag in HTML_TAGS && !(tag in allowed)) {
                        rawHtml << [location: "${name}:${l.no}".toString(), text: tm.group(0)]
                        rawTagNames << tag
                    }
                }
                def im = MD_IMAGE.matcher(line)
                while (im.find()) {
                    def ref = im.group(1)
                    if (isRemote(ref)) continue
                    def resolved = resolvePath(name, ref)
                    if (!ctx.entries.containsKey(resolved)) {
                        missingImages << [location: "${name}:${l.no}".toString(), text: "${ref} -> ${resolved}".toString()]
                    }
                }
            }

            // parser-backed HTML detection
            try {
                def doc = Parser.builder().build().parse(text)
                def visitor = new AbstractVisitor() {
                    void visit(HtmlBlock block) { report(block.literal); visitChildren(block) }
                    void visit(HtmlInline inline) { report(inline.literal); visitChildren(inline) }
                    void report(String literal) {
                        def m = HTML_TAG.matcher(literal ?: '')
                        while (m.find()) {
                            def tag = m.group(1).toLowerCase()
                            if (tag in HTML_TAGS && !(tag in allowed)) commonmarkHtml << [location: name, text: m.group(0)]
                        }
                    }
                }
                doc.accept(visitor)
            } catch (Exception e) {
                findings << finding('md.commonmark', "${name}: CommonMark parser failed: ${e.message}", [[location: name, text: e.toString()]])
            }

            // multi-page: chapter files start with a heading
            if (ctx.format in MP_FORMATS) {
                def first = lines(text).find { it.text.trim() }
                if (first && !MD_HEADING.matcher(first.text).matches()) {
                    mpNoHeading << [location: "${name}:${first.no}".toString(), text: first.text.take(80)]
                }
            }

            // front matter title with an image
            if (text.startsWith('---')) {
                def block = text.split(/\r?\n---\s*(\r?\n|$)/, 2)[0]
                def title = block.readLines().find { it =~ /^title:/ }
                if (title && title.contains('![')) frontMatter << [location: "${name}:1".toString(), text: title.trim()]
            }
        }

        if (pandoc) findings << finding('md.pandocSyntax', "${pandoc.size()} line(s) with pandoc-only syntax (fenced divs or heading attributes)", pandoc)
        if (rawHtml) findings << finding('md.rawHtml', "${rawHtml.size()} raw HTML tag(s) not allowed: ${rawTagNames.sort().join(', ')}", rawHtml)
        if (empty) findings << finding('md.emptyHeading', "${empty.size()} empty heading(s)", empty)
        if (missingImages) findings << finding('md.images', "${missingImages.size()} image reference(s) not found in ZIP", missingImages)
        if (commonmarkHtml) findings << finding('md.commonmark', "${commonmarkHtml.size()} HTML node(s) found by CommonMark parser", commonmarkHtml)
        if (mpNoHeading) findings << finding('md.multiPageHeading', "${mpNoHeading.size()} chapter file(s) do not start with a heading", mpNoHeading)
        if (frontMatter) findings << finding('md.frontMatter', "front matter title contains an image", frontMatter)

        return findings.findAll { it != null }
    }

    // ---- archives ------------------------------------------------------------

    /** Read a ZIP from bytes into name -> content. Directory entries are skipped. */
    static Map<String, byte[]> unzip(byte[] bytes) {
        def result = new LinkedHashMap<String, byte[]>()
        new java.util.zip.ZipInputStream(new ByteArrayInputStream(bytes)).withCloseable { zis ->
            java.util.zip.ZipEntry e
            while ((e = zis.nextEntry) != null) {
                if (!e.isDirectory()) result[e.name] = zis.readAllBytes()   // not zis.bytes: Groovy closes the stream
                zis.closeEntry()
            }
        }
        return result
    }

    /** For docx/epub: the single document package inside the dist ZIP, unpacked. Null when absent or unreadable. */
    Map<String, byte[]> innerZip(Map ctx) {
        def ext = '.' + extensionOf(ctx.format)
        def name = (ctx.entries ?: [:]).keySet().find { it.toLowerCase().endsWith(ext) }
        if (!name) return null
        try { return unzip(ctx.entries[name]) } catch (Exception e) { return null }
    }

    // ---- format-aware text -----------------------------------------------------

    private static final Pattern W_PARA = ~/(?s)<w:p[ >].*?<\/w:p>/
    private static final Pattern W_STYLE = ~/<w:pStyle w:val="([^"]+)"/
    private static final Pattern W_TEXT = ~/(?s)<w:t(?:\s[^>]*)?>(.*?)<\/w:t>/

    /** DOCX paragraphs as [style, text] from word/document.xml; runs are joined without separator. */
    List<List<String>> docxParagraphs(Map<String, byte[]> pkg) {
        def xml = pkg?.get('word/document.xml')
        if (!xml) return []
        def text = new String(xml, 'UTF-8')
        def result = []
        def m = W_PARA.matcher(text)
        while (m.find()) {
            def p = m.group()
            def sm = W_STYLE.matcher(p)
            def style = sm.find() ? sm.group(1) : ''
            def sb = new StringBuilder()
            def tm = W_TEXT.matcher(p)
            while (tm.find()) sb.append(tm.group(1))
            result << [style, unescapeXml(sb.toString())]
        }
        return result
    }

    static String unescapeXml(String s) {
        return s.replace('&lt;', '<').replace('&gt;', '>').replace('&quot;', '"').replace('&apos;', "'").replace('&amp;', '&')
    }

    /** EPUB content documents (xhtml manifest items except nav and title page), name -> text. */
    Map<String, String> epubContentDocs(Map<String, byte[]> pkg) {
        def result = new TreeMap<String, String>()
        if (!pkg) return result
        def opfName = pkg.keySet().find { it.toLowerCase().endsWith('.opf') }
        if (!opfName) return result
        def opfDir = opfName.contains('/') ? opfName.substring(0, opfName.lastIndexOf('/') + 1) : ''
        def opf = new String(pkg[opfName], 'UTF-8')
        def im = (~/<item\s[^>]*>/).matcher(opf)
        while (im.find()) {
            def item = im.group()
            if (!item.contains('application/xhtml+xml') || item.contains('properties="nav"')) continue
            def hm = (~/href="([^"]+)"/).matcher(item)
            if (!hm.find()) continue
            def href = hm.group(1)
            if (href.contains('title_page')) continue
            def entry = resolvePath(opfDir + 'x', href)
            if (pkg[entry] != null) result[entry] = new String(pkg[entry], 'UTF-8')
        }
        return result
    }

    /** All visible text of the output, whitespace-normalised. Used for sentinel and revnumber matching. */
    String plainText(Map ctx) {
        switch (ctx.format) {
            case 'html':
                return normalize(textEntries(ctx).values().collect { Jsoup.parse(it).text() }.join(' '))
            case 'docbook':
                return normalize(textEntries(ctx).values().collect { it.replaceAll(/<[^>]+>/, ' ') }.join(' ')).with { unescapeXml(it) }
            case 'docx':
                return normalize(docxParagraphs(innerZip(ctx)).collect { it[1] }.join('\n'))
            case 'epub':
                return normalize(epubContentDocs(innerZip(ctx)).values().collect { Jsoup.parse(it).text() }.join(' '))
            default:
                // markdown, asciidoc, rst, textile, latex: the text as written
                return normalize(textEntries(ctx).values().join('\n'))
        }
    }

    // ---- headings --------------------------------------------------------------

    /** Level at which chapters appear: Asciidoctor's book doctype renders chapters as h2 in HTML. */
    int chapterLevel(String format) {
        return format == 'html' ? 2 : 1
    }

    /** level -> count of non-empty headings, format-aware. Unknown formats return an empty map. */
    Map<Integer, Integer> headingCounts(Map ctx) {
        def counts = new TreeMap<Integer, Integer>()
        def add = { int level -> counts[level] = (counts[level] ?: 0) + 1 }
        def texts = textEntries(ctx)
        String format = ctx.format

        if (format in MD_FORMATS) {
            texts.each { name, text ->
                lines(text).each { l ->
                    if (l.inFence) return
                    def m = MD_HEADING.matcher(l.text)
                    if (m.matches() && m.group(2).trim()) add(m.group(1).length())
                }
            }
        } else if (format == 'asciidoc') {
            texts.each { name, text ->
                text.readLines().each { line ->
                    def m = (~/^(={2,6})[ \t]+(\S.*)$/).matcher(line)
                    if (m.matches()) add(m.group(1).length() - 1)
                }
            }
        } else if (format in ['textile', 'textile2']) {
            texts.each { name, text ->
                text.readLines().each { line ->
                    def m = (~/^h([1-6])(\([^)]*\))?\.[ \t]+(\S.*)$/).matcher(line)
                    if (m.matches()) add(m.group(1) as int)
                }
            }
        } else if (format == 'rst') {
            texts.each { name, text ->
                def ls = text.readLines()
                def levelOfChar = [:]
                for (int i = 1; i < ls.size(); i++) {
                    def line = ls[i], prev = ls[i - 1]
                    if (!(line ==~ /^([=\-~^"'`#*+:.])\1{2,}\s*$/)) continue
                    if (!prev.trim() || prev ==~ /^([=\-~^"'`#*+:.])\1{2,}\s*$/) continue
                    if (line.trim().length() < prev.trim().length()) continue
                    boolean overlined = i >= 2 && ls[i - 2].trim() == line.trim()
                    if (overlined) continue   // document title
                    def ch = line.trim()[0]
                    if (!levelOfChar.containsKey(ch)) levelOfChar[ch] = levelOfChar.size() + 1
                    add(levelOfChar[ch])
                }
            }
        } else if (format == 'latex') {
            texts.each { name, text ->
                boolean hasChapter = text.contains('\\chapter{')
                def order = hasChapter ? ['chapter', 'section', 'subsection', 'subsubsection'] : ['section', 'subsection', 'subsubsection', 'paragraph']
                def m = (~/\\(chapter|section|subsection|subsubsection|paragraph)\*?\{([^}]*)\}/).matcher(text)
                while (m.find()) {
                    int idx = order.indexOf(m.group(1))
                    if (idx >= 0 && m.group(2).trim()) add(idx + 1)
                }
            }
        } else if (format == 'html') {
            texts.each { name, text ->
                def doc = Jsoup.parse(text)
                (1..6).each { lvl -> doc.select("h${lvl}").each { if (it.text().trim()) add(lvl) } }
            }
        } else if (format == 'docbook') {
            texts.each { name, text ->
                counts[1] = (counts[1] ?: 0) + (text =~ /<chapter[\s>]/).count
                def sections = (text =~ /<section[\s>]/).count
                if (sections) counts[2] = (counts[2] ?: 0) + sections
            }
        } else if (format == 'docx') {
            docxParagraphs(innerZip(ctx)).each { p ->
                def m = (~/^Heading(\d)$/).matcher(p[0])
                if (m.matches() && p[1].trim()) add(m.group(1) as int)
            }
        } else if (format == 'epub') {
            epubContentDocs(innerZip(ctx)).each { name, text ->
                def doc = Jsoup.parse(text)
                (1..6).each { lvl -> doc.select("h${lvl}").each { if (it.text().trim()) add(lvl) } }
            }
        }
        return counts
    }

    // ---- structure rules -------------------------------------------------------

    List<Map> checkStructure(Map ctx) {
        def findings = []
        if (!ctx.entries) return findings   // completeness rules report the missing ZIP

        def counts = headingCounts(ctx)
        int level = chapterLevel(ctx.format)
        int chapters = counts[level] ?: 0
        int expected = (ctx.chapterCount ?: 12) as int
        if (chapters != expected) {
            findings << finding('structure.chapters', "expected ${expected} chapter headings at level ${level}, found ${chapters}",
                [[location: ctx.format, text: "heading counts by level: ${counts}".toString()]])
        }

        if (ctx.referenceCounts != null) {
            def diffs = (counts.keySet() + ctx.referenceCounts.keySet()).sort().findAll { (counts[it] ?: 0) != (ctx.referenceCounts[it] ?: 0) }
            if (diffs) {
                findings << finding('structure.referenceCounts',
                    "heading counts differ from reference: " + diffs.collect { "level ${it}: ${counts[it] ?: 0} vs ${ctx.referenceCounts[it] ?: 0}" }.join(', '),
                    diffs.collect { [location: ctx.format, text: "level ${it}".toString()] })
            }
        }

        def text = plainText(ctx)
        if (ctx.helpSentinel) {
            boolean present = text.contains(normalize(ctx.helpSentinel))
            boolean wantHelp = ctx.style != 'plain'
            if (wantHelp && !present) {
                findings << finding('structure.helpText', "with-help output does not contain the help sentinel", [[location: ctx.format, text: ctx.helpSentinel.take(80)]])
            } else if (!wantHelp && present) {
                findings << finding('structure.helpText', "plain output contains help text", [[location: ctx.format, text: ctx.helpSentinel.take(80)]])
            }
        }

        if (ctx.revnumber) {
            if (!text.contains(normalize(ctx.revnumber))) {
                findings << finding('structure.revnumber', "revnumber '${ctx.revnumber}' not found in output", [[location: ctx.format, text: ctx.revnumber]])
            }
        }
        return findings.findAll { it != null }
    }

    // ---- completeness ------------------------------------------------------------

    /** Entries with the format's extension, any directory depth, sorted. */
    List<String> primaryFiles(Map ctx) {
        def ext = '.' + extensionOf(ctx.format)
        return (ctx.entries ?: [:]).keySet().findAll { it.toLowerCase().endsWith(ext) }.sort()
    }

    List<Map> checkCompleteness(Map ctx) {
        def findings = []
        if (ctx.entries == null) {
            findings << finding('zip.exists', "ZIP not found: ${ctx.zipFile?.name ?: '(unknown)'}", [[location: ctx.zipFile?.path ?: '', text: 'missing']])
            return findings.findAll { it != null }
        }
        if (ctx.entries.isEmpty() || ctx.entries.values().every { it.length == 0 }) {
            findings << finding('zip.nonEmpty', "ZIP has no non-empty entries")
            return findings.findAll { it != null }
        }

        def primaries = primaryFiles(ctx)
        def ext = extensionOf(ctx.format)
        if (ctx.format in MP_FORMATS) {
            int expected = (ctx.chapterCount ?: 12) as int
            def chapterFiles = primaries.findAll { (it.tokenize('/').last() ==~ /^\d\d_.*\.${ext}$/) }
            def configFile = primaries.find { it.tokenize('/').last() == "config.${ext}" }
            if (chapterFiles.size() < expected || configFile) {
                def msg = chapterFiles.size() < expected ? "multi-page ZIP has ${chapterFiles.size()} chapter file(s), expected at least ${expected}" : "multi-page ZIP contains boilerplate ${configFile}"
                findings << finding('zip.primaryFile', msg, primaries.take(3).collect { [location: it, text: 'present'] })
            }
        } else if (primaries.isEmpty()) {
            findings << finding('zip.primaryFile', "no *.${ext} file in ZIP", ctx.entries.keySet().take(3).collect { [location: it, text: 'present'] })
        }

        if (ctx.formatConfig?.imageFolder && ctx.style != 'plain') {
            def imagePrefix = ctx.format in ['mkdocs', 'mkdocsMP'] ? 'docs/images/' : 'images/'
            if (!ctx.entries.keySet().any { it.startsWith(imagePrefix) && ctx.entries[it].length > 0 }) {
                findings << finding('zip.images', "with-help ZIP has no entries under ${imagePrefix}")
            }
        }
        return findings.findAll { it != null }
    }

    // ---- dispatch ---------------------------------------------------------------

    /** Run every rule family that applies to the context's format. */
    List<Map> checkAll(Map ctx) {
        def findings = checkCompleteness(ctx)
        // no ZIP, empty ZIP or no primary file: content rules would only add noise
        if (ctx.entries == null || ctx.entries.isEmpty() || findings.any { it.ruleId == 'zip.primaryFile' }) return findings
        findings.addAll(checkStructure(ctx))
        if (ctx.format in MD_FORMATS) findings.addAll(checkMarkdown(ctx))
        if (ctx.format == 'html') findings.addAll(checkHtml(ctx))
        if (ctx.format == 'docx') findings.addAll(checkDocx(ctx))
        if (ctx.format == 'epub') findings.addAll(checkEpub(ctx))
        return findings
    }

    // ---- HTML -------------------------------------------------------------------

    List<Map> checkHtml(Map ctx) {
        def findings = []
        textEntries(ctx).each { String name, String text ->
            int htmlOpen = (text =~ /(?i)<html[\s>]/).count, htmlClose = (text =~ /(?i)<\/html>/).count
            int bodyOpen = (text =~ /(?i)<body[\s>]/).count, bodyClose = (text =~ /(?i)<\/body>/).count
            if ([htmlOpen, htmlClose, bodyOpen, bodyClose] != [1, 1, 1, 1]) {
                findings << finding('html.wellFormed', "${name}: expected exactly one html and body element, found html ${htmlOpen}/${htmlClose}, body ${bodyOpen}/${bodyClose}", [[location: name, text: 'structure']])
            }
            def doc = Jsoup.parse(text)
            if (!doc.title()?.trim()) findings << finding('html.title', "${name}: <title> missing or empty", [[location: name, text: '<title>']])

            boolean charset = doc.select('meta[charset]').any { it.attr('charset').equalsIgnoreCase('utf-8') } ||
                doc.select('meta[http-equiv]').any { it.attr('content').toLowerCase().contains('utf-8') }
            if (!charset) findings << finding('html.charset', "${name}: no UTF-8 charset declaration", [[location: name, text: '<meta charset>']])

            def missing = []
            doc.select('img[src]').each { img ->
                def src = img.attr('src')
                if (isRemote(src)) return
                def resolved = resolvePath(name, src)
                if (!ctx.entries.containsKey(resolved)) missing << [location: name, text: src]
            }
            if (missing) findings << finding('html.images', "${name}: ${missing.size()} image source(s) not found in ZIP", missing)

            def broken = []
            doc.select('a[href^=#]').each { a ->
                def id = a.attr('href').substring(1)
                if (id && doc.getElementById(id) == null && doc.select("a[name=${id}]").isEmpty()) broken << [location: name, text: a.attr('href')]
            }
            if (broken) findings << finding('html.localLinks', "${name}: ${broken.size()} local link(s) without target", broken)
        }
        return findings.findAll { it != null }
    }

    // ---- DOCX -------------------------------------------------------------------

    private boolean parsesAsXml(byte[] bytes) {
        try {
            def parser = new groovy.xml.XmlSlurper(false, false)
            parser.parse(new ByteArrayInputStream(bytes))
            return true
        } catch (Exception e) {
            return false
        }
    }

    List<Map> checkDocx(Map ctx) {
        def findings = []
        def pkg = innerZip(ctx)
        def docName = primaryFiles(ctx)[0] ?: 'docx'
        if (pkg == null || pkg['word/document.xml'] == null || !parsesAsXml(pkg['word/document.xml'])) {
            findings << finding('docx.valid', "${docName}: not a readable DOCX package (word/document.xml missing or not XML)", [[location: docName, text: 'word/document.xml']])
            return findings.findAll { it != null }
        }
        int drawings = (new String(pkg['word/document.xml'], 'UTF-8') =~ /<w:drawing[\s>\/]/).count
        int media = pkg.keySet().count { it.startsWith('word/media/') }
        if (drawings != media || (ctx.style != 'plain' && media == 0)) {
            findings << finding('docx.media', "${docName}: ${drawings} drawing(s) but ${media} media file(s)" + (ctx.style != 'plain' && media == 0 ? ', with-help must embed images' : ''),
                [[location: docName, text: "word/media/* = ${media}".toString()]])
        }
        return findings.findAll { it != null }
    }

    // ---- EPUB -------------------------------------------------------------------

    List<Map> checkEpub(Map ctx) {
        def findings = []
        def pkg = innerZip(ctx)
        def epubName = primaryFiles(ctx)[0] ?: 'epub'
        def container = pkg?.get('META-INF/container.xml')
        if (pkg == null || container == null || !parsesAsXml(container)) {
            findings << finding('epub.valid', "${epubName}: META-INF/container.xml missing or not XML", [[location: epubName, text: 'META-INF/container.xml']])
            return findings.findAll { it != null }
        }
        def rootMatcher = (~/full-path="([^"]+)"/).matcher(new String(container, 'UTF-8'))
        def opfName = rootMatcher.find() ? rootMatcher.group(1) : null
        if (!opfName || pkg[opfName] == null || !parsesAsXml(pkg[opfName])) {
            findings << finding('epub.valid', "${epubName}: package document ${opfName} missing or not XML", [[location: epubName, text: opfName ?: 'rootfile']])
            return findings.findAll { it != null }
        }
        def opfDir = opfName.contains('/') ? opfName.substring(0, opfName.lastIndexOf('/') + 1) : ''
        def opf = new String(pkg[opfName], 'UTF-8')
        def missing = []
        int images = 0
        def im = (~/<item\s[^>]*>/).matcher(opf)
        while (im.find()) {
            def item = im.group()
            def hm = (~/href="([^"]+)"/).matcher(item)
            if (!hm.find()) continue
            def entry = resolvePath(opfDir + 'x', hm.group(1))
            if (pkg[entry] == null) missing << [location: opfName, text: entry]
            if (item.contains('media-type="image/')) images++
        }
        if (missing) findings << finding('epub.valid', "${epubName}: ${missing.size()} manifest item(s) missing from package", missing)
        if (ctx.style != 'plain' && images == 0) findings << finding('epub.media', "${epubName}: with-help EPUB declares no images in its manifest", [[location: opfName, text: 'manifest']])
        return findings.findAll { it != null }
    }
}
