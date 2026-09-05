/*
 * Copyright (c) Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */

// Stale-shadow-document fixture for the -pl exit: the build selects a single project and
// Scalpel is configured to disable itself then, so it returns before any measurement.
def dir = basedir
new File(dir, 'target').mkdirs()
new File(dir, 'target/scalpel-shadow.json').text = '{"stale": "previous-run-measurement"}'
// The invoker treats a non-null script result as a hook error; assignments return
// their value in Groovy, so end explicitly with null.
return null
