#!/bin/bash
#
# build-arc42.sh - Full arc42 template build pipeline
#
# This script performs a complete build of all arc42 templates:
# 1. Installs Pandoc (if needed)
# 2. Checks out the arc42-template submodule (Golden Master) at the commit
#    recorded in this repository - or, with --update-template, at the tip of
#    its master branch
# 3. Runs the Groovy build system (templates + conversion + distribution)
# 4. Validates the generated files:
#    - CommonMark syntax of the Markdown output (cmark; non-fatal)
#    - image directories and image references of the with-help flavors (fatal)
#
# Usage: ./build-arc42.sh [--update-template] [-h|--help]
# Environment: UPDATE_TEMPLATE=1 is the same as --update-template
#

set -e  # Exit on error

PANDOC_VERSION="3.7.0.2"
# SHA-256 of the Debian packages of that release; they are installed when
# pandoc is missing (i.e. outside the Docker image, which pins the same version)
PANDOC_DEB_SHA256_AMD64="4db8bad3d9f8451a3d52171664f3c58b08af6450fbd54a28dd05f6b00b0bbb04"
PANDOC_DEB_SHA256_ARM64="e17bbb48465de4f5eb4f258321233704973e83ce9958d70e38236acaaa4748a3"
SUBMODULE_DIR="arc42-template"

usage() {
    cat <<USAGE
Usage: ${0##*/} [OPTIONS]

Full arc42 template build: installs Pandoc if it is missing, checks out the
arc42-template submodule (Golden Master), runs the Groovy build (templates,
conversion, distribution ZIPs) and validates the generated files.

By default the submodule is checked out at the commit recorded in this
repository, so the build is reproducible from the generator's git history.

Options:
  --update-template  After the checkout, move the submodule to the tip of its
                     master branch (git checkout master && git pull --ff-only)
                     and build that instead of the recorded commit.
                     Same as setting UPDATE_TEMPLATE=1 in the environment.
  -h, --help         Show this help and exit.

Exit status:
  0  build and validations succeeded
  1  build failed, or the image validation of the with-help flavors failed
  2  invalid command line
USAGE
}

# --- Command line ------------------------------------------------------------
for arg in "$@"; do
    case "$arg" in
        --update-template) UPDATE_TEMPLATE=1 ;;
        -h|--help) usage; exit 0 ;;
        *)
            echo "Error: unknown option '$arg'" >&2
            echo "" >&2
            usage >&2
            exit 2
            ;;
    esac
done
case "${UPDATE_TEMPLATE:-0}" in
    1|true|yes) UPDATE_TEMPLATE=1 ;;
    *) UPDATE_TEMPLATE=0 ;;
esac

echo "╔═══════════════════════════════════════════════════════════════════════════╗"
echo "║                     arc42 Template Build Pipeline                        ║"
echo "╚═══════════════════════════════════════════════════════════════════════════╝"
echo ""

# --- Pandoc ------------------------------------------------------------------
if command -v pandoc > /dev/null 2>&1; then
    echo "✓ Pandoc already installed: $(pandoc --version | head -n 1)"
else
    echo "==> Installing Pandoc ${PANDOC_VERSION}..."

    # Detect architecture
    ARCH=$(dpkg --print-architecture 2>/dev/null || uname -m)
    case "$ARCH" in
        amd64|x86_64)
            PANDOC_DEB="pandoc-${PANDOC_VERSION}-1-amd64.deb"
            PANDOC_DEB_SHA256="$PANDOC_DEB_SHA256_AMD64"
            ;;
        arm64|aarch64)
            PANDOC_DEB="pandoc-${PANDOC_VERSION}-1-arm64.deb"
            PANDOC_DEB_SHA256="$PANDOC_DEB_SHA256_ARM64"
            ;;
        *)
            echo "Error: Unsupported architecture: $ARCH" >&2
            echo "Please install Pandoc manually from https://pandoc.org/installing.html" >&2
            exit 1
            ;;
    esac

    echo "Detected architecture: $ARCH, downloading $PANDOC_DEB"
    wget -nv "https://github.com/jgm/pandoc/releases/download/${PANDOC_VERSION}/${PANDOC_DEB}"
    echo "${PANDOC_DEB_SHA256}  ${PANDOC_DEB}" | sha256sum -c -
    dpkg -i "$PANDOC_DEB"
    rm -f "$PANDOC_DEB"
    echo "✓ Pandoc installed: $(pandoc --version | head -n 1)"
