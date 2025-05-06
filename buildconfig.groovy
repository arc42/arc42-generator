
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
    'docx': [imageFolder: false],
    'docbook': [imageFolder: true],
    'latex': [imageFolder: true],
]

distribution {
    targetPath = "arc42-template/dist/"
    //formats = ['asciidoc','html','epub','markdown','docx','docbook']
}
