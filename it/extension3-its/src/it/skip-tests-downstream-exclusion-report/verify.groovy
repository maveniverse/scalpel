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
assert log.contains('Scalpel') : "Expected Scalpel to be activated"
assert log.contains('mode=report') : "Expected report mode"

// Report should have been written
assert log.contains('Report written to') : "Expected report to be written"

// All modules should still be present in reactor (no trimming in report mode)
assert log.contains('BUILD SUCCESS')

// Check the report file exists and has valid content
File reportFile = new File(basedir, 'target/scalpel-report.json')
assert reportFile.exists() : "Report file should have been created at target/scalpel-report.json"

// Parsed, not hand-sliced: brace-counting breaks the moment a module object
// gains a nested field, which is exactly how a schema addition should NOT
// fail these assertions (see #96).
def report = new groovy.json.JsonSlurper().parseText(reportFile.text)
assert report.version == '2' : "Report should have version 2"
assert report.fullBuildTriggered == false : "fullBuildTriggered should be false"

def modules = report.affectedModules
def moduleA = modules.find { it.artifactId == 'module-a' }
def moduleB = modules.find { it.artifactId == 'module-b' }
def moduleC = modules.find { it.artifactId == 'module-c' }

// module-a should be directly affected with SOURCE_CHANGE
assert moduleA : "module-a should appear in report"
assert moduleA.reasons.contains('SOURCE_CHANGE') : "module-a should have SOURCE_CHANGE reason"
assert moduleA.category == 'DIRECT' : "module-a should have DIRECT category"

// module-b should be downstream with testsSkipped + testsSkippedReason=EXCLUDED_DOWNSTREAM
assert moduleB : "module-b should appear in report"
assert moduleB.reasons.contains('DOWNSTREAM_DEPENDENT') : "module-b should have DOWNSTREAM_DEPENDENT reason"
assert moduleB.testsSkipped == true : "module-b should have testsSkipped=true"
assert moduleB.testsSkippedReason == 'EXCLUDED_DOWNSTREAM' : \
    "module-b should have testsSkippedReason=EXCLUDED_DOWNSTREAM"

// module-c should be downstream but WITHOUT testsSkipped/testsSkippedReason
assert moduleC : "module-c should appear in report"
assert !moduleC.containsKey('testsSkipped') : \
    "module-c should NOT have testsSkipped in report"
assert !moduleC.containsKey('testsSkippedReason') : \
    "module-c should NOT have testsSkippedReason in report"
