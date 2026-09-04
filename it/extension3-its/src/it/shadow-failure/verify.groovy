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

// The full build ran everything and module-c's planted failure broke it
assert log.contains('mode=shadow')
assert log.contains('BUILD FAILURE')
assert log.contains('AlwaysFailsTest') : "the planted failing test must have run"
assert !log.contains('Scalpel: Building ') : "shadow mode must not trim the reactor"

// The shadow document is written even on a failed build (session end), and the
// false-negative counter is populated: module-c would have been skipped but failed.
// module-a is an upstream prerequisite of the changed module-b and must NOT be flagged.
File shadowFile = new File(basedir, 'target/scalpel-shadow.json')
assert shadowFile.exists() : "shadow json must be written even when the build fails"
String shadow = shadowFile.text
assert shadow.contains('module-b')
def fnStart = shadow.indexOf('"wouldHaveSkippedButFailed": [')
assert fnStart >= 0 : "shadow json must carry wouldHaveSkippedButFailed"
def fnOpen = shadow.indexOf('[', fnStart)
def fnClose = shadow.indexOf(']', fnOpen)
def fnSet = shadow.substring(fnOpen, fnClose)
assert fnSet.contains('module-c') : "module-c must be flagged as would-have-skipped-but-failed"
assert !fnSet.contains('module-a') : "module-a would have been built and must not be flagged"

File history = new File(basedir, 'target/scalpel-shadow-history.jsonl')
assert history.exists() : "history jsonl must be appended even on failure"
