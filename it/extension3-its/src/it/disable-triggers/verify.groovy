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

// Scalpel should have been activated but then disabled by the .github/** trigger
assert log.contains('Scalpel')
assert log.contains('Disabled due to change in') : "Expected Scalpel to be disabled by disable trigger"
assert log.contains('.github/') : "Expected .github/ file to be mentioned as the trigger"

// All modules should be built (no trimming happened)
assert !log.contains('directly affected') : "Scalpel should not have computed affected modules"
