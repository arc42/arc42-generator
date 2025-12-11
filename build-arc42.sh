#!/bin/bash
#
# build-arc42.sh - Full arc42 template build pipeline
#
# This script performs a complete build of all arc42 templates:
# 1. Installs Pandoc (if needed)
# 2. Updates the arc42-template submodule
# 3. Runs the Groovy build system (templates + conversion + distribution)
#

set -e  # Exit on error

echo "╔═══════════════════════════════════════════════════════════════════════════╗"
echo "║                     arc42 Template Build Pipeline                        ║"
echo "╚═══════════════════════════════════════════════════════════════════════════╝"
echo ""

# Check if Pandoc is installed
if ! command -v pandoc &> /dev/null; then
    echo "==> Installing Pandoc 3.7.0.2..."

    # Detect architecture
    ARCH=$(dpkg --print-architecture 2>/dev/null || uname -m)
    case "$ARCH" in
        amd64|x86_64)
            PANDOC_DEB="pandoc-3.7.0.2-1-amd64.deb"
            ;;
        arm64|aarch64)
            PANDOC_DEB="pandoc-3.7.0.2-1-arm64.deb"
            ;;
        *)
            echo "Error: Unsupported architecture: $ARCH"
            echo "Please install Pandoc manually from https://pandoc.org/installing.html"
            exit 1
            ;;
    esac

    echo "Detected architecture: $ARCH, downloading $PANDOC_DEB"
    wget https://github.com/jgm/pandoc/releases/download/3.7.0.2/$PANDOC_DEB
    dpkg -i $PANDOC_DEB
    rm $PANDOC_DEB
    echo "✓ Pandoc installed"
else
    echo "✓ Pandoc already installed: $(pandoc --version | head -1)"
fi
echo ""

# Update submodules
echo "==> Updating arc42-template submodule..."

# Handle Docker context where submodule might be in inconsistent state
# Remove entire submodule directory to avoid conflicts with existing files
if [ -d "arc42-template" ]; then
    echo "Cleaning up existing submodule directory..."
    rm -rf arc42-template
fi

# Initialize and update submodule
git submodule init
git submodule update --force
cd arc42-template
git checkout master
git pull
cd ..
echo "✓ Submodule updated"
echo ""

# Run Groovy build system
echo "==> Building arc42 templates with Groovy build system..."
groovy build.groovy

# Validate generated markdown files with cmark
echo ""
echo "==> Validating generated markdown files with cmark..."
MARKDOWN_DIR="build/EN/markdown"
VALIDATION_FAILED=0

if [ -d "$MARKDOWN_DIR" ]; then
    echo "Checking markdown files in: $MARKDOWN_DIR"
    for md_file in "$MARKDOWN_DIR"/*.md; do
        if [ -f "$md_file" ]; then
            echo -n "  Validating $(basename "$md_file")... "
            if cmark "$md_file" > /dev/null 2>&1; then
                echo "✓"
            else
                echo "✗ FAILED"
                VALIDATION_FAILED=1
            fi
        fi
    done

    if [ $VALIDATION_FAILED -eq 0 ]; then
        echo "✓ All markdown files are valid CommonMark"
    else
        echo "⚠ Warning: Some markdown files failed validation"
        echo "  (This is non-fatal, build continues)"
    fi
else
    echo "⚠ Warning: Markdown directory not found: $MARKDOWN_DIR"
    echo "  (Skipping validation)"
fi
# Validate images exist and are referenced correctly
# Images are only required for with-help flavors in specific formats
echo ""
echo "==> Validating images for with-help flavors..."
IMAGE_VALIDATION_FAILED=0

# Only check formats that need images: md, adoc, textile, rst, html
# Only check with-help flavor (not plain)
FORMATS_WITH_IMAGES="markdown asciidoc textile rst html"

# Option 1: Check if image directories exist and contain files
echo "Checking image directories in with-help flavors..."
for lang_dir in build/*/; do
    if [ -d "$lang_dir" ]; then
        lang=$(basename "$lang_dir")

        # Check for images directory in each format that needs it
        for format in $FORMATS_WITH_IMAGES; do
            # Check the with-help subdirectory (images only needed in with-help, not plain)
            with_help_dir="${lang_dir}${format}/with-help"
            if [ -d "$with_help_dir" ]; then
                images_dir="${with_help_dir}/images"

                if [ ! -d "$images_dir" ]; then
                    echo "  ⚠ Missing images directory: $lang/$format/with-help/images/"
                    IMAGE_VALIDATION_FAILED=1
                elif [ -z "$(ls -A "$images_dir" 2>/dev/null)" ]; then
                    echo "  ⚠ Empty images directory: $lang/$format/with-help/images/"
                    IMAGE_VALIDATION_FAILED=1
                fi
            fi
        done
    fi
done

# Option 2: Check markdown files for image references and validate they exist
echo "Checking image references in markdown with-help files..."
for lang_dir in build/*/markdown/with-help/; do
    if [ -d "$lang_dir" ]; then
        lang=$(basename "$(dirname "$(dirname "$lang_dir")")")
        images_dir="${lang_dir}images"

        # Find all image references in markdown files
        for md_file in "$lang_dir"*.md; do
            if [ -f "$md_file" ]; then
                # Extract image paths from markdown syntax ![alt](path)
                grep -oP '!\[.*?\]\(\K[^)]+' "$md_file" 2>/dev/null | while IFS= read -r img_ref; do
                    # Handle relative paths (remove leading ./)
                    img_ref="${img_ref#./}"

                    # Construct full path relative to markdown directory
                    if [[ "$img_ref" == images/* ]]; then
                        full_path="$lang_dir$img_ref"
                    else
                        full_path="$lang_dir$img_ref"
                    fi

                    # Check if file exists
                    if [ ! -f "$full_path" ]; then
                        echo "  ✗ Missing image in $lang: $img_ref (referenced in $(basename "$md_file"))"
                        IMAGE_VALIDATION_FAILED=1
                    fi
                done
            fi
        done
    fi
done

if [ $IMAGE_VALIDATION_FAILED -eq 0 ]; then
    echo "✓ All image directories present and references valid in with-help flavors"
else
    echo "⚠ Warning: Some image issues detected in with-help flavors"
    echo "  (This is non-fatal, build continues)"
fi
echo ""

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
