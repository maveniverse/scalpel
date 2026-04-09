/*
 * Copyright (c) Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */

// Initialize git repo, commit everything as base.
// Then modify a file in module-b WITHOUT committing.
// With scalpel.uncommitted=true, module-b should be detected.
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

// Create an uncommitted (but tracked) source file in module-b
new File(dir, 'module-b/src/main/java').mkdirs()
new File(dir, 'module-b/src/main/java/Foo.java').text = 'public class Foo {}'
// Do NOT git add or commit - this is an untracked file, but we test uncommitted here
// Let's stage it to make it "uncommitted" (added but not committed)
exec('git', 'add', 'module-b/src/main/java/Foo.java')
