/*
 * Copyright (c) Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.scalpel.core;

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
 * <p>Note: the build compiles against JGit 5.13.5 (the newest Java-8 compatible line), whose
 * CloneCommand/FetchCommand have no {@code setDepth}. A true shallow clone therefore cannot be
 * fetched with the in-use API, so the equivalent state is constructed via plumbing: two
 * disconnected roots (what a depth-limited fetch leaves behind: refs whose connecting commits
 * are missing, so the merge-base walk finds nothing) plus an explicit {@code .git/shallow}
 * marker, which is exactly what a real {@code fetch-depth: 1} checkout produces.</p>
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
