/*
 * Copyright (c) Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.scalpel.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
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

            // Whatever initial branch git chose is the base; advance a separate branch
            Repository repo = git.getRepository();
            String baseBranch = repo.getBranch();
            git.branchCreate().setName("feature").call();
            git.checkout().setName("feature").call();

            Files.write(tempDir.resolve("b.txt"), "branch".getBytes(StandardCharsets.UTF_8));
            git.add().addFilepattern("b.txt").call();
            git.commit().setMessage("branch commit").call();

            ObjectId mergeBase = detector.findMergeBase(repo, baseBranch, "feature");

            assertNotNull(mergeBase, "Merge base should be found");
            // The merge base is the "initial" commit, which is what the base branch points at
            ObjectId baseId = repo.resolve(baseBranch);
            assertEquals(baseId, mergeBase, "Merge base should be the base branch tip");
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

            // Base is the repository's real initial branch (no default-branch assumption)
            String baseBranch = git.getRepository().getBranch();
            ObjectId result = detector.findMergeBase(git.getRepository(), baseBranch, "nonexistent");

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
            byte[] read = detector.readFileAtCommit(
                    git.getRepository(), commitId, "data.txt", ScalpelConfiguration.DEFAULT_MAX_RESOURCE_FILE_SIZE);

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
            byte[] read = detector.readFileAtCommit(
                    git.getRepository(),
                    commitId,
                    "nonexistent.txt",
                    ScalpelConfiguration.DEFAULT_MAX_RESOURCE_FILE_SIZE);

            assertNull(read, "Should return null for a file not present at the commit");
        }
    }

    @Test
    void readFileAtCommit_oversizeBlob_returnsNullWithWarn() throws Exception {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            byte[] blob = new byte[300];
            Arrays.fill(blob, (byte) 'x');
            Files.write(tempDir.resolve("data.txt"), blob);
            git.add().addFilepattern("data.txt").call();
            git.commit().setMessage("add data").call();

            ObjectId commitId = git.getRepository().resolve("HEAD");
            String err = captureStderr(() -> {
                byte[] read;
                try {
                    read = detector.readFileAtCommit(git.getRepository(), commitId, "data.txt", 200);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                assertNull(read, "Blob over the cap must be skipped (null), not read into memory");
            });

            assertTrue(err.contains("WARN "), "expected a WARN about the oversized blob but stderr was: " + err);
            assertTrue(err.contains("data.txt"), "WARN should name the blob path but stderr was: " + err);
            assertTrue(err.contains("300"), "WARN should name the blob size but stderr was: " + err);
            assertTrue(err.contains("200"), "WARN should name the cap but stderr was: " + err);
            assertTrue(
                    err.contains("scalpel.maxResourceFileSize"),
                    "WARN should name how to raise the cap but stderr was: " + err);
        }
    }

    @Test
    void readFileAtCommit_blobAtExactCap_stillReads() throws Exception {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            byte[] blob = new byte[200];
            Arrays.fill(blob, (byte) 'x');
            Files.write(tempDir.resolve("data.txt"), blob);
            git.add().addFilepattern("data.txt").call();
            git.commit().setMessage("add data").call();

            ObjectId commitId = git.getRepository().resolve("HEAD");
            byte[] read = detector.readFileAtCommit(git.getRepository(), commitId, "data.txt", 200);

            assertNotNull(read, "A blob exactly at the cap must still be read (only strictly larger is skipped)");
            assertEquals(200, read.length);
        }
    }

    @Test
    void readFileAtCommit_nonPositiveCap_rejected() throws Exception {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            Files.write(tempDir.resolve("file.txt"), "x".getBytes(StandardCharsets.UTF_8));
            git.add().addFilepattern("file.txt").call();
            git.commit().setMessage("initial").call();

            ObjectId commitId = git.getRepository().resolve("HEAD");
            assertThrows(
                    IllegalArgumentException.class,
                    () -> detector.readFileAtCommit(git.getRepository(), commitId, "file.txt", 0),
                    "A zero cap must be rejected up front");
            assertThrows(
                    IllegalArgumentException.class,
                    () -> detector.readFileAtCommit(git.getRepository(), commitId, "file.txt", -1),
                    "A negative cap must be rejected up front");
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
            String pushedBranch = git.getRepository().getBranch();
            git.push().setRemote("origin").call();
            ObjectId pushedCommit = git.getRepository().resolve("HEAD");

            // Fetch the branch that was actually pushed, whatever its name
            detector.fetchBranch(git.getRepository(), "origin/" + pushedBranch);

            // The fetch must have materialized the remote-tracking ref at the pushed commit
            ObjectId fetched = git.getRepository().resolve("refs/remotes/origin/" + pushedBranch);
            assertNotNull(fetched, "Fetch should create the remote-tracking ref");
            assertEquals(pushedCommit, fetched, "Remote-tracking ref should point at the pushed commit");
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
    void fetchBranch_unconfiguredRemotePrefix_treatedAsLocalBranchAndSkipped() throws Exception {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            Files.write(tempDir.resolve("file.txt"), "x".getBytes(StandardCharsets.UTF_8));
            git.add().addFilepattern("file.txt").call();
            git.commit().setMessage("initial").call();

            // "bogus" is not a configured remote: per #85 the whole string is a
            // local branch name and the fetch is skipped, not attempted against
            // a URL parsed from the prefix
            assertDoesNotThrow(() -> detector.fetchBranch(git.getRepository(), "bogus/main"));
        }
    }

    @Test
    void fetchBranch_urlShapedRefspec_rejectedAsUrl() throws Exception {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            Files.write(tempDir.resolve("file.txt"), "x".getBytes(StandardCharsets.UTF_8));
            git.add().addFilepattern("file.txt").call();
            git.commit().setMessage("initial").call();

            // ":/nope" contains a colon and is URL-shaped: rejected up front with
            // an IOException naming the configuration problem (#85), instead of
            // escaping as a raw IllegalArgumentException from RefSpec parsing
            IOException e = assertThrows(
                    IOException.class,
                    () -> detector.fetchBranch(git.getRepository(), ":/nope"),
                    "A URL-shaped baseBranch should be rejected with IOException");
            assertTrue(e.getMessage().contains("URL"), "message should name the URL shape: " + e.getMessage());
        }
    }

    // ---- readPomFilesAtCommit ----

    @Test
    void findMergeBase_shallowClone_returnsNullGracefully() throws Exception {
        // Set up a bare "remote" repository with two branches diverging from a common base
        Path bareDir = tempDir.resolve("bare.git");
        String baseBranchName;
        try (Git bare = Git.init().setBare(true).setDirectory(bareDir.toFile()).call()) {
            // nothing to do
        }

        Path fullWorkDir = tempDir.resolve("full");
        Files.createDirectories(fullWorkDir);
        try (Git git = Git.init().setDirectory(fullWorkDir.toFile()).call()) {
            StoredConfig cfg = git.getRepository().getConfig();
            cfg.setString("user", null, "name", "Test");
            cfg.setString("user", null, "email", "test@test.invalid");
            cfg.save();

            git.remoteAdd()
                    .setName("origin")
                    .setUri(new org.eclipse.jgit.transport.URIish(
                            bareDir.toUri().toString()))
                    .call();

            // Initial commit (the merge-base)
            Files.write(fullWorkDir.resolve("base.txt"), "base".getBytes(StandardCharsets.UTF_8));
            git.add().addFilepattern("base.txt").call();
            git.commit().setMessage("base commit").call();
            baseBranchName = git.getRepository().getBranch();
            git.push().setRemote("origin").call();

            // Diverge: add commits on a feature branch
            git.branchCreate().setName("feature").call();
            git.checkout().setName("feature").call();
            // Add enough commits so the shallow clone boundary cuts off the merge-base
            for (int i = 0; i < 3; i++) {
                Files.write(fullWorkDir.resolve("f" + i + ".txt"), ("feature-" + i).getBytes(StandardCharsets.UTF_8));
                git.add().addFilepattern("f" + i + ".txt").call();
                git.commit().setMessage("feature commit " + i).call();
            }
            git.push().setRemote("origin").setForce(true).call();
        }

        // Shallow clone with depth=1: only the tip commit is available
        Path shallowDir = tempDir.resolve("shallow");
        Process cloneProc = new ProcessBuilder(
                        "git",
                        "clone",
                        "--depth",
                        "1",
                        "--branch",
                        "feature",
                        bareDir.toUri().toString(),
                        shallowDir.toString())
                .redirectErrorStream(true)
                .start();
        cloneProc.getInputStream().readAllBytes();
        assertEquals(0, cloneProc.waitFor(), "shallow clone should succeed");

        // Fetch the base branch with an explicit refspec so origin/<baseBranch> is created
        Process fetchProc = new ProcessBuilder(
                        "git",
                        "fetch",
                        "--depth",
                        "1",
                        "origin",
                        "+refs/heads/" + baseBranchName + ":refs/remotes/origin/" + baseBranchName)
                .directory(shallowDir.toFile())
                .redirectErrorStream(true)
                .start();
        fetchProc.getInputStream().readAllBytes();
        assertEquals(0, fetchProc.waitFor(), "fetch should succeed");

        // Remove .git/shallow so JGit does not treat the shallow boundary as a root.
        // Without this file JGit will attempt to walk parent commits that were not fetched,
        // triggering MissingObjectException instead of silently stopping at the boundary.
        Path shallowFile = shallowDir.resolve(".git/shallow");
        assertTrue(Files.exists(shallowFile), ".git/shallow should exist after a shallow clone");
        Files.delete(shallowFile);

        // Now try to find the merge base — both refs resolve, but parent objects are missing
        try (Git git = Git.open(shallowDir.toFile())) {
            ObjectId baseRef = git.getRepository().resolve("origin/" + baseBranchName);
            assertNotNull(baseRef, "origin/" + baseBranchName + " should be resolvable after fetch");

            ObjectId result = detector.findMergeBase(git.getRepository(), "origin/" + baseBranchName, "HEAD");

            // Should return null gracefully instead of throwing MissingObjectException
            assertNull(result, "findMergeBase should return null in a shallow clone without reachable merge-base");
        }
    }

    @Test
    void findMergeBase_corruptedObject_returnsNullGracefully() throws Exception {
        Path repoDir = tempDir.resolve("corrupt-repo");
        Files.createDirectories(repoDir);
        try (Git git = Git.init().setDirectory(repoDir.toFile()).call()) {
            StoredConfig cfg = git.getRepository().getConfig();
            cfg.setString("user", null, "name", "Test");
            cfg.setString("user", null, "email", "test@test.invalid");
            cfg.save();

            // Create a base commit (this will be the merge-base ancestor)
            Files.write(repoDir.resolve("base.txt"), "base".getBytes(StandardCharsets.UTF_8));
            git.add().addFilepattern("base.txt").call();
            RevCommit baseCommit = git.commit().setMessage("base").call();
            String baseSha = baseCommit.getName();

            // Create branch A from base
            git.checkout().setCreateBranch(true).setName("branchA").call();
            Files.write(repoDir.resolve("a.txt"), "a".getBytes(StandardCharsets.UTF_8));
            git.add().addFilepattern("a.txt").call();
            git.commit().setMessage("commit on A").call();

            // Create branch B from base
            git.checkout().setName(baseSha).call();
            git.checkout().setCreateBranch(true).setName("branchB").call();
            Files.write(repoDir.resolve("b.txt"), "b".getBytes(StandardCharsets.UTF_8));
            git.add().addFilepattern("b.txt").call();
            git.commit().setMessage("commit on B").call();

            // Corrupt the base commit's loose object file (CorruptObjectException extends IOException,
            // not MissingObjectException, so this exercises the generic IOException catch)
            Path objectFile = repoDir.resolve(".git/objects/" + baseSha.substring(0, 2) + "/" + baseSha.substring(2));
            assertTrue(Files.exists(objectFile), "Loose object file should exist before corruption");
            objectFile.toFile().setWritable(true);
            Files.write(objectFile, "CORRUPT".getBytes(StandardCharsets.UTF_8));
        }

        // Reopen the repository to clear any cached objects
        try (Git git = Git.open(repoDir.toFile())) {
            ObjectId result = detector.findMergeBase(git.getRepository(), "branchA", "branchB");
            assertNull(result, "findMergeBase should return null when git objects are corrupted");
        }
    }

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
                    git.getRepository(),
                    commitId,
                    Set.of("pom.xml", "sub/pom.xml", "missing/pom.xml"),
                    ScalpelConfiguration.DEFAULT_MAX_RESOURCE_FILE_SIZE);

            assertEquals(2, result.size(), "Should read two existing poms, skip the missing one");
            assertTrue(result.containsKey("pom.xml"));
            assertTrue(result.containsKey("sub/pom.xml"));
            assertFalse(result.containsKey("missing/pom.xml"), "Missing pom should not be in the map");
            assertEquals("<project/>", new String(result.get("pom.xml"), StandardCharsets.UTF_8));
            assertEquals("<module/>", new String(result.get("sub/pom.xml"), StandardCharsets.UTF_8));
        }
    }

    @Test
    void readPomFilesAtCommit_oversizeBlob_omitsEntryWithWarn() throws Exception {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            byte[] bigPom = new byte[300];
            Arrays.fill(bigPom, (byte) 'x');
            Files.write(tempDir.resolve("pom.xml"), bigPom);
            Files.createDirectories(tempDir.resolve("sub"));
            Files.write(tempDir.resolve("sub/pom.xml"), "<module/>".getBytes(StandardCharsets.UTF_8));
            git.add().addFilepattern("pom.xml").call();
            git.add().addFilepattern("sub/pom.xml").call();
            git.commit().setMessage("add poms").call();

            ObjectId commitId = git.getRepository().resolve("HEAD");
            String err = captureStderr(() -> {
                Map<String, byte[]> result;
                try {
                    result = detector.readPomFilesAtCommit(
                            git.getRepository(), commitId, Set.of("pom.xml", "sub/pom.xml"), 200);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                assertFalse(result.containsKey("pom.xml"), "Oversized pom blob must be omitted from the map");
                assertTrue(result.containsKey("sub/pom.xml"), "Pom under the cap must still be read");
                assertEquals(1, result.size());
            });

            assertTrue(err.contains("WARN "), "expected a WARN about the oversized blob but stderr was: " + err);
            assertTrue(err.contains("pom.xml"), "WARN should name the blob path but stderr was: " + err);
            assertTrue(
                    err.contains("scalpel.maxResourceFileSize"),
                    "WARN should name how to raise the cap but stderr was: " + err);
        }
    }

    @Test
    void readPomFilesAtCommit_nonPositiveCap_rejected() throws Exception {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            Files.write(tempDir.resolve("pom.xml"), "<project/>".getBytes(StandardCharsets.UTF_8));
            git.add().addFilepattern("pom.xml").call();
            git.commit().setMessage("add pom").call();

            ObjectId commitId = git.getRepository().resolve("HEAD");
            assertThrows(
                    IllegalArgumentException.class,
                    () -> detector.readPomFilesAtCommit(git.getRepository(), commitId, Set.of("pom.xml"), 0),
                    "A zero cap must be rejected up front");
            assertThrows(
                    IllegalArgumentException.class,
                    () -> detector.readPomFilesAtCommit(git.getRepository(), commitId, Set.of("pom.xml"), -5),
                    "A negative cap must be rejected up front");
        }
    }

    // ---- fetchBranch validation (#85) ----

    /**
     * Creates a work repo with a commit on its initial branch (whatever the environment
     * defaults to), plus a bare remote named "origin" that has that branch pushed to it,
     * and configures the remote on the work repo.
     */
    private Repository repoWithOriginRemote() throws Exception {
        Path remoteDir = tempDir.resolve("remote.git");
        Path workDir = tempDir.resolve("work");
        Files.createDirectories(workDir);

        try (Git remoteGit =
                Git.init().setDirectory(remoteDir.toFile()).setBare(true).call()) {
            try (Git git = Git.init().setDirectory(workDir.toFile()).call()) {
                git.getRepository().getConfig().setString("user", null, "name", "Scalpel Test");
                git.getRepository().getConfig().setString("user", null, "email", "scalpel@test.invalid");

                Files.write(workDir.resolve("file.txt"), "hello".getBytes(StandardCharsets.UTF_8));
                git.add().addFilepattern("file.txt").call();
                git.commit().setMessage("initial").call();
                // The initial branch name comes from the environment (init.defaultBranch),
                // so derive it from the repository instead of creating "main", which
                // already exists where that is the default
                String branch = git.getRepository().getBranch();
                git.push().setRemote(remoteDir.toUri().toString()).add(branch).call();
                git.remoteAdd()
                        .setName("origin")
                        .setUri(new org.eclipse.jgit.transport.URIish(
                                remoteDir.toUri().toString()))
                        .call();
                return git.getRepository();
            }
        }
    }

    @Test
    void fetchBranch_urlShapedBaseBranch_rejectedNotFetched() throws Exception {
        try (Repository repo = repoWithOriginRemote()) {
            IOException e = assertThrows(IOException.class, () -> detector.fetchBranch(repo, "git@host:evil.git/main"));
            assertTrue(
                    e.getMessage().contains("URL"), "expected URL-shaped rejection message but was: " + e.getMessage());
            IOException e2 = assertThrows(IOException.class, () -> detector.fetchBranch(repo, "https://evil.git/main"));
            assertTrue(
                    e2.getMessage().contains("URL"),
                    "expected URL-shaped rejection message but was: " + e2.getMessage());
        }
    }

    @Test
    void fetchBranch_slashContainingLocalBranch_skipsFetchInsteadOfBogusRemote() throws Exception {
        try (Repository repo = repoWithOriginRemote()) {
            // "release" is not a configured remote: the whole string is a local branch name
            assertDoesNotThrow(() -> detector.fetchBranch(repo, "release/1.0"));
        }
    }

    /**
     * Runs the callable with System.err captured (slf4j-simple writes to stderr)
     * and returns everything that was written during its execution.
     */
    private String captureStderr(Runnable action) {
        PrintStream originalErr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            action.run();
        } finally {
            System.setErr(originalErr);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    @Test
    void fetchBranch_unconfiguredPrefixWithSlash_warns() throws Exception {
        try (Repository repo = repoWithOriginRemote()) {
            String err = captureStderr(() -> assertDoesNotThrow(() -> detector.fetchBranch(repo, "bogus/main")));
            assertTrue(
                    err.contains("WARN "),
                    "expected a WARN about the unconfigured remote prefix but stderr was: " + err);
            assertTrue(
                    err.contains("'bogus' is not a configured remote"),
                    "WARN should name the unconfigured prefix 'bogus' but stderr was: " + err);
        }
    }

    @Test
    void fetchBranch_plainBranchNoSlash_noWarn() throws Exception {
        try (Repository repo = repoWithOriginRemote()) {
            String err = captureStderr(() -> assertDoesNotThrow(() -> detector.fetchBranch(repo, "main")));
            assertFalse(err.contains("WARN "), "no WARN expected for a plain branch name but stderr was: " + err);
        }
    }

    @Test
    void fetchBranch_wildcardBranch_rejected() throws Exception {
        try (Repository repo = repoWithOriginRemote()) {
            IOException e = assertThrows(IOException.class, () -> detector.fetchBranch(repo, "feat*"));
            assertTrue(
                    e.getMessage().contains("Invalid"),
                    "expected invalid-branch rejection message but was: " + e.getMessage());
            IOException e2 = assertThrows(IOException.class, () -> detector.fetchBranch(repo, "origin/ma*n"));
            assertTrue(
                    e2.getMessage().contains("Invalid"),
                    "expected invalid-branch rejection message but was: " + e2.getMessage());
        }
    }

    @Test
    void fetchBranch_typoBranchOnValidRemote_failsLoudly() throws Exception {
        try (Repository repo = repoWithOriginRemote()) {
            // Valid remote, typo'd branch: the fetch must surface the failure, not swallow it
            IOException e = assertThrows(IOException.class, () -> detector.fetchBranch(repo, "origin/typo"));
            assertTrue(
                    e.getMessage().contains("origin/typo"),
                    "expected failure naming the branch but was: " + e.getMessage());
        }
    }
}
