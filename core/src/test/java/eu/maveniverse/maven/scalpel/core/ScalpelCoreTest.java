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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.Set;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

class ScalpelCoreTest {

    @TempDir
    Path tempDir;

    @Test
    void detectChanges_detectsNewFileOnBranch() throws Exception {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            // Initial commit on default branch
            Files.write(tempDir.resolve("file.txt"), "hello".getBytes(StandardCharsets.UTF_8));
            git.add().addFilepattern("file.txt").call();
            git.commit().setMessage("initial").call();

            // Create 'main' branch at this point
            git.branchCreate().setName("main").call();

            // Make a change on current branch
            Files.write(tempDir.resolve("new-file.txt"), "world".getBytes(StandardCharsets.UTF_8));
            git.add().addFilepattern("new-file.txt").call();
            git.commit().setMessage("add new file").call();

            ScalpelCore core = new ScalpelCore(new GitChangeDetector());
            Properties sys = new Properties();
            sys.setProperty("scalpel.baseBranch", "main");
            ScalpelConfiguration config = ScalpelConfiguration.fromProperties(sys, new Properties());

            ChangeDetectionResult result = core.detectChanges(tempDir, config, Set.of());

            assertNotNull(result);
            assertTrue(result.getChangedFiles().contains("new-file.txt"));
        }
    }

    @Test
    void detectChanges_withUncommittedFiles() throws Exception {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            Files.write(tempDir.resolve("file.txt"), "hello".getBytes(StandardCharsets.UTF_8));
            git.add().addFilepattern("file.txt").call();
            git.commit().setMessage("initial").call();
            git.branchCreate().setName("main").call();

            // Create an uncommitted tracked change
            Files.write(tempDir.resolve("file.txt"), "modified".getBytes(StandardCharsets.UTF_8));

            ScalpelCore core = new ScalpelCore(new GitChangeDetector());
            Properties sys = new Properties();
            sys.setProperty("scalpel.baseBranch", "main");
            sys.setProperty("scalpel.uncommitted", "true");
            ScalpelConfiguration config = ScalpelConfiguration.fromProperties(sys, new Properties());

            ChangeDetectionResult result = core.detectChanges(tempDir, config, Set.of());

            assertNotNull(result);
            assertTrue(result.getChangedFiles().contains("file.txt"), "uncommitted change should be detected");
        }
    }

    @Test
    void detectChanges_withUntrackedFiles() throws Exception {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            Files.write(tempDir.resolve("file.txt"), "hello".getBytes(StandardCharsets.UTF_8));
            git.add().addFilepattern("file.txt").call();
            git.commit().setMessage("initial").call();
            git.branchCreate().setName("main").call();

            // Create an untracked file
            Files.write(tempDir.resolve("untracked.txt"), "new".getBytes(StandardCharsets.UTF_8));

            ScalpelCore core = new ScalpelCore(new GitChangeDetector());
            Properties sys = new Properties();
            sys.setProperty("scalpel.baseBranch", "main");
            sys.setProperty("scalpel.untracked", "true");
            ScalpelConfiguration config = ScalpelConfiguration.fromProperties(sys, new Properties());

            ChangeDetectionResult result = core.detectChanges(tempDir, config, Set.of());

            assertNotNull(result);
            assertTrue(result.getChangedFiles().contains("untracked.txt"), "untracked file should be detected");
        }
    }

    @Test
    void detectChanges_invalidRegexInDisableOnBranch_doesNotCrash() throws Exception {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            Files.write(tempDir.resolve("file.txt"), "hello".getBytes(StandardCharsets.UTF_8));
            git.add().addFilepattern("file.txt").call();
            git.commit().setMessage("initial").call();
            git.branchCreate().setName("main").call();

            Files.write(tempDir.resolve("new.txt"), "world".getBytes(StandardCharsets.UTF_8));
            git.add().addFilepattern("new.txt").call();
            git.commit().setMessage("change").call();

            ScalpelCore core = new ScalpelCore(new GitChangeDetector());
            Properties sys = new Properties();
            sys.setProperty("scalpel.baseBranch", "main");
            // Invalid regex pattern should be handled gracefully
            sys.setProperty("scalpel.disableOnBranch", "[unclosed");
            ScalpelConfiguration config = ScalpelConfiguration.fromProperties(sys, new Properties());

            // Should not throw, just log a warning and continue
            ChangeDetectionResult result = core.detectChanges(tempDir, config, Set.of());

            assertNotNull(result);
        }
    }

    @Test
    void detectChanges_disableOnBranchMatchingPattern_returnsNull() throws Exception {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            Files.write(tempDir.resolve("file.txt"), "hello".getBytes(StandardCharsets.UTF_8));
            git.add().addFilepattern("file.txt").call();
            git.commit().setMessage("initial").call();
            git.branchCreate().setName("main").call();

            ScalpelCore core = new ScalpelCore(new GitChangeDetector());
            Properties sys = new Properties();
            sys.setProperty("scalpel.baseBranch", "main");
            // Current branch is "master" (default), disable on "master"
            String currentBranch = git.getRepository().getBranch();
            sys.setProperty("scalpel.disableOnBranch", currentBranch);
            ScalpelConfiguration config = ScalpelConfiguration.fromProperties(sys, new Properties());

            ChangeDetectionResult result = core.detectChanges(tempDir, config, Set.of());

            assertNull(result, "Should return null when current branch matches disableOnBranch");
        }
    }

    @Test
    void detectChanges_notAGitRepo_returnsNull() throws Exception {
        // Create a temp directory outside the project tree to avoid JGit walking up
        // and discovering the project's own .git (java.io.tmpdir may be inside the repo)
        Path nonGitDir = Files.createTempDirectory(Paths.get("/tmp"), "scalpel-test-no-git");
        try {
            ScalpelCore core = new ScalpelCore(new GitChangeDetector());
            Properties sys = new Properties();
            sys.setProperty("scalpel.baseBranch", "main");
            ScalpelConfiguration config = ScalpelConfiguration.fromProperties(sys, new Properties());

            ChangeDetectionResult result = core.detectChanges(nonGitDir, config, Set.of());

            assertNull(result, "Should return null for non-git directory");
        } finally {
            Files.deleteIfExists(nonGitDir);
        }
    }

    static boolean isGitWorktreeSupported() {
        try {
            Process p = new ProcessBuilder("git", "worktree", "list")
                    .redirectErrorStream(true)
                    .start();
            return p.waitFor() == 0 || p.waitFor() == 128;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    @EnabledIf("isGitWorktreeSupported")
    void detectChanges_worksInGitWorktree() throws Exception {
        Path mainRepo = tempDir.resolve("main-repo");
        Files.createDirectories(mainRepo);

        try (Git git = Git.init().setDirectory(mainRepo.toFile()).call()) {
            Files.write(mainRepo.resolve("file.txt"), "hello".getBytes(StandardCharsets.UTF_8));
            git.add().addFilepattern("file.txt").call();
            git.commit().setMessage("initial").call();
            git.branchCreate().setName("main").call();
        }

        Path worktreeDir = tempDir.resolve("worktree");
        Process process = new ProcessBuilder("git", "worktree", "add", worktreeDir.toString(), "-b", "feature")
                .directory(mainRepo.toFile())
                .redirectErrorStream(true)
                .start();
        process.getInputStream().readAllBytes();
        int exitCode = process.waitFor();
        assertTrue(exitCode == 0, "git worktree add should succeed, exit code: " + exitCode);

        // Make a change in the worktree using git commands
        Files.write(worktreeDir.resolve("new-file.txt"), "world".getBytes(StandardCharsets.UTF_8));
        new ProcessBuilder("git", "add", "new-file.txt")
                .directory(worktreeDir.toFile())
                .start()
                .waitFor();
        new ProcessBuilder("git", "commit", "-m", "add new file")
                .directory(worktreeDir.toFile())
                .start()
                .waitFor();

        ScalpelCore core = new ScalpelCore(new GitChangeDetector());
        Properties sys = new Properties();
        sys.setProperty("scalpel.baseBranch", "main");
        ScalpelConfiguration config = ScalpelConfiguration.fromProperties(sys, new Properties());

        ChangeDetectionResult result = core.detectChanges(worktreeDir, config, Set.of());

        assertNotNull(result, "Should detect git repository in worktree");
        assertTrue(result.getChangedFiles().contains("new-file.txt"));
    }

    @Test
    void detectChanges_readOldPomContent() throws Exception {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            // Create initial POM
            String oldPom = "<project><version>1.0</version></project>";
            Files.write(tempDir.resolve("pom.xml"), oldPom.getBytes(StandardCharsets.UTF_8));
            git.add().addFilepattern("pom.xml").call();
            git.commit().setMessage("initial").call();
            git.branchCreate().setName("main").call();

            // Modify POM on current branch
            String newPom = "<project><version>2.0</version></project>";
            Files.write(tempDir.resolve("pom.xml"), newPom.getBytes(StandardCharsets.UTF_8));
            git.add().addFilepattern("pom.xml").call();
            git.commit().setMessage("bump version").call();

            ScalpelCore core = new ScalpelCore(new GitChangeDetector());
            Properties sys = new Properties();
            sys.setProperty("scalpel.baseBranch", "main");
            ScalpelConfiguration config = ScalpelConfiguration.fromProperties(sys, new Properties());

            Set<String> pomPaths = Set.of("pom.xml");
            ChangeDetectionResult result = core.detectChanges(tempDir, config, pomPaths);

            assertNotNull(result);
            assertTrue(result.getChangedFiles().contains("pom.xml"));
            assertTrue(result.getOldPomContents().containsKey("pom.xml"));
            String readOldPom = new String(result.getOldPomContents().get("pom.xml"), StandardCharsets.UTF_8);
            assertTrue(readOldPom.contains("1.0"), "Old POM should contain original version");
            assertFalse(readOldPom.contains("2.0"), "Old POM should not contain new version");
        }
    }
}
