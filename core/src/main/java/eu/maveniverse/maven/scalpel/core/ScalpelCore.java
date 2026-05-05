/*
 * Copyright (c) Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.scalpel.core;

import static java.util.Objects.requireNonNull;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.PatternSyntaxException;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
@Named
public class ScalpelCore {

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final GitChangeDetector gitChangeDetector;

    @Inject
    public ScalpelCore(GitChangeDetector gitChangeDetector) {
        this.gitChangeDetector = requireNonNull(gitChangeDetector, "gitChangeDetector");
    }

    /**
     * Detects changes between the base branch and head, returning changed files and old POM contents.
     *
     * @param reactorRoot the reactor root directory
     * @param config the scalpel configuration
     * @param allPomPaths all POM paths relative to reactor root (for reading old versions)
     * @return the result, or null if change detection should be skipped (no git repo, no base branch, etc.)
     * @throws ScalpelException if an error occurs and failSafe is false
     */
    public ChangeDetectionResult detectChanges(Path reactorRoot, ScalpelConfiguration config, Set<String> allPomPaths)
            throws ScalpelException {
        RepositoryInfo repoInfo;
        try {
            repoInfo = openRepository(reactorRoot);
        } catch (RepositoryNotFoundException | IllegalArgumentException e) {
            logger.info("Scalpel: Not a git repository, building all modules");
            return null;
        } catch (IOException e) {
            return handleError(config, "Error opening git repository", e);
        }

        Repository repository = repoInfo.repository;
        try {
            // Check branch-based disable conditions
            if (!config.getDisableOnBranch().isEmpty()) {
                String currentBranch =
                        repoInfo.worktree ? repoInfo.currentBranch : gitChangeDetector.getCurrentBranch(repository);
                if (currentBranch != null) {
                    for (String pattern : config.getDisableOnBranch()) {
                        if (matchesSafely(currentBranch, pattern, "disableOnBranch")) {
                            logger.info(
                                    "Scalpel: Disabled because current branch '{}' matches pattern '{}'",
                                    currentBranch,
                                    pattern);
                            return null;
                        }
                    }
                }
            }

            String baseBranch = config.getBaseBranch();

            if (!config.getDisableOnBaseBranch().isEmpty() && baseBranch != null) {
                // Strip remote prefix for matching (e.g., "origin/main" → "main")
                String baseBranchName = baseBranch;
                int slashIndex = baseBranchName.indexOf('/');
                if (slashIndex >= 0) {
                    baseBranchName = baseBranchName.substring(slashIndex + 1);
                }
                for (String pattern : config.getDisableOnBaseBranch()) {
                    if (matchesSafely(baseBranchName, pattern, "disableOnBaseBranch")) {
                        logger.info(
                                "Scalpel: Disabled because base branch '{}' matches pattern '{}'", baseBranch, pattern);
                        return null;
                    }
                }
            }

            if (baseBranch == null) {
                logger.info("Scalpel: No base branch configured or detected, building all modules");
                return null;
            }

            // Fetch base branch if configured and ref cannot be resolved
            if (config.isFetchBaseBranch()) {
                ObjectId baseId = repository.resolve(baseBranch);
                if (baseId == null) {
                    try {
                        gitChangeDetector.fetchBranch(repository, baseBranch);
                    } catch (IOException e) {
                        if (config.isFailSafe()) {
                            logger.warn(
                                    "Scalpel: Failed to fetch {}, building all modules: {}",
                                    baseBranch,
                                    e.getMessage());
                            return null;
                        } else {
                            throw new ScalpelException("Failed to fetch " + baseBranch, e);
                        }
                    }
                }
            }

            // For worktrees, replace HEAD in revspecs with the worktree's head ref
            // (e.g., "HEAD" -> "refs/heads/feature", "HEAD~1" -> "refs/heads/feature~1")
            String head = config.getHead();
            if (repoInfo.headRef != null && head.startsWith("HEAD")) {
                head = repoInfo.headRef + head.substring("HEAD".length());
            }

            ObjectId mergeBase = gitChangeDetector.findMergeBase(repository, baseBranch, head);
            if (mergeBase == null) {
                if (config.isFailSafe()) {
                    logger.warn(
                            "Scalpel: Could not find merge base between {} and {}, building all modules",
                            baseBranch,
                            head);
                    return null;
                } else {
                    throw new ScalpelException("Could not find merge base between " + baseBranch + " and " + head);
                }
            }

            ObjectId headId = repository.resolve(head);
            // Copy the set to avoid mutating the return value of getChangedFiles()
            Set<String> changedFiles =
                    new LinkedHashSet<>(gitChangeDetector.getChangedFiles(repository, mergeBase, headId));

            // Merge in uncommitted/untracked files if configured
            if (config.isUncommitted() || config.isUntracked()) {
                GitChangeDetector.StatusResult statusResult = gitChangeDetector.getStatusFiles(repository);
                if (config.isUncommitted() && !statusResult.getUncommitted().isEmpty()) {
                    logger.info(
                            "Scalpel: {} uncommitted files detected",
                            statusResult.getUncommitted().size());
                    changedFiles.addAll(statusResult.getUncommitted());
                }
                if (config.isUntracked() && !statusResult.getUntracked().isEmpty()) {
                    logger.info(
                            "Scalpel: {} untracked files detected",
                            statusResult.getUntracked().size());
                    changedFiles.addAll(statusResult.getUntracked());
                }
            }

            if (changedFiles.isEmpty()) {
                logger.info("Scalpel: No changes detected between {} and {}", baseBranch, head);
                return new ChangeDetectionResult(changedFiles, Collections.<String, byte[]>emptyMap());
            }

            // Read old POM files for comparison
            Map<String, byte[]> oldPomContents =
                    gitChangeDetector.readPomFilesAtCommit(repository, mergeBase, allPomPaths);

            return new ChangeDetectionResult(changedFiles, oldPomContents);
        } catch (ScalpelException e) {
            throw e;
        } catch (IOException e) {
            return handleError(config, "Error during change detection", e);
        } finally {
            repository.close();
        }
    }

    private boolean matchesSafely(String value, String pattern, String configKey) {
        try {
            return value.matches(pattern);
        } catch (PatternSyntaxException e) {
            logger.warn("Scalpel: Invalid regex pattern '{}' in {}: {}", pattern, configKey, e.getMessage());
            return false;
        }
    }

    private static class RepositoryInfo {
        final Repository repository;
        final boolean worktree;
        final String currentBranch;
        final String headRef;

        RepositoryInfo(Repository repository, boolean worktree, String currentBranch, String headRef) {
            this.repository = repository;
            this.worktree = worktree;
            this.currentBranch = currentBranch;
            this.headRef = headRef;
        }
    }

    private RepositoryInfo openRepository(Path reactorRoot) throws IOException {
        FileRepositoryBuilder builder =
                new FileRepositoryBuilder().readEnvironment().findGitDir(reactorRoot.toFile());

        File gitDir = builder.getGitDir();
        if (gitDir == null) {
            throw new RepositoryNotFoundException(reactorRoot.toFile());
        }

        // JGit 5.x findGitDir() correctly follows .git files (worktree pointers)
        // to .git/worktrees/<name>/, but JGit 5.x lacks commondir support, so
        // refs and objects from the common directory are invisible. To work around
        // this, we open the repository from the common directory (the main .git/)
        // and separately read the worktree's HEAD for branch/head resolution.
        Path gitDirPath = gitDir.toPath();
        Path commondirFile = gitDirPath.resolve("commondir");
        if (Files.isRegularFile(commondirFile)) {
            return openWorktreeRepository(gitDirPath, commondirFile);
        }

        return new RepositoryInfo(builder.setMustExist(true).build(), false, null, null);
    }

    private RepositoryInfo openWorktreeRepository(Path gitDirPath, Path commondirFile) throws IOException {
        logger.debug("Detected git worktree, gitdir={}", gitDirPath);

        String currentBranch = null;
        String headRef = null;
        Path worktreeHead = gitDirPath.resolve("HEAD");
        if (Files.isRegularFile(worktreeHead)) {
            String headContent = new String(Files.readAllBytes(worktreeHead), StandardCharsets.UTF_8).trim();
            if (headContent.startsWith("ref: ")) {
                headRef = headContent.substring(5);
                if (headRef.startsWith("refs/heads/")) {
                    currentBranch = headRef.substring("refs/heads/".length());
                }
            } else {
                headRef = headContent;
            }
        }

        File commonDirFile = resolvePathFromFile(commondirFile, gitDirPath).toFile();

        Path gitdirPointer = gitDirPath.resolve("gitdir");
        File workTree = null;
        if (Files.isRegularFile(gitdirPointer)) {
            workTree =
                    resolvePathFromFile(gitdirPointer, gitDirPath).getParent().toFile();
        }

        FileRepositoryBuilder worktreeBuilder =
                new FileRepositoryBuilder().readEnvironment().setGitDir(commonDirFile);
        if (workTree != null) {
            worktreeBuilder.setWorkTree(workTree);
        }
        Repository repository = worktreeBuilder.setMustExist(true).build();
        return new RepositoryInfo(repository, true, currentBranch, headRef);
    }

    private static Path resolvePathFromFile(Path file, Path baseDir) throws IOException {
        String value = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim();
        Path path = Paths.get(value);
        if (!path.isAbsolute()) {
            path = baseDir.resolve(value);
        }
        return path.toRealPath();
    }

    private ChangeDetectionResult handleError(ScalpelConfiguration config, String message, Exception e)
            throws ScalpelException {
        if (config.isFailSafe()) {
            logger.warn("Scalpel: {}, building all modules: {}", message, e.getMessage());
            logger.debug("{} details", message, e);
            return null;
        } else {
            throw new ScalpelException(message, e);
        }
    }
}
