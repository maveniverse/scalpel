/*
 * Copyright (c) Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */

// Issue #131: New managed dependencies in BOM should NOT trigger full downstream rebuild.
// This test verifies that when a BOM adds a brand-new managed dep (commons-io) that no
// existing module uses, it does NOT appear in changedManagedDependencies and does NOT
// cause extra modules to be affected. Only the existing bumped dep (commons-lang) should
// trigger rebuilds.

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

// module-a should be directly affected (imports BOM with changed managed dependency commons-lang)
assert log.contains('directly affected')
def directlyAffectedLines = log.readLines().findAll { it.contains('directly affected') }
assert !directlyAffectedLines.isEmpty() : "Expected 'directly affected' log line(s)"
assert directlyAffectedLines.any { it.contains('module-a') } : "module-a should be directly affected (imports BOM with changed managed dep commons-lang)"

// module-b should be transitively affected (gets commons-lang through module-a)
assert log.contains('transitively affected')
def transitivelyAffectedLines = log.readLines().findAll { it.contains('transitively affected') }
assert !transitivelyAffectedLines.isEmpty() : "Expected 'transitively affected' log line(s)"
assert transitivelyAffectedLines.any { it.contains('module-b') } : "module-b should be transitively affected"

// Check the report file
File reportFile = new File(basedir, 'target/scalpel-report.json')
assert reportFile.exists() : "Report file should have been created"

String json = reportFile.text
def report = new groovy.json.JsonSlurper().parseText(json)

assert report.version == '2' : "Report should have version 2"
assert report.fullBuildTriggered == false : "fullBuildTriggered should be false"

// commons-lang version was bumped (modified dep), so it should be in changedManagedDependencies
assert report.changedManagedDependencies.contains('commons-lang:commons-lang') : "changedManagedDependencies should contain commons-lang (version bumped)"

// commons-io is a NEW dep that nobody uses — it should NOT be in changedManagedDependencies (issue #131)
assert !report.changedManagedDependencies.contains('commons-io:commons-io') : "changedManagedDependencies should NOT contain commons-io (new, unused managed dep — issue #131)"

def modules = report.affectedModules
def moduleA = modules.find { it.artifactId == 'module-a' }
def moduleB = modules.find { it.artifactId == 'module-b' }
def moduleC = modules.find { it.artifactId == 'module-c' }

// module-a should be affected with POM_CHANGE reason (imports BOM with changed managed dep commons-lang)
assert moduleA != null : "module-a should appear in the report"
assert moduleA.reasons.contains('POM_CHANGE') : "module-a should have POM_CHANGE reason, got: ${moduleA.reasons}"

// module-b should be affected with TRANSITIVE_DEPENDENCY reason (gets commons-lang through module-a)
assert moduleB != null : "module-b should appear in the report"
assert moduleB.reasons.contains('TRANSITIVE_DEPENDENCY') : "module-b should have TRANSITIVE_DEPENDENCY reason, got: ${moduleB.reasons}"

// module-c should NOT appear in the report (no relationship to changed dependency, and
// the new unused commons-io managed dep should NOT have caused it to be affected)
assert moduleC == null : "module-c should NOT appear in the report (issue #131 — new unused managed dep should not trigger rebuild)"
