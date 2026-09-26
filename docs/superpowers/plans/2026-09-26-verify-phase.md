# Verify Phase Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a fifth build phase `verify` that inspects every distribution ZIP against a per-format rule catalogue and writes a JUnit XML report plus an HTML matrix showing language, style, format and template version.

**Architecture:** Three new flat library files follow the existing `lib/` pattern. `Checks.groovy` holds pure rule functions over an in-memory context (ZIP entries as `Map<String, byte[]>` plus metadata), so every rule is testable without a build. `Verifier.groovy` builds the expected matrix from the config, reads ZIPs, computes reference data and runs the checks. `Report.groovy` turns the result model into JUnit XML, HTML and console output. `build.groovy` wires the phase in and exits 1 when a test case failed.

**Tech Stack:** Groovy 4+/5 scripts loaded via `GroovyClassLoader` (no Gradle), `@Grab` dependencies `org.commonmark:commonmark:0.24.0` and `org.jsoup:jsoup:1.18.3`, `groovy.xml.MarkupBuilder` and `groovy.xml.XmlSlurper` from the Groovy standard library, `java.util.zip`. Pandoc and AsciidoctorJ are not used by the verifier.

**Spec:** `docs/superpowers/specs/2026-09-26-verify-phase-design.md`

## Global Constraints

- Groovy 4.0 or higher, Java 11 or higher (spec §10). No new binaries in the Docker image.
- All paths in the config are relative to the directory of the config file (`projectRoot`), never to the current directory. Every `new File(...)` for a config path goes through `new File(projectRoot, path)`.
- Library files live flat in `lib/` and are loaded with `gcl.parseClass(new File('lib/X.groovy'))`. Constructor signature is `(config, projectRoot = new File('.'))` like the existing classes.
- Rule ids are the exact strings from spec §6 (`zip.exists`, `md.rawHtml`, ...). Default severities are those in spec §6; `verify.severity[ruleId]` in the config overrides them with `error`, `warn` or `off`.
- Status rules (spec §4): `fail` if any finding has severity `error`, else `warn` if any finding, else `pass`.
- ZIP naming (spec §6.1, `Packager.groovy`): `<project.name>-<LANG>-<styleShort>-<format>.zip`, `styleShort = style.replaceAll("[^a-zA-Z]", "")`, so `with-help` becomes `withhelp`.
- Nothing is unpacked to disk. ZIP entries are read into memory.
- The generator output is NOT changed by this work. The first full run is expected to be red (spec §1, §11.1).
- Report locations: `<verify.reportDir>/junit/TEST-<suite>.xml` and `<verify.reportDir>/verify.html`, default `build/reports` (spec §8).
- Groovy is not installed on the development Mac. Every test and build command runs inside the Docker container. Build the image once with `docker compose build`, then use `docker compose run --rm arc42-builder <command>` where `<command>` is written below as `RUN <command>`. The repository is bind-mounted at `/workspace`, so test scripts in the repo root are available without rebuilding the image. New `@Grab` dependencies download on first use inside the container; network access is required for that first run.

## Review Focus

Inputs the spec implies but its rule list does not spell out. Each line has a pinned test in the task named.

1. A ZIP whose primary Markdown file is inside a subdirectory (mkdocs layout `docs/`) must still be found and image paths must resolve relative to the file's own directory, not the ZIP root. (Task 2, test "image path relative to entry directory"; Task 4, `primaryFiles` search is recursive.)
2. A language with a revnumber containing a space, such as `8.2 ES`, must match after whitespace normalisation, and a language whose help block starts with a bullet or a `.Contents` label must still yield a usable sentinel. (Task 3, tests "revnumber with space" and Task 7, test "sentinel skips label lines".)
3. Markdown files containing fenced code blocks with HTML or `:::` inside must not trigger `md.rawHtml` or `md.pandocSyntax`. (Task 2, test "code fences are ignored".)
4. A missing ZIP must produce exactly one failing test case with `zip.exists` and no cascade of NullPointerExceptions from later rules. (Task 4, test "missing zip yields single finding".)
5. Non-UTF-8 or binary entries in a Markdown ZIP (images) must never be decoded as text; only entries with the format's extension are text. A ZIP with zero text entries must fail `zip.primaryFile`, not crash. (Task 4, test "binary-only zip".)

## File Structure

| File | Action | Responsibility |
|---|---|---|
| `lib/Checks.groovy` | create | Rule catalogue. Pure functions: `List<Map> checkAll(Map ctx)` and per-family methods, text extraction, heading counting, severity resolution. `@Grab` commonmark and jsoup. |
| `lib/Verifier.groovy` | create | Expected matrix from config, ZIP reading, version.properties, help sentinel, reference heading counts, source suite, orchestration returning `List<Map>` suite results. |
| `lib/Report.groovy` | create | `statusOf`, JUnit XML writer, HTML matrix writer, console summary. |
| `build.groovy` | modify | Phase 5 `verify`, exit code, banner. |
| `buildconfig.groovy` | modify | `verify { }` block. |
| `init-groovy-deps.groovy` | modify | Add the two new `@Grab` lines so the Docker image caches them. |
| `build-arc42.sh` | modify | Remove cmark loop and both image loops. |
| `test-verifier.groovy` | create | Unit tests for Checks, integration test for Verifier and Report on a synthetic fixture, one smoke test on the real dist if present. |
| `run-all-tests.groovy` | modify | Register `test-verifier.groovy`. |
| `README.adoc`, `CLAUDE.md` | modify | Document the phase and the report. |

### Shared data shapes (used by every task)

```groovy
// ctx: everything a rule needs, built by Verifier.buildContext
[
  language      : 'EN',
  style         : 'with-help',           // or 'plain'
  format        : 'markdown',            // key from config.formats
  projectName   : 'arc42-template',      // config.project.name
  formatConfig  : [imageFolder: true],   // config.formats[format]
  entries       : [ 'arc42-template-EN.md': byte[], 'images/arc42-logo.png': byte[] ],  // null when ZIP missing
  zipFile       : File,                  // expected location, may not exist
  revnumber     : '9.0-EN',              // from version.properties, may be null
  helpSentinel  : 'Describes the relevant requirements ...',  // normalised, may be null
  chapterCount  : 12,                    // config.verify.chapterCount
  referenceCounts: [1: 12, 2: 19, 3: 14],  // heading counts of the reference language, same style+format; null when this IS the reference or unavailable
]

// finding
[ruleId: 'md.rawHtml', severity: 'error', message: '65 raw HTML tag(s) not allowed: div',
 examples: [[location: 'arc42-template-EN.md:87', text: '<div class="paragraph">'], ...]]  // max 3

// case result
[language: 'EN', style: 'with-help', format: 'markdown', revnumber: '9.0-EN', revdate: 'July 2025',
 durationMs: 12L, findings: [finding...], status: 'pass'|'warn'|'fail'|'skipped']

// suite result
[name: 'markdown', cases: [caseResult...]]
```

---

### Task 1: Config block, Checks skeleton with severity resolution, Report.statusOf, test harness

**Files:**
- Modify: `buildconfig.groovy` (append after the `distribution { }` block)
- Create: `lib/Checks.groovy`
- Create: `lib/Report.groovy`
- Create: `test-verifier.groovy`

**Interfaces:**
- Produces: `Checks(config)`; `String Checks.severityOf(String ruleId)`; `Map Checks.finding(String ruleId, String message, List examples = [])` returning `null` when the rule is `off`; `static String Checks.normalize(String)`; `Checks.DEFAULT_SEVERITY` map; `Checks.MD_FORMATS`, `Checks.MP_FORMATS`, `Checks.EXTENSIONS`.
- Produces: `static String Report.statusOf(List findings)`.
- Produces: the test file skeleton with helpers `section(name, Closure)`, `expectRules(List findings, List ruleIds)` and `noRule(List findings, String ruleId)` used by every later task.

- [ ] **Step 1: Add the `verify` block to `buildconfig.groovy`**

Append at the end of the file:

```groovy
verify {
    // language whose heading structure other languages are compared against
    referenceLanguage = 'EN'
    // number of top-level chapters every output must contain
    chapterCount = 12
    // reports are written here, relative to this file
    reportDir = 'build/reports'
    // HTML tags tolerated in Markdown output, per format (strict Markdown has no tables)
    allowedHtml = [
        markdownStrict:   ['table', 'thead', 'tbody', 'tr', 'th', 'td', 'col', 'colgroup'],
        markdownMPStrict: ['table', 'thead', 'tbody', 'tr', 'th', 'td', 'col', 'colgroup'],
    ]
    // rule id -> 'error' | 'warn' | 'off'; overrides the built-in default severity
    severity = [
        // 'md.rawHtml': 'warn',
    ]
}
```

- [ ] **Step 2: Write the failing tests for severity resolution and statusOf**

Create `test-verifier.groovy`:

