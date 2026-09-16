// Project-specific names. The values below describe arc42; another template
// (e.g. req42) brings its own copy of this file, see README "Building Other Templates".
// All paths in this file are relative to the directory of this file.
project {
    // base name of the main document (<LANG>/<name>.adoc) and of all generated files and ZIPs
    name = 'arc42-template'

    // prefix of the feature markers in the golden master, e.g. [role="arc42help"],
    // and of the about page (about-arc42.adoc)
    featurePrefix = 'arc42'

    // the only image copied into the plain style
    logo = 'arc42-logo.png'
}

goldenMaster {
    sourcePath = 'arc42-template/'
    targetPath = 'build/src_gen/'

    // a list of all features contained in the golden master
    allFeatures = ['help', 'example']

    // style: list of features
    templateStyles = [
            'plain'    : [],
            'with-help': ['help'],
            // deactivated for the moment - no content yet
            // 'with-examples':['help','example'],
    ]
}
formats = [
    'asciidoc': [imageFolder: true],
    'html': [imageFolder: true],
    'epub': [imageFolder: false],
    'rst': [imageFolder: true],
    'markdown': [imageFolder: true],
    'markdownMP': [imageFolder: true],
    'markdownStrict': [imageFolder: true],
    'markdownMPStrict': [imageFolder: true],
    'gitHubMarkdown': [imageFolder: true],
    'gitHubMarkdownMP': [imageFolder: true],
    'mkdocs': [imageFolder: true],
    'mkdocsMP': [imageFolder: true],
    'textile': [imageFolder: true],
    'textile2': [imageFolder: true],
    'docx': [imageFolder: true],
    'docbook': [imageFolder: true],
    'latex': [imageFolder: true],
]

distribution {
    targetPath = "arc42-template/dist/"
    //formats = ['asciidoc','html','epub','markdown','docx','docbook']
}
