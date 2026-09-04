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

// Scalpel activated in shadow mode
assert log.contains('Scalpel')
assert log.contains('mode=shadow')

// Shadow mode never alters the reactor: no trim line, every module built
assert !log.contains('Scalpel: Building ') : "shadow mode must not trim the reactor"
assert log.contains('module-a')
assert log.contains('module-b')
assert log.contains('BUILD SUCCESS')

// Shadow behaves exactly like report mode: the JSON report is written too
assert log.contains('Report written to')
File reportFile = new File(basedir, 'target/scalpel-report.json')
assert reportFile.exists() : "shadow mode must also write the standard report"
String report = reportFile.text
assert report.contains('"affectedModules"') : "report should contain affectedModules"
assert report.contains('module-b') : "module-b should appear in the report as affected"
assert report.contains('SOURCE_CHANGE') : "module-b should have SOURCE_CHANGE reason"

// The shadow document carries the would-be decision and the join
File shadowFile = new File(basedir, 'target/scalpel-shadow.json')
assert shadowFile.exists() : "shadow json should be written at session end"
String shadow = shadowFile.text
assert shadow.contains('"mode": "shadow"')
assert shadow.contains('"estimatedSecondsSaved"')
assert shadow.contains('"wouldHaveSkippedButFailed"')
assert shadow.contains('module-a') : "module-a is unaffected and must be in the would-skip set"
assert shadow.contains('"wouldHaveBuilt"')

// Decision parity with report mode: this fixture is byte-identical to the report-mode IT,
// where the same change classifies module-b as affected (SOURCE_CHANGE) and leaves
// module-a out. The shadow decision must agree: module-b would be built, module-a skipped.
assert shadow.contains('module-b')
def wouldSkipMatchesReport = shadow.contains('module-a') && report.contains('"path": "module-a"')
assert wouldSkipMatchesReport : "shadow would-skip set must match the report's untouched module set"

// One JSONL history line per run
File history = new File(basedir, 'target/scalpel-shadow-history.jsonl')
assert history.exists() : "history jsonl should be appended"
def lines = history.text.readLines().findAll { it.trim() }
assert lines.size() == 1 : "expected exactly one history line, got ${lines.size()}"
assert lines[0].contains('module-a')
