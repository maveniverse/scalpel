/*
 * Copyright (c) Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.scalpel.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Set;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for shallow clones (e.g. actions/checkout with fetch-depth: 1), where the merge base
 * between the base branch and head cannot be resolved because the connecting history is absent.
 *
 * <p>The build compiles against JGit 7.7.1, whose CloneCommand supports {@code setDepth};
 * {@code realShallowClone_isShallowAndCannotResolvePreCloneHistory} exercises a genuine
 * depth-limited clone. The disconnected-roots fixture below is a deliberate alternative: it
 * constructs the same observable state via plumbing (refs whose connecting commits are missing,
 * so the merge-base walk finds nothing, plus an explicit {@code .git/shallow} marker) without
 * depending on clone-transport depth behavior, which keeps the deterministic history shape
 * explicit.</p>
 */
class ShallowCloneTest {

    @TempDir
    Path tempDir;

    /**
     * Creates a repo whose HEAD ("feature", root B) and base branch ("main", root A) share no
     * history, and marks the repository shallow by writing {@code .git/shallow} listing both
     * boundary commits - the observable state of a CI checkout with fetch-depth: 1.
     */
    private Path createShallowRepoWithDisconnectedBaseBranch() throws Exception {
        try (Git git = Git.init()
                .setDirectory(tempDir.toFile())
                .setInitialBranch("main")
                .call()) {
            write("base.txt", "base");
            git.add().addFilepattern("base.txt").call();
            git.commit().setMessage("root A: on main").call();
            ObjectId mainTip = git.getRepository().resolve("main");
            assertNotNull(mainTip);

            // orphan branch: root B, no shared history with main
            git.checkout().setOrphan(true).setName("feature").call();
            write("feature.txt", "feature");
            git.add().addFilepattern("feature.txt").call();
            git.commit().setMessage("root B: on feature").call();
            ObjectId featureTip = git.getRepository().resolve("feature");

            // mark shallow at both boundary commits, as a depth-limited fetch would
            Path shallowFile = git.getRepository().getDirectory().toPath().resolve("shallow");
            Files.write(
                    shallowFile,
                    (mainTip.getName() + "\n" + featureTip.getName() + "\n").getBytes(StandardCharsets.UTF_8));
        }
        return tempDir;
    }

    @Test
    void realShallowClone_isShallowAndCannotResolvePreCloneHistory() throws Exception {
        // source repo with three commits, pushed to a local bare remote
        Path srcDir = tempDir.resolve("src");
        Path remoteDir = tempDir.resolve("remote.git");
        try (Git git = Git.init()
                .setDirectory(srcDir.toFile())
                .setInitialBranch("main")
                .call()) {
            for (int i = 1; i <= 3; i++) {
                write(srcDir, "f" + i + ".txt", "v" + i);
                git.add().addFilepattern("f" + i + ".txt").call();
                git.commit().setMessage("commit " + i).call();
            }
        }
        try (Git remote = Git.init()
                        .setBare(true)
                        .setDirectory(remoteDir.toFile())
                        .call();
                Git src = Git.open(srcDir.toFile())) {
            src.push()
                    .setRemote(remoteDir.toUri().toString())
                    .setRefSpecs(new org.eclipse.jgit.transport.RefSpec("refs/heads/main:refs/heads/main"))
                    .call();
        }

        // real depth-limited clone over a local path URI
        Path cloneDir = tempDir.resolve("clone");
        try (Git clone = Git.cloneRepository()
                        .setDepth(1)
                        .setBranchesToClone(java.util.List.of("refs/heads/main"))
                        .setBranch("refs/heads/main")
                        .setURI(remoteDir.toUri().toString())
                        .setDirectory(cloneDir.toFile())
                        .call();
                Repository repository = clone.getRepository()) {
            assertTrue(
                    Files.exists(repository.getDirectory().toPath().resolve("shallow")),
                    "setDepth(1) clone against a local path should produce .git/shallow");
            assertTrue(new GitChangeDetector().isShallow(repository));
            // the pre-clone history is genuinely absent: HEAD~1 cannot be resolved, so a
            // merge base reaching past the shallow boundary comes back null. Note the second
            // assertion below exercises the unresolvable-base guard in findMergeBase (the
            // baseId == null early return), not isShallow itself.
            assertNull(repository.resolve("HEAD~1"), "HEAD~1 should not exist in a depth-1 clone");
            assertNull(
                    new GitChangeDetector().findMergeBase(repository, "HEAD~1", "HEAD"),
                    "merge base with pre-clone history should not be resolvable on a depth-1 clone");
        }
    }

