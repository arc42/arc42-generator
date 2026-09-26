# Verify Phase and JUnit Report for the arc42 Generator

Date: 2026-09-26
Status: approved design, implementation pending

## 1. Goal

The generator produces 11 languages × 2 styles × 17 formats. Nobody can inspect
374 archives by hand, and the existing checks only prove that files exist. This
spec adds a `verify` phase that inspects the distribution ZIPs, applies a rule
catalogue per format, and writes a JUnit XML report plus an HTML matrix. The
report tells maintainers, per language, style and format, whether the output is
publishable and which template version (revnumber) was checked.

The verifier is expected to be red on its first run. The known defects it will
report (pandoc-only syntax and raw HTML in Markdown, empty title heading,
single-file multi-page ZIPs) are generator problems and are fixed in a separate,
later piece of work. This spec does not change the generator's output.

## 2. Scope

In scope:

- New build phase `verify`, new library files, new configuration block.
- Rule catalogue for all formats listed in `buildconfig.groovy`.
- JUnit XML report, HTML matrix report, console summary.
- A `source` suite that validates the Golden Master input per language.
- Test script for the verifier itself, wired into `run-all-tests.groovy`.
- Removal of the cmark and image shell loops from `build-arc42.sh`.

Out of scope:

- Any change to conversion (`Converter.groovy`), templates or packaging.
- Legacy artifacts in `arc42-template/dist/` that no configured format produces
  (confluence*, doxygen, eap, legacy, rhapsody). They are ignored.
- External linters (markdownlint, epubcheck, docutils). May come later.

## 3. Placement and invocation

`build.groovy` gains phase 5, `verify`, executed after `distribution` when the
target phase is `all`, and runnable alone with `groovy build.groovy verify`. The
standalone run checks the ZIPs currently in the distribution directory, so the
committed dist can be verified without rebuilding. `--format=<f>` restricts
verification to one format. `--config=` works as for the other phases.

Exit behaviour: the report is always written first. If any test case failed,
the build prints the summary and exits with code 1. ZIPs created earlier in the
run stay in place.

The verifier reads the dist ZIPs, not `build/<LANG>/<FORMAT>/`. The ZIPs are
what users download, and reading them catches packaging faults. ZIP entries are
read in memory via `java.util.zip.ZipFile`; nothing is unpacked to disk.

New files in the flat `lib/` layout, loaded with `GroovyClassLoader` like the
existing ones:

| File | Responsibility |
|---|---|
| `lib/Verifier.groovy` | Builds the expected matrix from config and discovered templates, runs checks, collects results, triggers reports, returns pass/fail. |
| `lib/Checks.groovy` | All rules. One method per format family, each returning a list of findings. Pure functions over ZIP content and reference data. |
| `lib/Report.groovy` | Writes JUnit XML, HTML matrix, console summary from the result model. |

`build-arc42.sh` loses the cmark loop and the two image loops; the verify phase
replaces them.

## 4. Result model

```
Finding   = [ruleId, severity ('error'|'warn'), message, examples: [[line, text], ...] (max 3)]
CaseResult = [language, style, format, revnumber, revdate, durationMs,
              findings: [Finding], status: 'pass'|'warn'|'fail'|'skipped']
SuiteResult = [name (format or 'source'), cases: [CaseResult]]
```

Status rules: `fail` if any finding has severity `error`; otherwise `warn` if any
finding exists; otherwise `pass`. `skipped` is used when a check needs an
optional dependency that is absent (not used by any rule in this spec, but the
model and report support it).

Language metadata (revnumber, revdate, revremark) is read from
`<goldenMaster.sourcePath>/<LANG>/version.properties` with UTF-8, the same way
`Discovery.groovy` does.

## 5. Test model

- One JUnit test suite per format, named after the format (`markdown`, `html`, …).
- One test case per language × style within a suite, named
  `<LANG>-<style> [<revnumber>]`, e.g. `EN-with-help [9.0-EN]`, so the version
  is visible in every JUnit consumer, including those that ignore properties.
- Suite-level `<properties>` list `revnumber.<LANG>` and `revdate.<LANG>` for
  every language in the suite. Each test case additionally carries its own
  `<properties>` element (`language`, `style`, `format`, `revnumber`,
  `revdate`); newer Surefire and JUnit consumers read it, older ones ignore it.
- A failed test case has one `<failure>` element whose message lists every
  error finding: rule id, count, up to three examples with line numbers.
- Warnings are written to the test case's `<system-out>`.
- One extra suite `source` with one test case per language, see section 6.6.

## 6. Rule catalogue

Rule ids are stable strings used in messages, in the HTML report and in
severity overrides. Default severity in brackets.

### 6.1 Completeness (all formats)

