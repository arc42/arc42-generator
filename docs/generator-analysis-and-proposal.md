# arc42-generator: analysis and proposal for one generator serving arc42, req42 and systems42

Date: 2026-09-28
Scope: `arc42-generator` (master, b07a9a9), `arc42-template` (8dff0d9), `req42-framework` (4c8abc9), the arc42.org download page.
Method: code reading plus a fresh build of the current generator in a 4-vCPU Linux container (Groovy 4.0.28, OpenJDK 21, Pandoc 3.7.0.2), and experiments with Asciidoctor 2.0.26, Asciidoctor PDF 2.3.27 and asciidoctor-reducer 1.1.2.
"Verified" means reproduced in that session. "Estimate" means not measured.

## 1. Summary

- The generator works and is fast: 12 languages x 2 styles x 17 formats = 408 outputs in about 30 s (warm), 0 failures. Since [PR #66](https://github.com/arc42/arc42-generator/pull/66) it is project-neutral, and req42-framework already builds with it.
- The published output has real defects, all verified: every *with-help* multi-page Markdown variant is missing all help text (48 ZIPs), stale files leak into every ZIP, six languages ship broken image links, and golden-master errors are never reported.
- The biggest gap versus the written requirements is PDF (priority 1). PDF exists only in the template repos' separate Gradle build, so one source currently has two toolchains.
- Recommendation: (1) fix the defects in the current code now, they are small; (2) rebuild the generator as a thin CLI around the native Asciidoctor toolchain plus Pandoc, shipped as a versioned Docker image that template repos call from CI; (3) define a small *template contract* plus a `template.yaml` manifest so systems42 becomes a config-only onboarding. Decision matrix in section 5. If the team prefers to stay on the JVM, option A is the fallback with the same phase plan.

## 2. Current state

### 2.1 Pipeline

`build.groovy` (phases, `--format`, `--parallel`, `--config`)
-> `lib/Templates.groovy` (language discovery; regex removal of `[role="<prefix>help"] **** ... ****` blocks and `ifdef::<prefix>help[]` lines; copies images, common, version.properties)
-> `lib/Discovery.groovy` (scans `build/src_gen`)
-> `lib/Converter.groovy` (AsciidoctorJ for html and docbook; Pandoc from DocBook for 13 formats; multi-page = per-chapter DocBook + Pandoc)
-> `lib/Packager.groovy` (one ZIP per language x style x format into the template repo's `dist/`).

`build-arc42.sh` wraps it: installs pandoc if missing, deletes and re-clones the submodule at `master`, runs the build, then runs non-fatal cmark and image checks.

### 2.2 Build matrix

| Dimension | Values | Source |
|---|---|---|
| Languages | 12: CZ DE EN ES FR HU IT NL PT RU UKR ZH | auto-discovered with `^[A-Z]{2,}$` (CLAUDE.md still says 9 languages and `{2}`) |
| Styles | plain, with-help (`with-examples` commented out) | `buildconfig.groovy` |
| Formats | 17: asciidoc html epub rst markdown markdownMP markdownStrict markdownMPStrict gitHubMarkdown gitHubMarkdownMP mkdocs mkdocsMP textile textile2 docx docbook latex | `buildconfig.groovy` |
| Outputs | 408 conversions, 408 ZIPs, plus 6 hand-made Confluence ZIPs and legacy folders; `dist/` = 108 MB, 414 ZIPs, committed to git per release | `arc42-template/dist` |
| Offered on arc42.org/download | 12 formats. Generated but not linked: docbook, epub, mkdocs, mkdocsMP, textile2 | `_pages/download.md` |
| Template versions | 9.1 DE; 9.0 EN CZ FR HU ZH; 8.2 ES IT NL PT RU UKR | `<LANG>/version.properties` |

Redundancy (verified): `textile2` is byte-identical to `textile`; `mkdocs` is byte-identical to `markdown`, only the image folder moves to `docs/images`. `mkdocs` and `mkdocsMP` are not MkDocs projects: no `mkdocs.yml`, pages are not under `docs/`.

### 2.3 Toolchain and Docker

| Component | Generator (`Dockerfile`) | Template repo (`arc42-template`) |
|---|---|---|
| Base | Alpine 3.20, OpenJDK 21 JRE, Groovy 5.0.3 | eclipse-temurin 11, Gradle 8.14 wrapper |
| AsciiDoc | AsciidoctorJ 2.5.10 via `@Grab` (current release 3.0.1) | asciidoctor Gradle plugins 4.0.5, asciidoctorj-pdf 2.3.19 |
| Converters | pandoc from the Alpine repo (unpinned; `build-arc42.sh` pins 3.7.0.2 only outside Docker), cmark | none |
| PDF | none | yes, with a ZH theme and Noto Sans SC fonts in `ZH/pdf-theme/` |
| Fonts | none | Noto CJK via apt |
| CI | none (no `.github/`) | GitHub Actions running `gradle asciidoctor` on JDK 11 |
| Image size | about 564 MB (README) | not measured |

Two toolchains render the same sources with different engines and versions. Neither is pinned end to end.

### 2.4 Multi-template support today

What works: `project.name`, `project.featurePrefix`, `project.logo`, `--config=<file>`, paths resolved relative to the config file. req42-framework carries a full copy of `buildconfig.groovy` (all 17 formats repeated) and is built with `docker compose run ... -v /path/to/req42-framework:/project ... --config=/project/buildconfig.groovy`. Its `dist/` holds 68 ZIPs.

Drawbacks: every template repo copies the whole format list (drift), the generator repo still owns the arc42 submodule (asymmetric), no template repo has CI that runs the generator, and no systems42 repository exists yet (none is accessible to this session).

### 2.5 Measured performance (this session, 4 vCPU, warm caches)

| Step | Wall time | Note |
|---|---|---|
| `templates` phase, 24 templates | 8.2 s | 6.3 s work plus JVM start |
| `convert`, all 17 formats, 408 outputs | 28 s | 21 s work; the full DocBook is rendered 11 times per template, plus four per-chapter passes |
| `convert --format=html` | 11.2 s | 2.8 s work plus about 8 s JVM and JRuby start |
| Cold first run including `@Grab` downloads (50 MB) | 71 s | |
| asciidoctor-pdf (Ruby CLI), with-help, one document | EN 5.9 s, DE 5.3 s, RU 2.6 s, UKR 2.2 s, ZH with theme 9.4 s | 22 to 25 pages each |

Speed is not the problem. The "5.2x faster than Gradle" claim in the docs is plausible but was not re-measured.

### 2.6 Verified defects in the published output

| # | Defect | Evidence | Cause |
|---|---|---|---|
| D1 | All *with-help* multi-page outputs (markdownMP, markdownMPStrict, gitHubMarkdownMP, mkdocsMP; 48 ZIPs) contain no help text. They equal *plain*. | Fresh build: `build/EN/markdownMP/with-help/01_introduction_and_goals.md` is 693 bytes, identical to plain. Published ZIPs show the same. | `Converter.convertToDocBookMP` converts chapters standalone. `:arc42help:` lives in `config.adoc`, which is skipped, so every `ifdef::arc42help[]` evaluates to false. Setting the feature attribute per style (`-a arc42help`) restores the text (verified). |
| D2 | Stale files in every published ZIP: the deleted image `01_2_iso-25010-topics-EN.drawio.png` in all EN with-help ZIPs; an obsolete single-page `arc42-template-EN.md` inside the MP ZIPs; images twice in mkdocsMP. | Diff of `dist/*.zip` against a fresh `build/`. | `build/` is a host bind mount and is never cleaned. |
| D3 | Broken image links in the with-help outputs of CZ, ES, NL, PT, RU and UKR: they reference EN diagrams (`05_building_blocks-EN.png`, `08-concepts-EN.drawio.png`, `01_2_iso-25010-topics-EN-2023.drawio.png`) that are not in their `images/` folder. | Per-language audit of references against `<LANG>/images`. | ZIPs contain only the language folder. `build-arc42.sh` would flag this for Markdown only and continues. AsciidoctorJ html and docbook conversion never check image existence. |
| D4 | Golden-master errors pass silently: `UKR/adoc/10_quality_requirements.adoc` has 3 `ifdef` but 2 `endif` (Asciidoctor: "ERROR: unterminated preprocessor conditional directive"). RU wraps 0 of 29 help blocks in `ifdef`, so only the regex keeps RU plain clean. | Asciidoctor CLI reports the error; the generator's build log contains zero content diagnostics. | AsciidoctorJ diagnostics are not surfaced. Regex stripping masks structural errors. |
| D5 | DOCX has no table of contents and no header, footer or logo (all required in `docs/arc42-requirements.adoc`). The ZIP ships a redundant `images/` folder; DOCX embeds its media. | Unzip of the published DOCX. | `pandoc --toc` and `--reference-doc` are not used. |
| D6 | Help text is indistinguishable from template text in every non-HTML format. | DocBook carries `<sidebar role="arc42help">`; Pandoc keeps only the class `sidebar`. | No Pandoc filter. A Lua filter of a few lines turns the sidebars into blockquotes (tested). |
| D7 | "markdown" is Pandoc Markdown, not CommonMark: 384 fenced-div lines (`:::`) in EN with-help. cmark cannot fail on them. | Fresh output. | Writer choice; `gfm` or `commonmark_x` emit `<div>` instead. |
| D8 | Reproducibility: `build-arc42.sh` deletes `arc42-template/` and checks out `master` HEAD, so a build is never tied to the submodule commit, and outside Docker it deletes local work. pandoc is unpinned in the image. `@Grab` has no lockfile. | Scripts. | |
| D9 | Docs drift: CLAUDE.md (9 languages, line counts), README ("PDF" in the output list), TEST-REPORT.md ("100 % coverage"). | | |

### 2.7 Gaps versus `docs/arc42_build_process_requirements.md`

| Requirement | Status |
|---|---|
| PDF (priority 1), configurable fonts and page size | missing in the generator; exists in the template repo's Gradle build |
| Confluence XHTML (priority 2) | not generated; 6 hand-made ZIPs from an older version |
| Help text visually distinguishable where the format allows | HTML only (CSS hover) |
| Validation phase that fails with actionable messages | absent; checks are non-fatal and Markdown-only |
| YAML configuration preferred | Groovy `ConfigSlurper` file, copied per template |
| Version pinning, deterministic builds | partial (D8) |
| Example CI workflow | none |
| Plugin-like format adapters | if/else chain in `Converter.groovy` |
| Smoke and structural tests in CI | 4 integration scripts, no CI |
| Output layout `build/<LANG>/<flavor>/<format>` | actual layout is `build/<LANG>/<format>/<style>` (cosmetic) |
| Two-repository model | implemented (submodule plus `--config`) |

### 2.8 Golden-master consistency (input for a lint step)

| Check | Result |
|---|---|
| Help blocks per language | 29 in all languages except FR (27: chapter 5 has 6 instead of 8) |
| `ifdef` / `endif` balance | UKR chapter 10 unbalanced; RU has no `ifdef` at all |
| Chapter set | NL has an extra `termen.adoc` |
| Image references | 6 languages reference missing EN images (D3) |
| `version.properties` | present in all 12; formats differ (`8.2 ES` vs `8.2-PT`) |
| req42-framework | 2 languages, 26 help blocks each, all wrapped in `ifdef::req42help[]`, images complete; `config.adoc` also carries `ifndef::revnumber[...]` fallbacks |

## 3. Target picture

### 3.1 Template contract (arc42, req42, systems42)

```
<template-repo>/
  template.yaml                 # manifest, see below
  <LANG>/<name>.adoc            # main document; first include is adoc/config.adoc
  <LANG>/adoc/*.adoc            # chapters; help = ifdef::<prefix>help[] + [role="<prefix>help"] **** ... ****
  <LANG>/images/                # every image this language references (no cross-language references)
  <LANG>/version.properties     # revnumber, revdate, revremark
  <LANG>/pdf-theme/             # optional: <lang>-theme.yml plus fonts (ZH today)
  common/                       # optional shared includes
  dist/                         # generated ZIPs (kept for URL compatibility)
```

Manifest. Defaults live in the generator; the manifest only overrides them:

```yaml
name: systems42-template          # <LANG>/<name>.adoc, output file and ZIP base name
feature_prefix: systems42         # ifdef::systems42help[], [role="systems42help"]
logo: systems42-logo.png          # the only image copied into plain
languages: auto                   # or an explicit list
styles:
  plain: []
  with-help: [help]
formats: [asciidoc, html, pdf, docx, markdown, markdownMP, gitHubMarkdown, gitHubMarkdownMP,
          markdownStrict, markdownMPStrict, rst, textile, latex]
pdf:
  page_size: A4
```

Consequence: systems42 is onboarded by creating a repository in this layout plus a ten-line manifest, with no generator change. A `generator init --name ... --prefix ...` command can scaffold EN and DE skeletons.

### 3.2 Invert the dependency: the generator is a versioned image, templates call it

- Publish `ghcr.io/arc42/arc42-generator:<semver>` from the generator repo on tags.
- Template repos run it locally and in CI without any local install:

  ```
  docker run --rm -v "$PWD:/work" ghcr.io/arc42/arc42-generator:2 build --template /work --out /work/dist
  ```

- GitHub Actions in each template repo: on pull request run `lint` plus an EN smoke build; on release run the full build and commit `dist/` or upload release assets.
- The generator repo keeps arc42-template and req42-framework only as pinned regression fixtures, not as the release path. Central builds from the generator repo remain possible with the same `docker run`.

### 3.3 Conversion strategy per format

| Output | Engine | Change |
|---|---|---|
| html | Asciidoctor html5 | unchanged |
| pdf | asciidoctor-pdf with a per-language theme directory (fonts shipped with the template or the image) | new. Verified: RU and UKR render with the default fonts; ZH needs its Noto Sans SC theme, since both the default theme and the built-in `default-with-font-fallbacks` theme print boxes; 2 to 10 s per document |
| docbook | Asciidoctor docbook5, once per language x style, reused by all Pandoc formats | today rendered 11 times per template |
| docx | pandoc with `--toc` and `--reference-doc=<template>.docx` plus the help Lua filter | meets the TOC, header and logo requirement |
| markdown family | pandoc from DocBook; Lua filter turns help sidebars into blockquotes or admonitions and flattens `formalpara`; prefer the `gfm` or `commonmark_x` writers | help visible, CommonMark-valid |
| multi-page variants | per chapter with the style's feature attributes set, or split the book DocBook by `<chapter>` | fixes D1 |
| asciidoc | with-help: copy. plain: `asciidoctor-reducer -a <prefix>help!` per chapter; modular layout kept | verified identical to today's regex result, except that it also resolves `ifndef::imagesdir[]`. Regex retired once RU is fixed |
| epub | pandoc, or asciidoctor-epub3 | unchanged, low priority |
| rst, textile, latex | pandoc | unchanged; drop `textile2` |
| confluence | not a ZIP format; document the asciidoc2confluence or docToolchain `publishToConfluence` path | later |

Plain versus with-help for rendered formats becomes an attribute toggle (`-a arc42help!`), not text surgery. Prerequisite: RU gets its `ifdef` wrappers and UKR its missing `endif`, both small pull requests to arc42-template.

### 3.4 Validation gate (the build fails)

- Asciidoctor `--failure-level=WARN` for html and pdf: missing images, unterminated conditionals, unresolved includes.
- Lint before conversion, also usable standalone in template-repo pull requests: `ifdef` and `endif` balance; every `[role="<prefix>help"]` inside an `ifdef::<prefix>help[]`; every image reference exists in `<LANG>/images`; `version.properties` complete; chapter set and help-block count per chapter compared with EN (warning: translation drift).
- Smoke tests: one language, every format, file exists and is non-empty; structural checks (12 chapters, version string present).

### 3.5 Distribution

- Keep ZIP names and the `dist/` location. arc42.org builds its links as `https://github.com/arc42/arc42-template/raw/master/dist/arc42-template-<LANG>-<style>-<format>.zip`, and req42.de links the same way.
- Clean the output directory per run. Write ZIPs deterministically (sorted entries, fixed timestamps) so unchanged content yields identical bytes and small git diffs.
- Later, optional: GitHub Releases as the canonical store, with `dist/` kept or redirected.

### 3.6 Format portfolio

Default set: the 12 formats offered today plus pdf. Drop `textile2` (duplicate) and `mkdocs` (duplicate of markdown). For `mkdocsMP`, either generate a real MkDocs project (`docs/*.md` plus `mkdocs.yml` with navigation) or drop it. Keep `docbook` and `epub` as opt-in.

## 4. Options

- **A. Evolve the Groovy scripts** (AsciidoctorJ plus Pandoc). Add `asciidoctorj-pdf` 2.3.27, fix D1 to D8, add the manifest, CI and image publishing. Variant A': keep Groovy as orchestrator but call the Ruby CLIs instead of running JRuby in-process.
- **B. Thin CLI around the native toolchain** (recommended). Python with the standard library only (`argparse`, `subprocess`, `concurrent.futures`, `zipfile`) orchestrating asciidoctor, asciidoctor-pdf, asciidoctor-reducer (Ruby gems) and pandoc with Lua filters. Image derived from `asciidoctor/docker-asciidoctor`, which pins asciidoctor 2.0.26, asciidoctor-pdf 2.3.27, asciidoctor-epub3 2.3.0, asciidoctor-reducer 1.0.2 and ships Noto CJK fonts, plus a pinned pandoc release. A smaller self-built Alpine image with the same pinned gems is an alternative. A Make and Bash variant is possible but harder to test.
- **C. docToolchain as the engine** (`doctoolchain/doctoolchain` image, currently v3.5.0): tasks for HTML, PDF, DocBook, DOCX via pandoc, Confluence publishing. The matrix, feature stripping, Markdown variants and packaging still need custom code around it.
- **D. Gradle with the asciidoctor plugins**, what the template repos use locally today.
- **E. Node with asciidoctor.js**: no asciidoctor-pdf (asciidoctor-web-pdf needs Chromium), and asciidoctor.js lags the Ruby release. Not viable for PDF; listed for completeness.

## 5. Decision matrix

Ratings: ++ strong, + good, o neutral, - weak, -- blocking.

| Criterion | A Groovy evolve | B thin CLI, native tools | C docToolchain | D Gradle | E Node |
|---|---|---|---|---|---|
| Docker-only, no local install | ++ | ++ | ++ | + | + |
| All formats including native PDF, CJK and Cyrillic | + (PDF via JRuby, proven in the template repo) | ++ (reference implementation, fonts in the base image) | + | + | -- |
| Reproducibility and pinning | o (`@Grab`, unpinned pandoc) | ++ (pinned gems, pandoc and base image) | o (docToolchain pinned, its dependencies inside) | + (lockfiles) | + |
| Maintainability, contributor pool | + (maintainers' home turf, about 1,200 lines) | + (widely known; 600 to 900 lines, estimate; not the team's turf) | o (framework coupling, Gradle inside) | - (3-D matrix in Gradle DSL; the team left it in 2025) | - |
| Testability, CI | o (integration scripts) | ++ (unit tests plus golden files) | - | o | + |
| Matrix performance | ++ (in-process; PDF via JRuby slower, estimate) | + (one process per conversion; PDF is the long pole, runs in parallel) | - (Gradle start per run) | o | o |
| Multi-template (manifest, inverted dependency) | + | ++ | o (layout expectations) | o | + |
| Migration effort and risk | ++ (incremental) | - (rewrite; parity test needed) | - (framework adoption, unknowns) | - | -- |
| Runtime footprint | o (about 564 MB) | o (similar, estimate) | - (larger, estimate) | o | o |

Pros and cons, one line each:

- **A**: keeps community knowledge, lowest risk, fastest matrix. Against it: JVM and JRuby start per invocation, AsciidoctorJ lags upstream (2.5.10 in use), PDF via JRuby, the `@Grab` dependency story, tests stay ad hoc.
- **B**: upstream tools and fonts pinned by a maintained base image, PDF and CJK proven here, a tiny testable orchestrator, YAML manifest is natural, one Dockerfile. Against it: a rewrite (small), Python is new to the team, Lua filters are a second tiny language, one process start per conversion.
- **C**: Confluence publishing and PDF out of the box, maintained inside the arc42 ecosystem. Against it: custom code is still needed for the matrix, feature stripping, Markdown variants and ZIPs; Gradle start per run; opinionated layout; arc42 releases coupled to docToolchain releases; image size.
- **D**: the template repos already have it for HTML and PDF, incremental build cache. Against it: the project moved away from it for good reasons, matrix logic in the Gradle DSL, still shells out to pandoc.
- **E**: no PDF path without Chromium, no advantage over B.

Recommendation: **B**. The formats that matter next (PDF with fonts, help-aware Markdown and DOCX) are native to the Ruby and Pandoc toolchain, the orchestration is small enough to rewrite behind a parity test, and a pinned image consumed by the template repos removes both the toolchain duplication and the local installs. If the team prefers to stay on the JVM, take **A** with the same contract, manifest, lint and CI; the defects in section 2.6 are fixable there within days.

## 6. Roadmap

| Phase | Content | Estimate |
|---|---|---|
| 0: stop the bleeding, current code | Fix D1 (set the feature attribute per chapter), clean `build/` per run, surface AsciidoctorJ errors and fail on them, fail on missing images, stop the `rm -rf` and `master` checkout in `build-arc42.sh` (build the pinned submodule commit). Golden master: UKR `endif`, RU `ifdef` wrappers, copy or localise the missing images in CZ, ES, NL, PT, RU, UKR. Regenerate the arc42 and req42 `dist/`. | 1 to 2 days |
| 1: new generator (B) | CLI, manifest, lint, all formats including PDF, Lua filters, DOCX reference document, golden-file parity against the phase-0 output (differences only where intended), CI, image on GHCR. | 2 to 4 weeks part-time |
| 2: template repos | `template.yaml` plus an Actions workflow (pull request: lint and EN smoke; release: full build). Retire their Gradle and Docker files. arc42-template first, then req42-framework; systems42 starts on the contract. | 1 week |
| 3: optional | Confluence via asciidoc2confluence or docToolchain, a real MkDocs project, EPUB via asciidoctor-epub3, GitHub Releases, deterministic ZIPs, docs cleanup (CLAUDE.md, README, TEST-REPORT). | as needed |

## 7. Not verified in this session

- Building the Docker images (no Docker daemon in the container) and the pandoc version inside the current image.
- docToolchain behaviour for this matrix (described from its documentation, not run).
- GitHub Actions run times.
