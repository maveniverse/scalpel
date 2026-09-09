/*
 * Copyright (c) Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.scalpel.extension3.internal;

import static eu.maveniverse.maven.scalpel.extension3.internal.Projects.key;
import static eu.maveniverse.maven.scalpel.extension3.internal.Projects.keys;
import static eu.maveniverse.maven.scalpel.extension3.internal.Projects.matchesDownstreamExclusion;

import eu.maveniverse.maven.scalpel.core.ScalpelConfiguration;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.apache.maven.project.MavenProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies skip-tests logic: determines which modules should have their tests skipped
 * in skip-tests mode, handles test-jar producer softening, and applies per-category args.
 */
class SkipTestsApplier {

    private static final String MAVEN_TEST_SKIP = "maven.test.skip";
    private static final String SKIP_TESTS = "skipTests";

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final ReactorTrimmer reactorTrimmer;
    private final TransitiveImpactAnalyzer transitiveImpactAnalyzer;

    SkipTestsApplier(ReactorTrimmer reactorTrimmer, TransitiveImpactAnalyzer transitiveImpactAnalyzer) {
        this.reactorTrimmer = reactorTrimmer;
        this.transitiveImpactAnalyzer = transitiveImpactAnalyzer;
    }

    void applySkipTests(
            List<MavenProject> allProjects,
            TrimResult trimResult,
            ScalpelConfiguration config,
            TransitiveImpactAnalyzer.EffectiveModels models,
            List<PathMatcher> includeMatchers,
            TransitiveImpactAnalyzer.ResolutionContext rctx) {

        List<MavenProject> testProjects = new ArrayList<>();
        List<MavenProject> skippedProjects = new ArrayList<>();

        // Directly affected modules always run tests
        classifyBuildSetProjects(trimResult, config, models, rctx, testProjects, skippedProjects);

        // Modules outside the build set
        classifyRemainingProjects(
                allProjects, trimResult, models, includeMatchers, rctx, testProjects, skippedProjects);

        // Apply per-category args
        applyPerCategoryArgs(trimResult, config);

        // Softening must have the last word on maven.test.skip/skipTests
        Set<MavenProject> softenedProjects = softenTestJarProducers(testProjects, skippedProjects);

        logger.info(
                "Scalpel: Testing {}, {} compile-only (test-jar producers), skipping tests on {} of {} modules: {}",
                testProjects.size(),
                softenedProjects.size(),
                skippedProjects.size(),
                allProjects.size(),
                keys(skippedProjects));
    }

    private void classifyBuildSetProjects(
            TrimResult trimResult,
            ScalpelConfiguration config,
            TransitiveImpactAnalyzer.EffectiveModels models,
            TransitiveImpactAnalyzer.ResolutionContext rctx,
            List<MavenProject> testProjects,
            List<MavenProject> skippedProjects) {
        for (MavenProject project : trimResult.getBuildSet()) {
            if (trimResult.getDirectlyAffected().contains(project)) {
                testProjects.add(project);
            } else if (config.isSkipTestsForUpstream()
                    && trimResult.getUpstreamOnly().contains(project)) {
                project.getProperties().setProperty(MAVEN_TEST_SKIP, "true");
                skippedProjects.add(project);
            } else if (shouldSkipTestsForExcludedDownstream(project, trimResult, config, models, rctx)) {
                project.getProperties().setProperty(MAVEN_TEST_SKIP, "true");
                skippedProjects.add(project);
                if (logger.isDebugEnabled()) {
                    logger.debug("Scalpel: Skipping tests on excluded downstream module {}", key(project));
                }
            } else {
                testProjects.add(project);
            }
        }
    }

    private void classifyRemainingProjects(
            List<MavenProject> allProjects,
            TrimResult trimResult,
            TransitiveImpactAnalyzer.EffectiveModels models,
            List<PathMatcher> includeMatchers,
            TransitiveImpactAnalyzer.ResolutionContext rctx,
            List<MavenProject> testProjects,
            List<MavenProject> skippedProjects) {
        Set<MavenProject> buildSetLookup = new LinkedHashSet<>(trimResult.getBuildSet());
        for (MavenProject project : allProjects) {
            if (buildSetLookup.contains(project)) {
                continue;
            }
            classifySingleRemainingProject(project, models, includeMatchers, rctx, testProjects, skippedProjects);
        }
    }

