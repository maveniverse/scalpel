/*
 * Copyright (c) Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.scalpel.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Named;
import javax.inject.Singleton;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilterGroup;
import org.eclipse.jgit.util.io.NullOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
@Named
public class GitChangeDetector {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    public String getCurrentBranch(Repository repository) throws IOException {
        return repository.getBranch();
    }

    /**
     * Tells whether the repository is shallow (created or fetched with a depth limit, e.g.
     * actions/checkout with {@code fetch-depth: 1}), which is recorded in {@code .git/shallow}.
     * JGit 7.x still exposes no public accessor for this ({@link Repository} has no shallow
     * method; only the internal DepthWalk classes track it), so the marker file is checked directly.
     */
    public boolean isShallow(Repository repository) {
        return repository.getDirectory() != null
                && Files.isRegularFile(repository.getDirectory().toPath().resolve("shallow"));
    }

    public ObjectId findMergeBase(Repository repository, String baseBranch, String head) throws IOException {
        ObjectId baseId = repository.resolve(baseBranch);
        if (baseId == null) {
            warnIfShallow(repository, baseBranch, head);
            logger.warn("Cannot resolve base branch: {}", baseBranch);
            return null;
        }
        ObjectId headId = repository.resolve(head);
        if (headId == null) {
            warnIfShallow(repository, baseBranch, head);
            logger.warn("Cannot resolve head: {}", head);
            return null;
        }

        try (RevWalk revWalk = new RevWalk(repository)) {
            revWalk.setRevFilter(RevFilter.MERGE_BASE);
            revWalk.markStart(revWalk.parseCommit(baseId));
            revWalk.markStart(revWalk.parseCommit(headId));
            RevCommit mergeBase = revWalk.next();
            if (mergeBase == null) {
                logger.warn("No merge base found between {} and {}", baseBranch, head);
                warnIfShallow(repository, baseBranch, head);
                return null;
            }
            logger.debug("Merge base between {} and {}: {}", baseBranch, head, mergeBase.getName());
            return mergeBase.getId();
        } catch (MissingObjectException e) {
            logger.warn(
                    "Cannot compute merge base between {} and {}: commit history is incomplete"
                            + " (shallow clone or missing objects). {}",
                    baseBranch,
                    head,
                    e.getMessage());
            logger.debug("MissingObjectException details", e);
            return null;
        } catch (IOException e) {
            logger.warn("Cannot compute merge base between {} and {}: {}", baseBranch, head, e.getMessage());
            logger.debug("Merge base computation error details", e);
            return null;
        }
    }

    /**
     * Emits the actionable remediation when the repository is shallow: the commits needed to relate
     * {@code baseBranch} and {@code head} may sit beyond the depth-limited boundary, so an
     * unresolved revision or a missing merge base is not a real topology problem but a clone artifact.
     */
    private void warnIfShallow(Repository repository, String baseBranch, String head) {
        if (isShallow(repository)) {
            logger.warn(
                    "Repository is shallow (depth-limited clone/fetch, e.g. actions/checkout with fetch-depth: 1);"
                            + " the connecting history between {} and {} may be missing. Fix: use fetch-depth: 0"
                            + " in CI, or run 'git fetch --unshallow' before the build",
                    baseBranch,
                    head);
        }
    }

    public Set<String> getChangedFiles(Repository repository, ObjectId base, ObjectId head) throws IOException {
        Set<String> changedFiles = new LinkedHashSet<>();

        try (RevWalk revWalk = new RevWalk(repository)) {
            RevCommit baseCommit = revWalk.parseCommit(base);
            RevCommit headCommit = revWalk.parseCommit(head);

            try (DiffFormatter diffFormatter = new DiffFormatter(NullOutputStream.INSTANCE)) {
                diffFormatter.setRepository(repository);
                for (DiffEntry entry : diffFormatter.scan(baseCommit.getTree(), headCommit.getTree())) {
                    switch (entry.getChangeType()) {
                        case ADD:
                        case MODIFY:
                        case COPY:
                            changedFiles.add(entry.getNewPath());
                            break;
                        case DELETE:
                            changedFiles.add(entry.getOldPath());
                            break;
                        case RENAME:
                            changedFiles.add(entry.getOldPath());
                            changedFiles.add(entry.getNewPath());
                            break;
                    }
                }
            }
        }

        logger.debug("Changed files between {} and {}: {}", base.name(), head.name(), changedFiles);
        return changedFiles;
    }

    public byte[] readFileAtCommit(Repository repository, ObjectId commitId, String path) throws IOException {
        try (RevWalk revWalk = new RevWalk(repository)) {
            RevCommit commit = revWalk.parseCommit(commitId);
            try (TreeWalk treeWalk = TreeWalk.forPath(repository, path, commit.getTree())) {
                if (treeWalk == null) {
                    return null;
                }
                ObjectLoader loader = repository.open(treeWalk.getObjectId(0));
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                loader.copyTo(out);
                return out.toByteArray();
            }
        }
    }

    /**
     * Result of a git status query, containing both uncommitted and untracked files.
     */
    public static class StatusResult {
        private final Set<String> uncommitted;
        private final Set<String> untracked;

        StatusResult(Set<String> uncommitted, Set<String> untracked) {
            this.uncommitted = Collections.unmodifiableSet(new LinkedHashSet<>(uncommitted));
            this.untracked = Collections.unmodifiableSet(new LinkedHashSet<>(untracked));
        }

        public Set<String> getUncommitted() {
            return uncommitted;
        }

        public Set<String> getUntracked() {
            return untracked;
        }
    }

    /**
     * Computes git status once and returns both uncommitted and untracked files.
     */
    public StatusResult getStatusFiles(Repository repository) throws IOException {
        Set<String> uncommitted = new LinkedHashSet<>();
        Set<String> untracked = new LinkedHashSet<>();
        try (Git git = new Git(repository)) {
            Status status = git.status().call();
            uncommitted.addAll(status.getModified());
            uncommitted.addAll(status.getChanged());
            uncommitted.addAll(status.getAdded());
            uncommitted.addAll(status.getRemoved());
            uncommitted.addAll(status.getMissing());
            uncommitted.addAll(status.getConflicting());
            untracked.addAll(status.getUntracked());
        } catch (GitAPIException | JGitInternalException e) {
            throw new IOException("Failed to get git status", e);
        }
        logger.debug("Uncommitted files: {}", uncommitted);
        logger.debug("Untracked files: {}", untracked);
        return new StatusResult(uncommitted, untracked);
    }

    public void fetchBranch(Repository repository, String baseBranch) throws IOException {
        if (baseBranch == null || baseBranch.isEmpty()) {
            logger.debug("Empty baseBranch, skipping fetch");
            return;
        }
        // Reject URL-shaped values early: JGit treats unknown remote names as URLs,
        // so a typo like "git@host:evil.git/main" would otherwise attempt a network fetch
        if (baseBranch.contains(":")) {
            throw new IOException("Invalid scalpel.baseBranch '" + baseBranch
                    + "': URL-shaped value, expected <remote>/<branch> or <branch>");
        }
        // Only treat the prefix before the first slash as a remote if it is a configured remote;
        // otherwise the whole string is a (possibly slash-containing) local branch name
        String remote = null;
        String branch = baseBranch;
        int slashIndex = baseBranch.indexOf('/');
        if (slashIndex > 0) {
            String candidate = baseBranch.substring(0, slashIndex);
            if (repository.getRemoteNames().contains(candidate)) {
                remote = candidate;
                branch = baseBranch.substring(slashIndex + 1);
            } else {
                logger.warn(
                        "Scalpel: baseBranch '{}' has a slash but '{}' is not a configured remote; "
                                + "treating it as a local branch name and skipping the fetch",
                        baseBranch,
                        candidate);
            }
        }
        if (!Repository.isValidRefName("refs/heads/" + branch)) {
            throw new IOException("Invalid scalpel.baseBranch '" + baseBranch + "': not a valid branch name"
                    + (branch.contains("*") ? " (wildcards are not allowed)" : ""));
        }
        if (remote == null) {
            logger.debug("No configured remote prefix in baseBranch '{}', skipping fetch", baseBranch);
            return;
        }
        String refspec = "+refs/heads/" + branch + ":refs/remotes/" + remote + "/" + branch;

        logger.info("Scalpel: Fetching {} from {}", branch, remote);
        if (isShallow(repository)) {
            // FetchCommand.setDepth is not wired here, so the fetch stays unbounded on shallow
            // repos and may or may not restore the missing history.
            logger.warn(
                    "Scalpel: Repository is shallow; fetching {} is unbounded and may not restore the"
                            + " missing history. Use fetch-depth: 0 in CI or 'git fetch --unshallow' before the build",
                    branch);
        }
        try (Git git = new Git(repository)) {
            git.fetch().setRemote(remote).setRefSpecs(new RefSpec(refspec)).call();
        } catch (GitAPIException | JGitInternalException e) {
            throw new IOException("Failed to fetch " + baseBranch, e);
        }
    }

    /**
     * Reads multiple files at the given commit in a single TreeWalk pass.
     * Uses a PathFilterGroup so the TreeWalk only visits matching entries,
     * and shares a single ObjectReader/RevWalk across all reads.
     */
    public Map<String, byte[]> readPomFilesAtCommit(Repository repository, ObjectId commitId, Set<String> paths)
            throws IOException {
        if (paths.isEmpty()) {
            return Map.of();
        }
        // PathFilterGroup requires a non-empty collection of path strings
        List<String> pathList = new ArrayList<>(paths);
        Map<String, byte[]> result = new HashMap<>();
        try (ObjectReader reader = repository.newObjectReader();
                RevWalk revWalk = new RevWalk(reader)) {
            RevCommit commit = revWalk.parseCommit(commitId);
            try (TreeWalk treeWalk = new TreeWalk(reader)) {
                treeWalk.addTree(commit.getTree());
                treeWalk.setRecursive(true);
                treeWalk.setFilter(PathFilterGroup.createFromStrings(pathList));
                while (treeWalk.next()) {
                    if (treeWalk.getFileMode(0) == FileMode.REGULAR_FILE
                            || treeWalk.getFileMode(0) == FileMode.EXECUTABLE_FILE) {
                        String path = treeWalk.getPathString();
                        ObjectLoader loader = reader.open(treeWalk.getObjectId(0));
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        loader.copyTo(out);
                        result.put(path, out.toByteArray());
                    }
                }
            }
        }
        return result;
    }
}
