/*
 * Copyright (c) Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */

// Initialize a git repo, create base branch, then commit both a root-level file
// (settings.properties — not owned by any module) and a change to module-a.
// Scalpel should recognise the root-level file as unattributable and trigger a full build.
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

// Commit a bare root-level file alongside a module-a change (#229)
new File(dir, 'settings.properties').text = 'timeout=30\n'
new File(dir, 'module-a/src/main/java').mkdirs()
new File(dir, 'module-a/src/main/java/Foo.java').text = 'class Foo {}\n'
exec('git', 'add', '.')
exec('git', 'commit', '-m', 'add settings.properties and change module-a')