    private void classifySingleRemainingProject(
            MavenProject project,
            TransitiveImpactAnalyzer.EffectiveModels models,
            List<PathMatcher> includeMatchers,
            TransitiveImpactAnalyzer.ResolutionContext rctx,
            List<MavenProject> testProjects,
            List<MavenProject> skippedProjects) {
        // Skip tests on modules outside includePaths scope
        if (!includeMatchers.isEmpty()
                && !ChangedFileClassifier.matchesIncludePaths(project, includeMatchers, rctx.normalizedRoot())) {
            project.getProperties().setProperty(MAVEN_TEST_SKIP, "true");
            skippedProjects.add(project);
            return;
        }

        // Check if this module's effective plugins or dependency tree changed
        if (transitiveImpactAnalyzer.hasEffectiveModelChanges(project, models, rctx)) {
            testProjects.add(project);
            return;
        }

        // Skip tests on this project
        project.getProperties().setProperty(MAVEN_TEST_SKIP, "true");
        skippedProjects.add(project);
    }

    /**
     * Modules slated for a full test skip also skip test-compile, which suppresses their
     * test-jar. If some other module depends on that test-jar, soften the producer:
     * drop maven.test.skip and use skipTests=true instead.
     */
    Set<MavenProject> softenTestJarProducers(List<MavenProject> testProjects, List<MavenProject> skippedProjects) {
        Set<MavenProject> compilingTests = new LinkedHashSet<>(testProjects);
        Set<MavenProject> softenedProjects = new LinkedHashSet<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (MavenProject candidate : skippedProjects) {
                if (softenedProjects.contains(candidate)) {
                    continue;
                }
                if (trySoftenCandidate(candidate, compilingTests, softenedProjects)) {
                    changed = true;
                }
            }
        }
        skippedProjects.removeAll(softenedProjects);
        if (!softenedProjects.isEmpty()) {
            logger.info(
                    "Scalpel: {} modules had test-compile restored for in-reactor test-jar consumers: {}",
                    softenedProjects.size(),
                    keys(softenedProjects));
        }
        return softenedProjects;
    }

    private boolean trySoftenCandidate(
            MavenProject candidate, Set<MavenProject> compilingTests, Set<MavenProject> softenedProjects) {
        for (MavenProject consumer : new ArrayList<>(compilingTests)) {
            if (reactorTrimmer.hasTestJarDependency(consumer, candidate)) {
                candidate.getProperties().remove(MAVEN_TEST_SKIP);
                candidate.getProperties().setProperty(SKIP_TESTS, "true");
                softenedProjects.add(candidate);
                compilingTests.add(candidate);
                if (logger.isDebugEnabled()) {
                    logger.debug(
                            "Scalpel: Keeping test-compile for {} because its test-jar is consumed"
                                    + " in-reactor by {} (softened: skipTests=true instead of"
                                    + " maven.test.skip=true)",
                            key(candidate),
                            key(consumer));
                }
                return true;
            }
        }
        return false;
    }

    boolean shouldSkipTestsForExcludedDownstream(
            MavenProject project,
            TrimResult trimResult,
            ScalpelConfiguration config,
            TransitiveImpactAnalyzer.EffectiveModels models,
            TransitiveImpactAnalyzer.ResolutionContext rctx) {
        if (config.getSkipTestsForDownstreamModules().isEmpty()) {
            return false;
        }
        if (!trimResult.getDownstreamOnly().contains(project)
                && !trimResult.getDownstreamTestOnly().contains(project)) {
            return false;
        }
        if (!matchesDownstreamExclusion(project, config.getSkipTestsForDownstreamModules())) {
            return false;
        }
        // Safety guard: don't skip tests if the module has effective model changes
        return !transitiveImpactAnalyzer.hasEffectiveModelChanges(project, models, rctx);
    }

    void applyPerCategoryArgs(TrimResult trimResult, ScalpelConfiguration config) {
        applyArgsToProjects(config.getUpstreamArgs(), trimResult.getUpstreamOnly());
        applyDownstreamArgs(config.getDownstreamArgs(), trimResult);
    }

    private void applyArgsToProjects(List<String> args, Set<MavenProject> projects) {
        for (String arg : args) {
            String[] parts = arg.split("=", 2);
            if (parts.length == 2) {
                for (MavenProject project : projects) {
                    project.getProperties().setProperty(parts[0], parts[1]);
                }
            } else {
                logger.warn("Scalpel: Malformed upstreamArgs entry '{}', expected key=value format", arg);
            }
        }
    }

    private void applyDownstreamArgs(List<String> args, TrimResult trimResult) {
        for (String arg : args) {
            String[] parts = arg.split("=", 2);
            if (parts.length == 2) {
                for (MavenProject project : trimResult.getDownstreamOnly()) {
                    project.getProperties().setProperty(parts[0], parts[1]);
                }
                for (MavenProject project : trimResult.getDownstreamTestOnly()) {
                    project.getProperties().setProperty(parts[0], parts[1]);
                }
            } else {
                logger.warn("Scalpel: Malformed downstreamArgs entry '{}', expected key=value format", arg);
            }
        }
    }

    void skipTestsOnAll(List<MavenProject> projects) {
        for (MavenProject project : projects) {
            project.getProperties().setProperty(MAVEN_TEST_SKIP, "true");
        }
    }
}
