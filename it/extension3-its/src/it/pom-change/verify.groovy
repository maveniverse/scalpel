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

// Scalpel should detect POM property change
assert log.contains('Scalpel')

// Only module-b references ${dep.version}, so only it should be directly affected
assert log.contains('1 modules directly affected') : "Expected exactly 1 module directly affected"

def directlyAffectedLine = log.readLines().find { it.contains('directly affected') }
assert directlyAffectedLine != null : "Expected 'directly affected' log line"
assert directlyAffectedLine.contains('module-b') : "module-b should be directly affected (references \${dep.version})"
assert !directlyAffectedLine.contains('module-a') : "module-a should NOT be directly affected (doesn't reference \${dep.version})"

// module-b should be in the build set, module-a should NOT (no dependency relationship)
def buildingLine = log.readLines().find { it.contains('Building') && it.contains('of 3 modules') }
assert buildingLine != null : "Expected 'Building X of 3 modules' log line"
assert buildingLine.contains('module-b') : "module-b should be in the build set"
assert !buildingLine.contains('module-a') : "module-a should NOT be in the build set"
