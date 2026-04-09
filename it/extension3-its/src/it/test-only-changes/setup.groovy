/*
 * Copyright (c) Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */

// Test-only changes: module-a gets only test source changes, module-b gets main source changes.
// module-c depends on module-a. Since module-a is test-only, module-c should NOT be downstream-affected.
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

// Add test-only source to module-a
new File(dir, 'module-a/src/test/java').mkdirs()
new File(dir, 'module-a/src/test/java/FooTest.java').text = 'public class FooTest {}'

// Add main source to module-b
new File(dir, 'module-b/src/main/java').mkdirs()
new File(dir, 'module-b/src/main/java/Bar.java').text = 'public class Bar {}'

exec('git', 'add', '.')
exec('git', 'commit', '-m', 'add test source to module-a, main source to module-b')
