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

// Scalpel should have been activated
assert log.contains('Scalpel')
assert log.contains('Impacted modules written to') : "Expected impacted log to be written"

// Check the impacted log file
File impactedLog = new File(basedir, 'target/scalpel-impacted.log')
assert impactedLog.exists() : "Expected impacted log file at target/scalpel-impacted.log"

List<String> lines = impactedLog.readLines()
assert lines.contains('module-b') : "module-b should be in impacted log (source changed)"
assert !lines.contains('module-a') : "module-a should NOT be in impacted log (not directly affected)"
assert !lines.contains('module-c') : "module-c should NOT be in impacted log (not directly affected)"
