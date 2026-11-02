/*
 * Copyright (c) Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */

// Stale-shadow-document fixture for the no-affected-modules exit: the only change is a
// profile-gated module POM (gated-module/pom.xml) that is present on disk but not part
// of the current reactor. ModuleMapper's pom.xml-boundary guard fires and returns null
// for gated-module/pom.xml, so it lands in unmatchedPomPaths → no modules affected.
// A previous run's scalpel-shadow.json is planted to prove this exit overwrites it too.
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

// Add a pom.xml for a profile-gated module that is NOT listed in the reactor.
// ModuleMapper sees gated-module/pom.xml → walks to "gated-module" → not in moduleByDir
// → finds gated-module/pom.xml on disk → returns null (pom.xml boundary guard).
// ScalpelLifecycleParticipant routes it through POM analysis as an unmatchedPomPath
// → no reactor module affected → "No modules affected by changes" early return.
new File(dir, 'gated-module').mkdirs()
new File(dir, 'gated-module/pom.xml').text = '''\
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <groupId>eu.maveniverse.maven.scalpel.it.shadowunaffected</groupId>
    <artifactId>gated-module</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</project>
'''
exec('git', 'add', '.')
exec('git', 'commit', '-m', 'update gated-module/pom.xml — not part of reactor, no module affected')

new File(dir, 'target').mkdirs()
new File(dir, 'target/scalpel-shadow.json').text = '{"stale": "previous-run-measurement"}'
// The invoker treats a non-null script result as a hook error; assignments return
// their value in Groovy, so end explicitly with null.
return null
