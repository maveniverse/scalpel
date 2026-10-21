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

// verifyFullBuild runs the full build; the upstream-only module-a test fails it
assert log.contains('verifyFullBuild forces a full build')
assert !log.contains('Scalpel: Building ') : "verify must not trim the reactor"
assert log.contains('AlwaysFailsTest') : "the planted failing upstream test must have run"
assert log.contains('BUILD FAILURE')

// module-a is inside the build set, so this is NOT a trim-decision false negative:
// the verify verdict must not fire
assert !log.contains('FAILED but Scalpel would have skipped it') : \
    "an upstream-only failure is not a would-have-skipped false negative"

// The shadow document surfaces the upstream-only failure in the dedicated field (#222)
File shadowFile = new File(basedir, 'target/scalpel-shadow.json')
assert shadowFile.exists() : "shadow json must be written"
String shadow = shadowFile.text
assert shadow.contains('"wouldHaveSkippedButFailed": []') : \
    "module-a is built, not skipped: the false-negative set must stay empty"
assert shadow.contains('"upstreamOnlyTestFailures": ["module-a"]') : \
    "the failing upstream prerequisite must be named in upstreamOnlyTestFailures"

// The history line carries the same join for accumulation across runs
File history = new File(basedir, 'target/scalpel-shadow-history.jsonl')
assert history.exists()
assert history.text.contains('"upstreamOnlyTestFailures": ["module-a"]')
