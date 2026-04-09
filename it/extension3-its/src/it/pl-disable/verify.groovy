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

// Scalpel should have been disabled due to -pl selection
assert log.contains('disabled due to -pl project selection') : \
    "Expected Scalpel to be disabled due to -pl selection, log: $log"

// Scalpel should NOT have computed affected modules
assert !log.contains('directly affected') : "Scalpel should not have computed affected modules"
