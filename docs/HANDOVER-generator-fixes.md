# Handover: arc42-generator phase-0 fixes

For: a Claude Code session (Opus) on a local clone of `arc42/arc42-generator`.
From: the analysis session of 2026-09-28 (Claude Code on the web). Companion document: `docs/generator-analysis-and-proposal.md` (analysis, decision matrix, roadmap).
Scope of this handover: the "phase 0" fixes in the current Groovy generator, plus the golden-master fixes in `arc42-template`. Not the rebuild.

Every statement marked *verified* was reproduced in the analysis session on a fresh build (Groovy 4.0.28, OpenJDK 21, Pandoc 3.7.0.2, AsciidoctorJ 2.5.10, arc42-template commit 8dff0d9).

## 0. State of things

- Upstream `master` is b07a9a9 ([PR #66](https://github.com/arc42/arc42-generator/pull/66) merged: project-neutral config, `--config`).
- The analysis session could not push (the Claude GitHub App is not installed for the arc42 organization). Its branch `claude/cool-shannon-8vy6w0` with commit cd68a65 exists only in that cloud container. Start from `master`. Both documents (`docs/generator-analysis-and-proposal.md` and this file) were delivered as files in the Claude app; copy them into `docs/` and commit them together with the fixes or separately.
- The generator has no CI. All verification is local.
- Two task cards from the analysis session were withdrawn; this document replaces them.

## 1. Setup and how to work

Prerequisites, local: Java 11+ (21 tested), Groovy 4.0+ (4.0.28 and 5.0.3 tested), Pandoc 3.x (3.7.0.2 tested), git. Alternative: Docker Compose (image: Alpine, JRE 21, Groovy 5.0.3, Alpine pandoc, cmark).

```
git clone --recurse-submodules git@github.com:arc42/arc42-generator.git   # or: git submodule update --init
cd arc42-generator
git checkout -b fix/phase-0-generator
groovy build.groovy templates                     # ~8 s warm; first run fetches ~50 MB via @Grab from Maven Central
groovy build.groovy convert --format=markdownMP   # one format
groovy build.groovy convert                       # all 17 formats, ~30 s
groovy run-all-tests.groovy                       # 4 integration scripts; needs the templates phase output
```

Docker: `docker compose run --rm arc42-builder groovy build.groovy templates` (the service bind-mounts the repo to `/workspace`, so the test scripts are available there although `.dockerignore` excludes them from the image).

Gotchas:

- Run `groovy` from the repo root. `build.groovy` loads `lib/*.groovy` relative to the working directory. Paths inside a config file are relative to that file.
- Do not use `./build-arc42.sh` while developing: it deletes `arc42-template/` and re-clones `master` (see F5), and a full run rewrites `arc42-template/dist/*.zip`.
- `groovy build.groovy` without a phase runs `all`, including `distribution`, which writes ZIPs into the submodule's `dist/`. Do not commit those ZIPs as part of the fixes; regenerating `dist/` is the maintainers' release step.
- `build/` can be root-owned after Docker builds; `test-converter.groovy` writes to `build2/` for that reason.
- Nothing cleans `build/` today (F2). Expect stale files from earlier runs until F2 is done.
- AsciidoctorJ 2.5.10 bundles Asciidoctor 2.0.20 (*verified*). That version does not report unterminated `ifdef` blocks; the Asciidoctor 2.0.26 CLI does. See F3 and F4.
- The style name in `template.style` (from `Discovery`) is the directory name, identical to the keys of `config.goldenMaster.templateStyles` (`plain`, `with-help`).

## 2. Fixes (must do)

### F1: with-help multi-page outputs lose all help text

Symptom (*verified*): `build/EN/markdownMP/with-help/01_introduction_and_goals.md` is byte-identical to the plain variant (693 bytes), while the single-page `build/EN/markdown/with-help/arc42-template-EN.md` contains the help text ("Describes the relevant requirements ..."). Affects `markdownMP`, `markdownMPStrict`, `gitHubMarkdownMP`, `mkdocsMP` for all 12 languages: 48 published ZIPs.

Root cause: `lib/Converter.groovy`, `convertToDocBookMP(Map template, String docbookMPRelDir)` converts each chapter of `template.srcDir` standalone with `createAttributes(template)`. `config.adoc`, which sets `:arc42help:`, is skipped, so every `ifdef::arc42help[]` block evaluates to false. The with-help sources still contain the `ifdef` lines (`Templates.removeFeatures` only strips the features a style does not want), so an attribute is all that is missing.

Fix, in `convertToDocBookMP` after `def attrs = createAttributes(template)`:

```groovy
def features = config.goldenMaster.templateStyles[template.style] ?: []
features.each { feature ->
    attrs["${config.project.featurePrefix}${feature}".toString()] = ''
}
```

*Verified* with AsciidoctorJ 2.5.10: a chapter converted to DocBook with attributes `['arc42help': '']` contains the help text; without the attribute it does not.

Test: extend `test-converter.groovy` (or add a case to `test-config.groovy`, whose fixture already has a `demohelp` block): convert EN with-help to `markdownMP`, assert `01_introduction_and_goals.md` contains a help sentence and the plain output does not. Cover `mkdocsMP` as well, since it deletes `config.md` and `about-<prefix>.md` afterwards.

### F2: clean output per run

Symptom (*verified*): published ZIPs contain files that no current build produces. Every EN with-help ZIP ships the deleted image `01_2_iso-25010-topics-EN.drawio.png`; the MP ZIPs contain an obsolete single-page `arc42-template-EN.md`; the mkdocsMP ZIP has the images twice. `build/` is a host bind mount (`docker-compose.yml`) and nothing cleans it.

Fix, in `build.groovy`:

- At the start of the `templates` phase delete `config.goldenMaster.targetPath` (`build/src_gen/`).
- At the start of the `convert` phase, before the parallel conversion begins, delete `build/<LANG>/<FORMAT>/<STYLE>` for every (template, format) in scope, plus the intermediates `build/<LANG>/docbook/<STYLE>` and `build/<LANG>/docbookMP/<STYLE>`. With `--format=X` only X and the intermediates. One deletion pass before `convertAll` avoids races; formats run sequentially per template, templates in parallel.
- Leave `build2/` (tests) alone. Document the behaviour in README and CLAUDE.md.

Test: put a stray file into an output directory, run the build, assert it is gone. After a full build `find build/EN/markdownMP/with-help -type f` lists 13 Markdown files plus `images/` only.

### F3: surface AsciidoctorJ diagnostics and fail on errors

Symptom (*verified*): the generator's build log contains no Asciidoctor content diagnostics at all; every conversion reports a check mark. AsciidoctorJ logs through `java.util.logging` (a missing include prints as `SEVERE`), but nothing is collected or acted on.

Fix, in `lib/Converter.groovy`: register one log handler on the shared `Asciidoctor` instance, collect records in a thread-safe list (conversions run inside `GParsPool`), print them after `convertAll` grouped by language and style with `cursor.file` and `cursor.lineNumber`, and make `build.groovy` exit 1 when any record has severity `ERROR` or `FATAL`. Failing on `WARN` too is reasonable and simpler; decide and document.

```groovy
import org.asciidoctor.log.LogHandler
import org.asciidoctor.log.LogRecord
import org.asciidoctor.log.Severity

def diagnostics = Collections.synchronizedList([])
asciidoctor.registerLogHandler({ LogRecord r -> diagnostics << r } as LogHandler)
// after all conversions:
def errors = diagnostics.findAll { it.severity in [Severity.ERROR, Severity.FATAL] }
```

*Verified*: the handler receives `ERROR: include file not found: ...` for a missing include. Not reported by the bundled Asciidoctor 2.0.20, therefore covered by F4 instead: unterminated `ifdef` blocks, and missing images for html and docbook output (Asciidoctor only checks images when embedding them into PDF).

Optional, not required for phase 0: upgrade to AsciidoctorJ 3.0.1 (latest on Maven Central). Check the API (`Options.builder()`, `SafeMode`) and whether its bundled Asciidoctor reports unterminated conditionals.

### F4: lint the golden master, fail on structural errors

Symptoms (*verified* in arc42-template 8dff0d9):

- `UKR/adoc/10_quality_requirements.adoc`: 3 `ifdef::arc42help[]`, 2 `endif::arc42help[]`.
- RU: 29 `[role="arc42help"]` sidebar blocks, 0 `ifdef::arc42help[]`. Only the regex stripping keeps RU plain clean.
- Missing images: CZ, NL, PT, RU, UKR reference `01_2_iso-25010-topics-EN-2023.drawio.png`, `05_building_blocks-EN.png`, `08-concepts-EN.drawio.png`; ES references `01_2_iso-25010-topics-EN-2023.drawio.png`, `08-concepts-EN.drawio.png`. None of them exist in the respective `<LANG>/images/`, and the ZIPs ship only the language folder. `build-arc42.sh` reports the Markdown cases as non-fatal warnings.

Fix: a validation step in `lib/Templates.groovy`, run per language inside `createFromGoldenMaster` before anything is generated, collecting problems as `file:line: message` and throwing `IllegalStateException` with the full list at the end (`build.groovy` already turns that into exit 1). Checks:

1. For every feature in `config.goldenMaster.allFeatures`: `ifdef::<prefix><feature>[]` and `endif::<prefix><feature>[]` counts are equal per file.
2. Every `[role="<prefix><feature>"]` block sits inside an `ifdef::<prefix><feature>[]` block. A simple, sufficient version: counts of role lines and `ifdef` lines are equal per file.
3. Every image reference in `<LANG>/adoc/*.adoc` and `<LANG>/<name>.adoc` exists in `<LANG>/images/`. Regex used in the analysis: `image::?[A-Za-z0-9_./-]+\[`; skip references starting with `http`. The template repo's `build.gradle` has a similar `validateImageReferences` that can be ported.
4. `version.properties` exists and has `revnumber`, `revdate`, `revremark`.
5. Warning only: chapter file set and help-block count per chapter compared with EN (translation drift; FR chapter 5 has 6 blocks instead of 8, NL has an extra `termen.adoc`).

Sequencing matters: until the golden master is fixed (section 4), this lint fails the arc42 build for RU, UKR and the six image languages. Order: implement the lint, fix arc42-template in a separate PR, bump the submodule, then the build passes. A `groovy build.groovy lint` phase that runs only the checks is useful for template-repo contributors.

Test: a fixture in the style of `test-config.groovy` with one unbalanced `ifdef` and one missing image; assert failure and that both messages name file and line.

### F5: reproducible inputs in `build-arc42.sh` and `Dockerfile`

Symptoms: `build-arc42.sh` runs `rm -rf arc42-template`, `git submodule update --force`, `git checkout master && git pull`. A build is therefore never tied to the submodule commit, and outside Docker the script deletes local changes in the submodule. The `Dockerfile` installs `pandoc` from the Alpine 3.20 repository without a version; the script pins 3.7.0.2 only for non-Docker runs.

Fix:

- `build-arc42.sh`: replace the block with `git submodule update --init --recursive` (checks out the pinned commit). Add an opt-in `--update-template` flag or `UPDATE_TEMPLATE=1` that does `git -C arc42-template checkout master && git pull`. Keep the `safe.directory` settings and the lock-retry loop if still needed.
- `Dockerfile`: pin pandoc, either `apk add pandoc=<exact version>` for Alpine 3.20 or the GitHub release tarball for the architecture, as the script does with the `.deb`. Print `pandoc --version` during the image build.
- Once F3 and F4 exist, drop the script's non-fatal cmark and image validations or make them fatal; they duplicate the generator's checks.

### F6: documentation drift

- `CLAUDE.md`: 12 languages and `^[A-Z]{2,}$` instead of 9 and `{2}`; line counts; the clean-build behaviour; the lint step.
- `README.adoc`: "Convert to 15+ output formats (HTML, Markdown, DOCX, PDF, etc.)" lists PDF, which is not produced.
- `TEST-REPORT.md` is a historical snapshot ("100 % coverage", 9 languages). Delete it or mark it as historical.

## 3. Quick wins (optional, after F1 to F6, separate commits)

- Q1 DOCX: `pandoc --toc --toc-depth=2` inserts a table-of-contents field (*verified*). A `--reference-doc` created from `pandoc --print-default-data-file reference.docx`, with header and footer carrying the logo, satisfies `docs/arc42-requirements.adoc`. Keep the reference document in the generator (for example `resources/reference.docx`) with a per-project override in the config.
- Q2 Help styling in Pandoc formats: DocBook carries `<sidebar role="arc42help">`, Pandoc's reader keeps only the class `sidebar`. This Lua filter turns help sidebars into blockquotes (*verified*); pass it with `--lua-filter` for the markdown, gfm, rst, textile and docx conversions. The representation (blockquote or admonition) is a maintainer decision.

  ```lua
  function Div(el)
    if el.classes:includes('sidebar') then
      local blocks = el.content:filter(function(b)
        return not (b.t == 'Div' and b.classes:includes('title') and #b.content == 0)
      end)
      return pandoc.BlockQuote(blocks)
    end
  end
  ```

- Q3 Performance: `convertViaPandoc` re-renders the full DocBook for each of the 10 single-page Pandoc formats, 11 renders per template including the `docbook` format. Render once per template and reuse; formats already run sequentially per template, so a per-template memo is enough.
- Q4 The DOCX ZIP ships a redundant `images/` folder (DOCX embeds its media). `docx: [imageFolder: false]` changes ZIP contents; ask the maintainers first.
- Do not change the format list (`textile2` and `mkdocs` are duplicates) or ZIP names in this work. Download pages on arc42.org and req42.de link the ZIP names directly. Portfolio changes are a maintainer decision (proposal, section 3.6).

## 4. Golden-master fixes (repository `arc42-template`, separate PR)

Work inside the submodule on a branch; open the PR against `arc42/arc42-template`. Do not regenerate `dist/`.

1. `UKR/adoc/10_quality_requirements.adoc`: add the missing `endif::arc42help[]` (compare with `EN/adoc/10_quality_requirements.adoc`; Asciidoctor 2.0.26 reports line 78).
2. RU: wrap all 29 `[role="arc42help"]` blocks in `RU/adoc/*.adoc` with `ifdef::arc42help[]` and `endif::arc42help[]`, exactly like `EN/adoc/01_introduction_and_goals.adoc`.
3. Missing images: copy the referenced files from `EN/images/` into `CZ`, `ES`, `NL`, `PT`, `RU`, `UKR` (`images/`), or point the references at existing localised diagrams. Verify with `./gradlew validateImages<LANG>` in the template repo or with the new lint (F4).

Done when: for every language, `ifdef::arc42help` and `endif::arc42help` counts are equal in every file, RU has 29 of each, and every image reference resolves in `<LANG>/images/`.

Afterwards, in the generator: `git -C arc42-template checkout <merged commit>`, `git add arc42-template`, commit.

## 5. Definition of done and PR checklist

- `groovy run-all-tests.groovy` passes locally and via `docker compose run --rm arc42-builder groovy run-all-tests.groovy`.
- With the golden-master fixes merged and the submodule bumped: `groovy build.groovy templates` and `groovy build.groovy convert` complete with 0 failures for 12 languages x 2 styles x 17 formats. Before the golden-master fixes, the lint reports exactly the items listed in F4.
- With-help MP chapter files contain the help text; plain ones do not.
- A second build after a first one leaves no stale files.
- `docker compose build` succeeds; `pandoc --version` is printed and pinned.
- Documentation updated (F6). No `dist/*.zip` committed.
- One PR against `arc42/arc42-generator` `master` for F1 to F6 (quick wins separately), one PR against `arc42/arc42-template` for section 4.

## 6. Out of scope

The rebuild (proposal option B), the `template.yaml` manifest, PDF output, CI and image publishing, format portfolio changes, systems42.

## 7. Commands used for the evidence

```
# D1: with-help MP equals plain
grep -c 'Describes the relevant requirements' build/EN/markdown/with-help/arc42-template-EN.md \
  build/EN/markdownMP/with-help/01_introduction_and_goals.md
wc -c build/EN/markdownMP/with-help/01_introduction_and_goals.md build/EN/markdownMP/plain/01_introduction_and_goals.md

# D2: published ZIP vs fresh build
unzip -l arc42-template/dist/arc42-template-EN-withhelp-markdownMP.zip
find build/EN/markdownMP/with-help -type f | sort

# D3: image references vs images folder, per language
cd arc42-template; for l in CZ DE EN ES FR HU IT NL PT RU UKR ZH; do
  for r in $(cat $l/adoc/*.adoc $l/arc42-template.adoc | grep -oE 'image::?[A-Za-z0-9_./-]+\[' \
            | sed -E 's/^image::?//; s/\[$//' | sort -u); do
    [ -f "$l/images/$r" ] || echo "$l missing $r"; done; done

# D4: ifdef/endif balance
for l in CZ DE EN ES FR HU IT NL PT RU UKR ZH; do for f in $l/adoc/*.adoc; do
  i=$(grep -c 'ifdef::arc42help' $f); e=$(grep -c 'endif::arc42help' $f); [ "$i" != "$e" ] && echo "$f $i/$e"; done; done
```

## 8. File map (functions, not line numbers)

- `build.groovy`: argument parsing, config loading (`ConfigSlurper`), class loading via `GroovyClassLoader.parseClass`, phases `templates`, `convert`, `distribution`, summary.
- `lib/Templates.groovy`: `discoverLanguages`, `adjustIncludePaths`, `removeFeatures` (regex), `copyImages`, `createFromGoldenMaster` (place for the lint, F4).
- `lib/Discovery.groovy`: `discoverTemplates` (template metadata map: `language`, `style`, `srcDir`, `imagesDir`, `mainFile`, `versionProperties`).
- `lib/Converter.groovy`: `convertTemplate` (dispatch), `convertToHTML`, `convertToDocBook`, `convertViaPandoc`, `copyAsciidoc`, `copyImages`, `isMultiPage`, `convertToDocBookMP` (F1), `convertViaPandocMP`, `createAttributes`, `getPandocConfig`, `convertAll` (GParsPool; place for the diagnostics summary, F3).
- `lib/Packager.groovy`: `createZip`, `createDistribution` (ZIP name `<name>-<LANG>-<style without hyphen>-<format>.zip`), `createAllDistributions`.
- `build-arc42.sh`: pandoc install, submodule block (F5), Groovy build, cmark and image checks.
- `Dockerfile`: two Alpine stages; pandoc from apk (F5). `docker-compose.yml`: bind mount `.:/workspace`.
- Tests: `run-all-tests.groovy`, `test-templates.groovy`, `test-discovery.groovy`, `test-converter.groovy`, `test-config.groovy` (non-arc42 fixture, good pattern for new tests).
