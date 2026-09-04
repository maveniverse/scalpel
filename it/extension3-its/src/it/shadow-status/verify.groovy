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

assert log.contains('mode=shadow')
assert log.contains('BUILD SUCCESS')

// The run bailed out before any measurement (no git repository, no base branch)
assert log.contains('building all modules')

// The stale measurement from a previous run must NOT survive: the shadow document is
// overwritten with a status-only document, the shadow twin of the report's #89 semantics.
File shadowFile = new File(basedir, 'target/scalpel-shadow.json')
assert shadowFile.exists() : "shadow document must exist"
String shadow = shadowFile.text
assert !shadow.contains('previous-run-measurement') : "stale shadow document survived the bail-out"
assert shadow.contains('"mode": "shadow"')
assert shadow.contains('"status": "skipped"')
assert shadow.contains('"reason"')

// The report carries the same status
File reportFile = new File(basedir, 'target/scalpel-report.json')
assert reportFile.exists()
assert reportFile.text.contains('"status": "skipped"')

// No history line is appended by a run that measured nothing: a gap means "not measured"
File history = new File(basedir, 'target/scalpel-shadow-history.jsonl')
assert !history.exists() : "an unmeasured run must not append history"
