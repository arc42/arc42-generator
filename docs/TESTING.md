# Testing the generator

Everything runs through `make`, and every target runs in Docker. You need `git` (to clone), `make` and Docker with Compose v2; nothing else is installed on your machine. All output is written below the generator directory (or below the template repository when you build another template).

## 1. Get the code and build the image

```
git clone --recurse-submodules https://github.com/arc42/arc42-generator.git
cd arc42-generator
make template-checkout                  # arc42-template at the recorded commit
make image                              # builds the image: Java, Groovy, Pandoc 3.7.0.2, cmark
```

`make help` lists all targets and variables; `make versions` shows the tool versions in the image.

## 2. Self-tests of the generator

```
make test
```

Runs the five test scripts inside the container (template generation, discovery, conversion, a non-arc42 fixture, the golden-master lint). Each script ends with `All Tests Passed!`; `make` exits with 0. One script alone: `make test-lint` (also `test-templates`, `test-discovery`, `test-converter`, `test-config`).

## 3. arc42

```
make templates
make convert
```

Expected:

- `templates`: `✓ Golden master validation: 0 error(s), 2 warning(s)`. The two warnings are known translation drift (FR chapter 5 has 6 help blocks where EN has 8; NL has an extra `termen.adoc`).
- `convert`: `Successful: 408`, `Failed: 0`, `✓ No Asciidoctor or Pandoc diagnostics` (12 languages x 2 styles x 17 formats).

Output: `build/<LANG>/<FORMAT>/<STYLE>/`, for example `build/EN/docx/with-help/arc42-template-EN.docx`. Things worth a look:

- `build/EN/markdownMP/with-help/01_introduction_and_goals.md` contains the help text ("Describes the relevant requirements ..."); the `plain` counterpart does not. Before this branch both were identical.
- Run `make convert` a second time: the files are byte-identical (compare with `sha256sum` or `git hash-object`), because timestamps come from the golden master's last commit, not from the build time.
- `make build` runs everything: submodule checkout, all phases including the ZIPs in `arc42-template/dist/`, and the output validation (`make validate`: cmark, with-help images). Afterwards `git -C arc42-template status` lists the regenerated ZIPs; that is expected and nothing to commit while testing. `make clean-dist` restores the committed ZIPs.

## 4. req42, or any other template repository

Any repository with the arc42 layout (`<LANG>/<name>.adoc`, `<LANG>/adoc/`, `<LANG>/images/`, `<LANG>/version.properties`) and its own `buildconfig.groovy` can be built the same way; `TEMPLATE` mounts it into the container:

```
git clone https://github.com/Hruschka/req42-framework.git ../req42-framework
make templates TEMPLATE=../req42-framework
make convert TEMPLATE=../req42-framework
```

Expected: languages `DE, EN`; `0 error(s), 1 warning(s)` (the German chapter file names differ from the English ones, which the lint reports as drift); `Successful: 68`; no diagnostics. Output: `../req42-framework/build/`. `make generate TEMPLATE=../req42-framework` additionally rewrites `../req42-framework/dist/*.zip`.

## 5. Seeing the gate work

The build must fail on broken sources. To provoke it:

```
rm arc42-template/EN/images/arc42-logo.png
make templates                                  # lint error naming the file, exit code 1
git -C arc42-template checkout -- EN/images     # restore
```

Options for `build.groovy` are passed with `OPTS`:

- `make templates OPTS=--lint=warn` reports golden-master problems without failing.
- `make convert OPTS=--failure-level=error` fails only on Asciidoctor/Pandoc errors, not on warnings (default: `warn`).
- `make convert FORMAT=html` converts one format only.

## 6. Reporting a problem

Please include the exact command, its complete output, the output of `docker compose version`, your operating system, and for template problems the template repository and commit. `make shell` opens a shell inside the container for a closer look.