fi
echo ""

# --- Submodule (Golden Master) -----------------------------------------------
echo "==> Preparing $SUBMODULE_DIR submodule..."

# Fix Git ownership issue when running in Docker with mounted volumes (Codespaces/CI)
git config --global --add safe.directory /workspace 2>/dev/null || true
git config --global --add safe.directory /workspace/arc42-template 2>/dev/null || true

# Check out the submodule at the commit recorded in this repository. Local
# changes inside the submodule are left alone (no rm -rf, no --force).
# Retry with stale-lock cleanup: Docker Desktop's macOS bind-mount layer can
# race on index.lock creation, especially with another git client (e.g. a
# GUI tool) watching the same repo concurrently.
for attempt in 1 2 3; do
    rm -f .git/index.lock ".git/modules/${SUBMODULE_DIR}/index.lock" 2>/dev/null || true
    if git submodule update --init --recursive; then
        break
    fi
    if [ "$attempt" -eq 3 ]; then
        echo "✗ Submodule update failed after $attempt attempts" >&2
        echo "  If $SUBMODULE_DIR has local changes, commit or discard them first" >&2
        echo "  (see: git -C $SUBMODULE_DIR status)" >&2
        exit 1
    fi
    echo "Submodule update failed (attempt $attempt/3), retrying..."
    sleep 2
done

RECORDED_COMMIT=$(git rev-parse "HEAD:${SUBMODULE_DIR}")
if [ "$UPDATE_TEMPLATE" -eq 1 ]; then
    echo "--update-template: moving $SUBMODULE_DIR to the tip of its master branch..."
    git -C "$SUBMODULE_DIR" checkout master
    git -C "$SUBMODULE_DIR" pull --ff-only
    TEMPLATE_COMMIT=$(git -C "$SUBMODULE_DIR" rev-parse HEAD)
    if [ "$TEMPLATE_COMMIT" = "$RECORDED_COMMIT" ]; then
        echo "✓ $SUBMODULE_DIR is at master tip ${TEMPLATE_COMMIT:0:7} (same as the recorded commit)"
    else
        echo "✓ $SUBMODULE_DIR moved from recorded commit ${RECORDED_COMMIT:0:7} to master tip ${TEMPLATE_COMMIT:0:7}"
        echo "  To make future builds use this commit: git add $SUBMODULE_DIR && git commit"
    fi
else
    echo "✓ $SUBMODULE_DIR checked out at recorded commit $(git -C "$SUBMODULE_DIR" rev-parse --short HEAD)"
    echo "  (pass --update-template to build the tip of master instead)"
fi
git -C "$SUBMODULE_DIR" log -1 --format='  %h %ad %s' --date=short
echo ""

# --- Groovy build ------------------------------------------------------------
echo "==> Building arc42 templates with Groovy build system..."
groovy build.groovy

# --- Validation 1: CommonMark (non-fatal) ------------------------------------
echo ""
echo "==> Validating generated Markdown files with cmark (non-fatal)..."
if ! command -v cmark > /dev/null 2>&1; then
    echo "⚠ cmark is not installed - skipping CommonMark validation"
else
    CMARK_CHECKED=0
    CMARK_FAILED=0
    for md_file in build/*/markdown/*/*.md; do
        [ -f "$md_file" ] || continue
        CMARK_CHECKED=$((CMARK_CHECKED + 1))
        if ! cmark "$md_file" > /dev/null 2>&1; then
            echo "  ✗ $md_file"
            CMARK_FAILED=$((CMARK_FAILED + 1))
        fi
    done
    if [ "$CMARK_CHECKED" -eq 0 ]; then
        echo "⚠ Warning: no Markdown files found under build/*/markdown/ (nothing validated)"
    elif [ "$CMARK_FAILED" -eq 0 ]; then
        echo "✓ All $CMARK_CHECKED Markdown files are valid CommonMark"
    else
        echo "⚠ Warning: $CMARK_FAILED of $CMARK_CHECKED Markdown files failed CommonMark validation"
        echo "  (non-fatal, build continues)"
    fi
fi

# --- Validation 2: images of the with-help flavors (fatal) -------------------
# Plain flavors contain no help text and therefore no images; the with-help
# flavors ship an images/ folder next to the document, which must be complete.
echo ""
echo "==> Validating images of the with-help flavors (fatal)..."
IMAGE_PROBLEMS=0

