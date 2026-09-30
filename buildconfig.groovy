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
// label: name of the format in manifest.json (and on the download page)
formats = [
    'asciidoc': [imageFolder: true, label: 'AsciiDoc'],
    'html': [imageFolder: true, label: 'HTML'],
    'epub': [imageFolder: false, label: 'EPUB'],
    'rst': [imageFolder: true, label: 'reStructuredText'],
    'markdown': [imageFolder: true, label: 'Markdown'],
    'markdownMP': [imageFolder: true, label: 'Markdown · multi-page'],
    'markdownStrict': [imageFolder: true, label: 'Markdown · strict'],
    'markdownMPStrict': [imageFolder: true, label: 'Markdown MP · strict'],
    'gitHubMarkdown': [imageFolder: true, label: 'GitHub Markdown'],
    'gitHubMarkdownMP': [imageFolder: true, label: 'GitHub Markdown · MP'],
    'textile': [imageFolder: true, label: 'Textile'],
    'docx': [imageFolder: true, label: 'Word (.docx)'],
    'docbook': [imageFolder: true, label: 'DocBook'],
    'latex': [imageFolder: true, label: 'LaTeX'],
    'pdf': [imageFolder: false, label: 'PDF'],
]

distribution {
    targetPath = "arc42-template/dist/"

    // language names for manifest.json; a language without an entry is listed by its code
    languageNames = [
            CZ : 'Čeština',
            DE : 'Deutsch',
            EN : 'English',
            ES : 'Español',
            FR : 'Français',
            HU : 'Magyar',
            IT : 'Italiano',
            NL : 'Nederlands',
            PT : 'Português',
            RU : 'Русский',
            UKR: 'Українська',
            ZH : '简体中文',
            'ZH-TW': '繁體中文',
    ]
    //formats = ['asciidoc','html','epub','markdown','docx','docbook']
}