- `zip.exists` [error]: ZIP `<name>-<LANG>-<styleShort>-<format>.zip` exists.
- `zip.nonEmpty` [error]: ZIP has at least one entry with size > 0.
- `zip.primaryFile` [error]: the primary file is present. Single-file formats:
  the file `Converter.getPandocConfig(format)` would produce
  (`<name>-<LANG>.<ext>`), or `<name>.html`, `<name>.xml`, `<name>.adoc`
  respectively. Multi-page formats: at least twelve `*.<ext>` files, and no
  `config.<ext>`.
- `zip.images` [error]: when `formats[format].imageFolder` is true and style is
  `with-help`, the ZIP contains at least one entry under `images/`
  (`docs/images/` for mkdocs variants).

### 6.2 Structure (Markdown variants, HTML, AsciiDoc, RST, Textile, LaTeX, DocBook, DOCX, EPUB)

- `structure.chapters` [error]: twelve chapter headings at the top structural
  level. Detection per family: Markdown `^# `, HTML `<h2` (book doctype puts
  chapters at h2), AsciiDoc `^== `, RST title underlines, Textile `^h1.`, LaTeX
  `\chapter{`, DocBook `<chapter`, DOCX paragraphs with style `Heading1`, EPUB
  `<h1`/`<h2` in content documents. Multi-page Markdown: twelve chapter files
  each starting with a heading.
- `structure.referenceCounts` [warn]: heading counts per level equal those of
  the reference language (config `verify.referenceLanguage`, default `EN`) for
  the same style and format. Warn, not error, because a translation may
  legitimately add or drop a sub-heading.
- `structure.helpText` [error]: the with-help output contains the help sentinel
  for that language; the plain output does not. The sentinel is the first
  sentence of the first `[role="<prefix>help"]` block of chapter 01 in the
  language's Golden Master, whitespace-normalised, minimum 20 characters,
  compared case-sensitively against the whitespace-normalised output. Binary
  formats (DOCX, EPUB) are checked on their extracted XML text.
- `structure.revnumber` [error]: the `revnumber` value from version.properties
  occurs in the output text.

### 6.3 Markdown purity (markdown, markdownMP, markdownStrict, markdownMPStrict, gitHubMarkdown, gitHubMarkdownMP, mkdocs, mkdocsMP)

All rules apply to every `*.md` entry in the ZIP.

- `md.pandocSyntax` [error]: no line matches `^:{3,}` (fenced div), and no
  heading line ends with a `{#id}` or `{.class}` attribute block.
- `md.rawHtml` [error]: no HTML tag `<tag ...>` or `</tag>` whose tag name is
  not on the format's allow list (`verify.allowedHtml[format]`). Tag names are
  matched case-insensitively against a fixed set of HTML element names, so
  placeholders such as `<Name black box 1>` inside escaped text are not
  counted. Default allow list: markdownStrict and markdownMPStrict allow
  `table thead tbody tr th td col colgroup`; all other variants allow nothing.
- `md.emptyHeading` [error]: no line matches `^#{1,6}\s*$`.
- `md.images` [error]: every `![...](path)` target, after stripping a leading
  `./`, is an entry in the ZIP.
- `md.commonmark` [error]: the file parses with commonmark-java
  (`org.commonmark:commonmark`, pulled via `@Grab` like AsciidoctorJ). A parse
  exception is a finding. In addition, the parsed document must contain no
  `HtmlBlock` or `HtmlInline` node other than allowed tags; this backs
  `md.rawHtml` with a real parser.
- `md.multiPageHeading` [error], multi-page variants only: every chapter file's
  first non-blank line is a heading.
- `md.frontMatter` [warn]: if the file starts with a `---` YAML block, the
  block contains a `title` key that does not include `![`. Reports the image in
  the title as a warning, not an error.

### 6.4 HTML (html)

- `html.wellFormed` [error]: the document parses with jsoup
  (`org.jsoup:jsoup` via `@Grab`) and contains exactly one `<html>` and one
  `<body>`.
- `html.title` [error]: `<title>` exists and is non-empty.
- `html.images` [error]: every `<img src>` with a relative path resolves to a
  ZIP entry.
- `html.localLinks` [warn]: every `href="#id"` has a matching `id` attribute.
- `html.charset` [error]: a `<meta charset>` or equivalent declares UTF-8.

### 6.5 DOCX and EPUB

- `docx.valid` [error]: ZIP opens, `word/document.xml` parses as XML.
- `docx.media` [error]: number of files under `word/media/` equals the number
  of `<w:drawing>` elements in `document.xml`, and is at least 1 for with-help.
- `epub.valid` [error]: ZIP opens, `META-INF/container.xml` and the referenced
  `.opf` parse as XML, every manifest item exists as an entry.
- `epub.media` [error]: at least one image item in the manifest for with-help.

Both formats also run `structure.chapters`, `structure.helpText` and
`structure.revnumber` on their extracted text.

