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

// Scalpel should detect the BOM change
assert log.contains('Scalpel')

// module-a uses a dependency the BOM manages, so the BOM bump affects it
def directlyAffectedLine = log.readLines().find { it.contains('directly affected') }
assert directlyAffectedLine != null : "Expected 'directly affected' log line"
assert directlyAffectedLine.contains('module-a') : "module-a should be directly affected (uses a BOM-managed dependency)"
assert !directlyAffectedLine.contains('module-b') : "module-b should NOT be directly affected (uses no BOM-managed dependency)"

// Scalpel must download the old BOM revision for its old effective model
assert !log.contains('No connector factories available') : "Scalpel could not download the old BOM revision"
assert !log.contains('Partial effective model') : "Scalpel built a partial effective model"
