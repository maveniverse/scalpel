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

// Scalpel should be activated
assert log.contains('Scalpel') : "Expected Scalpel to be activated"

// Should detect uncommitted files
assert log.contains('uncommitted files detected') : "Expected uncommitted files to be detected, log: $log"

// module-b should be directly affected
assert log.contains('directly affected') : "Expected directly affected modules"
def directlyAffectedLine = log.readLines().find { it.contains('directly affected') }
assert directlyAffectedLine.contains('module-b') : "module-b should be directly affected (uncommitted source)"
