/*
 * Copyright (c) Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */

// Profile-aware detection: change properties in both profiles (prod and staging).
// Only 'prod' is activated via -Pprod; 'staging' remains inactive.
// module-a references ${app.version} (from prod), module-b references ${staging.url} (from staging).
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

// Change properties in both profiles
def pomFile = new File(dir, 'pom.xml')
def pomText = pomFile.text
pomText = pomText.replace('<app.version>1.0</app.version>', '<app.version>2.0</app.version>')
pomText = pomText.replace('<staging.url>http://staging.example.com</staging.url>', '<staging.url>http://staging-v2.example.com</staging.url>')
pomFile.text = pomText
exec('git', 'add', '.')
exec('git', 'commit', '-m', 'change properties in both profiles')