# Formats whose with-help output must contain a non-empty images/ directory
FORMATS_WITH_IMAGES="markdown asciidoc textile rst html"

echo "Checking image directories..."
for lang_dir in build/*/; do
    [ -d "$lang_dir" ] || continue
    lang=$(basename "$lang_dir")
    for format in $FORMATS_WITH_IMAGES; do
        with_help_dir="${lang_dir}${format}/with-help"
        [ -d "$with_help_dir" ] || continue
        images_dir="${with_help_dir}/images"
        if [ ! -d "$images_dir" ]; then
            echo "  ✗ Missing images directory: $lang/$format/with-help/images/"
            IMAGE_PROBLEMS=$((IMAGE_PROBLEMS + 1))
        elif [ -z "$(ls -A "$images_dir")" ]; then
            echo "  ✗ Empty images directory: $lang/$format/with-help/images/"
            IMAGE_PROBLEMS=$((IMAGE_PROBLEMS + 1))
        fi
    done
done

echo "Checking image references in Markdown with-help files..."
MD_FILES_CHECKED=0
for md_file in build/*/markdown/with-help/*.md; do
    [ -f "$md_file" ] || continue
    MD_FILES_CHECKED=$((MD_FILES_CHECKED + 1))
    md_dir=$(dirname "$md_file")
    lang=$(basename "$(dirname "$(dirname "$md_dir")")")
    # Every inline target "](...)" - images ![alt](path) as well as links - is
    # extracted with a POSIX BRE (works with GNU and busybox grep) and kept when
    # it points to an image file. Pandoc may wrap the alt text over several
    # lines, but never the target.
    while IFS= read -r match; do
        img_ref="${match#](}"
        img_ref="${img_ref%)}"
        img_ref="${img_ref%% \"*}"      # optional "title"
        img_ref="${img_ref%% \'*}"      # optional 'title'
        img_ref="${img_ref#<}"          # <path with spaces>
        img_ref="${img_ref%>}"
        img_ref="${img_ref#./}"
        [ -n "$img_ref" ] || continue
        ext="${img_ref##*.}"
        case "${ext,,}" in
            png|jpg|jpeg|gif|svg|webp|bmp) ;;
            *) continue ;;
        esac
        case "$img_ref" in
            http://*|https://*|data:*) continue ;;   # not shipped in the ZIP
        esac
        if [ ! -f "$md_dir/$img_ref" ]; then
            echo "  ✗ Missing image in $lang: $img_ref (referenced in $(basename "$md_file"))"
            IMAGE_PROBLEMS=$((IMAGE_PROBLEMS + 1))
        fi
    done < <(grep -o '[]][(][^)]*[)]' "$md_file" || true)
done
if [ "$MD_FILES_CHECKED" -eq 0 ]; then
    echo "⚠ Warning: no Markdown with-help files found under build/*/markdown/with-help/"
fi

if [ "$IMAGE_PROBLEMS" -eq 0 ]; then
    echo "✓ Image directories present and all image references valid ($MD_FILES_CHECKED Markdown with-help file(s) checked)"
else
    echo "✗ $IMAGE_PROBLEMS image problem(s) detected in the with-help flavors (see above)"
fi
echo ""

if [ "$IMAGE_PROBLEMS" -ne 0 ]; then
    echo "╔═══════════════════════════════════════════════════════════════════════════╗"
    echo "║                          BUILD FAILED                                     ║"
    echo "╚═══════════════════════════════════════════════════════════════════════════╝"
    echo ""
    echo "The image validation of the with-help flavors reported $IMAGE_PROBLEMS problem(s)."
    echo "The generated files in build/ and arc42-template/dist/ are incomplete - fix the"
    echo "problems listed above and rebuild before publishing anything."
    echo ""
    exit 1
fi

echo "╔═══════════════════════════════════════════════════════════════════════════╗"
echo "║                          BUILD COMPLETE                                   ║"
echo "╚═══════════════════════════════════════════════════════════════════════════╝"
echo ""
echo "Distribution files created in: arc42-template/dist/"
echo ""
echo "Next steps:"
echo "  1. Review the generated files in arc42-template/dist/"
echo "  2. If everything looks good:"
echo "     cd arc42-template"
echo "     git add dist/*.zip"
echo "     git commit -m 'Update distributions'"
echo "     git push"
echo ""
