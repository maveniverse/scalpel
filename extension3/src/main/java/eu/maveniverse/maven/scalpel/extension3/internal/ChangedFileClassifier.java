/*
 * Copyright (c) Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.scalpel.extension3.internal;

import eu.maveniverse.maven.scalpel.core.ScalpelConfiguration;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.apache.maven.project.MavenProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Classifies changed files into POM changes vs source changes, applies exclusion/inclusion
 * filters, and detects disable/full-build triggers.
 */
class ChangedFileClassifier {

    private static final String GLOB_PREFIX = "glob:";

    private final Logger logger = LoggerFactory.getLogger(getClass());

    /**
     * Result of classifying changed files into POM changes and source changes.
     */
    static final class ClassificationResult {
        final Set<String> pomChanges;
        final Set<String> sourceChanges;

        ClassificationResult(Set<String> pomChanges, Set<String> sourceChanges) {
            this.pomChanges = pomChanges;
            this.sourceChanges = sourceChanges;
        }
    }

    /**
     * Separates the given changed files into POM changes and source changes.
     * Files under {@code .mvn/} are ignored (build infrastructure).
     */
    ClassificationResult classifyChanges(Set<String> changedFiles) {
        Set<String> pomChanges = new LinkedHashSet<>();
        Set<String> sourceChanges = new LinkedHashSet<>();
        for (String file : changedFiles) {
            if (file.endsWith("/pom.xml") || file.equals("pom.xml")) {
                pomChanges.add(file);
            } else if (file.startsWith(".mvn/")) {
                logger.debug("Ignoring build infrastructure file: {}", file);
            } else {
                sourceChanges.add(file);
            }
        }
        return new ClassificationResult(pomChanges, sourceChanges);
    }

    /**
     * Returns {@code true} if any changed file matches one of the configured disable triggers.
     */
    boolean matchesDisableTrigger(Set<String> changedFiles, ScalpelConfiguration config) {
        for (String pattern : config.getDisableTriggers()) {
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher(GLOB_PREFIX + normalizeGlobPattern(pattern));
            for (String changedFile : changedFiles) {
                if (matcher.matches(Path.of(changedFile))) {
                    logger.info(
                            "Scalpel: Disabled due to change in {} (matches disable trigger {})", changedFile, pattern);
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Removes files matching exclusion path patterns from the changed file set.
     */
    Set<String> filterExcludedPaths(Set<String> changedFiles, ScalpelConfiguration config) {
        List<PathMatcher> excludeMatchers = compileGlobMatchers(config.getExcludePaths());
        if (excludeMatchers.isEmpty()) {
            return changedFiles;
        }
        Set<String> filtered = new LinkedHashSet<>();
        for (String file : changedFiles) {
            boolean excluded = false;
            for (PathMatcher matcher : excludeMatchers) {
                if (matcher.matches(Path.of(file))) {
                    excluded = true;
                    break;
                }
            }
            if (!excluded) {
                filtered.add(file);
            }
        }
        int excludedCount = changedFiles.size() - filtered.size();
        if (excludedCount > 0) {
            logger.info("Scalpel: {} files excluded by path filters", excludedCount);
        }
        return filtered;
    }

    /**
     * Returns the first changed file matching a full-build trigger pattern, or {@code null}
     * if no trigger matches.
     */
    String findFullBuildTrigger(Set<String> changedFiles, ScalpelConfiguration config) {
        for (String pattern : config.getFullBuildTriggers()) {
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher(GLOB_PREFIX + normalizeGlobPattern(pattern));
            for (String changedFile : changedFiles) {
                if (matcher.matches(Path.of(changedFile))) {
                    logger.info("Scalpel: Full build triggered by change to {} (matches {})", changedFile, pattern);
                    return changedFile;
                }
            }
        }
        return null;
    }

    /**
     * Returns {@code true} if the project's module path matches at least one of the
     * given include-paths matchers.
     */
    static boolean matchesIncludePaths(MavenProject project, List<PathMatcher> matchers, Path normalizedRoot) {
        String relPath = relativePath(normalizedRoot, project);
        Path modulePath = Path.of(relPath);
        for (PathMatcher matcher : matchers) {
            if (matcher.matches(modulePath) || matcher.matches(modulePath.resolve("pom.xml"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Compiles a list of glob patterns into PathMatcher instances.
     */
    static List<PathMatcher> compileGlobMatchers(List<String> patterns) {
        if (patterns.isEmpty()) {
            return List.of();
        }
        List<PathMatcher> matchers = new ArrayList<>(patterns.size());
        for (String pattern : patterns) {
            matchers.add(FileSystems.getDefault().getPathMatcher(GLOB_PREFIX + normalizeGlobPattern(pattern)));
        }
        return matchers;
    }

    /**
     * Normalizes a user-supplied glob pattern so that bare patterns (those containing no path
     * separator) match files at any depth in the repository tree.
     */
    static String normalizeGlobPattern(String pattern) {
        if (pattern.contains("/")) {
            return pattern;
        }
        return "{" + pattern + ",**/" + pattern + "}";
    }

    /**
     * Computes the relative path from the (pre-normalized) reactor root to the project's
     * base directory.  Callers must pass a root that has already been normalized via
     * {@code Path.toAbsolutePath().normalize()} — this method does NOT re-normalize the
     * root on every call (#113).
     */
    static String relativePath(Path normalizedRoot, MavenProject project) {
        return normalizedRoot
                .relativize(project.getBasedir().toPath().toAbsolutePath().normalize())
                .toString()
                .replace('\\', '/');
    }
}
