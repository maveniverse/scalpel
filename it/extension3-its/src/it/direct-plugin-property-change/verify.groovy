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

// Scalpel should detect the directly-declared plugin config change (issue #241)
assert log.contains('Scalpel')

// Both module-a and module-b inherit the surefire <forkCount> from the parent's
// <build><plugins> entry.  Changing ${lab.forks} must affect both children.
assert log.contains('2 modules directly affected') : 'Expected exactly 2 modules directly affected (issue #241: both inherit the changed <forkCount> via ${lab.forks})\nBuild log:\n' + log

def directlyAffectedLine = log.readLines().find { it.contains('directly affected') }
assert directlyAffectedLine != null : "Expected 'directly affected' log line"
assert directlyAffectedLine.contains('module-a') : "module-a should be directly affected (inherits changed <forkCount>)"
assert directlyAffectedLine.contains('module-b') : "module-b should be directly affected (inherits changed <forkCount>)"
