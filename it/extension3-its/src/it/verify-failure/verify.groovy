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

// verifyFullBuild with the default trim mode: the reactor is NOT trimmed, the full build
// runs, and the override is announced
assert log.contains('verifyFullBuild forces a full build')
assert !log.contains('Scalpel: Building ') : "verify must not trim the reactor"
assert log.contains('AlwaysFailsTest') : "the planted failing test must have run"
assert log.contains('BUILD FAILURE')

// The verify verdict: module-c failed but Scalpel would have skipped it, with the skip
// reason and the decision identity quoted so the failure is correlatable (#101)
assert log.contains('module-c') : "the false negative must be named"
assert log.contains('NOT_AFFECTED') : "the skip reason must be named"
assert log.contains('decisionId') : "the decision identity must be quotable"

// Both artifacts of the observed run
File shadowFile = new File(basedir, 'target/scalpel-shadow.json')
assert shadowFile.exists() : "shadow json must be written"
assert shadowFile.text.contains('"decisionId"')
assert shadowFile.text.contains('module-c')
File reportFile = new File(basedir, 'target/scalpel-report.json')
assert reportFile.exists() : "the standard report must also be written"
assert reportFile.text.contains('"decisionId"')
File history = new File(basedir, 'target/scalpel-shadow-history.jsonl')
assert history.exists()
