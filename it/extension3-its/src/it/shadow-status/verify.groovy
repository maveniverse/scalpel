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

// The run bailed out before any measurement. Which status it bails out with depends on
// the environment: with no base branch detected (a local run) it is "skipped"; on CI,
// where GITHUB_BASE_REF auto-detection configures a base branch that then cannot resolve
// in this git-less fixture, it is "failed" via the fail-safe path. Both are status-only
// documents; the property under test is the overwrite itself, not the specific status.
File shadowFile = new File(basedir, 'target/scalpel-shadow.json')
assert shadowFile.exists() : "shadow document must exist"
String shadow = shadowFile.text
assert !shadow.contains('previous-run-measurement') : "stale shadow document survived the bail-out"
assert shadow.contains('"mode": "shadow"')
def statusValue = (shadow =~ /"status": "(skipped|failed)"/)
assert statusValue.find() : "shadow document must carry a skipped or failed status, got: ${shadow}"
assert shadow.contains('"reason"')

// The report carries a status document too
File reportFile = new File(basedir, 'target/scalpel-report.json')
assert reportFile.exists()
assert (reportFile.text =~ /"status": "(skipped|failed)"/).find() : "report must carry the same status"

// No history line is appended by a run that measured nothing: a gap means "not measured"
File history = new File(basedir, 'target/scalpel-shadow-history.jsonl')
assert !history.exists() : "an unmeasured run must not append history"
