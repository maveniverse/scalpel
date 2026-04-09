/*
 * Copyright (c) Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */

// Initialize git repo, create base branch, then change a .github file and a source file
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

// Add a .github/ci.yml file (should trigger disable)
new File(dir, '.github').mkdirs()
new File(dir, '.github/ci.yml').text = 'name: CI'
// Also add a source file in module-b
new File(dir, 'module-b/src/main/java').mkdirs()
new File(dir, 'module-b/src/main/java/Foo.java').text = 'public class Foo {}'
exec('git', 'add', '.')
exec('git', 'commit', '-m', 'add CI config and source')
