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
    wget https://github.com/jgm/pandoc/releases/download/3.7.0.2/pandoc-3.7.0.2-1-amd64.deb
    sudo dpkg -i pandoc-3.7.0.2-1-amd64.deb
    rm pandoc-3.7.0.2-1-amd64.deb
    echo "✓ Pandoc installed"
else
    echo "✓ Pandoc already installed: $(pandoc --version | head -1)"
fi
echo ""

# Update submodules
echo "==> Updating arc42-template submodule..."
git submodule init
git submodule update
cd arc42-template
git checkout master
git pull
cd ..
echo "✓ Submodule updated"
echo ""

# Run Groovy build system
echo "==> Building arc42 templates with Groovy build system..."
groovy build.groovy

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
