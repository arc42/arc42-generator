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
}
