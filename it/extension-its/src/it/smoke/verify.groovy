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

// The extension artifact (not extension3) must have been loaded via .mvn/extensions.xml
assert log.contains('Scalpel') : "Scalpel extension should have been activated"

// module-b was changed -> it should be the only directly affected module
assert log.contains('1 modules directly affected') : "Expected exactly 1 module directly affected"

def directlyAffectedLine = log.readLines().find { it.contains('directly affected') }
assert directlyAffectedLine != null : "Expected 'directly affected' log line"
assert directlyAffectedLine.contains('module-b') : "module-b should be directly affected (source changed)"

// Trivial two-module reactor: module-a is upstream of the changed module-b, so all modules stay in the build
def scalpelBuildingLine = log.readLines().find { it.contains('Scalpel') && it.contains('of 3 modules') }
assert scalpelBuildingLine != null : "Expected Scalpel 'Building X of 3 modules' log line"
assert scalpelBuildingLine.contains('module-a') : "module-a should remain in the build set (upstream of changed module-b)"
assert scalpelBuildingLine.contains('module-b') : "module-b should remain in the build set"
assert log.contains('BUILD SUCCESS')
