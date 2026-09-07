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

// verifyFullBuild overrides the default trim mode into a full observed build
assert log.contains('verifyFullBuild forces a full build')
assert !log.contains('Scalpel: Building ') : "verify must not trim the reactor"
assert log.contains('BUILD SUCCESS')

// Every module built, the report and the shadow document both carry the decision identity
File reportFile = new File(basedir, 'target/scalpel-report.json')
assert reportFile.exists()
assert reportFile.text.contains('"decisionId"') : "the report must quote the decisionId"
File shadowFile = new File(basedir, 'target/scalpel-shadow.json')
assert shadowFile.exists()
assert shadowFile.text.contains('"decisionId"')
assert shadowFile.text.contains('"wouldHaveSkippedButFailed": []') : "no false negatives on the happy path"
File history = new File(basedir, 'target/scalpel-shadow-history.jsonl')
assert history.exists()
assert history.text.contains('decisionId')
