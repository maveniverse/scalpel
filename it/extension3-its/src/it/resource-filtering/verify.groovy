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

assert log.contains('Scalpel')
assert log.contains('mode=report')
assert log.contains('BUILD SUCCESS')

// Check the report file
File reportFile = new File(basedir, 'target/scalpel-report.json')
assert reportFile.exists() : "Report file should have been created"

def report = new groovy.json.JsonSlurper().parseText(reportFile.text)
def modules = report.affectedModules

def moduleA = modules.find { it.artifactId == 'module-a' }
def moduleB = modules.find { it.artifactId == 'module-b' }

// module-a should be affected: it has filtered resources containing ${app.name}
assert moduleA != null : "module-a should appear in the report (filtered resources reference changed property)"
assert moduleA.reasons.contains('POM_CHANGE') : "module-a should have POM_CHANGE reason, got: ${moduleA.reasons}"

// module-b should NOT be affected: no filtered resources, no property reference in POM
assert moduleB == null : "module-b should NOT appear in the report (no filtered resources, no property reference)"

// Changed properties should include app.name
assert report.changedProperties.contains('app.name') : "changedProperties should contain 'app.name'"
