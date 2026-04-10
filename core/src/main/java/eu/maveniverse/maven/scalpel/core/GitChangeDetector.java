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
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import javax.inject.Named;
import javax.inject.Singleton;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.treewalk.TreeWalk;
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

    public ObjectId findMergeBase(Repository repository, String baseBranch, String head) throws IOException {
        ObjectId baseId = repository.resolve(baseBranch);
        if (baseId == null) {
            logger.warn("Cannot resolve base branch: {}", baseBranch);
            return null;
        }
        ObjectId headId = repository.resolve(head);
        if (headId == null) {
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
                return null;
            }
            logger.debug("Merge base between {} and {}: {}", baseBranch, head, mergeBase.getName());
            return mergeBase.getId();
        }
    }

    public Set<String> getChangedFiles(Repository repository, ObjectId base, ObjectId head) throws IOException {
        Set<String> changedFiles = new LinkedHashSet<>();

        try (RevWalk revWalk = new RevWalk(repository)) {
            RevCommit baseCommit = revWalk.parseCommit(base);
            RevCommit headCommit = revWalk.parseCommit(head);

            try (DiffFormatter diffFormatter = new DiffFormatter(NullOutputStream.INSTANCE)) {
                diffFormatter.setRepository(repository);
                diffFormatter.setDetectRenames(true);

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
        } catch (GitAPIException e) {
            throw new IOException("Failed to get git status", e);
        }
        logger.debug("Uncommitted files: {}", uncommitted);
        logger.debug("Untracked files: {}", untracked);
        return new StatusResult(uncommitted, untracked);
    }

    public void fetchBranch(Repository repository, String baseBranch) throws IOException {
        // Parse "origin/main" → remote="origin", branch="main"
        int slashIndex = baseBranch.indexOf('/');
        if (slashIndex < 0) {
            logger.debug("Cannot parse remote from baseBranch '{}', skipping fetch", baseBranch);
            return;
        }
        String remote = baseBranch.substring(0, slashIndex);
        String branch = baseBranch.substring(slashIndex + 1);
        String refspec = "+refs/heads/" + branch + ":refs/remotes/" + remote + "/" + branch;

        logger.info("Scalpel: Fetching {} from {}", branch, remote);
        try (Git git = new Git(repository)) {
            git.fetch().setRemote(remote).setRefSpecs(new RefSpec(refspec)).call();
        } catch (GitAPIException e) {
            throw new IOException("Failed to fetch " + baseBranch, e);
        }
    }

    public Map<String, byte[]> readPomFilesAtCommit(Repository repository, ObjectId commitId, Set<String> paths)
            throws IOException {
        Map<String, byte[]> result = new HashMap<>();
        for (String path : paths) {
            byte[] content = readFileAtCommit(repository, commitId, path);
            if (content != null) {
                result.put(path, content);
            }
        }
        return result;
    }
}
