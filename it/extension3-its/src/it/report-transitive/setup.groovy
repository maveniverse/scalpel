/*
 * Copyright (c) Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */

// Initialize git repo, create base branch, bump managed dependency version
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

// Bump the managed dependency version in parent POM
def pomFile = new File(dir, 'pom.xml')
def pomText = pomFile.text
pomText = pomText.replace('<commons-lang.version>2.5</commons-lang.version>', '<commons-lang.version>2.6</commons-lang.version>')
pomFile.text = pomText
exec('git', 'add', '.')
exec('git', 'commit', '-m', 'bump commons-lang version')
