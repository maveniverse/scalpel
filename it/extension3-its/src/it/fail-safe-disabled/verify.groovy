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

// The build must have failed (asserted by invoker.buildResult = failure);
// here we assert it failed with the Scalpel error, not some unrelated cause.
assert log.contains('Could not find merge base between nonexistent-branch') : \
    "Expected Scalpel merge-base failure message, log: $log"
assert log.contains('[ERROR] Scalpel: Could not find merge base') : "Expected Scalpel error line in log"
