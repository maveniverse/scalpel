/*
 * Copyright (c) Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.scalpel.extension3.internal;

import static java.util.Objects.requireNonNull;

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
 * Centralises all glob-based path filtering for Scalpel: exclude paths, include paths,
 * disable triggers, and full-build triggers. All glob patterns are compiled once in the
 * constructor and reused across every evaluation site, replacing the previous approach of
 * recompiling matchers independently at each call site.
 *
 * <h3>Trim mode vs skip-tests mode</h3>
 * <p>There is one <em>intentional</em> semantic difference between how include-path filtering
 * is applied in <strong>trim mode</strong> and <strong>skip-tests mode</strong>:</p>
 * <ul>
 *   <li><strong>Trim mode</strong> ({@link #filterBuildSet}): upstream build prerequisites
 *       survive the include filter even when their path falls outside {@code includePaths},
 *       because removing them would break the build (missing compile-time dependencies).
 *       Downstream modules outside the scope <em>are</em> dropped.</li>
 *   <li><strong>Skip-tests mode</strong> ({@link #matchesIncludePaths}): every module outside
 *       {@code includePaths} has its tests skipped, with <em>no</em> exception for upstream
 *       prerequisites. The upstream module is still built (skip-tests mode never removes
 *       modules from the reactor), but its tests are not run.</li>
 * </ul>
 */
class PathFilters {

    private static final String GLOB_PREFIX = "glob:";

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final List<PathMatcher> excludeMatchers;
    private final List<PathMatcher> includeMatchers;
    private final List<String> disableTriggerPatterns;
    private final List<PathMatcher> disableTriggerMatchers;
    private final List<String> fullBuildTriggerPatterns;
    private final List<PathMatcher> fullBuildTriggerMatchers;

    PathFilters(ScalpelConfiguration config) {
        requireNonNull(config, "config");
        this.excludeMatchers = compileGlobMatchers(config.getExcludePaths());
        this.includeMatchers = compileGlobMatchers(config.getIncludePaths());
        this.disableTriggerPatterns = List.copyOf(config.getDisableTriggers());
        this.disableTriggerMatchers = compileGlobMatchers(config.getDisableTriggers());
        this.fullBuildTriggerPatterns = List.copyOf(config.getFullBuildTriggers());
        this.fullBuildTriggerMatchers = compileGlobMatchers(config.getFullBuildTriggers());
    }

    /**
     * Filters out changed files matching any {@code excludePaths} glob pattern.
     *
     * @param changedFiles the original set of changed file paths (forward-slash separated)
     * @return a new set with excluded files removed; the original set when no excludes are
     *     configured
     */
    Set<String> filterExcludedPaths(Set<String> changedFiles) {
        if (excludeMatchers.isEmpty()) {
            return changedFiles;
        }
        Set<String> filtered = new LinkedHashSet<>();
        for (String file : changedFiles) {
            Path p = Path.of(file);
            boolean excluded = false;
            for (PathMatcher matcher : excludeMatchers) {
                if (matcher.matches(p)) {
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
     * Returns {@code true} if any changed file matches a {@code disableTriggers} glob pattern.
     */
    boolean matchesDisableTrigger(Set<String> changedFiles) {
        if (disableTriggerMatchers.isEmpty()) {
            return false;
        }
        for (int i = 0; i < disableTriggerMatchers.size(); i++) {
            PathMatcher matcher = disableTriggerMatchers.get(i);
            String pattern = disableTriggerPatterns.get(i);
            for (String changedFile : changedFiles) {
                Path p = Path.of(changedFile);
                if (matcher.matches(p)) {
                    logger.info(
                            "Scalpel: Disabled due to change in {} (matches disable trigger {})", changedFile, pattern);
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Returns the first changed file matching a {@code fullBuildTriggers} glob pattern, or
     * {@code null} if none match.
     */
    String findFullBuildTrigger(Set<String> changedFiles) {
        if (fullBuildTriggerMatchers.isEmpty()) {
            return null;
        }
        for (int i = 0; i < fullBuildTriggerMatchers.size(); i++) {
            PathMatcher matcher = fullBuildTriggerMatchers.get(i);
            String pattern = fullBuildTriggerPatterns.get(i);
            for (String changedFile : changedFiles) {
                Path p = Path.of(changedFile);
                if (matcher.matches(p)) {
                    logger.info("Scalpel: Full build triggered by change to {} (matches {})", changedFile, pattern);
                    return changedFile;
                }
            }
        }
        return null;
    }

    /**
     * Returns whether any {@code includePaths} patterns are configured.
     */
    boolean hasIncludeFilters() {
        return !includeMatchers.isEmpty();
    }

    /**
     * Returns whether the given project's module path matches any {@code includePaths} glob
     * pattern. This is the <strong>strict</strong> check used in skip-tests mode: no exception
     * is made for upstream prerequisites.
     *
     * @param project the Maven project to test
     * @param reactorRoot the reactor root directory
     * @return {@code true} if the module path matches at least one include pattern
     */
    boolean matchesIncludePaths(MavenProject project, Path reactorRoot) {
        String relPath = relativePath(reactorRoot, project);
        Path modulePath = Path.of(relPath);
        for (PathMatcher matcher : includeMatchers) {
            if (matcher.matches(modulePath) || matcher.matches(modulePath.resolve("pom.xml"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Filters a build set for <strong>trim mode</strong>: removes downstream modules outside
     * {@code includePaths} scope while keeping directly/transitively affected modules and
     * upstream build prerequisites. Upstream prerequisites survive even when their path falls
     * outside the include scope, because removing them would break compilation.
     *
     * @param buildSet the full build set from {@code ReactorTrimmer}
     * @param affected the set of directly and transitively affected modules
     * @param upstreamOnly the set of modules classified as upstream-only by the trimmer
     * @param reactorRoot the reactor root directory
     * @return the filtered build set (a new mutable list); the original list when no include
     *     filters are configured
     */
    List<MavenProject> filterBuildSet(
            List<MavenProject> buildSet, Set<MavenProject> affected, Set<MavenProject> upstreamOnly, Path reactorRoot) {
        if (includeMatchers.isEmpty()) {
            return buildSet;
        }
        List<MavenProject> filtered = new ArrayList<>(buildSet);
        filtered.removeIf(project -> !affected.contains(project)
                && !upstreamOnly.contains(project)
                && !matchesIncludePaths(project, reactorRoot));
        return filtered;
    }

    // ---- internal helpers ----

    private static List<PathMatcher> compileGlobMatchers(List<String> patterns) {
        if (patterns.isEmpty()) {
            return List.of();
        }
        List<PathMatcher> matchers = new ArrayList<>(patterns.size());
        for (String pattern : patterns) {
            matchers.add(FileSystems.getDefault()
                    .getPathMatcher(GLOB_PREFIX + ChangedFileClassifier.normalizeGlobPattern(pattern)));
        }
        return matchers;
    }

    /**
     * Computes the relative path from the reactor root to the project's base directory.
     * The root must already be normalized via {@code Path.toAbsolutePath().normalize()} — this
     * method does NOT re-normalize the root on every call (#113).
     */
    private static String relativePath(Path normalizedRoot, MavenProject project) {
        return normalizedRoot
                .relativize(project.getBasedir().toPath().toAbsolutePath().normalize())
                .toString()
                .replace('\\', '/');
    }
}
