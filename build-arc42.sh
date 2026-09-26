#!/bin/bash
#
# build-arc42.sh - Full arc42 template build pipeline
#
# This script performs a complete build of all arc42 templates:
# 1. Installs Pandoc (if needed)
# 2. Updates the arc42-template submodule
# 3. Runs the Groovy build system (templates + conversion + distribution + verification)
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

# Fix Git ownership issue when running in Docker with mounted volumes (Codespaces/CI)
git config --global --add safe.directory /workspace 2>/dev/null || true
git config --global --add safe.directory /workspace/arc42-template 2>/dev/null || true

# Handle Docker context where submodule might be in inconsistent state
# Remove entire submodule directory to avoid conflicts with existing files
if [ -d "arc42-template" ]; then
    echo "Cleaning up existing submodule directory..."
    rm -rf arc42-template
fi

# Initialize and update submodule
# Retry with stale-lock cleanup: Docker Desktop's macOS bind-mount layer can
# race on index.lock creation, especially with another git client (e.g. a
# GUI tool) watching the same repo concurrently.
for attempt in 1 2 3; do
    rm -f .git/index.lock .git/modules/arc42-template/index.lock
    if git submodule init && git submodule update --force \
        && (cd arc42-template && git checkout master && git pull); then
        break
    fi
    if [ "$attempt" -eq 3 ]; then
        echo "✗ Submodule update failed after $attempt attempts" >&2
        exit 1
    fi
    echo "Submodule update failed (attempt $attempt/3), retrying..."
    sleep 2
done
echo "✓ Submodule updated"
echo ""

# Run Groovy build system
echo "==> Building arc42 templates with Groovy build system..."
if ! groovy build.groovy; then
    echo ""
    echo "✗ Build or verification failed. See build/reports/verify.html"
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
