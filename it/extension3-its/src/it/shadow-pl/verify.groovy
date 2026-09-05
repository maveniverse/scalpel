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

// The -pl exit returns before the activation line is logged, so assert the disable
// message itself; the mode is only visible in the shadow status document.
assert log.contains('Scalpel')
assert log.contains('disabled due to -pl project selection')
assert log.contains('BUILD SUCCESS')

// The -pl exit happens before any measurement, so the stale document from a previous
// run must be overwritten with a skipped status document, not left behind.
File shadowFile = new File(basedir, 'target/scalpel-shadow.json')
assert shadowFile.exists() : "shadow document must exist"
String shadow = shadowFile.text
assert !shadow.contains('previous-run-measurement') : "stale shadow document survived the -pl exit"
assert shadow.contains('"mode": "shadow"')
assert shadow.contains('"status": "skipped"') : "the -pl exit is a deliberate skip and must say so"
assert shadow.contains('-pl')

File history = new File(basedir, 'target/scalpel-shadow-history.jsonl')
assert !history.exists() : "an unmeasured run must not append history"