```groovy
#!/usr/bin/env groovy

/**
 * Test script for the verify phase: Checks.groovy, Verifier.groovy, Report.groovy
 *
 * Runs unit tests on synthetic inputs and one smoke test on the real dist if present.
 * Uses build/test-verifier/ as scratch directory.
 */

import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

def gcl = new GroovyClassLoader()
def checksClass = gcl.parseClass(new File('lib/Checks.groovy'))
def reportClass = gcl.parseClass(new File('lib/Report.groovy'))

def failures = []
def section = { String name, Closure body ->
    println "=== ${name} ==="
    try {
        body()
        println "✓ ${name}\n"
    } catch (Throwable t) {
        println "✗ ${name}: ${t.message}\n"
        failures << name
    }
}

// ---- helpers shared by all sections -----------------------------------------

def expectRules = { List findings, List ruleIds ->
    def got = findings*.ruleId.unique().sort()
    ruleIds.each { assert it in got, "expected rule ${it}, got ${got}: ${findings*.message}" }
}
def noRule = { List findings, String ruleId ->
    assert !(ruleId in findings*.ruleId), "rule ${ruleId} must not fire: ${findings.find { it.ruleId == ruleId }?.message}"
}
def baseConfig = { Map verifyOverrides = [:] ->
    def text = '''
project { name = 'demo-template'; featurePrefix = 'demo'; logo = 'demo-logo.png' }
goldenMaster { sourcePath = 'gm/'; targetPath = 'build/src_gen/'; allFeatures = ['help', 'example']
               templateStyles = ['plain': [], 'with-help': ['help']] }
formats = ['markdown': [imageFolder: true], 'markdownMP': [imageFolder: true], 'markdownStrict': [imageFolder: true],
           'html': [imageFolder: true], 'asciidoc': [imageFolder: true], 'docx': [imageFolder: true],
           'epub': [imageFolder: false], 'latex': [imageFolder: true], 'rst': [imageFolder: true],
           'textile': [imageFolder: true], 'docbook': [imageFolder: true]]
distribution { targetPath = 'dist/' }
verify { referenceLanguage = 'EN'; chapterCount = 2; reportDir = 'build/reports'
         allowedHtml = [markdownStrict: ['table', 'thead', 'tbody', 'tr', 'th', 'td', 'col', 'colgroup']]
         severity = [:] }
'''
    def cfg = new ConfigSlurper().parse(text)
    verifyOverrides.each { k, v -> cfg.verify[k] = v }
    return cfg
}
def bytesOf = { Map<String, Object> files ->
    // name -> String or byte[]
    files.collectEntries { k, v -> [(k): v instanceof byte[] ? v : v.toString().getBytes('UTF-8')] }
}
def ctxOf = { Map overrides ->
    def ctx = [language: 'EN', style: 'with-help', format: 'markdown', projectName: 'demo-template',
               formatConfig: [imageFolder: true], entries: [:], zipFile: null, revnumber: '1.0-EN',
               helpSentinel: 'HELP SENTINEL SENTENCE FOR TESTS', chapterCount: 2, referenceCounts: null]
    ctx.putAll(overrides)
    return ctx
}

// ---- Task 1 -----------------------------------------------------------------

section('severity: defaults, overrides, off') {
    def checks = checksClass.newInstance(baseConfig())
    assert checks.severityOf('md.rawHtml') == 'error'
    assert checks.severityOf('structure.referenceCounts') == 'warn'
    assert checks.severityOf('unknown.rule') == 'error', "unknown rules default to error"

    def overridden = checksClass.newInstance(baseConfig([severity: ['md.rawHtml': 'warn', 'md.emptyHeading': 'off']]))
    assert overridden.severityOf('md.rawHtml') == 'warn'
    assert overridden.finding('md.emptyHeading', 'x') == null, "off rules produce no finding"
    def f = overridden.finding('md.rawHtml', 'msg', [[location: 'a.md:1', text: '<div>'], [location: 'a.md:2', text: '<div>'],
                                                    [location: 'a.md:3', text: '<div>'], [location: 'a.md:4', text: '<div>']])
    assert f.severity == 'warn' && f.ruleId == 'md.rawHtml' && f.message == 'msg'
    assert f.examples.size() == 3, "examples are capped at three"
}

section('normalize collapses whitespace') {
    assert checksClass.normalize("  a \n\t b c  ") == 'a b c'
    assert checksClass.normalize(null) == ''
}

section('Report.statusOf') {
    assert reportClass.statusOf([]) == 'pass'
    assert reportClass.statusOf([[ruleId: 'x', severity: 'warn']]) == 'warn'
    assert reportClass.statusOf([[ruleId: 'x', severity: 'warn'], [ruleId: 'y', severity: 'error']]) == 'fail'
}

// ---- summary ----------------------------------------------------------------

println failures ? "✗ ${failures.size()} section(s) failed: ${failures}" : "=== All Tests Passed! ==="
System.exit(failures ? 1 : 0)
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `RUN groovy test-verifier.groovy`
Expected: exception `lib/Checks.groovy (No such file or directory)` before any section runs.

- [ ] **Step 4: Create `lib/Checks.groovy` skeleton**

```groovy
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
        return s.replaceAll(/[\s ]+/, ' ').trim()
    }

    String extensionOf(String format) {
        return EXTENSIONS[format] ?: format
    }
}
```

- [ ] **Step 5: Create `lib/Report.groovy` skeleton**

```groovy
#!/usr/bin/env groovy

import groovy.xml.MarkupBuilder

/**
 * Report.groovy - Output of the verify phase
 *
 * Responsibilities:
 * - Derive a test case status from its findings
 * - Write JUnit XML (Surefire schema), one file per suite
 * - Write a self-contained HTML matrix
 * - Print a console summary
 */
class Report {

    def config
    def projectRoot

    Report(config, projectRoot = new File('.')) {
        this.config = config
        this.projectRoot = projectRoot
    }

    /** 'fail' if any error finding, else 'warn' if any finding, else 'pass' */
    static String statusOf(List findings) {
        if (!findings) return 'pass'
        if (findings.any { it.severity == 'error' }) return 'fail'
        return 'warn'
    }

