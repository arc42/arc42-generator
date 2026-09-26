#!/usr/bin/env groovy

@Grab('org.asciidoctor:asciidoctorj:2.5.10')
@Grab('org.asciidoctor:asciidoctorj-diagram:2.2.14')
@Grab('org.codehaus.gpars:gpars:1.2.1')
@Grab('org.commonmark:commonmark:0.24.0')
@Grab('org.jsoup:jsoup:1.18.3')

import org.asciidoctor.Asciidoctor
import groovyx.gpars.GParsPool

println("==> Pre-downloading Groovy dependencies...")
println("✓ AsciidoctorJ 2.5.10")
println("✓ AsciidoctorJ Diagram 2.2.14")
println("✓ GPars 1.2.1")
println("✓ commonmark-java 0.24.0")
println("✓ jsoup 1.18.3")
println("")
println("Dependencies cached successfully in Groovy Grape cache")
println("Location: ~/.groovy/grapes (or GROOVY_HOME/grapes)")
