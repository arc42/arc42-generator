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
}
