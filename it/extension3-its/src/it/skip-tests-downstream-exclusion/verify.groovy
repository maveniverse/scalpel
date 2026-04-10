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

// module-a should be directly affected (source changed)
assert log.contains('directly affected') : "Expected directly affected modules"
def directlyAffectedLine = log.readLines().find { it.contains('directly affected') }
assert directlyAffectedLine.contains('module-a') : "module-a should be directly affected"

// In skip-tests mode, the log should show skipping tests on some modules
def skippingLine = log.readLines().find { it.contains('skipping tests on') }
assert skippingLine != null : "Expected 'skipping tests on' log line"

// module-b should have tests skipped (excluded downstream)
assert skippingLine.contains('module-b') : "Expected module-b to have tests skipped (excluded downstream), got: $skippingLine"

// module-c should NOT have tests skipped (downstream but not excluded)
// module-c should be in the testing set, not in the skipped set
assert !skippingLine.contains('module-c') : "module-c should NOT have tests skipped, got: $skippingLine"
