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
