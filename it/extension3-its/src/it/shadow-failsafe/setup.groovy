/*
 * Copyright (c) Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */

// Stale-shadow-document fixture for the fail-safe exit: a git repository exists but the
// configured base branch does not, so change detection throws, the fail-safe catches the
// ScalpelException, and the run continues with a full build and no measurement.
def dir = basedir

def exec = { String... args ->
    def proc = args.execute(null, dir)
    def out = new StringBuilder()
    def err = new StringBuilder()
    proc.waitForProcessOutput(out, err)
    if (proc.exitValue() != 0) {
        throw new RuntimeException("Command failed: ${args.join(' ')}\nstdout: $out\nstderr: $err")
    }
}

exec('git', 'init')
exec('git', 'config', 'user.email', 'test@test.com')
exec('git', 'config', 'user.name', 'Test')
exec('git', 'add', '.')
exec('git', 'commit', '-m', 'initial')

new File(dir, 'target').mkdirs()
new File(dir, 'target/scalpel-shadow.json').text = '{"stale": "previous-run-measurement"}'
// The invoker treats a non-null script result as a hook error; assignments return
// their value in Groovy, so end explicitly with null.
return null
