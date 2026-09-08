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

def report = new groovy.json.JsonSlurper().parseText(reportFile.text)
assert report.version == '2' : "Report should contain version 2"
assert report.fullBuildTriggered == false : "fullBuildTriggered should be false"

// module-a should be directly affected with SOURCE_CHANGE
def moduleA = report.affectedModules.find { it.artifactId == 'module-a' }
assert moduleA != null : "module-a should appear in report"
assert moduleA.reasons.contains('SOURCE_CHANGE') : "module-a should have SOURCE_CHANGE reason"
assert moduleA.category == 'DIRECT' : "module-a should have DIRECT category"

// module-b should be downstream with testsSkippedReason=EXCLUDED_DOWNSTREAM
def moduleB = report.affectedModules.find { it.artifactId == 'module-b' }
assert moduleB != null : "module-b should appear in report"
assert moduleB.reasons.contains('DOWNSTREAM_DEPENDENT') : "module-b should have DOWNSTREAM_DEPENDENT reason"
assert moduleB.testsSkippedReason == 'EXCLUDED_DOWNSTREAM' : \
    "module-b should have testsSkippedReason=EXCLUDED_DOWNSTREAM in report, got: $moduleB"

// module-c should be downstream but WITHOUT testsSkippedReason
def moduleC = report.affectedModules.find { it.artifactId == 'module-c' }
assert moduleC != null : "module-c should appear in report"
assert moduleC.testsSkippedReason == null : \
    "module-c should NOT have testsSkippedReason in report, got: $moduleC"
