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

// module-a should be affected: it references ${app.version} which changed in the active 'prod' profile
assert moduleA != null : "module-a should appear in the report (references changed active profile property)"
assert moduleA.reasons.contains('POM_CHANGE') : "module-a should have POM_CHANGE reason, got: ${moduleA.reasons}"

// module-b should NOT be affected: it references ${staging.url} which changed in the inactive 'staging' profile
assert moduleB == null : "module-b should NOT appear in the report (references property from inactive profile only)"

// Changed properties should include app.version but NOT staging.url
assert report.changedProperties.contains('app.version') : "changedProperties should contain 'app.version'"
assert !report.changedProperties.contains('staging.url') : "changedProperties should NOT contain 'staging.url' (inactive profile)"
