/*
 * Copyright (c) Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.scalpel.core;

import static java.util.Objects.requireNonNull;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
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
        Repository repository;
        try {
            repository = openRepository(reactorRoot);
        } catch (RepositoryNotFoundException e) {
            logger.info("Scalpel: Not a git repository, building all modules");
            return null;
        } catch (IOException e) {
            return handleError(config, "Error opening git repository", e);
        }

        try {
            // Check branch-based disable conditions
            if (!config.getDisableOnBranch().isEmpty()) {
                String currentBranch = gitChangeDetector.getCurrentBranch(repository);
                if (currentBranch != null) {
                    for (String pattern : config.getDisableOnBranch()) {
                        if (currentBranch.matches(pattern.trim())) {
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
                    if (baseBranchName.matches(pattern.trim())) {
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

            String head = config.getHead();
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
            Set<String> changedFiles = gitChangeDetector.getChangedFiles(repository, mergeBase, headId);

            // Merge in uncommitted/untracked files if configured
            if (config.isUncommitted()) {
                Set<String> uncommittedFiles = gitChangeDetector.getUncommittedFiles(repository);
                if (!uncommittedFiles.isEmpty()) {
                    logger.info("Scalpel: {} uncommitted files detected", uncommittedFiles.size());
                    changedFiles.addAll(uncommittedFiles);
                }
            }
            if (config.isUntracked()) {
                Set<String> untrackedFiles = gitChangeDetector.getUntrackedFiles(repository);
                if (!untrackedFiles.isEmpty()) {
                    logger.info("Scalpel: {} untracked files detected", untrackedFiles.size());
                    changedFiles.addAll(untrackedFiles);
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

    private Repository openRepository(Path reactorRoot) throws IOException {
        FileRepositoryBuilder builder =
                new FileRepositoryBuilder().readEnvironment().findGitDir(reactorRoot.toFile());

        File gitDir = builder.getGitDir();

        // Handle git worktrees: .git may be a file containing "gitdir: <path>"
        if (gitDir == null) {
            File dotGit = findDotGit(reactorRoot.toFile());
            if (dotGit != null && dotGit.isFile()) {
                String gitDirPath = readGitDirFromFile(dotGit);
                if (gitDirPath != null) {
                    Path resolvedPath = Paths.get(gitDirPath);
                    if (!resolvedPath.isAbsolute()) {
                        resolvedPath = dotGit.getParentFile().toPath().resolve(gitDirPath);
                    }
                    File resolved = resolvedPath.toFile();
                    logger.debug("Detected git worktree, gitdir={}", resolved);
                    builder.setGitDir(resolved);
                }
            }
        }

        return builder.setMustExist(true).build();
    }

    private static File findDotGit(File dir) {
        Path current = dir.toPath();
        while (current != null) {
            Path dotGit = current.resolve(".git");
            if (Files.exists(dotGit)) {
                return dotGit.toFile();
            }
            current = current.getParent();
        }
        return null;
    }

    private static String readGitDirFromFile(File dotGitFile) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(dotGitFile.toPath(), StandardCharsets.UTF_8)) {
            String line = reader.readLine();
            if (line != null && line.startsWith("gitdir:")) {
                return line.substring("gitdir:".length()).trim();
            }
        }
        return null;
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
