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

// The base branch must have been auto-detected from GITHUB_BASE_REF; if it had
// not been, Scalpel would log "No base branch configured or detected".
assert !log.contains('No base branch configured or detected') : \
    "Expected base branch to be auto-detected from GITHUB_BASE_REF, log: $log"

// module-a changed; with origin/base detected, module-b must be trimmed out
def directlyAffectedLine = log.readLines().find { it.contains('directly affected') }
assert directlyAffectedLine != null : "Expected 'directly affected' log line"
assert directlyAffectedLine.contains('module-a') : "module-a should be directly affected"

def buildingLine = log.readLines().find { it.contains('Scalpel: Building') && it.contains('of 3 modules') }
assert buildingLine != null : "Expected 'Scalpel: Building X of 3 modules' log line"
assert buildingLine.contains('module-a') : "module-a should be in the build set"
assert !buildingLine.contains('module-b') : "module-b should NOT be in the build set"
