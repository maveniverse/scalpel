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

// Scalpel should be activated in skip-tests mode
assert log.contains('Scalpel') : "Expected Scalpel to be activated"
assert log.contains('mode=skip-tests') : "Expected skip-tests mode"

// module-b should be directly affected (source changed)
assert log.contains('directly affected') : "Expected directly affected modules"
def directlyAffectedLine = log.readLines().find { it.contains('directly affected') }
assert directlyAffectedLine.contains('module-b') : "module-b should be directly affected"

// In skip-tests mode, tests on upstream-only module-a should be skipped
// The log should show that tests are skipped on some modules
def skippingLine = log.readLines().find { it.contains('skipping tests on') }
assert skippingLine != null : "Expected 'skipping tests on' log line"
