/*
 * Copyright (c) Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */

// Upstream-only failure fixture (#222): module-a (upstream prerequisite of module-b,
// which module-c depends on transitively) carries a failing test, and the only change
// base..HEAD is a source file in module-c. The full observed build fails on module-a,
// but module-a sits inside the build set, so the failure is invisible to
// wouldHaveSkippedButFailed and must surface in upstreamOnlyTestFailures.
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

// Create a source file in module-c: the only change base..HEAD
new File(dir, 'module-c/src/main/java').mkdirs()
new File(dir, 'module-c/src/main/java/App.java').text = 'public class App {}'
exec('git', 'add', '.')
exec('git', 'commit', '-m', 'add source to module-c')
