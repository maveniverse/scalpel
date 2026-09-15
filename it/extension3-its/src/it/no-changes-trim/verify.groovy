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

// With buildAllIfNoChanges=false (default) and no changes detected, Scalpel trims
// the reactor to empty and Maven exits with a non-zero code (#199).
assert log.contains('No changes detected, trimming reactor to empty (buildAllIfNoChanges=false)')
