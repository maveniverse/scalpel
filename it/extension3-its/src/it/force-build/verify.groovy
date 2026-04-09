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

// module-a changed (source) + module-c force-included → 2 directly affected
assert log.contains('2 modules directly affected') : "Expected 2 modules directly affected, log: $log"

def directlyAffectedLine = log.readLines().find { it.contains('directly affected') }
assert directlyAffectedLine.contains('module-a') : "module-a should be directly affected (source changed)"
assert directlyAffectedLine.contains('module-c') : "module-c should be directly affected (force-included)"

// module-c should be in the build set even though it has no changes
def buildingLine = log.readLines().find { it.contains('Building') && it.contains('of 4 modules') }
assert buildingLine != null : "Expected 'Building X of 4 modules' log line"
assert buildingLine.contains('module-a') : "module-a should be in the build set"
assert buildingLine.contains('module-c') : "module-c should be in the build set (force-included)"
