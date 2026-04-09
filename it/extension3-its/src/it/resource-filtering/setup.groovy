/*
 * Copyright (c) Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */

// Resource filtering: module-a has filtered resources referencing ${app.name}.
// Change the app.name property in parent POM.
// module-a should be affected (filtered resource references changed property).
// module-b should NOT be affected (no filtered resources, no property reference).
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

// Change the app.name property in parent POM
def pomFile = new File(dir, 'pom.xml')
def pomText = pomFile.text
pomText = pomText.replace('<app.name>MyApp</app.name>', '<app.name>MyApp-v2</app.name>')
pomFile.text = pomText
exec('git', 'add', '.')
exec('git', 'commit', '-m', 'change app.name property')
