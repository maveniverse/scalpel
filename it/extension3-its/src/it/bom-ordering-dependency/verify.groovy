/*
 * Copyright (c) Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
File buildLog = new File(basedir, 'build.log')
assert buildLog.exists()
String log = buildLog.text

// Scalpel should have been activated in report mode
assert log.contains('Scalpel')
assert log.contains('mode=report')
assert log.contains('Report written to')
assert log.contains('BUILD SUCCESS')

File reportFile = new File(basedir, 'target/scalpel-report.json')
assert reportFile.exists() : "Report file should have been created"
def report = new groovy.json.JsonSlurper().parseText(reportFile.text)
assert report.fullBuildTriggered == false : "fullBuildTriggered should be false"

def modules = report.affectedModules
def bom = modules.find { it.artifactId == 'bom' }
def moduleA = modules.find { it.artifactId == 'module-a' }
def moduleB = modules.find { it.artifactId == 'module-b' }
def processor = modules.find { it.artifactId == 'processor' }
def moduleC = modules.find { it.artifactId == 'module-c' }

// The BOM's managed dependency version changed through the property of the root POM
assert bom != null : "bom should appear in the report"
assert bom.category == 'DIRECT' : "bom should be directly affected, got: ${bom.category}"

// module-a uses the managed dependency whose version changed
assert moduleA != null : "module-a should appear in the report"
assert moduleA.category == 'DIRECT' : "module-a should be directly affected, got: ${moduleA.category}"

// module-b inherits the test-scoped pom dependency on the BOM with a *:* exclusion from build-parent: it only
// makes sure the BOM is built first and brings nothing to module-b, which does not use the changed dependency
assert moduleB == null : "module-b should NOT appear in the report, got: ${moduleB}"

// The processor (jar packaging) uses the managed dependency whose version changed
assert processor != null : "processor should appear in the report"
assert processor.category == 'DIRECT' : "processor should be directly affected, got: ${processor.category}"

// module-c depends on the processor through a test-scoped pom dependency with a *:* exclusion too, but the processor
// has the jar packaging: the dependency is there to order the reactor as module-c uses the processor in a way Maven
// does not see (e.g. as an annotation processor), so a change of the processor does reach module-c
assert moduleC != null : "module-c should appear in the report"
