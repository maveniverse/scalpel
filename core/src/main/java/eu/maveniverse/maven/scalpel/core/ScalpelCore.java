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
            repository = new FileRepositoryBuilder()
                    .readEnvironment()
                    .findGitDir(reactorRoot.toFile())
                    .setMustExist(true)
                    .build();
        } catch (RepositoryNotFoundException e) {
            logger.info("Scalpel: Not a git repository, building all modules");
            return null;
        } catch (IOException e) {
            return handleError(config, "Error opening git repository", e);
        }

        try {
            String baseBranch = config.getBaseBranch();
            if (baseBranch == null) {
                logger.info("Scalpel: No base branch configured or detected, building all modules");
                return null;
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
