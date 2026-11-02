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

// The bare root-level file (settings.properties) is not owned by any module.
// Scalpel must trigger a full build rather than building only module-a (#229).
assert log.contains('Full build triggered by'), \
    "Expected full-build log line for unmapped root file, got:\n" + log
assert log.contains('unattributable root-level file'), \
    "Expected 'unattributable root-level file' in log, got:\n" + log
