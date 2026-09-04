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

assert log.contains('mode=shadow')
assert log.contains('BUILD SUCCESS')
assert !log.contains('Scalpel: Building ') : "shadow mode must not trim the reactor"

// Trim mode, on this exact fixture (see the include-paths IT), filters module-b out of
// the build set: module-b is downstream of the changed module-a but outside the
// includePaths scope. The shadow decision must mirror that filtered set, not the raw
// computeBuildSet output: module-b belongs in wouldHaveSkipped, and its measured
// duration counts toward the savings estimate.
File shadowFile = new File(basedir, 'target/scalpel-shadow.json')
assert shadowFile.exists() : "shadow json should be written"
String shadow = shadowFile.text
def skipStart = shadow.indexOf('"wouldHaveSkipped": [')
def skipOpen = shadow.indexOf('[', skipStart)
def skipClose = shadow.indexOf(']', skipOpen)
def skipSet = shadow.substring(skipOpen, skipClose)
assert skipSet.contains('module-b') : "downstream module-b outside includePaths must be in the would-skip set"
def builtStart = shadow.indexOf('"wouldHaveBuilt": [')
def builtOpen = shadow.indexOf('[', builtStart)
def builtClose = shadow.indexOf(']', builtOpen)
def builtSet = shadow.substring(builtOpen, builtClose)
assert builtSet.contains('module-a') : "the changed module must be in the would-build set"
assert !builtSet.contains('module-b') : "module-b outside includePaths scope must not be in the would-build set"

File history = new File(basedir, 'target/scalpel-shadow-history.jsonl')
assert history.exists()
def lines = history.text.readLines().findAll { it.trim() }
assert lines.size() == 1
assert lines[0].contains('module-b')
