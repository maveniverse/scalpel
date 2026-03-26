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

// Scalpel should have been activated
assert log.contains('Scalpel')

// module-b was changed, module-a is upstream dep of module-b → both should be built
// module-c has no relationship to module-b → should be skipped
assert log.contains('Building 3 of 4 modules') || log.contains('Building 2 of 4 modules') || log.contains('modules directly affected')