    File reportDir() {
        return new File(projectRoot, (config.verify?.reportDir ?: 'build/reports').toString())
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `RUN groovy test-verifier.groovy`
Expected: three `✓` lines and `=== All Tests Passed! ===`. The first run downloads commonmark and jsoup via Grape; that can take a minute.

- [ ] **Step 7: Commit**

```bash
git add buildconfig.groovy lib/Checks.groovy lib/Report.groovy test-verifier.groovy
git commit -m "verify: add config block, Checks and Report skeletons with severity resolution

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 2: Markdown purity rules

**Files:**
- Modify: `lib/Checks.groovy` (add methods below the severity section)
- Modify: `test-verifier.groovy` (add sections before the summary)

**Interfaces:**
- Consumes: `finding`, `normalize`, `extensionOf`, `MP_FORMATS`, `HTML_TAGS` from Task 1.
- Produces: `Map<String, String> textEntries(Map ctx)` (entry name → UTF-8 text for entries with the format's extension); `List<Map> lines(String text)` (each `[no, text, inFence]`); `List<Map> checkMarkdown(Map ctx)`; `static String resolvePath(String entryName, String ref)`.

- [ ] **Step 1: Write the failing tests**

Insert before `// ---- summary` in `test-verifier.groovy`:

```groovy
// ---- Task 2: Markdown purity ------------------------------------------------

def GOOD_MD = '''# Chapter One

HELP SENTINEL SENTENCE FOR TESTS. Template Version 1.0-EN.

![logo](images/demo-logo.png)

## Section

- item one
- item two

# Chapter Two

Text with an autolink <https://arc42.org> and escaped \\<placeholder\\>.
'''

section('markdown: clean file has no findings') {
    def checks = checksClass.newInstance(baseConfig())
    def ctx = ctxOf([entries: bytesOf(['demo-template-EN.md': GOOD_MD, 'images/demo-logo.png': [1, 2, 3] as byte[]])])
    def findings = checks.checkMarkdown(ctx)
    assert findings.isEmpty(), "expected no findings, got ${findings*.ruleId} ${findings*.message}"
}

section('markdown: pandoc fenced divs and heading attributes') {
    def checks = checksClass.newInstance(baseConfig())
    def md = '# Chapter One {#section-one}\n\n:::: note\n::: title\n:::\ntext\n::::\n\n# Chapter Two\n'
    def findings = checks.checkMarkdown(ctxOf([entries: bytesOf(['demo-template-EN.md': md])]))
    expectRules(findings, ['md.pandocSyntax'])
    def f = findings.find { it.ruleId == 'md.pandocSyntax' }
    assert f.message.contains('5'), "1 heading attribute + 4 fenced div lines = 5, got: ${f.message}"
    assert f.examples[0].location == 'demo-template-EN.md:1'
}

section('markdown: raw HTML outside the allow list') {
    def checks = checksClass.newInstance(baseConfig())
    def md = '# Chapter One\n\n<div class="paragraph">\n<p>text</p>\n</div>\n\n<img src="images/x.png" />\n\n# Chapter Two\n'
    def findings = checks.checkMarkdown(ctxOf([entries: bytesOf(['demo-template-EN.md': md])]))
    expectRules(findings, ['md.rawHtml', 'md.commonmark'])
    def f = findings.find { it.ruleId == 'md.rawHtml' }
    assert f.message.contains('div') && f.message.contains('img') && f.message.contains('p')
}

section('markdown: tables are allowed in markdownStrict only') {
    def checks = checksClass.newInstance(baseConfig())
    def md = '# Chapter One\n\n<table>\n<tr><td>a</td></tr>\n</table>\n\n# Chapter Two\n'
    noRule(checks.checkMarkdown(ctxOf([format: 'markdownStrict', entries: bytesOf(['demo-template-EN.md': md])])), 'md.rawHtml')
    noRule(checks.checkMarkdown(ctxOf([format: 'markdownStrict', entries: bytesOf(['demo-template-EN.md': md])])), 'md.commonmark')
    expectRules(checks.checkMarkdown(ctxOf([format: 'markdown', entries: bytesOf(['demo-template-EN.md': md])])), ['md.rawHtml'])
}

section('markdown: code fences are ignored') {
    def checks = checksClass.newInstance(baseConfig())
    def md = '# Chapter One\n\n```html\n<div>\n:::\n# not a heading\n```\n\n    <span>indented code</span>\n\n# Chapter Two\n'
    def findings = checks.checkMarkdown(ctxOf([entries: bytesOf(['demo-template-EN.md': md])]))
    noRule(findings, 'md.rawHtml'); noRule(findings, 'md.pandocSyntax'); noRule(findings, 'md.commonmark')
}

section('markdown: empty heading') {
    def checks = checksClass.newInstance(baseConfig())
    def md = '# \n\n# Chapter One\n\n# Chapter Two\n'
    def f = checks.checkMarkdown(ctxOf([entries: bytesOf(['demo-template-EN.md': md])])).find { it.ruleId == 'md.emptyHeading' }
    assert f != null && f.examples[0].location == 'demo-template-EN.md:1'
}

section('markdown: image path relative to entry directory') {
    def checks = checksClass.newInstance(baseConfig())
    def md = '# Chapter One\n\n![a](./images/ok.png)\n![b](images/missing.png)\n![c](https://example.org/remote.png)\n\n# Chapter Two\n'
    def findings = checks.checkMarkdown(ctxOf([entries: bytesOf(['docs/index.md': md, 'docs/images/ok.png': [1] as byte[]])]))
    def f = findings.find { it.ruleId == 'md.images' }
    assert f != null && f.message.contains('1'), "only missing.png is missing: ${f?.message}"
    assert f.examples[0].text.contains('docs/images/missing.png')
}

section('markdown: multi-page chapter files must start with a heading') {
    def checks = checksClass.newInstance(baseConfig())
    def entries = bytesOf(['01_intro.md': '# Chapter One\n\ntext\n', '02_second.md': 'text before heading\n\n# Chapter Two\n'])
    def findings = checks.checkMarkdown(ctxOf([format: 'markdownMP', entries: entries]))
    expectRules(findings, ['md.multiPageHeading'])
    assert findings.find { it.ruleId == 'md.multiPageHeading' }.examples[0].location == '02_second.md:1'
}

section('markdown: front matter title with image warns') {
    def checks = checksClass.newInstance(baseConfig())
    def md = '---\ndate: July 2025\ntitle: "![arc42](images/arc42-logo.png) Template"\n---\n\n# Chapter One\n\n# Chapter Two\n'
    def f = checks.checkMarkdown(ctxOf([entries: bytesOf(['demo-template-EN.md': md])])).find { it.ruleId == 'md.frontMatter' }
    assert f != null && f.severity == 'warn'
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `RUN groovy test-verifier.groovy`
Expected: each new section prints `✗ ... No signature of method: Checks.checkMarkdown`.

- [ ] **Step 3: Implement text helpers and `checkMarkdown`**

Add to `lib/Checks.groovy` inside the class, after `extensionOf`:

```groovy
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
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `RUN groovy test-verifier.groovy`
Expected: all sections `✓`, `=== All Tests Passed! ===`. If "code fences are ignored" fails on `md.commonmark`, check that the indented-code test line is preceded by a blank line; commonmark-java treats it as a code block and emits no HtmlBlock.

- [ ] **Step 5: Commit**

```bash
git add lib/Checks.groovy test-verifier.groovy
git commit -m "verify: Markdown purity rules

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 3: Text extraction, heading counts and structure rules for every format family

**Files:**
- Modify: `lib/Checks.groovy`
- Modify: `test-verifier.groovy`

**Interfaces:**
- Consumes: `textEntries`, `lines`, `normalize`, `finding` from Tasks 1 and 2.
- Produces: `static Map<String, byte[]> unzip(byte[] bytes)`; `String plainText(Map ctx)` (whole visible text of the output, format-aware); `Map<Integer, Integer> headingCounts(Map ctx)` (level → number of non-empty headings); `int chapterLevel(String format)` (2 for html, else 1); `List<Map> checkStructure(Map ctx)`; `Map<String, byte[]> innerZip(Map ctx)` for docx/epub (the document package inside the dist ZIP).
- The test file gains `zipBytes(Map files) -> byte[]`, used again in Tasks 6 and 7.

- [ ] **Step 1: Write the failing tests**

Insert before `// ---- summary`:

```groovy
// ---- Task 3: heading counts and structure -----------------------------------

def zipBytes = { Map<String, Object> files ->
    def bos = new ByteArrayOutputStream()
    new ZipOutputStream(bos).withCloseable { zos ->
        bytesOf(files).each { String name, byte[] data ->
            zos.putNextEntry(new ZipEntry(name)); zos.write(data); zos.closeEntry()
        }
    }
    return bos.toByteArray()
}

section('unzip reads entries into memory') {
    def entries = checksClass.unzip(zipBytes(['a.md': 'hello', 'images/x.png': [1, 2] as byte[]]))
    assert entries.keySet() == ['a.md', 'images/x.png'] as Set
    assert new String(entries['a.md'], 'UTF-8') == 'hello'
}

section('headingCounts: markdown, asciidoc, textile, rst, latex, html, docbook') {
    def checks = checksClass.newInstance(baseConfig())
    def count = { String format, Map files -> checks.headingCounts(ctxOf([format: format, entries: bytesOf(files)])) }

    assert count('markdown', ['x.md': '# \n\n# One\n\n## Sub\n\n### Deep\n\n# Two\n\n```\n# code\n```\n']) == [1: 2, 2: 1, 3: 1]
    assert count('asciidoc', ['demo-template.adoc': '= Title\n\ninclude::src/01.adoc[]\n', 'src/01.adoc': '== One\n\n=== Sub\n', 'src/02.adoc': '== Two\n']) == [1: 2, 2: 1]
    assert count('textile', ['x.textile': 'h1. \n\nh1(#one). One\n\nh2(#sub). Sub\n\nh1. Two\n']) == [1: 2, 2: 1]
    assert count('rst', ['x.rst': '=====\nTitle\n=====\n\nOne\n===\n\nSub\n---\n\nTwo\n===\n']) == [1: 2, 2: 1]
    assert count('latex', ['x.tex': '\\section{}\n\\section{One}\\label{a}\n\\subsection{Sub}\n\\section{Two}\n']) == [1: 2, 2: 1]
    assert count('latex', ['x.tex': '\\chapter{One}\n\\section{Sub}\n\\chapter{Two}\n']) == [1: 2, 2: 1], "chapter present: chapter is level 1"
    assert count('html', ['demo-template.html': '<html><body><h1>T</h1><h2 id="a">One</h2><h3>Sub</h3><h2>Two</h2><h2></h2></body></html>']) == [1: 1, 2: 2, 3: 1]
    assert count('docbook', ['demo-template.xml': '<book><chapter><title>One</title><section><title>S</title></section></chapter><chapter><title>Two</title></chapter></book>']) == [1: 2, 2: 1]
}

def DOCX_XML = { List<List> paragraphs ->
    // paragraphs: [[style, text], ...]
    def body = paragraphs.collect { p ->
        def style = p[0] ? "<w:pPr><w:pStyle w:val=\"${p[0]}\"/></w:pPr>" : ''
        "<w:p>${style}<w:r><w:t>${p[1]}</w:t></w:r></w:p>"
    }.join('')
    return """<?xml version="1.0" encoding="UTF-8"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>${body}</w:body></w:document>"""
}

section('headingCounts and plainText: docx and epub') {
    def checks = checksClass.newInstance(baseConfig())
    def docx = zipBytes(['word/document.xml': DOCX_XML([['Title', 'T'], ['Heading1', ''], ['Heading1', 'One'], ['Heading2', 'Sub'], ['BodyText', 'HELP SENTINEL SENTENCE FOR TESTS 1.0-EN'], ['Heading1', 'Two']])])
    def dctx = ctxOf([format: 'docx', entries: ['demo-template-EN.docx': docx]])
    assert checks.headingCounts(dctx) == [1: 2, 2: 1]
    assert checks.plainText(dctx).contains('HELP SENTINEL SENTENCE FOR TESTS 1.0-EN')

    def epub = zipBytes([
        'mimetype': 'application/epub+zip',
        'META-INF/container.xml': '<container><rootfiles><rootfile full-path="EPUB/content.opf"/></rootfiles></container>',
        'EPUB/content.opf': '<package><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/><item id="t" href="text/title_page.xhtml" media-type="application/xhtml+xml"/><item id="c1" href="text/ch001.xhtml" media-type="application/xhtml+xml"/><item id="c2" href="text/ch002.xhtml" media-type="application/xhtml+xml"/><item id="i" href="media/f.png" media-type="image/png"/></manifest></package>',
        'EPUB/nav.xhtml': '<html><body><h1>Contents</h1></body></html>',
        'EPUB/text/title_page.xhtml': '<html><body><h1>Title</h1></body></html>',
        'EPUB/text/ch001.xhtml': '<html><body><h1></h1><p>about</p></body></html>',
        'EPUB/text/ch002.xhtml': '<html><body><h1>One</h1><h2>Sub</h2><p>HELP SENTINEL SENTENCE FOR TESTS</p><h1>Two</h1></body></html>',
        'EPUB/media/f.png': [1] as byte[]])
    def ectx = ctxOf([format: 'epub', entries: ['demo-template-EN.epub': epub]])
    assert checks.headingCounts(ectx) == [1: 2, 2: 1], "nav and title page are excluded, empty h1 is not counted"
    assert checks.plainText(ectx).contains('HELP SENTINEL SENTENCE FOR TESTS')
}

section('structure: chapters, help text, revnumber') {
    def checks = checksClass.newInstance(baseConfig())
    def good = ctxOf([entries: bytesOf(['demo-template-EN.md': GOOD_MD])])
    assert checks.checkStructure(good).isEmpty(), checks.checkStructure(good)*.message.toString()

    def oneChapter = ctxOf([entries: bytesOf(['demo-template-EN.md': '# Only\n\nHELP SENTINEL SENTENCE FOR TESTS 1.0-EN\n'])])
    expectRules(checks.checkStructure(oneChapter), ['structure.chapters'])

    def plainWithHelp = ctxOf([style: 'plain', entries: bytesOf(['demo-template-EN.md': GOOD_MD])])
    expectRules(checks.checkStructure(plainWithHelp), ['structure.helpText'])

    def helpMissing = ctxOf([entries: bytesOf(['demo-template-EN.md': '# One\n\nVersion 1.0-EN\n\n# Two\n'])])
    expectRules(checks.checkStructure(helpMissing), ['structure.helpText'])

    def noRev = ctxOf([entries: bytesOf(['demo-template-EN.md': '# One\n\nHELP SENTINEL SENTENCE FOR TESTS\n\n# Two\n'])])
    expectRules(checks.checkStructure(noRev), ['structure.revnumber'])

    def noSentinelKnown = ctxOf([helpSentinel: null, entries: bytesOf(['demo-template-EN.md': GOOD_MD])])
    noRule(checks.checkStructure(noSentinelKnown), 'structure.helpText')
}

section('structure: revnumber with space matches after normalisation') {
    def checks = checksClass.newInstance(baseConfig())
    def md = '# One\n\nHELP SENTINEL SENTENCE FOR TESTS\nTemplate Version 8.2\nES\n\n# Two\n'
    noRule(checks.checkStructure(ctxOf([revnumber: '8.2 ES', entries: bytesOf(['x.md': md])])), 'structure.revnumber')
}

section('structure: reference counts warn on mismatch') {
    def checks = checksClass.newInstance(baseConfig())
    def ctx = ctxOf([entries: bytesOf(['x.md': GOOD_MD]), referenceCounts: [1: 2, 2: 3]])
    def f = checks.checkStructure(ctx).find { it.ruleId == 'structure.referenceCounts' }
    assert f != null && f.severity == 'warn' && f.message.contains('level 2')
    noRule(checks.checkStructure(ctxOf([entries: bytesOf(['x.md': GOOD_MD]), referenceCounts: [1: 2, 2: 1]])), 'structure.referenceCounts')
}

section('structure: html chapters are h2') {
    def checks = checksClass.newInstance(baseConfig())
    def html = '<html><head><title>T</title></head><body><h1>T</h1><h2>One</h2><p>HELP SENTINEL SENTENCE FOR TESTS 1.0-EN</p><h2>Two</h2></body></html>'
    assert checks.checkStructure(ctxOf([format: 'html', entries: bytesOf(['demo-template.html': html])])).isEmpty()
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `RUN groovy test-verifier.groovy`
Expected: new sections fail with `No signature of method: Checks.unzip` / `headingCounts` / `checkStructure`.

- [ ] **Step 3: Implement extraction, heading counts and `checkStructure`**

Add to `lib/Checks.groovy`:

```groovy
    // ---- archives ------------------------------------------------------------

    /** Read a ZIP from bytes into name -> content. Directory entries are skipped. */
    static Map<String, byte[]> unzip(byte[] bytes) {
        def result = new LinkedHashMap<String, byte[]>()
        new java.util.zip.ZipInputStream(new ByteArrayInputStream(bytes)).withCloseable { zis ->
            java.util.zip.ZipEntry e
            while ((e = zis.nextEntry) != null) {
                if (!e.isDirectory()) result[e.name] = zis.bytes
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
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `RUN groovy test-verifier.groovy`
Expected: all sections `✓`. If the RST count is off, print `checks.headingCounts(...)` for the RST fixture and compare the underline detection against the sample; the sample's title is overlined and must be skipped.

- [ ] **Step 5: Commit**

```bash
git add lib/Checks.groovy test-verifier.groovy
git commit -m "verify: text extraction, heading counts and structure rules for all format families

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 4: Completeness rules and `checkAll` dispatch

**Files:**
- Modify: `lib/Checks.groovy`
- Modify: `test-verifier.groovy`

**Interfaces:**
- Consumes: `finding`, `extensionOf`, `MP_FORMATS`, `MD_FORMATS`, `checkMarkdown`, `checkStructure`.
- Produces: `List<String> primaryFiles(Map ctx)` (entry names carrying the format's extension, searched recursively); `List<Map> checkCompleteness(Map ctx)`; `List<Map> checkAll(Map ctx)` which later tasks extend with `checkHtml`, `checkDocx`, `checkEpub`.

- [ ] **Step 1: Write the failing tests**

Insert before `// ---- summary`:

```groovy
// ---- Task 4: completeness and dispatch ----------------------------------------

section('completeness: missing zip yields single finding') {
    def checks = checksClass.newInstance(baseConfig())
    def findings = checks.checkAll(ctxOf([entries: null, zipFile: new File('build/test-verifier/does-not-exist.zip')]))
    assert findings*.ruleId == ['zip.exists'], "got ${findings*.ruleId}"
}

section('completeness: empty zip, primary file, images') {
    def checks = checksClass.newInstance(baseConfig())
    expectRules(checks.checkCompleteness(ctxOf([entries: [:]])), ['zip.nonEmpty'])
    expectRules(checks.checkCompleteness(ctxOf([entries: bytesOf(['images/demo-logo.png': [1] as byte[]])])), ['zip.primaryFile'])
    // binary-only zip must not crash the text rules either
    def all = checks.checkAll(ctxOf([entries: bytesOf(['images/demo-logo.png': [1] as byte[]])]))
    expectRules(all, ['zip.primaryFile'])
    // with-help + imageFolder true needs images/
    expectRules(checks.checkCompleteness(ctxOf([entries: bytesOf(['demo-template-EN.md': GOOD_MD])])), ['zip.images'])
    noRule(checks.checkCompleteness(ctxOf([style: 'plain', entries: bytesOf(['demo-template-EN.md': GOOD_MD])])), 'zip.images')
    noRule(checks.checkCompleteness(ctxOf([format: 'epub', formatConfig: [imageFolder: false], entries: bytesOf(['demo-template-EN.epub': [1] as byte[]])])), 'zip.images')
    // mkdocs keeps images under docs/images
    noRule(checks.checkCompleteness(ctxOf([format: 'mkdocs', entries: bytesOf(['demo-template-EN.md': GOOD_MD, 'docs/images/l.png': [1] as byte[]])])), 'zip.images')
}

section('completeness: primary file names per family') {
    def checks = checksClass.newInstance(baseConfig())
    assert checks.primaryFiles(ctxOf([format: 'html', entries: bytesOf(['demo-template.html': 'x', 'images/a.png': [1] as byte[]])])) == ['demo-template.html']
    assert checks.primaryFiles(ctxOf([format: 'asciidoc', entries: bytesOf(['demo-template.adoc': 'x', 'src/01.adoc': 'y'])])) == ['demo-template.adoc', 'src/01.adoc']
    assert checks.primaryFiles(ctxOf([format: 'mkdocs', entries: bytesOf(['docs/index.md': 'x'])])) == ['docs/index.md'], "recursive search"
}

section('completeness: multi-page needs chapterCount chapter files and no config file') {
    def checks = checksClass.newInstance(baseConfig())
    def single = ctxOf([format: 'markdownMP', entries: bytesOf(['demo-template-EN.md': GOOD_MD, 'images/l.png': [1] as byte[]])])
    def f = checks.checkCompleteness(single).find { it.ruleId == 'zip.primaryFile' }
    assert f != null && f.message.contains('0 chapter file(s)'), f?.message
    def good = ctxOf([format: 'markdownMP', entries: bytesOf(['01_a.md': '# One\n', '02_b.md': '# Two\n', 'images/l.png': [1] as byte[]])])
    noRule(checks.checkCompleteness(good), 'zip.primaryFile')
    def withConfig = ctxOf([format: 'markdownMP', entries: bytesOf(['01_a.md': '# One\n', '02_b.md': '# Two\n', 'config.md': '', 'images/l.png': [1] as byte[]])])
    assert checks.checkCompleteness(withConfig).find { it.ruleId == 'zip.primaryFile' }.message.contains('config.md')
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `RUN groovy test-verifier.groovy`
Expected: new sections fail with `No signature of method: Checks.checkAll` / `checkCompleteness` / `primaryFiles`.

- [ ] **Step 3: Implement completeness rules and dispatch**

Add to `lib/Checks.groovy`:

```groovy
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
        if (ctx.format == 'html' && respondsTo('checkHtml')) findings.addAll(checkHtml(ctx))
        if (ctx.format == 'docx' && respondsTo('checkDocx')) findings.addAll(checkDocx(ctx))
        if (ctx.format == 'epub' && respondsTo('checkEpub')) findings.addAll(checkEpub(ctx))
        return findings
    }
```

The `respondsTo` guards let this task pass before Tasks 5 and 6 add the methods; Task 6 removes the guards.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `RUN groovy test-verifier.groovy`
Expected: all `✓`.

- [ ] **Step 5: Commit**

```bash
git add lib/Checks.groovy test-verifier.groovy
git commit -m "verify: completeness rules and checkAll dispatch

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 5: HTML rules

**Files:**
- Modify: `lib/Checks.groovy`
- Modify: `test-verifier.groovy`

**Interfaces:**
- Consumes: `textEntries`, `resolvePath`, `isRemote`, `finding`, jsoup.
- Produces: `List<Map> checkHtml(Map ctx)`.

- [ ] **Step 1: Write the failing tests**

Insert before `// ---- summary`:

```groovy
// ---- Task 5: HTML -----------------------------------------------------------

def GOOD_HTML = '''<!DOCTYPE html><html lang="en"><head><meta charset="UTF-8"><title>Template</title></head>
<body><h1>Template</h1><div id="toc"><a href="#one">One</a></div>
<h2 id="one">One</h2><p>HELP SENTINEL SENTENCE FOR TESTS 1.0-EN</p><img src="images/demo-logo.png" alt="logo">
<h2 id="two">Two</h2><a href="https://arc42.org">ext</a></body></html>'''

section('html: clean document') {
    def checks = checksClass.newInstance(baseConfig())
    def ctx = ctxOf([format: 'html', entries: bytesOf(['demo-template.html': GOOD_HTML, 'images/demo-logo.png': [1] as byte[]])])
    assert checks.checkAll(ctx).isEmpty(), checks.checkAll(ctx)*.message.toString()
}

section('html: missing title, charset, image, broken anchor, malformed') {
    def checks = checksClass.newInstance(baseConfig())
    def bad = '<html><head><title></title></head><body><h2>One</h2><a href="#nowhere">x</a><img src="images/gone.png"></body></html>'
    def findings = checks.checkHtml(ctxOf([format: 'html', entries: bytesOf(['demo-template.html': bad])]))
    expectRules(findings, ['html.title', 'html.charset', 'html.images', 'html.localLinks'])
    assert findings.find { it.ruleId == 'html.localLinks' }.severity == 'warn'
    noRule(findings, 'html.wellFormed')

    def twoBodies = '<html><head><meta charset="utf-8"><title>T</title></head><body></body><body></body></html>'
    expectRules(checks.checkHtml(ctxOf([format: 'html', entries: bytesOf(['demo-template.html': twoBodies])])), ['html.wellFormed'])
    def noHtmlTag = '<h2>One</h2>'
    expectRules(checks.checkHtml(ctxOf([format: 'html', entries: bytesOf(['demo-template.html': noHtmlTag])])), ['html.wellFormed'])
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `RUN groovy test-verifier.groovy`
Expected: `No signature of method: Checks.checkHtml`.

- [ ] **Step 3: Implement `checkHtml`**

Add to `lib/Checks.groovy`:

```groovy
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
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `RUN groovy test-verifier.groovy`
Expected: all `✓`.

- [ ] **Step 5: Commit**

```bash
git add lib/Checks.groovy test-verifier.groovy
git commit -m "verify: HTML rules

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 6: DOCX and EPUB rules

**Files:**
- Modify: `lib/Checks.groovy`
- Modify: `test-verifier.groovy`

**Interfaces:**
- Consumes: `innerZip`, `docxParagraphs`, `epubContentDocs`, `resolvePath`, `finding`, `groovy.xml.XmlSlurper`.
- Produces: `List<Map> checkDocx(Map ctx)`, `List<Map> checkEpub(Map ctx)`. Removes the `respondsTo` guards from `checkAll`.

- [ ] **Step 1: Write the failing tests**

Insert before `// ---- summary`:

```groovy
// ---- Task 6: DOCX and EPUB ----------------------------------------------------

section('docx: valid package with matching media') {
    def checks = checksClass.newInstance(baseConfig())
    def xml = DOCX_XML([['Heading1', 'One'], ['BodyText', 'HELP SENTINEL SENTENCE FOR TESTS 1.0-EN'], ['Heading1', 'Two']])
        .replace('</w:body>', '<w:p><w:r><w:drawing/></w:r></w:p></w:body>')
    def docx = zipBytes(['word/document.xml': xml, 'word/media/image1.png': [1] as byte[]])
    def ctx = ctxOf([format: 'docx', entries: ['demo-template-EN.docx': docx, 'images/l.png': [1] as byte[]]])
    assert checks.checkAll(ctx).isEmpty(), checks.checkAll(ctx)*.message.toString()
}

section('docx: unparsable xml, media mismatch, plain needs no media') {
    def checks = checksClass.newInstance(baseConfig())
    def broken = zipBytes(['word/document.xml': '<w:document><unclosed>'])
    expectRules(checks.checkDocx(ctxOf([format: 'docx', entries: ['demo-template-EN.docx': broken]])), ['docx.valid'])
    def notAZip = ctxOf([format: 'docx', entries: ['demo-template-EN.docx': 'plain text'.bytes]])
    expectRules(checks.checkDocx(notAZip), ['docx.valid'])

    def xml = DOCX_XML([['Heading1', 'One'], ['Heading1', 'Two']]).replace('</w:body>', '<w:p><w:r><w:drawing/></w:r></w:p></w:body>')
    def noMedia = zipBytes(['word/document.xml': xml])
    expectRules(checks.checkDocx(ctxOf([format: 'docx', entries: ['demo-template-EN.docx': noMedia]])), ['docx.media'])
    def plainXml = DOCX_XML([['Heading1', 'One'], ['Heading1', 'Two']])
    noRule(checks.checkDocx(ctxOf([format: 'docx', style: 'plain', entries: ['demo-template-EN.docx': zipBytes(['word/document.xml': plainXml])]])), 'docx.media')
}

def EPUB_FILES = { Map extra ->
    def files = [
        'mimetype': 'application/epub+zip',
        'META-INF/container.xml': '<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="EPUB/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>',
        'EPUB/content.opf': '<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0"><manifest><item id="c1" href="text/ch001.xhtml" media-type="application/xhtml+xml"/><item id="i" href="media/f.png" media-type="image/png"/></manifest></package>',
        'EPUB/text/ch001.xhtml': '<html><body><h1>One</h1><p>HELP SENTINEL SENTENCE FOR TESTS 1.0-EN</p><h1>Two</h1></body></html>',
        'EPUB/media/f.png': [1] as byte[]]
    files.putAll(extra)
    return files
}

section('epub: valid package') {
    def checks = checksClass.newInstance(baseConfig())
    def ctx = ctxOf([format: 'epub', formatConfig: [imageFolder: false], entries: ['demo-template-EN.epub': zipBytes(EPUB_FILES([:]))]])
    assert checks.checkAll(ctx).isEmpty(), checks.checkAll(ctx)*.message.toString()
}

section('epub: missing manifest item, missing container, no images in with-help') {
    def checks = checksClass.newInstance(baseConfig())
    def files = EPUB_FILES([:]); files.remove('EPUB/media/f.png')
    def findings = checks.checkEpub(ctxOf([format: 'epub', entries: ['demo-template-EN.epub': zipBytes(files)]]))
    expectRules(findings, ['epub.valid'])
    assert findings.find { it.ruleId == 'epub.valid' }.examples[0].text.contains('media/f.png')

    def noContainer = EPUB_FILES([:]); noContainer.remove('META-INF/container.xml')
    expectRules(checks.checkEpub(ctxOf([format: 'epub', entries: ['demo-template-EN.epub': zipBytes(noContainer)]])), ['epub.valid'])

    def noImages = EPUB_FILES(['EPUB/content.opf': '<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf"><manifest><item id="c1" href="text/ch001.xhtml" media-type="application/xhtml+xml"/></manifest></package>'])
    noImages.remove('EPUB/media/f.png')
    expectRules(checks.checkEpub(ctxOf([format: 'epub', entries: ['demo-template-EN.epub': zipBytes(noImages)]])), ['epub.media'])
    noRule(checks.checkEpub(ctxOf([format: 'epub', style: 'plain', entries: ['demo-template-EN.epub': zipBytes(noImages)]])), 'epub.media')
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `RUN groovy test-verifier.groovy`
Expected: `No signature of method: Checks.checkDocx` / `checkEpub`.

- [ ] **Step 3: Implement `checkDocx` and `checkEpub`, remove the guards**

Add to `lib/Checks.groovy`:

```groovy
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
```

Then in `checkAll` replace the three guarded lines with:

```groovy
        if (ctx.format == 'html') findings.addAll(checkHtml(ctx))
        if (ctx.format == 'docx') findings.addAll(checkDocx(ctx))
        if (ctx.format == 'epub') findings.addAll(checkEpub(ctx))
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `RUN groovy test-verifier.groovy`
Expected: all `✓`.

- [ ] **Step 5: Commit**

```bash
git add lib/Checks.groovy test-verifier.groovy
git commit -m "verify: DOCX and EPUB rules

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 7: Verifier: matrix, ZIP reading, metadata, sentinel, reference counts

**Files:**
- Create: `lib/Verifier.groovy`
- Modify: `test-verifier.groovy`

**Interfaces:**
- Consumes: `Checks` (constructed inside Verifier from the same config), `Checks.unzip`, `Checks.checkAll`, `Checks.headingCounts`, `Report.statusOf`.
- Produces: `Verifier(config, projectRoot, checks)` (no defaults, all three required); `List<String> languages()`; `List<String> styles()`; `Map versionProps(String lang)`; `String helpSentinel(String lang)`; `File zipFile(String lang, String style, String format)`; `Map buildContext(String lang, String style, String format)`; `Map verifyCase(String lang, String style, String format)`; `List<Map> verifySuites(List<String> formats)` (one suite per format, no source suite yet).
- Verifier loads `Checks` and `Report` itself via `GroovyClassLoader` from `lib/` next to its own source? No: build.groovy and the test load all classes with one `GroovyClassLoader` and pass instances. `Verifier` therefore takes the checks object as a constructor argument: `Verifier(config, projectRoot, checks)`.

- [ ] **Step 1: Write the failing tests**

Insert before `// ---- summary`. This creates a synthetic golden master and dist under `build/test-verifier/`:

```groovy
// ---- Task 7: Verifier ---------------------------------------------------------

def verifierClass = gcl.parseClass(new File('lib/Verifier.groovy'))

def fixtureRoot = new File('build/test-verifier')
def makeFixture = { Map<String, Object> zips ->
    if (fixtureRoot.exists()) fixtureRoot.deleteDir()
    new File(fixtureRoot, 'gm/EN/adoc').mkdirs()
    new File(fixtureRoot, 'gm/EN/images').mkdirs()
    new File(fixtureRoot, 'gm/EN/version.properties').write("revnumber=1.0-EN\nrevdate=September 2026\nrevremark=(test)\n", 'utf-8')
    new File(fixtureRoot, 'gm/EN/demo-template.adoc').write('= Demo\n\ninclude::adoc/config.adoc[]\n\ninclude::adoc/01_one.adoc[]\n\ninclude::adoc/02_two.adoc[]\n', 'utf-8')
    new File(fixtureRoot, 'gm/EN/adoc/config.adoc').write(':imagesdir: ./images\n:demohelp:\n', 'utf-8')
    new File(fixtureRoot, 'gm/EN/adoc/01_one.adoc').write('''== Chapter One

ifdef::demohelp[]
[role="demohelp"]
****
.Contents
* a bullet first
HELP SENTINEL SENTENCE FOR TESTS and some more words.
****
endif::demohelp[]

image::demo-logo.png[]
''', 'utf-8')
    new File(fixtureRoot, 'gm/EN/adoc/02_two.adoc').write('== Chapter Two\n\ntext\n', 'utf-8')
    new File(fixtureRoot, 'gm/EN/images/demo-logo.png').bytes = [1, 2, 3] as byte[]
    new File(fixtureRoot, 'dist').mkdirs()
    zips.each { String name, Object files -> new File(fixtureRoot, "dist/${name}").bytes = zipBytes(files as Map) }
    def cfg = baseConfig()
    cfg.formats = ['markdown': [imageFolder: true], 'markdownMP': [imageFolder: true]]
    return cfg
}

def GOOD_ZIP = ['demo-template-EN.md': GOOD_MD, 'images/demo-logo.png': [1] as byte[]]
def PLAIN_ZIP = ['demo-template-EN.md': '# Chapter One\n\nTemplate Version 1.0-EN\n\n# Chapter Two\n', 'images/demo-logo.png': [1] as byte[]]

section('verifier: languages, styles, version properties, sentinel skips label lines') {
    def cfg = makeFixture([:])
    def v = verifierClass.newInstance(cfg, fixtureRoot, checksClass.newInstance(cfg))
    assert v.languages() == ['EN']
    assert v.styles() == ['plain', 'with-help']
    assert v.versionProps('EN').revnumber == '1.0-EN' && v.versionProps('EN').revdate == 'September 2026'
    assert v.versionProps('XX') == [:]
    assert v.helpSentinel('EN') == 'HELP SENTINEL SENTENCE FOR TESTS and some more words.', "got '${v.helpSentinel('EN')}'"
    assert v.zipFile('EN', 'with-help', 'markdown').name == 'demo-template-EN-withhelp-markdown.zip'
}

section('verifier: buildContext and verifyCase on a good and a missing zip') {
    def cfg = makeFixture(['demo-template-EN-withhelp-markdown.zip': GOOD_ZIP])
    def v = verifierClass.newInstance(cfg, fixtureRoot, checksClass.newInstance(cfg))
    def ctx = v.buildContext('EN', 'with-help', 'markdown')
    assert ctx.entries.keySet() == GOOD_ZIP.keySet() && ctx.revnumber == '1.0-EN' && ctx.chapterCount == 2
    assert ctx.referenceCounts == null, "EN is the reference language"
    def good = v.verifyCase('EN', 'with-help', 'markdown')
    assert good.status == 'pass' && good.revnumber == '1.0-EN' && good.revdate == 'September 2026', good.findings*.message.toString()
    def missing = v.verifyCase('EN', 'plain', 'markdown')
    assert missing.status == 'fail' && missing.findings*.ruleId == ['zip.exists']
}

section('verifier: reference counts come from the reference language') {
    def cfg = makeFixture(['demo-template-EN-withhelp-markdown.zip': GOOD_ZIP, 'demo-template-DE-withhelp-markdown.zip': ['demo-template-DE.md': '# Eins\n\nHELP SENTINEL SENTENCE FOR TESTS 1.0-DE\n\n## Extra\n\n## Extra2\n\n# Zwei\n', 'images/l.png': [1] as byte[]]])
    new File(fixtureRoot, 'gm/DE/adoc').mkdirs()
    new File(fixtureRoot, 'gm/DE/version.properties').write("revnumber=1.0-DE\nrevdate=2026\n", 'utf-8')
    new File(fixtureRoot, 'gm/DE/adoc/01_one.adoc').write('== Eins\n\n[role="demohelp"]\n****\nHELP SENTINEL SENTENCE FOR TESTS\n****\n', 'utf-8')
    def v = verifierClass.newInstance(cfg, fixtureRoot, checksClass.newInstance(cfg))
    def de = v.buildContext('DE', 'with-help', 'markdown')
    assert de.referenceCounts == [1: 2, 2: 1], "EN GOOD_MD has 2 H1 and 1 H2, got ${de.referenceCounts}"
    def result = v.verifyCase('DE', 'with-help', 'markdown')
    assert result.status == 'warn' && result.findings*.ruleId == ['structure.referenceCounts'], result.findings*.message.toString()
}

section('verifier: verifySuites builds one suite per format with every language x style') {
    def cfg = makeFixture(['demo-template-EN-withhelp-markdown.zip': GOOD_ZIP, 'demo-template-EN-plain-markdown.zip': PLAIN_ZIP])
    def v = verifierClass.newInstance(cfg, fixtureRoot, checksClass.newInstance(cfg))
    def suites = v.verifySuites(['markdown', 'markdownMP'])
    assert suites*.name == ['markdown', 'markdownMP']
    assert suites[0].cases*.status == ['pass', 'pass'], suites[0].cases.collect { "${it.style}: ${it.findings*.message}" }.toString()
    assert suites[1].cases*.status == ['fail', 'fail'], "markdownMP zips do not exist"
    assert suites[0].cases*.durationMs.every { it != null }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `RUN groovy test-verifier.groovy`
Expected: `lib/Verifier.groovy (No such file or directory)`.

- [ ] **Step 3: Create `lib/Verifier.groovy`**

```groovy
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
        def chapter = adocDir.listFiles()?.findAll { it.name ==~ /^01_.*\.adoc$/ }?.sort()?.first()
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
```

`statusOf` is duplicated from `Report` on purpose so `Verifier` does not depend on `Report`; both are three lines.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `RUN groovy test-verifier.groovy`
Expected: all `✓`. If "sentinel skips label lines" fails, print `chapter.getText('UTF-8')` and check the regex: the block opener is `[role="demohelp"]` followed by a newline and `****`.

- [ ] **Step 5: Commit**

```bash
git add lib/Verifier.groovy test-verifier.groovy
git commit -m "verify: Verifier builds the matrix, reads ZIPs and runs the checks

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 8: Source suite (Golden Master validation)

**Files:**
- Modify: `lib/Verifier.groovy`
- Modify: `test-verifier.groovy`

**Interfaces:**
- Consumes: `languages`, `versionProps`, `goldenMasterDir`, `checks.finding`.
- Produces: `Map verifySource()` returning a suite `[name: 'source', cases: [...]]` with one case per language (`style: '-'`, `format: 'source'`); `List<Map> verifyAll(List<String> formats)` returning `[sourceSuite] + verifySuites(formats)`.

- [ ] **Step 1: Write the failing tests**

Insert before `// ---- summary`:

```groovy
// ---- Task 8: source suite -----------------------------------------------------

section('source suite: clean golden master passes') {
    def cfg = makeFixture([:])
    def v = verifierClass.newInstance(cfg, fixtureRoot, checksClass.newInstance(cfg))
    def suite = v.verifySource()
    assert suite.name == 'source' && suite.cases.size() == 1
    assert suite.cases[0].status == 'pass', suite.cases[0].findings*.message.toString()
    assert suite.cases[0].language == 'EN' && suite.cases[0].revnumber == '1.0-EN'
}

section('source suite: missing revdate, broken include, missing image, wrong chapter count, missing main') {
    def cfg = makeFixture([:])
    new File(fixtureRoot, 'gm/EN/version.properties').write("revnumber=1.0-EN\n", 'utf-8')
    new File(fixtureRoot, 'gm/EN/demo-template.adoc').append('\ninclude::adoc/99_missing.adoc[]\n', 'utf-8')
    new File(fixtureRoot, 'gm/EN/adoc/02_two.adoc').append('\nimage::not-there.png[]\n', 'utf-8')
    new File(fixtureRoot, 'gm/EN/adoc/03_three.adoc').write('== Three\n', 'utf-8')
    def v = verifierClass.newInstance(cfg, fixtureRoot, checksClass.newInstance(cfg))
    def c = v.verifySource().cases[0]
    expectRules(c.findings, ['src.versionProperties', 'src.includes', 'src.images', 'src.chapters'])
    assert c.findings.find { it.ruleId == 'src.includes' }.examples[0].text.contains('99_missing.adoc')

    assert new File(fixtureRoot, 'gm/EN/demo-template.adoc').delete()
    expectRules(verifierClass.newInstance(cfg, fixtureRoot, checksClass.newInstance(cfg)).verifySource().cases[0].findings, ['src.mainFile'])
}

section('verifyAll puts the source suite first') {
    def cfg = makeFixture(['demo-template-EN-withhelp-markdown.zip': GOOD_ZIP])
    def v = verifierClass.newInstance(cfg, fixtureRoot, checksClass.newInstance(cfg))
    assert v.verifyAll(['markdown'])*.name == ['source', 'markdown']
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `RUN groovy test-verifier.groovy`
Expected: `No signature of method: Verifier.verifySource`.

- [ ] **Step 3: Implement `verifySource` and `verifyAll`**

Add to `lib/Verifier.groovy` before the closing brace:

```groovy
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
```

Image references in the arc42 chapters look like `image::05_building_blocks-EN.png[...]`, relative to `imagesdir`, which `config.adoc` sets to `./images`. Resolution against `<LANG>/images/` matches that. The logo reference in the main document is `image:arc42-logo.png[arc42]` and resolves the same way.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `RUN groovy test-verifier.groovy`
Expected: all `✓`.

- [ ] **Step 5: Commit**

```bash
git add lib/Verifier.groovy test-verifier.groovy
git commit -m "verify: source suite validates the golden master per language

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 9: JUnit XML report

**Files:**
- Modify: `lib/Report.groovy`
- Modify: `test-verifier.groovy`

**Interfaces:**
- Consumes: suite results from `Verifier.verifyAll`, `Report.statusOf`, `Report.reportDir`.
- Produces: `List<File> writeJUnit(List suites, File dir)` writing `TEST-<suite>.xml` per suite and returning the files; `static String caseName(Map c)` = `"<LANG>-<style> [<revnumber>]"`.

- [ ] **Step 1: Write the failing tests**

Insert before `// ---- summary`:

```groovy
// ---- Task 9: JUnit XML ---------------------------------------------------------

def SAMPLE_SUITES = [
    [name: 'source', cases: [[language: 'EN', style: '-', format: 'source', revnumber: '1.0-EN', revdate: '2026', durationMs: 5L, findings: [], status: 'pass']]],
    [name: 'markdown', cases: [
        [language: 'EN', style: 'with-help', format: 'markdown', revnumber: '1.0-EN', revdate: '2026', durationMs: 12L, findings: [], status: 'pass'],
        [language: 'EN', style: 'plain', format: 'markdown', revnumber: '1.0-EN', revdate: '2026', durationMs: 7L,
         findings: [[ruleId: 'md.rawHtml', severity: 'error', message: '2 raw HTML tag(s) not allowed: div', examples: [[location: 'x.md:3', text: '<div class="a">'], [location: 'x.md:9', text: '</div>']]],
                    [ruleId: 'md.frontMatter', severity: 'warn', message: 'front matter title contains an image', examples: [[location: 'x.md:1', text: 'title: "![l](i.png)"']]]],
         status: 'fail'],
        [language: 'DE', style: 'plain', format: 'markdown', revnumber: '0.9-DE', revdate: '2025', durationMs: 7L,
         findings: [[ruleId: 'structure.referenceCounts', severity: 'warn', message: 'heading counts differ from reference: level 2: 3 vs 1', examples: []]], status: 'warn'],
    ]],
]

section('junit: one file per suite with Surefire structure') {
    def cfg = baseConfig()
    def report = reportClass.newInstance(cfg, fixtureRoot)
    def dir = new File(fixtureRoot, 'reports/junit')
    def files = report.writeJUnit(SAMPLE_SUITES, dir)
    assert files*.name == ['TEST-source.xml', 'TEST-markdown.xml']

    def suite = new groovy.xml.XmlSlurper().parse(new File(dir, 'TEST-markdown.xml'))
    assert suite.name() == 'testsuite'
    assert suite.@name == 'markdown' && suite.@tests == '3' && suite.@failures == '1' && suite.@errors == '0' && suite.@skipped == '0'
    assert suite.properties.property.find { it.@name == 'revnumber.EN' }.@value == '1.0-EN'
    assert suite.properties.property.find { it.@name == 'revnumber.DE' }.@value == '0.9-DE'

    def cases = suite.testcase
    assert cases.collect { it.@name.text() } == ['EN-with-help [1.0-EN]', 'EN-plain [1.0-EN]', 'DE-plain [0.9-DE]']
    assert cases[0].@classname == 'demo.verify.markdown'
    assert cases[1].failure.size() == 1
    assert cases[1].failure.@message.text() == 'md.rawHtml (2)'
    assert cases[1].failure.text().contains('x.md:3') && cases[1].failure.text().contains('<div class="a">')
    assert cases[1]['system-out'].text().contains('md.frontMatter'), "warnings go to system-out"
    assert cases[1].properties.property.find { it.@name == 'revnumber' }.@value == '1.0-EN'
    assert cases[2].failure.size() == 0 && cases[2]['system-out'].text().contains('structure.referenceCounts')
    assert cases[0].@time.text() == '0.012'
}

section('junit: skipped status is rendered') {
    def report = reportClass.newInstance(baseConfig(), fixtureRoot)
    def suites = [[name: 'x', cases: [[language: 'EN', style: 'plain', format: 'x', revnumber: null, revdate: null, durationMs: 0L, findings: [], status: 'skipped']]]]
    report.writeJUnit(suites, new File(fixtureRoot, 'reports/junit2'))
    def suite = new groovy.xml.XmlSlurper().parse(new File(fixtureRoot, 'reports/junit2/TEST-x.xml'))
    assert suite.@skipped == '1' && suite.testcase[0].skipped.size() == 1
    assert suite.testcase[0].@name == 'EN-plain', "no revnumber, no bracket"
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `RUN groovy test-verifier.groovy`
Expected: `No signature of method: Report.writeJUnit`.

- [ ] **Step 3: Implement `writeJUnit`**

Add to `lib/Report.groovy`:

```groovy
    static String caseName(Map c) {
        def base = "${c.language}-${c.style}"
        return c.revnumber ? "${base} [${c.revnumber}]".toString() : base.toString()
    }

    private static String seconds(Long ms) { return String.format(Locale.ROOT, '%.3f', (ms ?: 0L) / 1000.0) }

    private static String describe(List findings) {
        return findings.collect { f ->
            def lines = ["${f.ruleId} [${f.severity}]: ${f.message}"]
            (f.examples ?: []).each { e -> lines << "    ${e.location}: ${e.text}" }
            lines.join('\n')
        }.join('\n')
    }

    /** One Surefire-style XML file per suite: TEST-<suite>.xml */
    List<File> writeJUnit(List suites, File dir) {
        dir.mkdirs()
        def classPrefix = "${config.project.featurePrefix ?: 'project'}.verify"
        return suites.collect { suite ->
            def file = new File(dir, "TEST-${suite.name}.xml")
            def cases = suite.cases
            def writer = new StringWriter()
            def xml = new MarkupBuilder(writer)
            xml.mkp.xmlDeclaration(version: '1.0', encoding: 'UTF-8')
            xml.testsuite(name: suite.name, tests: cases.size(),
                          failures: cases.count { it.status == 'fail' }, errors: 0,
                          skipped: cases.count { it.status == 'skipped' },
                          time: seconds(cases.sum { it.durationMs ?: 0L } as Long),
                          timestamp: new Date().format("yyyy-MM-dd'T'HH:mm:ss")) {
                properties {
                    cases.collect { it.language }.unique().each { lang ->
                        def c = cases.find { it.language == lang }
                        if (c.revnumber) property(name: "revnumber.${lang}", value: c.revnumber)
                        if (c.revdate) property(name: "revdate.${lang}", value: c.revdate)
                    }
                }
                cases.each { c ->
                    testcase(name: caseName(c), classname: "${classPrefix}.${suite.name}", time: seconds(c.durationMs)) {
                        properties {
                            property(name: 'language', value: c.language)
                            property(name: 'style', value: c.style)
                            property(name: 'format', value: c.format)
                            if (c.revnumber) property(name: 'revnumber', value: c.revnumber)
                            if (c.revdate) property(name: 'revdate', value: c.revdate)
                        }
                        def errors = c.findings.findAll { it.severity == 'error' }
                        def warns = c.findings.findAll { it.severity != 'error' }
                        if (c.status == 'skipped') {
                            skipped()
                        } else if (errors) {
                            failure(message: errors.collect { "${it.ruleId} (${it.message.find(/\d+/) ?: '1'})" }.join(', '), describe(errors))
                        }
                        if (warns) {
                            'system-out'(describe(warns))
                        }
                    }
                }
            }
            file.write(writer.toString(), 'UTF-8')
            return file
        }
    }
```

The failure `message` attribute shows each rule with the leading number of its message (the count), e.g. `md.rawHtml (2)`; the element body carries the full text with examples.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `RUN groovy test-verifier.groovy`
Expected: all `✓`. If `@time` differs, check that `seconds` uses `Locale.ROOT`; a German locale would print `0,012`.

- [ ] **Step 5: Commit**

```bash
git add lib/Report.groovy test-verifier.groovy
git commit -m "verify: JUnit XML report writer

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 10: HTML matrix and console summary

**Files:**
- Modify: `lib/Report.groovy`
- Modify: `test-verifier.groovy`

**Interfaces:**
- Consumes: suite results, `caseName`, `describe`.
- Produces: `File writeHtml(List suites, File file, Map meta)` where `meta` has `project`, `date`, `generatorCommit`, `goldenMasterCommit` (strings, may be null); `void printSummary(List suites, File junitDir, File htmlFile)`; `boolean hasFailures(List suites)`; `List<File> writeAll(List suites, Map meta)` which writes both reports under `reportDir()` and prints the summary.

- [ ] **Step 1: Write the failing tests**

Insert before `// ---- summary`:

```groovy
// ---- Task 10: HTML matrix and console -------------------------------------------

section('html report: matrix rows per language with version, failures grouped by rule') {
    def report = reportClass.newInstance(baseConfig(), fixtureRoot)
    def file = report.writeHtml(SAMPLE_SUITES, new File(fixtureRoot, 'reports/verify.html'), [project: 'demo-template', date: '2026-09-26', generatorCommit: 'abc1234', goldenMasterCommit: 'def5678'])
    def html = file.getText('UTF-8')
    assert html.startsWith('<!DOCTYPE html>') && !html.contains('<link ') && !html.contains('<script src'), "self-contained"
    assert html.contains('demo-template') && html.contains('abc1234') && html.contains('def5678')
    assert html.contains('1.0-EN') && html.contains('0.9-DE'), "versions per language row"
    assert html.contains('<th>markdown</th>') && html.contains('<th>source</th>')
    assert html.contains('EN-plain [1.0-EN]'), "failed case is named"
    assert html.contains('md.rawHtml') && html.contains('&lt;div class=&quot;a&quot;&gt;'), "examples are HTML-escaped"
    assert html.indexOf('md.rawHtml') < html.indexOf('structure.referenceCounts'), "errors before warnings"
    assert html.count('class="cell fail"') == 1 && html.count('class="cell warn"') == 1 && html.count('class="cell pass"') == 2
    assert html.contains('class="cell missing"'), "DE with-help markdown has no case and shows as missing"
}

section('console summary and hasFailures') {
    def report = reportClass.newInstance(baseConfig(), fixtureRoot)
    assert report.hasFailures(SAMPLE_SUITES)
    assert !report.hasFailures([SAMPLE_SUITES[0]])
    def out = new ByteArrayOutputStream()
    def old = System.out
    System.setOut(new PrintStream(out, true, 'UTF-8'))
    try { report.printSummary(SAMPLE_SUITES, new File('r/junit'), new File('r/verify.html')) } finally { System.setOut(old) }
    def text = out.toString('UTF-8')
    assert text.contains('markdown') && text.contains('1 pass') && text.contains('1 warn') && text.contains('1 fail')
    assert text.contains('EN-plain [1.0-EN]') && text.contains('md.rawHtml')
    assert text.contains('r/junit') && text.contains('verify.html')
}

section('writeAll writes into reportDir and returns both files') {
    def cfg = baseConfig([reportDir: 'reports-all'])
    def report = reportClass.newInstance(cfg, fixtureRoot)
    def files = report.writeAll(SAMPLE_SUITES, [project: 'demo-template', date: 'today', generatorCommit: null, goldenMasterCommit: null])
    assert files*.name.containsAll(['TEST-source.xml', 'TEST-markdown.xml', 'verify.html'])
    assert new File(fixtureRoot, 'reports-all/junit/TEST-markdown.xml').exists()
    assert new File(fixtureRoot, 'reports-all/verify.html').exists()
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `RUN groovy test-verifier.groovy`
Expected: `No signature of method: Report.writeHtml`.

- [ ] **Step 3: Implement the HTML matrix, console summary and `writeAll`**

Add to `lib/Report.groovy`:

```groovy
    static String esc(Object s) {
        return (s == null ? '' : s.toString()).replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;').replace('"', '&quot;')
    }

    boolean hasFailures(List suites) {
        return suites.any { s -> s.cases.any { it.status == 'fail' } }
    }

    /** Self-contained HTML page: header, matrix (languages x formats), findings grouped by rule */
    File writeHtml(List suites, File file, Map meta) {
        file.parentFile.mkdirs()
        def formats = suites*.name
        def cases = suites.collectMany { s -> s.cases }
        def languages = cases*.language.unique().sort()
        def styles = cases.findAll { it.format != 'source' }*.style.unique().sort()
        def lookup = cases.collectEntries { [("${it.language}|${it.style}|${it.format}".toString()): it] }
        def version = { lang -> def c = cases.find { it.language == lang && it.revnumber }; c ? "${c.revnumber}<br><small>${esc(c.revdate)}</small>" : '' }

        def sb = new StringBuilder()
        sb << '<!DOCTYPE html>\n<html lang="en"><head><meta charset="UTF-8"><title>Verify report: ' << esc(meta.project) << '</title>\n<style>\n'
        sb << 'body{font-family:system-ui,sans-serif;margin:2em;color:#222}table{border-collapse:collapse}th,td{border:1px solid #ccc;padding:4px 8px;text-align:center;font-size:90%}'
        sb << 'th{background:#f3f3f3}td.lang{text-align:left;font-weight:bold}.cell,.tag{display:inline-block;min-width:1.6em;padding:2px 4px;margin:1px;border-radius:3px;color:#fff;font-size:80%}'
        sb << '.pass{background:#2e8b57}.warn{background:#d4a017}.fail{background:#c0392b}.missing{background:#999}'
        sb << 'details{margin:.4em 0}summary{cursor:pointer}pre{background:#f7f7f7;padding:.5em;overflow:auto}.legend span{margin-right:1em}\n</style></head><body>\n'
        sb << '<h1>Verify report: ' << esc(meta.project) << '</h1>\n'
        sb << '<p>Date: ' << esc(meta.date) << (meta.generatorCommit ? ' · generator ' + esc(meta.generatorCommit) : '') << (meta.goldenMasterCommit ? ' · golden master ' + esc(meta.goldenMasterCommit) : '') << '</p>\n'
        int pass = cases.count { it.status == 'pass' }, warn = cases.count { it.status == 'warn' }, fail = cases.count { it.status == 'fail' }
        sb << "<p class=\"legend\"><span class=\"tag pass\">${pass} pass</span><span class=\"tag warn\">${warn} warn</span><span class=\"tag fail\">${fail} fail</span> · cell order: ${styles.collect { esc(it) }.join(' / ')}</p>\n"

        sb << '<table>\n<tr><th>Language</th><th>Version</th>' << formats.collect { "<th>${esc(it)}</th>" }.join('') << '</tr>\n'
        languages.each { lang ->
            sb << '<tr><td class="lang">' << esc(lang) << '</td><td>' << version(lang) << '</td>'
            formats.each { fmt ->
                sb << '<td>'
                if (fmt == 'source') {
                    def c = lookup["${lang}|-|source".toString()]
                    sb << "<span class=\"cell ${c ? c.status : 'missing'}\" title=\"source\">${c ? c.status : '–'}</span>"
                } else {
                    styles.each { style ->
                        def c = lookup["${lang}|${style}|${fmt}".toString()]
                        sb << "<span class=\"cell ${c ? c.status : 'missing'}\" title=\"${esc(style)}\">${c ? esc(style[0]) : '–'}</span>"
                    }
                }
                sb << '</td>'
            }
            sb << '</tr>\n'
        }
        sb << '</table>\n'

        // findings grouped by rule id, errors first
        def byRule = [:]
        cases.each { c -> c.findings.each { f -> byRule.computeIfAbsent(f.ruleId) { [severity: f.severity, hits: []] }.hits << [c: c, f: f] } }
        def ordered = byRule.entrySet().sort { a, b -> (a.value.severity == 'error' ? 0 : 1) <=> (b.value.severity == 'error' ? 0 : 1) ?: a.key <=> b.key }
        if (ordered) sb << '<h2>Findings</h2>\n'
        ordered.each { e ->
            sb << "<details${e.value.severity == 'error' ? ' open' : ''}><summary><span class=\"tag ${e.value.severity == 'error' ? 'fail' : 'warn'}\">${e.value.severity}</span> <code>${esc(e.key)}</code> in ${e.value.hits.size()} output(s)</summary>\n<ul>\n"
            e.value.hits.each { h ->
                sb << '<li><b>' << esc(h.c.format) << ' / ' << esc(caseName(h.c)) << '</b>: ' << esc(h.f.message)
                if (h.f.examples) sb << '<pre>' << h.f.examples.collect { "${esc(it.location)}: ${esc(it.text)}" }.join('\n') << '</pre>'
                sb << '</li>\n'
            }
            sb << '</ul></details>\n'
        }
        sb << '</body></html>\n'
        file.write(sb.toString(), 'UTF-8')
        return file
    }

    void printSummary(List suites, File junitDir, File htmlFile) {
        println "\n=== Verification Summary ==="
        suites.each { s ->
            def c = s.cases
            println String.format('  %-18s %3d pass  %3d warn  %3d fail', s.name, c.count { it.status == 'pass' }, c.count { it.status == 'warn' }, c.count { it.status == 'fail' })
        }
        def failed = suites.collectMany { s -> s.cases.findAll { it.status == 'fail' }.collect { [suite: s.name, c: it] } }
        if (failed) {
            println "\nFailed:"
            failed.each { println "  ✗ ${it.suite} ${caseName(it.c)}: ${it.c.findings.findAll { f -> f.severity == 'error' }*.ruleId.join(', ')}" }
        }
        println "\nReports:"
        println "  JUnit XML: ${junitDir}"
        println "  HTML:      ${htmlFile}"
        println ""
    }

    /** Write both reports under reportDir() and print the summary */
    List<File> writeAll(List suites, Map meta) {
        def junitDir = new File(reportDir(), 'junit')
        def htmlFile = new File(reportDir(), 'verify.html')
        def files = writeJUnit(suites, junitDir)
        files << writeHtml(suites, htmlFile, meta)
        printSummary(suites, junitDir, htmlFile)
        return files
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `RUN groovy test-verifier.groovy`
Expected: all `✓`.

- [ ] **Step 5: Commit**

```bash
git add lib/Report.groovy test-verifier.groovy
git commit -m "verify: HTML matrix report and console summary

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 11: Wire the phase into build.groovy, clean up the shell script, register the test, document

**Files:**
- Modify: `build.groovy` (argument parsing near line 45, class loading near line 100, new phase after phase 4, summary block at the end)
- Modify: `build-arc42.sh` (remove lines from `# Validate generated markdown files with cmark` down to the line before the final `BUILD COMPLETE` banner)
- Modify: `init-groovy-deps.groovy`
- Modify: `run-all-tests.groovy`
- Modify: `README.adoc`, `CLAUDE.md`
- Modify: `test-verifier.groovy` (real-dist smoke section)

**Interfaces:**
- Consumes: `Verifier(config, projectRoot, checks).verifyAll(formats)`, `Report(config, projectRoot).writeAll(suites, meta)`, `Report.hasFailures(suites)`.

- [ ] **Step 1: Add the real-dist smoke test**

Insert before `// ---- summary` in `test-verifier.groovy`:

```groovy
// ---- Task 11: smoke test on the real dist, when present -------------------------

section('real dist: EN with-help markdown currently fails md.pandocSyntax') {
    def realZip = new File('arc42-template/dist/arc42-template-EN-withhelp-markdown.zip')
    if (!realZip.exists()) { println "  (skipped, ${realZip} not present)"; return }
    def cfg = new ConfigSlurper().parse(new File('buildconfig.groovy').toURI().toURL())
    def checks = checksClass.newInstance(cfg)
    def v = verifierClass.newInstance(cfg, new File('.'), checks)
    def result = v.verifyCase('EN', 'with-help', 'markdown')
    assert result.revnumber == '9.0-EN'
    // Known generator defect documented in the spec; invert this assertion once the generator emits CommonMark.
    expectRules(result.findings, ['md.pandocSyntax', 'md.emptyHeading'])
    noRule(result.findings, 'structure.chapters')
    noRule(result.findings, 'structure.revnumber')
    noRule(result.findings, 'structure.helpText')
}
```

Run: `RUN groovy test-verifier.groovy`
Expected: the section passes (or prints "skipped" on a checkout without dist).

- [ ] **Step 2: Wire the phase into `build.groovy`**

In the header comment add `groovy build.groovy verify   # Only verify existing distribution ZIPs`.

After `packagerClass = gcl.parseClass(new File('lib/Packager.groovy'))` and its println add:

```groovy
    checksClass = gcl.parseClass(new File('lib/Checks.groovy'))
    println "✓ Loaded Checks.groovy"

    verifierClass = gcl.parseClass(new File('lib/Verifier.groovy'))
    println "✓ Loaded Verifier.groovy"

    reportClass = gcl.parseClass(new File('lib/Report.groovy'))
    println "✓ Loaded Report.groovy"
```

and declare `def checksClass`, `def verifierClass`, `def reportClass` next to the other `def ...Class` lines.

After the `def packager = ...` line add:

```groovy
def checks = checksClass.newInstance(config)
def verifier = verifierClass.newInstance(config, projectRoot, checks)
def report = reportClass.newInstance(config, projectRoot)
```

After the Phase 4 block insert:

```groovy
// ============================================================================
// Phase 5: Verify Distribution ZIPs
// ============================================================================

def verificationFailed = false

if (targetPhase in ['all', 'verify']) {
    try {
        println "=== Verifying Distributions ==="
        def formatsToVerify = targetFormat ? [targetFormat] : (config.formats.keySet() as List)
        def suites = verifier.verifyAll(formatsToVerify)

        def gitShort = { File dir ->
            try {
                def p = ['git', '-C', dir.absolutePath, 'rev-parse', '--short', 'HEAD'].execute()
                p.waitFor()
                return p.exitValue() == 0 ? p.text.trim() : null
            } catch (Exception e) { return null }
        }
        report.writeAll(suites, [project: config.project.name, date: new Date().format('yyyy-MM-dd HH:mm'),
                                 generatorCommit: gitShort(projectRoot),
                                 goldenMasterCommit: gitShort(new File(projectRoot, config.goldenMaster.sourcePath.toString()))])
        verificationFailed = report.hasFailures(suites)
    } catch (Exception e) {
        println "\n✗ Verification failed to run: ${e.message}"
        e.printStackTrace()
        System.exit(1)
    }
}
```

Replace the final banner block (from `def endTime` to the end of the file) with:

```groovy
def endTime = System.currentTimeMillis()
def duration = (endTime - startTime) / 1000.0

if (verificationFailed) {
    println """
╔═══════════════════════════════════════════════════════════════════════════╗
║               BUILD FAILED: verification reported failures                ║
╚═══════════════════════════════════════════════════════════════════════════╝
"""
} else {
    println """
╔═══════════════════════════════════════════════════════════════════════════╗
║                            BUILD SUCCESSFUL                               ║
╚═══════════════════════════════════════════════════════════════════════════╝
"""
}

println "Duration: ${String.format('%.1f', duration)}s"

if (targetPhase in ['all', 'convert'] && discoveredTemplates) {
    def languages = discoveredTemplates*.language.unique().size()
    def styles = discoveredTemplates*.style.unique().size()
    def formats = targetFormat ? 1 : (config.formats.keySet().size())
    def totalOutputs = languages * styles * formats

    println """
Summary:
  Languages: ${languages}
  Styles: ${styles}
  Formats: ${formats}
  Total outputs: ${totalOutputs}
"""
}

println "Build completed: ${new Date()}"
println ""

if (verificationFailed) {
    System.exit(1)
}
```

- [ ] **Step 3: Run the standalone phase against the committed dist**

Run: `RUN groovy build.groovy verify`
Expected: the phase runs for all 17 formats and 11 languages, writes `build/reports/junit/TEST-*.xml` (18 files including `TEST-source.xml`) and `build/reports/verify.html`, prints the summary with failures in every Markdown suite and in the multi-page suites, prints the `BUILD FAILED` banner and exits 1. Confirm the exit code with `echo $?` inside the container shell, or run `RUN sh -c 'groovy build.groovy verify; echo EXIT=$?'`.

Run: `RUN groovy build.groovy verify --format=html`
Expected: html suite with 22 cases; the only expected findings are `html.localLinks` warnings if any; exit 0. If `structure.helpText` fails for a language, inspect `helpSentinel(lang)` for that language; the sentinel logic must be adjusted, not the rule.

Open `build/reports/verify.html` in a browser and check the matrix: 11 rows, versions 9.0 for CZ DE EN FR ZH and 8.2 for ES IT NL PT RU UKR, one column per format plus `source`.

- [ ] **Step 4: Remove the shell validation loops**

In `build-arc42.sh` delete everything from the line `# Validate generated markdown files with cmark` through the line `echo ""` that follows `echo "  (This is non-fatal, build continues)"` of the image validation block, so that `groovy build.groovy` is directly followed by the `BUILD COMPLETE` banner. Add `set -e` handling: `groovy build.groovy` now exits 1 on verification failures, which with `set -e` aborts the script before the banner. Replace the plain `groovy build.groovy` line with:

```bash
if ! groovy build.groovy; then
    echo ""
    echo "✗ Build or verification failed. See build/reports/verify.html"
    exit 1
fi
```

- [ ] **Step 5: Cache the new dependencies and register the test**

`init-groovy-deps.groovy`: add after the existing `@Grab` lines

```groovy
@Grab('org.commonmark:commonmark:0.24.0')
@Grab('org.jsoup:jsoup:1.18.3')
```

and two println lines `✓ commonmark-java 0.24.0`, `✓ jsoup 1.18.3`.

`run-all-tests.groovy`: append to the `tests` list

```groovy
    [
        name: "Verify Phase",
        script: "test-verifier.groovy",
        description: "Tests distribution checks, golden master validation and JUnit/HTML reporting"
    ]
```

Run: `RUN groovy run-all-tests.groovy`
Expected: five suites, `✅ ALL TESTS PASSED`. (`test-templates.groovy` writes `build/src_gen`; run order is unchanged.)

- [ ] **Step 6: Document**

`README.adoc`, section "Build Validation": replace the three bullets and the "non-fatal" sentence with:

```
The fifth build phase `verify` inspects every distribution ZIP and the Golden Master:

* **Completeness**: every language × style × format ZIP exists and contains the expected files
* **Structure**: twelve chapters, help text present only in with-help, template version present
* **Markdown purity**: no pandoc-only syntax, no raw HTML, valid CommonMark, images resolve
* **HTML, DOCX, EPUB**: well-formed packages, images embedded or referenced correctly
* **Source**: version.properties, includes and image references of each language

Results are written to `build/reports/junit/TEST-*.xml` (JUnit XML, usable in any CI) and
`build/reports/verify.html` (matrix of languages × formats with the template version per language).
A failing check makes `groovy build.groovy` exit with code 1. Run `groovy build.groovy verify`
to check the committed ZIPs without rebuilding. Rule severities can be adjusted in the
`verify` block of `buildconfig.groovy`.
```

`CLAUDE.md`: in "Manual Build Steps" add `groovy build.groovy verify         # Phase 5: Verify distribution ZIPs, write reports`; in "Build Pipeline Flow" add step 6 `**Verification** (lib/Verifier.groovy, lib/Checks.groovy, lib/Report.groovy) → Checks ZIPs and Golden Master, writes build/reports/`; in "Core Components" add a short entry for the three files; in "Testing" add `groovy test-verifier.groovy   # Test verify phase`; in "Output Locations" add `build/reports/`.

- [ ] **Step 7: Commit**

```bash
git add build.groovy build-arc42.sh init-groovy-deps.groovy run-all-tests.groovy README.adoc CLAUDE.md test-verifier.groovy
git commit -m "verify: add phase 5 to the build, replace shell validation, document

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 12: End-to-end run in Docker and acceptance check

**Files:**
- Possibly modify: `lib/Checks.groovy`, `lib/Verifier.groovy` (only for detection bugs found against real output)

- [ ] **Step 1: Rebuild the image so the Grape cache contains the new libraries**

Run: `docker compose build`
Expected: the `init-groovy-deps.groovy` step prints the two new dependencies.

- [ ] **Step 2: Full build**

Run: `docker compose run --rm arc42-builder groovy build.groovy` (not `build-arc42.sh`, which re-clones the submodule).
Expected: phases 1 to 5 run, fresh ZIPs land in `arc42-template/dist/`, the summary lists failures in the Markdown suites, the banner says `BUILD FAILED: verification reported failures`, exit code 1.

- [ ] **Step 3: Check the acceptance criteria from spec §11**

1. `build/reports/junit/` has one `TEST-<format>.xml` per configured format plus `TEST-source.xml`; `build/reports/verify.html` exists.
2. `RUN groovy build.groovy verify --format=html` exits 0.
3. Every `<failure>` body in the XML files contains a rule id and a `name:line` location. Check with:
   ```bash
   grep -L 'location\|:[0-9]' build/reports/junit/TEST-*.xml   # prints nothing
   grep -c '<failure' build/reports/junit/TEST-markdown.xml     # 22 expected today
   ```
4. Open `verify.html`: rows CZ, DE, EN, FR, ZH show 9.0; ES, IT, NL, PT, RU, UKR show 8.2.
5. `RUN groovy run-all-tests.groovy` passes.
6. Add `'md.rawHtml': 'warn'` to `verify.severity` in `buildconfig.groovy`, run `RUN groovy build.groovy verify --format=gitHubMarkdown`; the `gitHubMarkdown` suite must now report warnings instead of failures for that rule (other rules may still fail). Revert the config change afterwards.

- [ ] **Step 4: Review the real findings for detection bugs**

Read `verify.html`. For every rule that fires, open one example and confirm it is a real defect of the output, not a detection error. Known real defects (spec §1): `md.pandocSyntax` in markdown, markdownMP, mkdocs, mkdocsMP; `md.rawHtml` in gitHubMarkdown, gitHubMarkdownMP, markdownStrict (img/figure), `md.emptyHeading` everywhere in Markdown; `zip.primaryFile` for the multi-page formats only if the fresh build still produces a single file (the February 2026 fix should make them pass). Anything else that fires, such as `structure.chapters` on rst or latex, is a detection bug: fix it in `Checks.groovy`, add the failing sample as a unit test section in `test-verifier.groovy`, rerun.

- [ ] **Step 5: Do not commit the regenerated ZIPs**

The dist ZIPs in the submodule are regenerated by the full build. Publishing them is a separate decision for the maintainer, so leave the submodule working tree alone: `git -C arc42-template status` will show modified ZIPs, and that is expected. Commit only generator files:

```bash
git status --short          # only files outside arc42-template/ should be staged
git add lib test-verifier.groovy
git commit -m "verify: adjust detection after first end-to-end run

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

Skip the commit if nothing changed in Step 4.

---

## Self-review notes

Spec coverage: §3 placement → Task 11; §4 result model → Tasks 1, 7; §5 test model → Tasks 7, 9; §6.1 → Task 4; §6.2 → Task 3; §6.3 → Task 2; §6.4 → Task 5; §6.5 → Task 6; §6.6 → Task 8; §6.7 → Task 3 (`checkAll` runs structure rules for every format); §7 configuration → Tasks 1, 4 (allowedHtml), 1 (severity); §8 reports → Tasks 9, 10; §9 testing → every task plus the real-dist smoke in Task 11; §10 dependencies → Tasks 1, 11; §11 acceptance → Task 12.

Deviation from the spec worth knowing: `Verifier` takes the `Checks` instance as a third constructor argument instead of loading it itself, so a single `GroovyClassLoader` in `build.groovy` and in the test owns all classes, as the existing code does.
