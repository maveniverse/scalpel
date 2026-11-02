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
// gated-module/pom.xml has a directory separator; ModuleMapper's pom.xml-boundary guard
// returns null for it (not part of the reactor). POM analysis treats it as an unmatched
// POM path (#229 scope fix). The analysis completes with no modules affected; the passive
// branch writes a skipped report.
assert log.contains('No modules affected by changes')
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