    @Test
    void findMergeBase_returnsNullOnShallowRepoWithDisconnectedBaseBranch() throws Exception {
        Path repoDir = createShallowRepoWithDisconnectedBaseBranch();
        try (Git git = Git.open(repoDir.toFile());
                Repository repository = git.getRepository()) {
            assertTrue(
                    Files.exists(repository.getDirectory().toPath().resolve("shallow")),
                    "repo should carry the shallow marker (.git/shallow exists)");
            assertNotNull(repository.resolve("main"));
            assertNotNull(repository.resolve("HEAD"));

            GitChangeDetector detector = new GitChangeDetector();
            assertNull(
                    detector.findMergeBase(repository, "main", "HEAD"),
                    "merge base should not be resolvable on a shallow repo with disconnected refs");
        }
    }

    @Test
    void isShallow_detectsShallowMarker() throws Exception {
        Path repoDir = createShallowRepoWithDisconnectedBaseBranch();
        try (Git git = Git.open(repoDir.toFile());
                Repository repository = git.getRepository()) {
            assertTrue(new GitChangeDetector().isShallow(repository));
        }
        // a plain repo with full history is not shallow
        Path fullDir = tempDir.resolve("full");
        try (Git git = Git.init().setDirectory(fullDir.toFile()).call()) {
            write(fullDir, "f.txt", "x");
            git.add().addFilepattern("f.txt").call();
            git.commit().setMessage("c").call();
            assertFalse(new GitChangeDetector().isShallow(git.getRepository()));
        }
    }

    @Test
    void detectChanges_onShallowRepo_buildsAllModulesWithActionableWarning() throws Exception {
        Path repoDir = createShallowRepoWithDisconnectedBaseBranch();
        ScalpelCore core = new ScalpelCore(new GitChangeDetector());
        Properties sys = new Properties();
        sys.setProperty("scalpel.baseBranch", "main");
        ScalpelConfiguration config = ScalpelConfiguration.fromProperties(sys, new Properties());

        String logged = captureErr(() -> {
            try {
                assertNull(core.detectChanges(repoDir, config, Set.of()), "failSafe default should build all modules");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        assertTrue(
                logged.contains("shallow"),
                "expected an actionable WARN naming the shallow clone cause, got: " + logged);
        assertTrue(
                logged.contains("fetch-depth") || logged.contains("unshallow"),
                "expected the WARN to name the fix (fetch-depth: 0 / --unshallow), got: " + logged);
    }

    private void write(String name, String content) throws Exception {
        Files.write(tempDir.resolve(name), content.getBytes(StandardCharsets.UTF_8));
    }

    private static void write(Path dir, String name, String content) throws Exception {
        Files.write(dir.resolve(name), content.getBytes(StandardCharsets.UTF_8));
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    /**
     * Runs {@code action} capturing everything written to {@link System#err} and returns it.
     * This relies on the test classpath using slf4j-simple, whose default output target is
     * System.err; the GitChangeDetector warnings are therefore observable through this stream.
     */
    private static String captureErr(ThrowingRunnable action) throws Exception {
        PrintStream originalErr = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setErr(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            action.run();
        } finally {
            System.setErr(originalErr);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
