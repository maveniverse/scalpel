/*
 * Copyright (c) Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.scalpel.core;

import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
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
    private final BoundedRegexMatcher regexMatcher = new BoundedRegexMatcher();

    /**
     * Human-readable reason when the last {@link #detectChanges} invocation deliberately skipped
     * change detection (returned null for a non-error condition such as a disableOnBranch match),
     * or null if the last invocation did not skip or skipped due to a failSafe bail-out.
     * Set on each call; advisory only, so a stale value from a concurrent build is harmless.
     */
    private volatile String lastDetectionSkipReason;

    @Inject
    public ScalpelCore(GitChangeDetector gitChangeDetector) {
        this.gitChangeDetector = requireNonNull(gitChangeDetector, "gitChangeDetector");
    }

    public String getLastDetectionSkipReason() {
        return lastDetectionSkipReason;
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
        lastDetectionSkipReason = null;
        Repository repository;
        try {
            repository = openRepository(reactorRoot);
        } catch (RepositoryNotFoundException | IllegalArgumentException e) {
            logger.info("Scalpel: Not a git repository, building all modules");
            lastDetectionSkipReason = "not a git repository";
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
                        if (matchesSafely(currentBranch, pattern, "disableOnBranch")) {
                            logger.info(
                                    "Scalpel: Disabled because current branch '{}' matches pattern '{}'",
                                    currentBranch,
                                    pattern);
                            lastDetectionSkipReason = "disabled by disableOnBranch";
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
                        lastDetectionSkipReason = "disabled by disableOnBaseBranch";
                        return null;
                    }
                }
            }

            if (baseBranch == null) {
                logger.info("Scalpel: No base branch configured or detected, building all modules");
                lastDetectionSkipReason = "no base branch configured";
                return null;
            }

            // Fetch base branch if configured and ref cannot be resolved
            if (config.isFetchBaseBranch()) {
                ObjectId baseId = repository.resolve(baseBranch);
                if (baseId == null) {
                    try {
                        gitChangeDetector.fetchBranch(repository, baseBranch);
                    } catch (Exception e) {
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
                return new ChangeDetectionResult(changedFiles, Map.of());
            }

            // Read old POM files for comparison — only read the ones that actually changed,
            // not every reactor POM. The PomChangeAnalyzer only needs old content for changed POMs.
            Set<String> changedPomPaths = new LinkedHashSet<>();
            for (String path : changedFiles) {
                if (allPomPaths.contains(path)) {
                    changedPomPaths.add(path);
                }
            }
            Map<String, byte[]> oldPomContents = gitChangeDetector.readPomFilesAtCommit(
                    repository, mergeBase, changedPomPaths, config.getMaxResourceFileSize());

            return new ChangeDetectionResult(changedFiles, oldPomContents);
        } catch (ScalpelException e) {
            throw e;
        } catch (Exception e) {
            return handleError(config, "Error during change detection", e);
        } finally {
            repository.close();
        }
    }

    private boolean matchesSafely(String value, String pattern, String configKey) {
        return regexMatcher.matches(value, pattern, configKey, logger);
    }

    private Repository openRepository(Path reactorRoot) throws IOException {
        FileRepositoryBuilder builder =
                new FileRepositoryBuilder().readEnvironment().findGitDir(reactorRoot.toFile());
        if (builder.getGitDir() == null) {
            throw new RepositoryNotFoundException(reactorRoot.toFile());
        }
        return builder.setMustExist(true).build();
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
