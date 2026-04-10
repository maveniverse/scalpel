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

String json = reportFile.text
assert json.contains('"version": "1"') : "Report should contain version field"
assert json.contains('"fullBuildTriggered": false') : "fullBuildTriggered should be false"

// module-a should be directly affected with SOURCE_CHANGE
assert json.contains('"module-a"') : "module-a should appear in report"
assert json.contains('"SOURCE_CHANGE"') : "module-a should have SOURCE_CHANGE reason"
assert json.contains('"category": "DIRECT"') : "module-a should have DIRECT category"

// module-b should be downstream with testsSkippedReason=EXCLUDED_DOWNSTREAM
assert json.contains('"module-b"') : "module-b should appear in report"
assert json.contains('"DOWNSTREAM_DEPENDENT"') : "module-b should have DOWNSTREAM_DEPENDENT reason"

// Parse module-b block and verify testsSkippedReason
def moduleBStart = json.indexOf('"module-b"')
assert moduleBStart >= 0
def moduleBBlock = json.substring(json.lastIndexOf('{', moduleBStart), json.indexOf('}', moduleBStart) + 1)
assert moduleBBlock.contains('"testsSkippedReason": "EXCLUDED_DOWNSTREAM"') : \
    "module-b should have testsSkippedReason=EXCLUDED_DOWNSTREAM in report, got: $moduleBBlock"

// module-c should be downstream but WITHOUT testsSkippedReason
assert json.contains('"module-c"') : "module-c should appear in report"
def moduleCStart = json.indexOf('"module-c"')
assert moduleCStart >= 0
def moduleCBlock = json.substring(json.lastIndexOf('{', moduleCStart), json.indexOf('}', moduleCStart) + 1)
assert !moduleCBlock.contains('testsSkippedReason') : \
    "module-c should NOT have testsSkippedReason in report, got: $moduleCBlock"
