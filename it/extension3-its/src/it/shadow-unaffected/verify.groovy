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
// Since notes.md is a bare root-level file not owned by any module (#229), Scalpel triggers
// a full build.  The shadow document is still overwritten with a skipped/non-measurement status.
assert log.contains('Full build triggered by unattributable root-level file') || log.contains('No modules affected by changes')
assert log.contains('BUILD SUCCESS')

// The analysis completed but nothing was measured; the stale document from a previous run
// must be overwritten (regardless of which exit path fired).
File shadowFile = new File(basedir, 'target/scalpel-shadow.json')
assert shadowFile.exists() : "shadow document must exist"
String shadow = shadowFile.text
assert !shadow.contains('previous-run-measurement') : "stale shadow document survived the exit"
assert shadow.contains('"mode": "shadow"')
assert shadow.contains('"status": "skipped"')
assert shadow.contains('"reason"')

File history = new File(basedir, 'target/scalpel-shadow-history.jsonl')
assert !history.exists() : "an unmeasured run must not append history"
