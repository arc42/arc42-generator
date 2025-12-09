#!/usr/bin/env groovy

@Grab('org.asciidoctor:asciidoctorj:2.5.10')
@Grab('org.asciidoctor:asciidoctorj-diagram:2.2.14')
@Grab('org.codehaus.gpars:gpars:1.2.1')

import org.asciidoctor.Asciidoctor
import groovyx.gpars.GParsPool

println("==> Pre-downloading Groovy dependencies...")
println("✓ AsciidoctorJ 2.5.10")
println("✓ AsciidoctorJ Diagram 2.2.14")
println("✓ GPars 1.2.1")
println("")
println("Dependencies cached successfully in Groovy Grape cache")
println("Location: ~/.groovy/grapes (or GROOVY_HOME/grapes)")
