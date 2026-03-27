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

// All modules should still be present in reactor (no trimming)
assert log.contains('BUILD SUCCESS')

// Check the report file exists and has valid content
File reportFile = new File(basedir, 'target/scalpel-report.json')
assert reportFile.exists() : "Report file should have been created at target/scalpel-report.json"

String json = reportFile.text
assert json.contains('"version": "1"') : "Report should contain version field"
assert json.contains('"fullBuildTriggered": false') : "fullBuildTriggered should be false"
assert json.contains('"affectedModules"') : "Report should contain affectedModules"
assert json.contains('"module-b"') : "module-b should appear in the report as affected"
assert json.contains('"SOURCE_CHANGE"') : "module-b should have SOURCE_CHANGE reason"
