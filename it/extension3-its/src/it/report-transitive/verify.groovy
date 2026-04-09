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

// Report should have been written
assert log.contains('Report written to')

// All modules should still be present in reactor (no trimming in report mode)
assert log.contains('BUILD SUCCESS')

// module-a should be directly affected (uses changed managed dependency)
assert log.contains('directly affected')
def directlyAffectedLine = log.readLines().find { it.contains('directly affected') }
assert directlyAffectedLine != null : "Expected 'directly affected' log line"
assert directlyAffectedLine.contains('module-a') : "module-a should be directly affected"

// module-b should be transitively affected
assert log.contains('transitively affected')
def transitivelyAffectedLine = log.readLines().find { it.contains('transitively affected') }
assert transitivelyAffectedLine != null : "Expected 'transitively affected' log line"
assert transitivelyAffectedLine.contains('module-b') : "module-b should be transitively affected"

// Check the report file
File reportFile = new File(basedir, 'target/scalpel-report.json')
assert reportFile.exists() : "Report file should have been created"

String json = reportFile.text
def report = new groovy.json.JsonSlurper().parseText(json)

assert report.version == '1' : "Report should have version 1"
assert report.fullBuildTriggered == false : "fullBuildTriggered should be false"
assert report.changedManagedDependencies.contains('commons-lang:commons-lang') : "changedManagedDependencies should contain commons-lang"

def modules = report.affectedModules
def moduleA = modules.find { it.artifactId == 'module-a' }
def moduleB = modules.find { it.artifactId == 'module-b' }
def moduleC = modules.find { it.artifactId == 'module-c' }

// module-a should be affected with POM_CHANGE reason (directly uses changed managed dep)
assert moduleA != null : "module-a should appear in the report"
assert moduleA.reasons.contains('POM_CHANGE') : "module-a should have POM_CHANGE reason, got: ${moduleA.reasons}"

// module-b should be affected with TRANSITIVE_DEPENDENCY reason (gets it through module-a)
assert moduleB != null : "module-b should appear in the report"
assert moduleB.reasons.contains('TRANSITIVE_DEPENDENCY') : "module-b should have TRANSITIVE_DEPENDENCY reason, got: ${moduleB.reasons}"

// module-c should NOT appear in the report (no relationship to changed dependency)
assert moduleC == null : "module-c should NOT appear in the report"
