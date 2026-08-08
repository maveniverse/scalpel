/*
 * Copyright (c) Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.scalpel.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link GitChangeDetector} exercising rename, delete,
 * untracked-file, and fetch-branch logic directly (without going
 * through {@link ScalpelCore}).
 */
class GitChangeDetectorTest {

    @TempDir
    Path tempDir;

    private final GitChangeDetector detector = new GitChangeDetector();

    // ---- getCurrentBranch ----

    @Test
    void getCurrentBranch_returnsActiveBranch() throws Exception {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            Files.write(tempDir.resolve("file.txt"), "init".getBytes(StandardCharsets.UTF_8));
            git.add().addFilepattern("file.txt").call();
            git.commit().setMessage("initial").call();
            git.branchCreate().setName("feature").call();
            git.checkout().setName("feature").call();

            String branch = detector.getCurrentBranch(git.getRepository());
            assertEquals("feature", branch);
        }
    }

    // ---- getChangedFiles: RENAME ----

    @Test
    void getChangedFiles_rename_includesBothOldAndNewPaths() throws Exception {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            // Initial commit with a file
            Files.write(tempDir.resolve("old-name.txt"), "content".getBytes(StandardCharsets.UTF_8));
            git.add().addFilepattern("old-name.txt").call();
            git.commit().setMessage("initial").call();

            ObjectId base = git.getRepository().resolve("HEAD");

            // Rename the file (git rm + git add under a new name)
            Files.move(tempDir.resolve("old-name.txt"), tempDir.resolve("new-name.txt"));
            git.rm().addFilepattern("old-name.txt").call();
            git.add().addFilepattern("new-name.txt").call();
            git.commit().setMessage("rename file").call();

            ObjectId head = git.getRepository().resolve("HEAD");

            Set<String> changed = detector.getChangedFiles(git.getRepository(), base, head);

            assertTrue(changed.contains("old-name.txt"), "Rename should include old path");
            assertTrue(changed.contains("new-name.txt"), "Rename should include new path");
        }
    }

    // ---- getChangedFiles: DELETE ----

    @Test
    void getChangedFiles_delete_includesDeletedFilePath() throws Exception {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            Files.write(tempDir.resolve("to-delete.txt"), "content".getBytes(StandardCharsets.UTF_8));
            git.add().addFilepattern("to-delete.txt").call();
            git.commit().setMessage("initial").call();

            ObjectId base = git.getRepository().resolve("HEAD");

            git.rm().addFilepattern("to-delete.txt").call();
            git.commit().setMessage("delete file").call();

            ObjectId head = git.getRepository().resolve("HEAD");

            Set<String> changed = detector.getChangedFiles(git.getRepository(), base, head);

            assertTrue(changed.contains("to-delete.txt"), "Deleted file should appear in changed set");
            assertEquals(1, changed.size(), "Only the deleted file should be reported");
        }
    }

    // ---- getChangedFiles: ADD ----

    @Test
    void getChangedFiles_add_includesNewFilePath() throws Exception {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            Files.write(tempDir.resolve("existing.txt"), "exists".getBytes(StandardCharsets.UTF_8));
            git.add().addFilepattern("existing.txt").call();
            git.commit().setMessage("initial").call();

            ObjectId base = git.getRepository().resolve("HEAD");

            Files.write(tempDir.resolve("added.txt"), "new".getBytes(StandardCharsets.UTF_8));
            git.add().addFilepattern("added.txt").call();
            git.commit().setMessage("add file").call();

            ObjectId head = git.getRepository().resolve("HEAD");

            Set<String> changed = detector.getChangedFiles(git.getRepository(), base, head);

            assertTrue(changed.contains("added.txt"), "Added file should appear in changed set");
            assertFalse(changed.contains("existing.txt"), "Unchanged file should not appear");
        }
    }

    // ---- getChangedFiles: MODIFY ----

    @Test
    void getChangedFiles_modify_includesModifiedFilePath() throws Exception {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            Files.write(tempDir.resolve("file.txt"), "v1".getBytes(StandardCharsets.UTF_8));
            git.add().addFilepattern("file.txt").call();
            git.commit().setMessage("initial").call();

            ObjectId base = git.getRepository().resolve("HEAD");

            Files.write(tempDir.resolve("file.txt"), "v2".getBytes(StandardCharsets.UTF_8));
            git.add().addFilepattern("file.txt").call();
            git.commit().setMessage("modify file").call();

            ObjectId head = git.getRepository().resolve("HEAD");

            Set<String> changed = detector.getChangedFiles(git.getRepository(), base, head);

            assertTrue(changed.contains("file.txt"), "Modified file should appear in changed set");
        }
    }

    // ---- getStatusFiles: untracked ----

    @Test
    void getStatusFiles_untrackedFileAppearsInUntrackedSet() throws Exception {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            Files.write(tempDir.resolve("tracked.txt"), "tracked".getBytes(StandardCharsets.UTF_8));
            git.add().addFilepattern("tracked.txt").call();
            git.commit().setMessage("initial").call();

            // Create an untracked file (not staged, not committed)
            Files.write(tempDir.resolve("untracked.txt"), "new".getBytes(StandardCharsets.UTF_8));

            GitChangeDetector.StatusResult status = detector.getStatusFiles(git.getRepository());

            assertTrue(status.getUntracked().contains("untracked.txt"), "Untracked file should be in untracked set");
            assertFalse(
                    status.getUncommitted().contains("untracked.txt"),
                    "Untracked file should NOT be in uncommitted set");
        }
    }

    @Test
    void getStatusFiles_trackedModifiedFileAppearsInUncommittedSet() throws Exception {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            Files.write(tempDir.resolve("file.txt"), "original".getBytes(StandardCharsets.UTF_8));
            git.add().addFilepattern("file.txt").call();
            git.commit().setMessage("initial").call();

            // Modify tracked file without staging
            Files.write(tempDir.resolve("file.txt"), "modified".getBytes(StandardCharsets.UTF_8));

            GitChangeDetector.StatusResult status = detector.getStatusFiles(git.getRepository());

            assertTrue(
                    status.getUncommitted().contains("file.txt"), "Modified tracked file should be in uncommitted set");
            assertFalse(
                    status.getUntracked().contains("file.txt"), "Modified tracked file should NOT be in untracked set");
        }
    }

    @Test
    void getStatusFiles_cleanRepo_returnsEmptySets() throws Exception {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            Files.write(tempDir.resolve("file.txt"), "content".getBytes(StandardCharsets.UTF_8));
            git.add().addFilepattern("file.txt").call();
            git.commit().setMessage("initial").call();

            GitChangeDetector.StatusResult status = detector.getStatusFiles(git.getRepository());

            assertTrue(status.getUncommitted().isEmpty(), "Clean repo should have no uncommitted files");
            assertTrue(status.getUntracked().isEmpty(), "Clean repo should have no untracked files");
        }
    }

    // ---- findMergeBase ----

    @Test
    void findMergeBase_returnsMergeBaseCommit() throws Exception {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            Files.write(tempDir.resolve("file.txt"), "init".getBytes(StandardCharsets.UTF_8));
            git.add().addFilepattern("file.txt").call();
            git.commit().setMessage("initial").call();

            git.branchCreate().setName("main").call();

            // Advance current branch
            Files.write(tempDir.resolve("b.txt"), "branch".getBytes(StandardCharsets.UTF_8));
            git.add().addFilepattern("b.txt").call();
            git.commit().setMessage("branch commit").call();

            Repository repo = git.getRepository();
            ObjectId mergeBase = detector.findMergeBase(repo, "main", "HEAD");

            assertNotNull(mergeBase, "Merge base should be found");
            // The merge base is the "initial" commit, which is what 'main' points at
            ObjectId mainId = repo.resolve("main");
            assertEquals(mainId, mergeBase, "Merge base should be the 'main' branch tip");
        }
    }

    @Test
    void findMergeBase_unresolvableBase_returnsNull() throws Exception {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            Files.write(tempDir.resolve("file.txt"), "init".getBytes(StandardCharsets.UTF_8));
            git.add().addFilepattern("file.txt").call();
            git.commit().setMessage("initial").call();

            ObjectId result = detector.findMergeBase(git.getRepository(), "nonexistent-branch", "HEAD");

            assertNull(result, "Should return null when base branch cannot be resolved");
        }
    }

    @Test
    void findMergeBase_unresolvableHead_returnsNull() throws Exception {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            Files.write(tempDir.resolve("file.txt"), "init".getBytes(StandardCharsets.UTF_8));
            git.add().addFilepattern("file.txt").call();
            git.commit().setMessage("initial").call();
            git.branchCreate().setName("main").call();

            ObjectId result = detector.findMergeBase(git.getRepository(), "main", "nonexistent");

            assertNull(result, "Should return null when head cannot be resolved");
        }
    }

    // ---- readFileAtCommit ----

    @Test
    void readFileAtCommit_returnsFileContent() throws Exception {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            String content = "file-content-v1";
            Files.write(tempDir.resolve("data.txt"), content.getBytes(StandardCharsets.UTF_8));
            git.add().addFilepattern("data.txt").call();
            git.commit().setMessage("add data").call();

            ObjectId commitId = git.getRepository().resolve("HEAD");
            byte[] read = detector.readFileAtCommit(git.getRepository(), commitId, "data.txt");

            assertNotNull(read);
            assertEquals(content, new String(read, StandardCharsets.UTF_8));
        }
    }

    @Test
    void readFileAtCommit_missingFile_returnsNull() throws Exception {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            Files.write(tempDir.resolve("file.txt"), "x".getBytes(StandardCharsets.UTF_8));
            git.add().addFilepattern("file.txt").call();
            git.commit().setMessage("initial").call();

            ObjectId commitId = git.getRepository().resolve("HEAD");
            byte[] read = detector.readFileAtCommit(git.getRepository(), commitId, "nonexistent.txt");

            assertNull(read, "Should return null for a file not present at the commit");
        }
    }

    // ---- fetchBranch ----

    @Test
    void fetchBranch_successfulFetchFromBareRemote() throws Exception {
        // Set up a bare "remote" repository
        Path bareDir = tempDir.resolve("bare.git");
        try (Git bare = Git.init().setBare(true).setDirectory(bareDir.toFile()).call()) {
            // nothing to do, just init
        }

        Path workDir = tempDir.resolve("work");
        Files.createDirectories(workDir);
        try (Git git = Git.init().setDirectory(workDir.toFile()).call()) {
            git.getRepository().getConfig().setString("user", null, "name", "Test");
            git.getRepository().getConfig().setString("user", null, "email", "test@test.invalid");
            git.getRepository().getConfig().save();

            // Add bare repo as "origin"
            git.remoteAdd()
                    .setName("origin")
                    .setUri(new org.eclipse.jgit.transport.URIish(
                            bareDir.toUri().toString()))
                    .call();

            // Create content and push to bare
            Files.write(workDir.resolve("file.txt"), "content".getBytes(StandardCharsets.UTF_8));
            git.add().addFilepattern("file.txt").call();
            git.commit().setMessage("initial").call();
            git.push().setRemote("origin").call();

            // Fetch should succeed without error
            detector.fetchBranch(git.getRepository(), "origin/master");
        }
    }

    @Test
    void fetchBranch_noSlashInBaseBranch_skipsWithoutError() throws Exception {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            Files.write(tempDir.resolve("file.txt"), "x".getBytes(StandardCharsets.UTF_8));
            git.add().addFilepattern("file.txt").call();
            git.commit().setMessage("initial").call();

            // "main" has no slash, so fetchBranch should return early
            detector.fetchBranch(git.getRepository(), "main");
            // No exception means success (silent skip)
        }
    }

    @Test
    void fetchBranch_nonexistentRemote_throwsIOException() throws Exception {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            Files.write(tempDir.resolve("file.txt"), "x".getBytes(StandardCharsets.UTF_8));
            git.add().addFilepattern("file.txt").call();
            git.commit().setMessage("initial").call();

            // "bogus/main" parses but there is no remote called "bogus"
            assertThrows(
                    IOException.class,
                    () -> detector.fetchBranch(git.getRepository(), "bogus/main"),
                    "Fetching from a nonexistent remote should throw IOException");
        }
    }

    @Test
    void fetchBranch_malformedRefspec_throwsIllegalArgumentException() throws Exception {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            Files.write(tempDir.resolve("file.txt"), "x".getBytes(StandardCharsets.UTF_8));
            git.add().addFilepattern("file.txt").call();
            git.commit().setMessage("initial").call();

            // ":/nope" parses as remote=":", branch="nope" which produces an
            // invalid RefSpec.  The current code does not catch
            // IllegalArgumentException, so the raw exception escapes.
            assertThrows(
                    IllegalArgumentException.class,
                    () -> detector.fetchBranch(git.getRepository(), ":/nope"),
                    "A malformed refspec should throw IllegalArgumentException");
        }
    }

    // ---- readPomFilesAtCommit ----

    @Test
    void readPomFilesAtCommit_readsMultiplePoms() throws Exception {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            Files.write(tempDir.resolve("pom.xml"), "<project/>".getBytes(StandardCharsets.UTF_8));
            Files.createDirectories(tempDir.resolve("sub"));
            Files.write(tempDir.resolve("sub/pom.xml"), "<module/>".getBytes(StandardCharsets.UTF_8));
            git.add().addFilepattern("pom.xml").call();
            git.add().addFilepattern("sub/pom.xml").call();
            git.commit().setMessage("add poms").call();

            ObjectId commitId = git.getRepository().resolve("HEAD");
            var result = detector.readPomFilesAtCommit(
                    git.getRepository(), commitId, Set.of("pom.xml", "sub/pom.xml", "missing/pom.xml"));

            assertEquals(2, result.size(), "Should read two existing poms, skip the missing one");
            assertTrue(result.containsKey("pom.xml"));
            assertTrue(result.containsKey("sub/pom.xml"));
            assertFalse(result.containsKey("missing/pom.xml"), "Missing pom should not be in the map");
            assertEquals("<project/>", new String(result.get("pom.xml"), StandardCharsets.UTF_8));
            assertEquals("<module/>", new String(result.get("sub/pom.xml"), StandardCharsets.UTF_8));
        }
    }
}
