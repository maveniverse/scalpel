/*
 * Copyright (c) Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */

// Create a bare remote repo and a working repo that uses it as origin.
// Push initial state to origin, create a "base" branch on the remote,
// then prune local tracking so origin/base doesn't exist locally.
// With fetchBaseBranch=true, Scalpel should fetch it before change detection.
def dir = basedir
def remoteDir = new File(basedir.parentFile, 'fetch-base-branch-remote.git')

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
exec('git', 'init')
exec('git', 'config', 'user.email', 'test@test.com')
exec('git', 'config', 'user.name', 'Test')
exec('git', 'remote', 'add', 'origin', remoteDir.absolutePath)

// 3. Commit initial state and push
exec('git', 'add', '.')
exec('git', 'commit', '-m', 'initial')
exec('git', 'push', 'origin', 'HEAD:refs/heads/main')

// 4. Create "base" branch on remote (same commit as main)
exec('git', 'push', 'origin', 'HEAD:refs/heads/base')

// 5. Make sure origin/base does NOT exist locally
// git push updates the remote tracking ref, so we must explicitly delete it
exec('git', 'update-ref', '-d', 'refs/remotes/origin/base')

// 6. Make a source change and commit on the working branch
new File(dir, 'module-b/src/main/java').mkdirs()
new File(dir, 'module-b/src/main/java/Foo.java').text = 'public class Foo {}'
exec('git', 'add', '.')
exec('git', 'commit', '-m', 'add source to module-b')
