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

// module-b was changed → it should be the only directly affected module
assert log.contains('1 modules directly affected') : "Expected exactly 1 module directly affected"

def directlyAffectedLine = log.readLines().find { it.contains('directly affected') }
assert directlyAffectedLine != null : "Expected 'directly affected' log line"
assert directlyAffectedLine.contains('module-b') : "module-b should be directly affected (source changed)"

// module-c has no relationship to module-b → should NOT be in the build set
def buildingLine = log.readLines().find { it.contains('Building') && it.contains('of 4 modules') }
assert buildingLine != null : "Expected 'Building X of 4 modules' log line"
assert buildingLine.contains('module-b') : "module-b should be in the build set"
assert !buildingLine.contains('module-c') : "module-c should NOT be in the build set (no relation to changed module)"
