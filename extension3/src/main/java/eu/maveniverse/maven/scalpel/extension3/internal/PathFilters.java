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
 * Owns compilation and evaluation of every path-pattern family Scalpel applies (includePaths,
 * excludePaths, disableTriggers, fullBuildTriggers), constructed once per build from the resolved
 * configuration and passed down to every consumer (#124). Before this type existed the include
 * matchers were re-derived at four sites with subtly different rules and the trigger families
 * compiled their matchers inline inside per-file loops.
 *
 * <p>The one intentional semantic difference between consumers is expressed at the call sites,
 * not hidden in the matching rules: in {@code trim} mode an upstream build prerequisite outside
 * the includePaths scope survives in the build set (the reactor would not compile without it),
 * while in {@code skip-tests} and report mode a module outside the scope has its tests skipped
 * or is omitted, respectively. {@link #matchesModuleInclude(MavenProject, Path)} is deliberately
 * neutral about that; each site states its own survival rule.
 *
 * <p>Changed-file batches are converted to {@link Path} once per family evaluation, not once per
 * pattern per file.
 */
final class PathFilters {

    private static final Logger logger = LoggerFactory.getLogger(PathFilters.class);

    private static final String GLOB_PREFIX = "glob:";

    private final List<PathMatcher> includeMatchers;
    private final List<CompiledPattern> disableTriggers;
    private final List<CompiledPattern> fullBuildTriggers;
    private final List<PathMatcher> excludeMatchers;

    PathFilters(
            List<String> includePatterns,
            List<String> excludePatterns,
            List<String> disableTriggerPatterns,
            List<String> fullBuildTriggerPatterns) {
        this.includeMatchers = compileGlobs(includePatterns);
        this.excludeMatchers = compileGlobs(excludePatterns);
        this.disableTriggers = compileNamed(disableTriggerPatterns);
        this.fullBuildTriggers = compileNamed(fullBuildTriggerPatterns);
    }

    static PathFilters from(ScalpelConfiguration config) {
        return new PathFilters(
                config.getIncludePaths(),
                config.getExcludePaths(),
                config.getDisableTriggers(),
                config.getFullBuildTriggers());
    }

    // ------------------------------------------------------------------
    // includePaths: module-scoped
    // ------------------------------------------------------------------

    boolean hasIncludePatterns() {
        return !includeMatchers.isEmpty();
    }

    /**
     * Whether the module is inside the includePaths scope. A module matches when its directory
     * path matches a pattern directly (e.g. pattern {@code module-a} matches module
     * {@code module-a}) or when a file inside it would match (e.g. pattern {@code module-a/**}
     * matches {@code module-a} via its {@code pom.xml}).
     */
    boolean matchesModuleInclude(MavenProject project, Path reactorRoot) {
        String relPath = ScalpelLifecycleParticipant.relativePath(reactorRoot, project);
        Path modulePath = Path.of(relPath);
        for (PathMatcher matcher : includeMatchers) {
            if (matcher.matches(modulePath) || matcher.matches(modulePath.resolve("pom.xml"))) {
                return true;
            }
        }
        return false;
    }

    /** Convenience for filtering collections: true when the module is OUTSIDE the scope. */
    boolean outsideModuleInclude(MavenProject project, Path reactorRoot) {
        return !matchesModuleInclude(project, reactorRoot);
    }

    // ------------------------------------------------------------------
    // excludePaths / disableTriggers / fullBuildTriggers: file-scoped
    // ------------------------------------------------------------------

    /** Removes changed files matching excludePaths, preserving iteration order. */
    Set<String> filterExcluded(Set<String> changedFiles) {
        if (excludeMatchers.isEmpty()) {
            return changedFiles;
        }
        int before = changedFiles.size();
        Set<String> filtered = new LinkedHashSet<>();
        for (String file : changedFiles) {
            boolean excluded = false;
            Path path = Path.of(file);
            for (PathMatcher matcher : excludeMatchers) {
                if (matcher.matches(path)) {
                    excluded = true;
                    break;
                }
            }
            if (!excluded) {
                filtered.add(file);
            }
        }
        int excludedCount = before - filtered.size();
        if (excludedCount > 0) {
            logger.info("Scalpel: {} files excluded by path filters", excludedCount);
        }
        return filtered;
    }

    /**
     * Returns the first changed file matching a disableTrigger pattern (Scalpel turns itself
     * off entirely), or null. Files are converted to {@link Path} once for the whole batch.
     */
    String findDisableTrigger(Set<String> changedFiles) {
        return findTrigger(changedFiles, disableTriggers, "Disabled due to change in {} (matches disable trigger {})");
    }

    /**
     * Returns the first changed file matching a fullBuildTrigger pattern (the run falls back to
     * a full build), or null.
     */
    String findFullBuildTrigger(Set<String> changedFiles) {
        return findTrigger(changedFiles, fullBuildTriggers, "Full build triggered by change to {} (matches {})");
    }

    private String findTrigger(Set<String> changedFiles, List<CompiledPattern> triggers, String logFormat) {
        if (triggers.isEmpty()) {
            return null;
        }
        List<Path> paths = new ArrayList<>(changedFiles.size());
        for (String file : changedFiles) {
            paths.add(Path.of(file));
        }
        int i = 0;
        for (String file : changedFiles) {
            Path path = paths.get(i++);
            for (CompiledPattern trigger : triggers) {
                if (trigger.matcher().matches(path)) {
                    logger.info(logFormat, file, trigger.pattern());
                    return file;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Compilation
    // ------------------------------------------------------------------

    private static List<PathMatcher> compileGlobs(List<String> patterns) {
        if (patterns.isEmpty()) {
            return List.of();
        }
        List<PathMatcher> matchers = new ArrayList<>(patterns.size());
        for (String pattern : patterns) {
            matchers.add(FileSystems.getDefault()
                    .getPathMatcher(GLOB_PREFIX + ScalpelLifecycleParticipant.normalizeGlobPattern(pattern)));
        }
        return java.util.List.copyOf(matchers);
    }

    private static List<CompiledPattern> compileNamed(List<String> patterns) {
        if (patterns.isEmpty()) {
            return List.of();
        }
        List<CompiledPattern> compiled = new ArrayList<>(patterns.size());
        for (String pattern : patterns) {
            compiled.add(new CompiledPattern(
                    pattern,
                    FileSystems.getDefault()
                            .getPathMatcher(GLOB_PREFIX + ScalpelLifecycleParticipant.normalizeGlobPattern(pattern))));
        }
        return java.util.List.copyOf(compiled);
    }

    private record CompiledPattern(String pattern, PathMatcher matcher) {}
}
