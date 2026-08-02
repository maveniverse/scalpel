/*
 * Copyright (c) Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */

// module-a depends on module-b (compile) and on module-common's test-jar (test scope).
// module-b depends on module-common's main jar only.
// Change source in module-b only: module-common becomes upstream-only (via alsoMake), and
// with skipTestsForUpstream=true it is a skip-test candidate. Since module-a's test-compile
// consumes module-common's test-jar, module-common's skip must be softened (skipTests=true)
// instead of maven.test.skip=true, or module-a's test-compile fails (scalpel#47).
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

// Only change module-b source
new File(dir, 'module-b/src/main/java').mkdirs()
new File(dir, 'module-b/src/main/java/Bar.java').text = 'public class Bar {}'
exec('git', 'add', '.')
exec('git', 'commit', '-m', 'add source to module-b')
