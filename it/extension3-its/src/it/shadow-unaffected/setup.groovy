/*
 * Copyright (c) Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */

// Stale-shadow-document fixture for the no-affected-modules exit: the only change is a
// file under docs/, owned by no reactor module, so the analysis completes with nothing
// affected and the passive branch writes a report with an empty affected set. A previous
// run's scalpel-shadow.json is planted to prove this exit overwrites it too.
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
exec('git', 'branch', 'base')

// A repo-ROOT file maps to no module at all: the root aggregator stopped catching
// repo-root files in #66, so the analysis completes with nothing affected.
new File(dir, 'notes.md').text = 'root-level note owned by no module'
exec('git', 'add', '.')
exec('git', 'commit', '-m', 'root-level file change')

new File(dir, 'target').mkdirs()
new File(dir, 'target/scalpel-shadow.json').text = '{"stale": "previous-run-measurement"}'
// The invoker treats a non-null script result as a hook error; assignments return
// their value in Groovy, so end explicitly with null.
return null
