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

// Scalpel should detect the managed plugin change
assert log.contains('Scalpel')

// module-a binds surefire through its jar packaging, so the managed version bump affects it
assert log.contains('1 modules directly affected') : "Expected exactly 1 module directly affected"

def directlyAffectedLine = log.readLines().find { it.contains('directly affected') }
assert directlyAffectedLine != null : "Expected 'directly affected' log line"
assert directlyAffectedLine.contains('module-a') : "module-a should be directly affected (binds surefire by default)"
assert !directlyAffectedLine.contains('module-b') : "module-b should NOT be directly affected (pom packaging binds no surefire)"
