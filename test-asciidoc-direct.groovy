#!/usr/bin/env groovy

@Grab('org.asciidoctor:asciidoctorj:2.5.10')

import org.asciidoctor.Asciidoctor
import org.asciidoctor.Options
import org.asciidoctor.SafeMode

def asciidoctor = Asciidoctor.Factory.create()

def baseDir = new File('/workspaces/arc42-generator/build/src_gen/EN/asciidoc/plain')
def mainFile = new File(baseDir, 'src/arc42-template.adoc')
def outputDir = new File('/workspaces/arc42-generator/build/test-direct')
outputDir.mkdirs()
def outputFile = new File(outputDir, 'arc42-template.html')

println "BaseDir: ${baseDir.absolutePath}"
println "BaseDir exists: ${baseDir.exists()}"
println "MainFile: ${mainFile.absolutePath}"
println "MainFile exists: ${mainFile.exists()}"
println "Output: ${outputFile.absolutePath}"

def options = Options.builder()
    .toFile(outputFile)
    .backend('html5')
    .safe(SafeMode.UNSAFE)
    .baseDir(baseDir)
    .mkDirs(true)
    .build()

println "\nConverting..."
try {
    def result = asciidoctor.convertFile(mainFile, options)
    println "Result: ${result}"
    println "File exists: ${outputFile.exists()}"
    if (outputFile.exists()) {
        println "File size: ${outputFile.length()} bytes"
    }
} catch (Exception e) {
    println "Error: ${e.message}"
    e.printStackTrace()
}
