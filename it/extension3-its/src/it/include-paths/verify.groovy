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

// module-a source changed -> module-a is directly affected
assert log.contains('1 modules directly affected') : "Expected exactly 1 module directly affected"
def directlyAffectedLine = log.readLines().find { it.contains('directly affected') }
assert directlyAffectedLine != null : "Expected 'directly affected' log line"
assert directlyAffectedLine.contains('module-a') : "module-a should be directly affected (source changed)"
assert !directlyAffectedLine.contains('module-b') : "module-b should not be directly affected"

// module-b is downstream of module-a but outside includePaths -> NOT in the build set
def buildingLine = log.readLines().find { it.contains('Scalpel: Building') && it.contains('of 3 modules') }
assert buildingLine != null : "Expected 'Scalpel: Building X of 3 modules' log line"
assert buildingLine.contains('module-a') : "module-a should be in the build set"
assert !buildingLine.contains('module-b') : "module-b should be excluded by includePaths filter"
