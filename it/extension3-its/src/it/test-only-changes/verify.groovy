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

// Scalpel should be activated in report mode
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
def moduleC = modules.find { it.artifactId == 'module-c' }

// module-a should have TEST_CHANGE reason (only test files changed)
assert moduleA != null : "module-a should appear in the report"
assert moduleA.reasons.contains('TEST_CHANGE') : "module-a should have TEST_CHANGE reason, got: ${moduleA.reasons}"

// module-b should have SOURCE_CHANGE reason (main source changed)
assert moduleB != null : "module-b should appear in the report"
assert moduleB.reasons.contains('SOURCE_CHANGE') : "module-b should have SOURCE_CHANGE reason, got: ${moduleB.reasons}"

// module-c should NOT appear in the report: it depends on module-a via compile scope,
// but module-a only has test changes, so downstream propagation is suppressed
assert moduleC == null : "module-c should NOT appear in the report (test-only module-a should not propagate to compile-scoped downstream)"
