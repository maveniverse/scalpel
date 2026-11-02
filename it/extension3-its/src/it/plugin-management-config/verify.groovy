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

// Scalpel should detect the pluginManagement configuration change (issue #228)
assert log.contains('Scalpel')

// Both module-a and module-b inherit the pluginManagement <argLine> configuration
// from the parent, so both must be directly affected when it changes (issue #228).
// The <argLine> change affects test execution for all child modules regardless of
// whether they explicitly declare the surefire plugin in their own POM.
assert log.contains('2 modules directly affected') : "Expected exactly 2 modules directly affected (issue #228: both inherit the changed <argLine>)\nBuild log:\n" + log

def directlyAffectedLine = log.readLines().find { it.contains('directly affected') }
assert directlyAffectedLine != null : "Expected 'directly affected' log line"
assert directlyAffectedLine.contains('module-a') : "module-a should be directly affected (inherits changed <argLine>)"
assert directlyAffectedLine.contains('module-b') : "module-b should be directly affected (inherits changed <argLine>)"