### 6.6 Source suite (Golden Master)

One test case per language directory in `goldenMaster.sourcePath`:

- `src.versionProperties` [error]: file exists, has non-empty `revnumber` and
  `revdate`.
- `src.mainFile` [error]: `<name>.adoc` exists.
- `src.chapters` [error]: `adoc/` contains twelve files matching `^\d\d_.*\.adoc$`.
- `src.includes` [error]: every `include::path[]` in the main file and in
  `adoc/*.adoc` resolves to an existing file (relative to the including file;
  `../common/` relative to the language directory).
- `src.images` [error]: every `image::path[]` and `image:path[]` target
  resolves to a file in `<LANG>/images/` after `imagesdir` handling as
  `Converter.createAttributes` sets it (`images`).

### 6.7 Formats without content rules

asciidoc, docbook, latex, rst, textile, textile2 run completeness and structure
rules only (`structure.chapters`, `structure.helpText`, `structure.revnumber`).

## 7. Configuration

New block in `buildconfig.groovy` (and therefore in any other project's config;
all keys have defaults, a missing block is fine):

```groovy
verify {
    referenceLanguage = 'EN'
    chapterCount = 12
    reportDir = 'build/reports'        // relative to the config file, like all paths
    allowedHtml = [
        markdownStrict:   ['table', 'thead', 'tbody', 'tr', 'th', 'td', 'col', 'colgroup'],
        markdownMPStrict: ['table', 'thead', 'tbody', 'tr', 'th', 'td', 'col', 'colgroup'],
    ]
    // rule id -> 'error' | 'warn' | 'off'
    severity = [
        // 'md.rawHtml': 'warn',
    ]
}
```

`severity` overrides the default of any rule. `off` disables it. This is how a
known generator limitation is downgraded while it is being fixed, without code
changes.

## 8. Reports

- `build/reports/junit/TEST-<suite>.xml`, one file per suite, Surefire schema:
  `<testsuite name tests failures errors skipped time>` containing
  `<properties>` and `<testcase name classname time>` with optional
  `<failure message>`, `<skipped/>` and `<system-out>`. Written with
  `groovy.xml.MarkupBuilder`. `classname` is `arc42.verify.<suite>`.
- `build/reports/verify.html`, one self-contained page, inline CSS, no external
  resources. Header: project name, date, generator commit, Golden Master commit.
  Matrix: rows are languages with their revnumber and revdate, columns are
  formats, each cell shows plain and with-help status as two coloured marks
  (green pass, yellow warn, red fail, grey missing). Below the matrix: the list
  of failed and warned test cases with rule ids, counts and examples, grouped
  by rule id so the same defect across 22 outputs reads as one item.
- Console: one line per suite with pass/warn/fail counts, then the list of
  failed test cases, then the report paths.

## 9. Testing the verifier

`test-verifier.groovy`, added to `run-all-tests.groovy`:

- Builds a temporary config pointing at a scratch directory under `build2/`
  (the existing convention for tests).
- Generates synthetic ZIPs in code: one valid Markdown ZIP, and one ZIP per
  defect class (pandoc div, raw div, empty heading, missing image, missing
  chapter, missing revnumber, single-file multi-page). Asserts that exactly the
  expected rule ids fire for each.
- Asserts that `Report.groovy` produces XML that parses and contains the
  expected counts, and that the HTML file exists and names every failed case.
- Runs `Checks.groovy` against the real EN with-help Markdown ZIP if present in
  dist and asserts that `md.pandocSyntax` fires (documents the known red
  state; when the generator is fixed this assertion is inverted).

`test-converter.groovy` and the others stay unchanged.

## 10. Dependencies and environment

- New `@Grab` coordinates in `Checks.groovy`: `org.commonmark:commonmark`,
  `org.jsoup:jsoup`. `init-groovy-deps.groovy` gains the same two lines so the
  Docker image caches them.
- No new binaries. The Dockerfile stays unchanged. Dropping the now unused
  cmark package from it is a follow-up.
- Groovy 4+ and Java 11+ as today.

## 11. Acceptance

The work is done when:

1. `groovy build.groovy` on the current Golden Master runs all five phases,
   writes `build/reports/junit/*.xml` and `build/reports/verify.html`, and
   exits with code 1 because of the known Markdown defects.
2. `groovy build.groovy verify --format=html` passes on the current output.
3. Every failure in the report names a rule id and at least one example with a
   line number.
4. The HTML matrix shows 9.0 for CZ, DE, EN, FR, ZH and 8.2 for ES, IT, NL, PT,
   RU, UKR.
5. `groovy run-all-tests.groovy` passes, including `test-verifier.groovy`.
6. Setting `verify.severity['md.rawHtml'] = 'warn'` in the config turns the
   corresponding failures into warnings without a code change.
