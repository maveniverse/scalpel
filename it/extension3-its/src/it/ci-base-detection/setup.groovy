/*
 * Copyright (c) Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */

// Create a bare remote repo with a "base" branch and a working repo using it as
// origin, so the ref origin/base exists locally. GITHUB_BASE_REF=base is set in
// invoker.properties; Scalpel must auto-detect the base branch as origin/base
// (no -Dscalpel.baseBranch) and trim the build to the changed module.
def dir = basedir
def remoteDir = new File(basedir.parentFile, 'ci-base-detection-remote.git')

def exec = { String... args ->
    def proc = args.execute(null, dir)
    def out = new StringBuilder()
    def err = new StringBuilder()
    proc.waitForProcessOutput(out, err)
    if (proc.exitValue() != 0) {
        throw new RuntimeException("Command failed: ${args.join(' ')}\nstdout: $out\nstderr: $err")
    }
}

def execIn = { File workDir, String... args ->
    def proc = args.execute(null, workDir)
    def out = new StringBuilder()
    def err = new StringBuilder()
    proc.waitForProcessOutput(out, err)
    if (proc.exitValue() != 0) {
        throw new RuntimeException("Command failed: ${args.join(' ')}\nstdout: $out\nstderr: $err")
    }
}

// 1. Create a bare remote repo
execIn(basedir.parentFile, 'git', 'init', '--bare', remoteDir.absolutePath)

// 2. Initialize working repo
exec('git', 'init', '-b', 'main')
exec('git', 'config', 'user.email', 'test@test.com')
exec('git', 'config', 'user.name', 'Test')
exec('git', 'remote', 'add', 'origin', remoteDir.absolutePath)

// 3. Commit initial state and push (creates local origin/main tracking ref)
exec('git', 'add', '.')
exec('git', 'commit', '-m', 'initial')
exec('git', 'push', 'origin', 'HEAD:refs/heads/main')

// 4. Create "base" branch on remote at the same commit (origin/base exists locally)
exec('git', 'push', 'origin', 'HEAD:refs/heads/base')

// 5. Make a source change and commit on the working branch
new File(dir, 'module-a/src/main/java').mkdirs()
new File(dir, 'module-a/src/main/java/Foo.java').text = 'public class Foo {}'
exec('git', 'add', '.')
exec('git', 'commit', '-m', 'add source to module-a')
