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
import static java.util.Objects.requireNonNull;

import eu.maveniverse.maven.scalpel.core.BoundedRegexMatcher;
import eu.maveniverse.maven.scalpel.core.ChangeDetectionResult;
import eu.maveniverse.maven.scalpel.core.ScalpelConfiguration;
import eu.maveniverse.maven.scalpel.core.ScalpelCore;
import eu.maveniverse.maven.scalpel.core.ScalpelException;
import eu.maveniverse.maven.scalpel.core.ScalpelReport;
import eu.maveniverse.maven.scalpel.core.Timings;
import eu.maveniverse.maven.scalpel.core.Version;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Model;
import org.apache.maven.project.DefaultDependencyResolutionRequest;
import org.apache.maven.project.DependencyResolutionException;
import org.apache.maven.project.DependencyResolutionResult;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectDependenciesResolver;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyFilter;
import org.eclipse.aether.graph.DependencyNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
@Named
class ScalpelLifecycleParticipant extends AbstractMavenLifecycleParticipant {

    private static final String MAVEN_TEST_SKIP = "maven.test.skip";
    private static final String SKIP_TESTS = "skipTests";
    private static final String GLOB_PREFIX = "glob:";
    private static final String UNRESOLVED_GA = "(unresolved)";

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final ScalpelCore scalpelCore;
    private final ModuleMapper moduleMapper;
    private final PomChangeAnalyzer pomChangeAnalyzer;
    private final ReactorTrimmer reactorTrimmer;
    private final ProjectDependenciesResolver dependenciesResolver;
    private final BoundedRegexMatcher regexMatcher = new BoundedRegexMatcher();

    @Inject
    public ScalpelLifecycleParticipant(
            ScalpelCore scalpelCore,
            ModuleMapper moduleMapper,
            PomChangeAnalyzer pomChangeAnalyzer,
            ReactorTrimmer reactorTrimmer,
            ProjectDependenciesResolver dependenciesResolver) {
        this.scalpelCore = requireNonNull(scalpelCore, "scalpelCore");
        this.moduleMapper = requireNonNull(moduleMapper, "moduleMapper");
        this.pomChangeAnalyzer = requireNonNull(pomChangeAnalyzer, "pomChangeAnalyzer");
        this.reactorTrimmer = requireNonNull(reactorTrimmer, "reactorTrimmer");
        this.dependenciesResolver = requireNonNull(dependenciesResolver, "dependenciesResolver");
    }

    @Override
    public void afterProjectsRead(MavenSession session) throws MavenExecutionException {
        ScalpelConfiguration config;
        try {
            config = ScalpelConfiguration.fromProperties(session.getSystemProperties(), session.getUserProperties());
        } catch (Exception e) {
            // Default for failSafe is true; if config parsing fails we cannot check the flag,
            // so default to the safe behaviour: warn and let the build proceed normally.
            logger.warn("Scalpel: Error parsing configuration, building all modules: {}", e.getMessage());
            logger.debug("Configuration parsing error details", e);
            return;
        }

        for (String warning : config.getWarnings()) {
            logger.warn("Scalpel: {}", warning);
        }

        String version = Version.version();

        if (!config.isEnabled()) {
            logger.info("Scalpel {} is disabled", version);
            return;
        }

        // Check if -pl is active and disableOnSelectedProjects is set
        if (config.isDisableOnSelectedProjects()) {
            List<String> selectedProjects = session.getRequest().getSelectedProjects();
            if (selectedProjects != null && !selectedProjects.isEmpty()) {
                logger.info("Scalpel {} disabled due to -pl project selection", version);
                return;
            }
        }

        logger.info("Scalpel {} activated (mode={})", version, config.getMode());
        logger.debug("Configuration: {}", config);

        Path reactorRoot = session.getRequest().getMultiModuleProjectDirectory().toPath();
        List<MavenProject> allProjects = session.getProjects();

        Timings timings = new Timings();
        long analysisStartNano = System.nanoTime();
        try {
            // Collect ALL reactor POM paths
            Set<String> allPomPaths = new LinkedHashSet<>();
            for (MavenProject project : allProjects) {
                Path pomPath = project.getFile().toPath().toAbsolutePath().normalize();
                Path relativePom = reactorRoot.toAbsolutePath().normalize().relativize(pomPath);
                allPomPaths.add(relativePom.toString().replace('\\', '/'));
            }

            // Detect changes
            ChangeDetectionResult result = scalpelCore.detectChanges(reactorRoot, config, allPomPaths, timings);
            if (result == null) {
                if (config.isPassiveMode()) {
                    String skipReason = scalpelCore.getLastDetectionSkipReason();
                    if (skipReason != null) {
                        writeStatusReport(config, reactorRoot, "skipped", skipReason);
                    } else {
                        writeStatusReport(
                                config, reactorRoot, "failed", "change detection did not run (see build log)");
                    }
                }
                return;
            }

            Set<String> changedFiles = result.getChangedFiles();
            if (changedFiles.isEmpty()) {
                if (config.isBuildAllIfNoChanges()) {
                    logger.info("Scalpel: No changes detected, building all modules (buildAllIfNoChanges=true)");
                }
                if (config.isPassiveMode()) {
                    writeStatusReport(config, reactorRoot, "skipped", "no changes detected");
                }
                return;
            }

            logger.info("Scalpel: {} changed files detected", changedFiles.size());

            // Check disable triggers (bail out entirely if any changed file matches)
            if (matchesDisableTrigger(changedFiles, config)) {
                if (config.isPassiveMode()) {
                    writeStatusReport(config, reactorRoot, "skipped", "disabled by disableTriggers match");
                }
                return;
            }

            // Filter out excluded paths
            changedFiles = filterExcludedPaths(changedFiles, config);
            if (changedFiles.isEmpty()) {
                logger.info("Scalpel: All changed files excluded by path filters, building all modules");
                if (config.isPassiveMode()) {
                    writeStatusReport(config, reactorRoot, "skipped", "all changed files excluded by path filters");
                }
                return;
            }

            // Check full build triggers
            String triggerFile = findFullBuildTrigger(changedFiles, config);
            if (triggerFile != null) {
                if (config.isPassiveMode()) {
                    writeFullBuildReport(config, reactorRoot, triggerFile, changedFiles);
                }
                return;
            }

            // Separate POM changes from source changes
            // .mvn/ files are Maven build infrastructure (extensions.xml, maven.config, jvm.config)
            // and should not be mapped to the root module as source changes — doing so would
            // make the root DIRECT and cascade every reactor module as DOWNSTREAM.
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

            // Map source changes to modules (classifying test-only vs main changes)
            ModuleMapper.Result sourceResult;
            timings.start(Timings.PHASE_MODULE_MAPPING);
            try {
                sourceResult = moduleMapper.mapToProjectsClassified(
                        sourceChanges, allProjects, reactorRoot, config.isExplain());
            } finally {
                timings.stop(Timings.PHASE_MODULE_MAPPING);
            }
            Set<MavenProject> affectedBySource = sourceResult.getAllAffected();
            logger.debug("Modules affected by source changes: {}", keys(affectedBySource));
            if (!sourceResult.getTestOnlyAffected().isEmpty()) {
                logger.debug(
                        "Test-only modules (no downstream propagation): {}", keys(sourceResult.getTestOnlyAffected()));
            }

            // Analyze POM changes directly (no model building needed)
            Set<MavenProject> affectedByPom = new LinkedHashSet<>();
            Map<String, Model> oldEffectiveModels = Map.of();
            Map<String, Model> newEffectiveModels = Map.of();
            Set<String> changedProperties = new LinkedHashSet<>();
            Map<MavenProject, Set<String>> pomEvidence = Map.of();
            Set<String> unmatchedPomPaths = new LinkedHashSet<>();
            if (!pomChanges.isEmpty()) {
                logger.debug("POM changes detected: {}", pomChanges);
                try {
                    PomChangeAnalyzer.Result pomResult;
                    timings.start(Timings.PHASE_POM_ANALYSIS);
                    try {
                        pomResult = pomChangeAnalyzer.analyzeChanges(
                                pomChanges,
                                result.getOldPomContents(),
                                allProjects,
                                reactorRoot,
                                config.isExplain(),
                                new PomChangeAnalyzer.ModelResolutionContext(
                                        session.getSystemProperties(),
                                        session.getUserProperties(),
                                        session.getRepositorySession(),
                                        allProjects.get(0).getRemoteProjectRepositories()));
                    } finally {
                        timings.stop(Timings.PHASE_POM_ANALYSIS);
                    }
                    affectedByPom = pomResult.getAffectedProjects();
                    oldEffectiveModels = pomResult.getOldEffectiveModels();
                    newEffectiveModels = pomResult.getNewEffectiveModels();
                    changedProperties = pomResult.getChangedProperties();
                    pomEvidence = pomResult.getEvidence();
                    unmatchedPomPaths.addAll(pomResult.getUnmatchedPomPaths());
                    timings.increment(
                            Timings.OP_EFFECTIVE_MODELS, oldEffectiveModels.size() + newEffectiveModels.size());
                    timings.increment(Timings.OP_RESOURCES_VISITED, pomResult.getResourcesVisited());
                } catch (Exception e) {
                    if (config.isFailSafe()) {
                        logger.warn("Scalpel: Error analyzing POM changes, building all modules: {}", e.getMessage());
                        logger.debug("POM analysis error details", e);
                        if (config.isPassiveMode()) {
                            writeFailedStatusReport(config, reactorRoot, "error analyzing POM changes");
                        }
                        return;
                    } else {
                        throw new MavenExecutionException("Scalpel: Error analyzing POM changes", e);
                    }
                }
                logger.debug("Modules affected by POM changes: {}", keys(affectedByPom));
            }

            // Derive changed managed dep/plugin GAs from effective models (for report only)
            Set<String> changedManagedDepGAs = deriveChangedManagedDeps(oldEffectiveModels, newEffectiveModels);
            Set<String> changedManagedPluginGAs = deriveChangedManagedPlugins(oldEffectiveModels, newEffectiveModels);

            // Combine
            Set<MavenProject> directlyAffected = new LinkedHashSet<>();
            directlyAffected.addAll(affectedBySource);
            directlyAffected.addAll(affectedByPom);

            // Compute test-only modules for downstream propagation control
            // Modules with only test source changes don't propagate downstream
            // (unless downstream depends on test-jar). Modules also affected by POM
            // changes are NOT test-only since POM changes can affect production builds.
            Set<MavenProject> testOnlyModules = new LinkedHashSet<>(sourceResult.getTestOnlyAffected());
            testOnlyModules.removeAll(affectedByPom);

            // Force-include modules matching forceBuildModules patterns
            Set<MavenProject> forceIncluded = new LinkedHashSet<>();
            Map<MavenProject, String> forceBuildPatterns = new LinkedHashMap<>();
            if (!config.getForceBuildModules().isEmpty()) {
                for (MavenProject project : allProjects) {
                    if (directlyAffected.contains(project)) {
                        continue;
                    }
                    String pattern = matchesForceBuild(project, config.getForceBuildModules());
                    if (pattern != null) {
                        directlyAffected.add(project);
                        forceIncluded.add(project);
                        forceBuildPatterns.put(project, pattern);
                    }
                }
            }

            // Force-included modules are not test-only
            testOnlyModules.removeAll(forceIncluded);

            if (directlyAffected.isEmpty() && oldEffectiveModels.isEmpty()) {
                logger.info("Scalpel: No modules affected by changes");
                if (config.isPassiveMode()) {
                    writeReport(
                            config,
                            reactorRoot,
                            allProjects,
                            AnalysisContext.empty(
                                    changedFiles,
                                    changedProperties,
                                    changedManagedDepGAs,
                                    changedManagedPluginGAs,
                                    unmatchedPomPaths),
                            timings,
                            analysisStartNano);
                } else if (config.isModeSkipTests()) {
                    skipTestsOnAll(allProjects);
                }
                return;
            }

            if (!directlyAffected.isEmpty()) {
                logger.info(
                        "Scalpel: {} modules directly affected: {}", directlyAffected.size(), keys(directlyAffected));
            }

            // Shared caches for dependency collection results — used by both
            // computeTransitivelyAffected and applySkipTests to avoid resolving the same
            // module twice.  Separate caches for old (from effective models) and new (current).
            Map<MavenProject, DependencyResolutionResult> collectCache = new LinkedHashMap<>();
            Map<MavenProject, DependencyResolutionResult> oldCollectCache = new LinkedHashMap<>();

            // Compute transitively affected modules by comparing old vs new dependency trees
            Map<MavenProject, List<String>> transitiveEvidence = new LinkedHashMap<>();
            Map<MavenProject, List<String>> transitivelyAffected;
            timings.start(Timings.PHASE_TRANSITIVE_RESOLVE);
            try {
                transitivelyAffected = computeTransitivelyAffected(
                        allProjects,
                        directlyAffected,
                        oldEffectiveModels,
                        newEffectiveModels,
                        reactorRoot,
                        session,
                        collectCache,
                        oldCollectCache,
                        timings,
                        config.isExplain(),
                        transitiveEvidence);
            } finally {
                timings.stop(Timings.PHASE_TRANSITIVE_RESOLVE);
            }

            // Explain-mode evidence: which specific input triggered each module
            Map<MavenProject, List<String>> evidence = config.isExplain()
                    ? buildEvidence(
                            sourceResult.getTriggeringFiles(),
                            pomEvidence,
                            transitiveEvidence,
                            forceIncluded,
                            forceBuildPatterns)
                    : Map.of();

            if (directlyAffected.isEmpty() && transitivelyAffected.isEmpty()) {
                logger.info("Scalpel: No modules affected by changes");
                if (config.isPassiveMode()) {
                    writeReport(
                            config,
                            reactorRoot,
                            allProjects,
                            AnalysisContext.empty(
                                    changedFiles,
                                    changedProperties,
                                    changedManagedDepGAs,
                                    changedManagedPluginGAs,
                                    unmatchedPomPaths),
                            timings,
                            analysisStartNano);
                } else if (config.isModeSkipTests()) {
                    skipTestsOnAll(allProjects);
                }
                return;
            }

            // Include transitively affected modules (from managed dep/plugin changes) in the affected set
            Set<MavenProject> allAffected = new LinkedHashSet<>(directlyAffected);
            allAffected.addAll(transitivelyAffected.keySet());

            // Apply includePaths module filter: restrict affected modules by path while
            // keeping full diff visibility for change detection and POM analysis above.
            // The matchers are compiled once here and reused later in trim/report/skip-tests
            // mode to also filter downstream expansions and managed-dep re-enabling.
            List<PathMatcher> includeMatchers = compileGlobMatchers(config.getIncludePaths());
            if (!includeMatchers.isEmpty()) {
                int beforeCount = allAffected.size();
                directlyAffected.removeIf(p -> !matchesIncludePaths(p, includeMatchers, reactorRoot));
                transitivelyAffected.keySet().removeIf(p -> !matchesIncludePaths(p, includeMatchers, reactorRoot));
                testOnlyModules.retainAll(directlyAffected);
                forceIncluded.retainAll(directlyAffected);

                allAffected = new LinkedHashSet<>(directlyAffected);
                allAffected.addAll(transitivelyAffected.keySet());

                int removedCount = beforeCount - allAffected.size();
                if (removedCount > 0) {
                    logger.info("Scalpel: {} modules excluded by includePaths filters", removedCount);
                }

                if (allAffected.isEmpty()) {
                    logger.info("Scalpel: No modules match includePaths filters");
                    if (config.isPassiveMode()) {
                        writeReport(
                                config,
                                reactorRoot,
                                allProjects,
                                AnalysisContext.empty(
                                        changedFiles,
                                        changedProperties,
                                        changedManagedDepGAs,
                                        changedManagedPluginGAs,
                                        unmatchedPomPaths),
                                timings,
                                analysisStartNano);
                    } else if (config.isModeSkipTests()) {
                        skipTestsOnAll(allProjects);
                    }
                    return;
                }
            }

            // Write impacted module log if configured
            if (config.getImpactedLog() != null) {
                writeImpactedLog(config, reactorRoot, allAffected);
            }

            if (config.isPassiveMode()) {
                // Compute upstream/downstream categorization for report enrichment
                // Use directlyAffected here (not allAffected) to preserve correct DOWNSTREAM categorization;
                // transitively affected modules are added to the report separately via addTransitivelyAffectedModules
                TrimResult trimResult = null;
                if (!directlyAffected.isEmpty()) {
                    timings.start(Timings.PHASE_TRIM);
                    try {
                        trimResult = reactorTrimmer.computeBuildSet(
                                directlyAffected, testOnlyModules, session.getProjectDependencyGraph(), config);
                    } finally {
                        timings.stop(Timings.PHASE_TRIM);
                    }
                }
                if (trimResult != null && config.isExplain()) {
                    mergeTrimReasons(evidence, trimResult);
                }

                writeReport(
                        config,
                        reactorRoot,
                        allProjects,
                        AnalysisContext.builder(
                                        changedFiles, changedProperties, changedManagedDepGAs, changedManagedPluginGAs)
                                .unmatchedPomPaths(unmatchedPomPaths)
                                .directlyAffected(directlyAffected)
                                .affectedBySource(affectedBySource)
                                .testOnlyBySource(sourceResult.getTestOnlyAffected())
                                .affectedByPom(affectedByPom)
                                .forceIncluded(forceIncluded)
                                .transitivelyAffected(transitivelyAffected)
                                .evidence(evidence)
                                .trimResult(trimResult)
                                .build(),
                        timings,
                        analysisStartNano);
                if (config.isExplain()) {
                    Set<MavenProject> reportedModules = new LinkedHashSet<>(allAffected);
                    if (trimResult != null) {
                        reportedModules.addAll(trimResult.getDownstreamOnly());
                        reportedModules.addAll(trimResult.getDownstreamTestOnly());
                    }
                    logExplainDecisions(allProjects, reportedModules, evidence);
                }
                if (config.isModeShadow()) {
                    // Shadow mode (#92): hand the would-be trim decision to a monitor wrapped
                    // around the session's ExecutionListener; the full build below runs
                    // unmodified while per-module wall-clock and failures are recorded, and at
                    // session end the join is written to target/scalpel-shadow.json plus one
                    // JSONL history line. The decision uses the same ReactorTrimmer call trim
                    // mode runs on the same inputs, so shadow and trim decisions agree by
                    // construction; when nothing is transitively affected, the report branch
                    // above already computed that exact set.
                    TrimResult decision;
                    if (trimResult != null && allAffected.equals(directlyAffected)) {
                        decision = trimResult;
                    } else {
                        timings.start(Timings.PHASE_TRIM);
                        try {
                            decision = reactorTrimmer.computeBuildSet(
                                    allAffected, testOnlyModules, session.getProjectDependencyGraph(), config);
                        } finally {
                            timings.stop(Timings.PHASE_TRIM);
                        }
                    }
                    Set<String> wouldHaveBuilt = new LinkedHashSet<>();
                    for (MavenProject project : decision.getBuildSet()) {
                        wouldHaveBuilt.add(relativePath(reactorRoot, project));
                    }
                    Set<String> wouldHaveSkipped = new LinkedHashSet<>();
                    for (MavenProject project : allProjects) {
                        String path = relativePath(reactorRoot, project);
                        if (!wouldHaveBuilt.contains(path)) {
                            wouldHaveSkipped.add(path);
                        }
                    }
                    session.getRequest()
                            .setExecutionListener(new ShadowBuildMonitor(
                                    session.getRequest().getExecutionListener(),
                                    reactorRoot,
                                    wouldHaveBuilt,
                                    wouldHaveSkipped,
                                    Version.version(),
                                    config.getBaseBranch(),
                                    changedFiles,
                                    System::nanoTime,
                                    project -> relativePath(reactorRoot, project)));
                    logger.info(
                            "Scalpel: Shadow mode observing the full build: would build {} of {} modules, would skip {}",
                            wouldHaveBuilt.size(),
                            allProjects.size(),
                            wouldHaveSkipped.size());
                }
                return;
            }

            // Compute full build set with upstream/downstream
            TrimResult trimResult;
            timings.start(Timings.PHASE_TRIM);
            try {
                trimResult = reactorTrimmer.computeBuildSet(
                        allAffected, testOnlyModules, session.getProjectDependencyGraph(), config);
            } finally {
                timings.stop(Timings.PHASE_TRIM);
            }
            if (config.isExplain()) {
                mergeTrimReasons(evidence, trimResult);
            }

            if (config.isModeSkipTests()) {
                timings.start(Timings.PHASE_APPLY_SKIP_TESTS);
                try {
                    applySkipTests(
                            session,
                            allProjects,
                            trimResult,
                            config,
                            oldEffectiveModels,
                            newEffectiveModels,
                            includeMatchers,
                            reactorRoot,
                            collectCache,
                            oldCollectCache,
                            timings);
                } finally {
                    timings.stop(Timings.PHASE_APPLY_SKIP_TESTS);
                }
                if (config.isExplain()) {
                    logExplainDecisions(allProjects, new LinkedHashSet<>(trimResult.getBuildSet()), evidence);
                }
            } else {
                // trim mode: remove unaffected projects from reactor
                List<MavenProject> buildSet = trimResult.getBuildSet();
                if (!includeMatchers.isEmpty()) {
                    // Filter downstream modules outside includePaths scope while keeping
                    // upstream build prerequisites and directly/transitively affected modules
                    Set<MavenProject> finalAllAffected = allAffected;
                    buildSet = new ArrayList<>(buildSet);
                    buildSet.removeIf(p -> !finalAllAffected.contains(p)
                            && !trimResult.getUpstreamOnly().contains(p)
                            && !matchesIncludePaths(p, includeMatchers, reactorRoot));
                }
                logger.info(
                        "Scalpel: Building {} of {} modules: {}", buildSet.size(), allProjects.size(), keys(buildSet));
                session.setProjects(buildSet);
                // Apply per-category args in trim mode
                applyPerCategoryArgs(trimResult, config);
                if (config.isExplain()) {
                    logExplainDecisions(allProjects, new LinkedHashSet<>(buildSet), evidence);
                }
                // Write the same JSON report as report mode, so the skipped set (allProjects
                // minus buildSet) is reviewable alongside the green trimmed build (#91)
                writeReport(
                        config,
                        reactorRoot,
                        allProjects,
                        AnalysisContext.builder(
                                        changedFiles, changedProperties, changedManagedDepGAs, changedManagedPluginGAs)
                                .unmatchedPomPaths(unmatchedPomPaths)
                                .directlyAffected(directlyAffected)
                                .affectedBySource(affectedBySource)
                                .testOnlyBySource(sourceResult.getTestOnlyAffected())
                                .affectedByPom(affectedByPom)
                                .forceIncluded(forceIncluded)
                                .transitivelyAffected(transitivelyAffected)
                                .trimResult(trimResult)
                                .filteredBuildSet(buildSet)
                                .build(),
                        timings,
                        analysisStartNano);
            }

        } catch (ScalpelException e) {
            if (config.isFailSafe()) {
                logger.warn("Scalpel: {}, building all modules", e.getMessage());
                logger.debug("ScalpelException details", e);
                return;
            }
            throw new MavenExecutionException("Scalpel: " + e.getMessage(), e);
        } catch (Exception e) {
            if (config.isFailSafe()) {
                logger.warn("Scalpel: Unexpected error, building all modules: {}", e.getMessage());
                logger.debug("Unexpected error details", e);
                if (config.isPassiveMode()) {
                    writeFailedStatusReport(config, reactorRoot, "unexpected error: " + e.getMessage());
                }
                return;
            }
            throw new MavenExecutionException("Scalpel: " + e.getMessage(), e);
        } finally {
            logAnalysisSummary(timings, analysisStartNano);
        }
    }

    /**
     * One greppable INFO line answering "how long did Scalpel take and where did it go":
     * total wall-clock millis, the per-phase breakdown, and the operation counters (#99).
     */
    private void logAnalysisSummary(Timings timings, long analysisStartNano) {
        StringBuilder line = new StringBuilder("Scalpel: analysis took ")
                .append(millisSince(analysisStartNano))
                .append("ms");
        String phases = timings.toString();
        if (!phases.isEmpty()) {
            line.append(" (").append(phases).append(")");
        }
        String operations = timings.formatOperations();
        if (!operations.isEmpty()) {
            line.append(" ops: ").append(operations);
        }
        logger.info("{}", line);
    }

    private static long millisSince(long startNano) {
        return (System.nanoTime() - startNano) / 1_000_000;
    }

    private boolean matchesDisableTrigger(Set<String> changedFiles, ScalpelConfiguration config) {
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

    private static List<PathMatcher> compileGlobMatchers(List<String> patterns) {
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
     * separator) match files at any depth in the repository tree. For example, {@code *.md} is
     * rewritten to {@code {*.md,**&#47;*.md}} so that it matches both {@code README.md} (root)
     * and {@code docs/guide.md} (nested). A plain {@code **&#47;} prefix alone would not match
     * root-level files on the default {@link java.nio.file.FileSystem} because the path separator
     * in the pattern is required to be present in the matched path. Patterns that already contain
     * a {@code /} are returned unchanged because the user explicitly specified the directory
     * structure.
     */
    static String normalizeGlobPattern(String pattern) {
        if (pattern.contains("/")) {
            return pattern;
        }
        return "{" + pattern + ",**/" + pattern + "}";
    }

    private Set<String> filterExcludedPaths(Set<String> changedFiles, ScalpelConfiguration config) {
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

    private boolean matchesIncludePaths(MavenProject project, List<PathMatcher> matchers, Path reactorRoot) {
        String relPath = relativePath(reactorRoot, project);
        Path modulePath = Path.of(relPath);
        for (PathMatcher matcher : matchers) {
            // Match the module path directly (e.g., "module-a" matches pattern "module-a")
            // or match a file within the module (e.g., "module-a/pom.xml" matches pattern "module-a/**")
            if (matcher.matches(modulePath) || matcher.matches(modulePath.resolve("pom.xml"))) {
                return true;
            }
        }
        return false;
    }

    private String findFullBuildTrigger(Set<String> changedFiles, ScalpelConfiguration config) {
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
     * Determine which non-directly-affected modules are <em>transitively</em> affected
     * by changes in the reactor.  Uses a two-pass algorithm:
     *
     * <h4>Pass 1 — Effective model and dependency tree comparison</h4>
     * For each non-directly-affected module, compare old vs new effective models:
     * <ul>
     *   <li><b>Effective plugins:</b> diff plugin versions from the fully-interpolated
     *       models.  A managed plugin version bump that flows into this module's effective
     *       plugin list triggers {@code MANAGED_PLUGIN}.</li>
     *   <li><b>Resolved dependency tree:</b> resolve the module's dependency tree twice —
     *       once from the old effective model, once from the current project — and diff the
     *       (GA → version) maps.  A version difference triggers {@code TRANSITIVE_DEPENDENCY}
     *       or {@code TRANSITIVE_DEPENDENCY_TEST} depending on scope.</li>
     * </ul>
     *
     * <h4>Pass 2 — Reactor dependency propagation</h4>
     * Pass 1 misses modules whose dependency tree changed only <em>transitively through
     * reactor siblings</em>.  When resolving "old" dependency trees, Maven's reactor always
     * provides the current (new) version of reactor siblings, so a module that depends on an
     * affected reactor module will have identical old/new trees.
     * <p>
     * To catch this, pass 2 collects the GAs of all affected modules (directly + from pass 1)
     * and checks each remaining module's dependency tree for those GAs.  If found, the module
     * is transitively affected because rebuilding the upstream reactor module will change its
     * output artifacts.  This propagates iteratively to a fixed point to handle chains
     * (A → B → C where only A's POM changed).
     */
    private Map<MavenProject, List<String>> computeTransitivelyAffected(
            List<MavenProject> allProjects,
            Set<MavenProject> directlyAffected,
            Map<String, Model> oldEffectiveModels,
            Map<String, Model> newEffectiveModels,
            Path reactorRoot,
            MavenSession session,
            Map<MavenProject, DependencyResolutionResult> collectCache,
            Map<MavenProject, DependencyResolutionResult> oldCollectCache,
            Timings timings,
            boolean explain,
            Map<MavenProject, List<String>> returnEvidence) {
        Map<MavenProject, List<String>> transitivelyAffected = new LinkedHashMap<>();
        Map<MavenProject, List<String>> transitiveEvidence = new LinkedHashMap<>();
        if (oldEffectiveModels.isEmpty()) {
            logger.debug("Skipping transitive analysis: no effective models available");
            return transitivelyAffected;
        }
        Path absRoot = reactorRoot.toAbsolutePath().normalize();
        logger.debug(
                "Computing transitively affected modules: comparing old vs new dependency trees for {} non-direct modules",
                allProjects.size() - directlyAffected.size());
        // First pass: compare effective models and dependency trees directly
        for (MavenProject project : allProjects) {
            if (directlyAffected.contains(project)) {
                continue;
            }
            TransitiveMatch match = computeTransitiveMatch(
                    project,
                    oldEffectiveModels,
                    newEffectiveModels,
                    absRoot,
                    session,
                    collectCache,
                    oldCollectCache,
                    timings,
                    explain);
            if (!match.reasons.isEmpty()) {
                transitivelyAffected.put(project, match.reasons);
                if (explain) {
                    transitiveEvidence.put(project, match.evidence);
                }
            }
        }

        // Second pass: propagate through reactor dependencies.
        // When resolving "old" dependency trees, Maven's reactor always provides the current
        // (new) version of reactor siblings.  So a module whose POM didn't change but depends
        // on an affected reactor module will have identical old/new trees — the first pass
        // misses it.  Fix: if a module's dependency tree contains the GA of any affected
        // reactor module, it is transitively affected because rebuilding that upstream module
        // will change its output artifacts.
        Set<String> affectedGAs = new LinkedHashSet<>();
        for (MavenProject p : directlyAffected) {
            affectedGAs.add(p.getGroupId() + ":" + p.getArtifactId());
        }
        for (MavenProject p : transitivelyAffected.keySet()) {
            affectedGAs.add(p.getGroupId() + ":" + p.getArtifactId());
        }
        boolean propagated = true;
        while (propagated) {
            propagated = false;
            for (MavenProject project : allProjects) {
                if (directlyAffected.contains(project) || transitivelyAffected.containsKey(project)) {
                    continue;
                }
                DependencyResolutionResult depResult =
                        resolveProjectDependencies(project, session, collectCache, timings);
                if (depResult == null || depResult.getDependencyGraph() == null) {
                    if (!affectedGAs.isEmpty()) {
                        // Conservative: dependency resolution failed while there are
                        // affected reactor modules whose impact could reach this one
                        // through the unresolvable graph. Treat the module as affected
                        // instead of silently dropping it. When nothing is affected
                        // there is no impact the failure could hide, so skipping is
                        // correct.
                        logger.warn(
                                "Cannot resolve dependencies of {} while propagating changes, conservatively marking as affected",
                                key(project));
                        List<String> reasons = new ArrayList<>();
                        reasons.add(ScalpelReport.REASON_TRANSITIVE_DEPENDENCY_UNRESOLVED);
                        transitivelyAffected.put(project, reasons);
                        affectedGAs.add(project.getGroupId() + ":" + project.getArtifactId());
                        if (explain) {
                            transitiveEvidence.put(
                                    project,
                                    List.of("dependency resolution failed; conservatively treated as affected"));
                        }
                        propagated = true;
                    }
                    continue;
                }
                Map<String, String> depScopes = collectDependencyScopes(depResult.getDependencyGraph());
                for (Map.Entry<String, String> dep : depScopes.entrySet()) {
                    if (affectedGAs.contains(dep.getKey())) {
                        List<String> reasons = new ArrayList<>();
                        if ("test".equals(dep.getValue())) {
                            reasons.add(ScalpelReport.REASON_TRANSITIVE_DEPENDENCY_TEST);
                        } else {
                            reasons.add(ScalpelReport.REASON_TRANSITIVE_DEPENDENCY);
                        }
                        transitivelyAffected.put(project, reasons);
                        affectedGAs.add(project.getGroupId() + ":" + project.getArtifactId());
                        if (explain) {
                            transitiveEvidence.put(
                                    project, List.of("depends on affected reactor module " + dep.getKey()));
                        }
                        propagated = true;
                        break;
                    }
                }
            }
        }
        if (!transitivelyAffected.isEmpty()) {
            logger.info(
                    "Scalpel: {} modules transitively affected: {}",
                    transitivelyAffected.size(),
                    keys(transitivelyAffected.keySet()));
        }
        returnEvidence.putAll(transitiveEvidence);
        return transitivelyAffected;
    }

    private static final class TransitiveMatch {
        final List<String> reasons;
        final List<String> evidence;

        TransitiveMatch(List<String> reasons, List<String> evidence) {
            this.reasons = reasons;
            this.evidence = evidence;
        }
    }

    /**
     * Compare old vs new effective models and dependency trees for a single module.
     * Effective plugin comparison uses the fully-interpolated models directly.
     * Dependency tree comparison resolves both old and new trees and diffs them.
     */
    private TransitiveMatch computeTransitiveMatch(
            MavenProject project,
            Map<String, Model> oldEffectiveModels,
            Map<String, Model> newEffectiveModels,
            Path absRoot,
            MavenSession session,
            Map<MavenProject, DependencyResolutionResult> collectCache,
            Map<MavenProject, DependencyResolutionResult> oldCollectCache,
            Timings timings,
            boolean explain) {
        List<String> reasons = new ArrayList<>();
        List<String> evidence = explain ? new ArrayList<>() : List.of();

        String relPath = absRoot.relativize(
                        project.getFile().toPath().toAbsolutePath().normalize())
                .toString()
                .replace('\\', '/');
        Model oldModel = oldEffectiveModels.get(relPath);
        Model newModel = newEffectiveModels.get(relPath);
        if (oldModel == null || newModel == null) {
            return new TransitiveMatch(reasons, evidence);
        }

        // Compare effective plugins directly from the fully-interpolated models
        Set<String> changedPlugins = pomChangeAnalyzer.diffManagedPluginVersions(
                pomChangeAnalyzer.getEffectivePlugins(oldModel), pomChangeAnalyzer.getEffectivePlugins(newModel));
        if (!changedPlugins.isEmpty()) {
            reasons.add(ScalpelReport.REASON_MANAGED_PLUGIN);
            if (explain) {
                evidence.add("effective plugin " + changedPlugins.iterator().next());
            }
        }

        // Compare resolved dependency trees: old effective model vs current project
        ChangedDependencyMatch depMatch =
                findChangedDependencyInTree(project, oldModel, session, collectCache, oldCollectCache, timings);
        if (depMatch != null) {
            if (UNRESOLVED_GA.equals(depMatch.ga)) {
                reasons.add(ScalpelReport.REASON_TRANSITIVE_DEPENDENCY_UNRESOLVED);
            } else if ("test".equals(depMatch.scope)) {
                reasons.add(ScalpelReport.REASON_TRANSITIVE_DEPENDENCY_TEST);
            } else {
                reasons.add(ScalpelReport.REASON_TRANSITIVE_DEPENDENCY);
            }
            if (explain) {
                evidence.add("dependency tree diff " + depMatch.ga);
            }
        }

        return new TransitiveMatch(reasons, evidence);
    }

    private void applySkipTests(
            MavenSession session,
            List<MavenProject> allProjects,
            TrimResult trimResult,
            ScalpelConfiguration config,
            Map<String, Model> oldEffectiveModels,
            Map<String, Model> newEffectiveModels,
            List<PathMatcher> includeMatchers,
            Path reactorRoot,
            Map<MavenProject, DependencyResolutionResult> collectCache,
            Map<MavenProject, DependencyResolutionResult> oldCollectCache,
            Timings timings) {

        Path absRoot = reactorRoot.toAbsolutePath().normalize();
        Set<MavenProject> buildSetLookup = new LinkedHashSet<>(trimResult.getBuildSet());
        List<MavenProject> testProjects = new ArrayList<>();
        List<MavenProject> skippedProjects = new ArrayList<>();

        // Directly affected modules always run tests
        for (MavenProject project : trimResult.getBuildSet()) {
            if (trimResult.getDirectlyAffected().contains(project)) {
                testProjects.add(project);
            } else if (config.isSkipTestsForUpstream()
                    && trimResult.getUpstreamOnly().contains(project)) {
                // Skip tests on upstream-only modules if configured
                project.getProperties().setProperty(MAVEN_TEST_SKIP, "true");
                skippedProjects.add(project);
            } else if (shouldSkipTestsForExcludedDownstream(
                    project,
                    trimResult,
                    config,
                    oldEffectiveModels,
                    newEffectiveModels,
                    absRoot,
                    session,
                    collectCache,
                    oldCollectCache,
                    timings)) {
                // Skip tests on excluded downstream modules (unless they also have plugin/dep changes)
                project.getProperties().setProperty(MAVEN_TEST_SKIP, "true");
                skippedProjects.add(project);
                if (logger.isDebugEnabled()) {
                    logger.debug("Scalpel: Skipping tests on excluded downstream module {}", key(project));
                }
            } else {
                // Downstream modules run tests by default
                testProjects.add(project);
            }
        }

        for (MavenProject project : allProjects) {
            if (buildSetLookup.contains(project)) {
                continue; // Already handled above
            }

            // Skip tests on modules outside includePaths scope — managed dep/plugin
            // re-enabling should not override the user's includePaths restriction
            if (!includeMatchers.isEmpty() && !matchesIncludePaths(project, includeMatchers, reactorRoot)) {
                project.getProperties().setProperty(MAVEN_TEST_SKIP, "true");
                skippedProjects.add(project);
                continue;
            }

            // Check if this module's effective plugins or dependency tree changed
            if (hasEffectiveModelChanges(
                    project,
                    oldEffectiveModels,
                    newEffectiveModels,
                    absRoot,
                    session,
                    collectCache,
                    oldCollectCache,
                    timings)) {
                testProjects.add(project);
                continue;
            }

            // Skip tests on this project
            project.getProperties().setProperty(MAVEN_TEST_SKIP, "true");
            skippedProjects.add(project);
        }

        // Apply per-category args
        applyPerCategoryArgs(trimResult, config);

        // Softening must have the last word on maven.test.skip/skipTests: it runs after
        // applyPerCategoryArgs so user-configured upstreamArgs/downstreamArgs cannot
        // reintroduce the #47 failure on a consumed test-jar producer.
        Set<MavenProject> softenedProjects = softenTestJarProducers(testProjects, skippedProjects);

        logger.info(
                "Scalpel: Testing {}, {} compile-only (test-jar producers), skipping tests on {} of {} modules: {}",
                testProjects.size(),
                softenedProjects.size(),
                skippedProjects.size(),
                allProjects.size(),
                keys(skippedProjects));
    }

    /**
     * Modules slated for a full test skip (maven.test.skip=true) also skip test-compile, which
     * suppresses their test-jar. If some other module that will still run tests (directly, or
     * because it was already softened by a previous pass) depends on that test-jar
     * (&lt;type&gt;test-jar&lt;/type&gt;), its test-compile would fail looking for classes that
     * were never built. Soften those producers: drop maven.test.skip and use skipTests=true
     * instead, which only disables the surefire/failsafe execution while leaving test-compile
     * (and jar:test-jar) intact. Softening a producer can itself depend on another skipped
     * producer's test-jar (chained test-jar consumers), so this iterates to a fixpoint.
     */
    private Set<MavenProject> softenTestJarProducers(
            List<MavenProject> testProjects, List<MavenProject> skippedProjects) {
        Set<MavenProject> compilingTests = new LinkedHashSet<>(testProjects);
        Set<MavenProject> softenedProjects = new LinkedHashSet<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (MavenProject candidate : skippedProjects) {
                if (softenedProjects.contains(candidate)) {
                    continue;
                }
                // Iterate a snapshot: compilingTests grows in the loop body as softened producers
                // become potential consumers for later candidates, and mutating the live set during
                // iteration would throw ConcurrentModificationException.
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
                        changed = true;
                        break;
                    }
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

    private boolean matchesDownstreamExclusion(MavenProject project, List<String> patterns) {
        for (String pattern : patterns) {
            if (pattern.contains(":")) {
                if (key(project).equals(pattern)) {
                    return true;
                }
            } else {
                if (project.getArtifactId().equals(pattern)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean shouldSkipTestsForExcludedDownstream(
            MavenProject project,
            TrimResult trimResult,
            ScalpelConfiguration config,
            Map<String, Model> oldEffectiveModels,
            Map<String, Model> newEffectiveModels,
            Path absRoot,
            MavenSession session,
            Map<MavenProject, DependencyResolutionResult> collectCache,
            Map<MavenProject, DependencyResolutionResult> oldCollectCache,
            Timings timings) {
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
        return !hasEffectiveModelChanges(
                project,
                oldEffectiveModels,
                newEffectiveModels,
                absRoot,
                session,
                collectCache,
                oldCollectCache,
                timings);
    }

    /**
     * A DependencyFilter that rejects all nodes, preventing artifact downloads
     * while still allowing the dependency graph to be collected.
     */
    private static final DependencyFilter COLLECT_ONLY_FILTER = new DependencyFilter() {
        @Override
        public boolean accept(DependencyNode node, List<DependencyNode> parents) {
            return false;
        }
    };

    /**
     * Checks whether a module's effective plugins or resolved dependency tree changed
     * between old and new effective models.
     */
    private boolean hasEffectiveModelChanges(
            MavenProject project,
            Map<String, Model> oldEffectiveModels,
            Map<String, Model> newEffectiveModels,
            Path absRoot,
            MavenSession session,
            Map<MavenProject, DependencyResolutionResult> collectCache,
            Map<MavenProject, DependencyResolutionResult> oldCollectCache,
            Timings timings) {
        String relPath = absRoot.relativize(
                        project.getFile().toPath().toAbsolutePath().normalize())
                .toString()
                .replace('\\', '/');
        Model oldModel = oldEffectiveModels.get(relPath);
        Model newModel = newEffectiveModels.get(relPath);
        if (oldModel == null || newModel == null) {
            return false;
        }

        // Check effective plugins
        Set<String> changedPlugins = pomChangeAnalyzer.diffManagedPluginVersions(
                pomChangeAnalyzer.getEffectivePlugins(oldModel), pomChangeAnalyzer.getEffectivePlugins(newModel));
        if (!changedPlugins.isEmpty()) {
            return true;
        }

        // Check dependency tree
        return findChangedDependencyInTree(project, oldModel, session, collectCache, oldCollectCache, timings) != null;
    }

    private static final class ChangedDependencyMatch {
        final String ga;
        final String scope;

        ChangedDependencyMatch(String ga, String scope) {
            this.ga = ga;
            this.scope = scope;
        }
    }

    /**
     * Resolve dependency trees from both old effective model and current project,
     * then diff them.  Returns the first changed dependency (with scope), or null if trees match.
     * <p>
     * Uses a reject-all DependencyFilter so only the dependency graph is collected
     * without downloading any artifact files — we only need GA coordinates, versions, and scopes.
     */
    private ChangedDependencyMatch findChangedDependencyInTree(
            MavenProject project,
            Model oldEffectiveModel,
            MavenSession session,
            Map<MavenProject, DependencyResolutionResult> collectCache,
            Map<MavenProject, DependencyResolutionResult> oldCollectCache,
            Timings timings) {

        // Resolve new (current) dependency tree
        DependencyResolutionResult newResult = resolveProjectDependencies(project, session, collectCache, timings);
        if (newResult == null || newResult.getDependencyGraph() == null) {
            // Conservative: when the current tree cannot be resolved at all, assume it
            // changed so the module is treated as affected (and its tests are not
            // skipped downstream). A redundant rebuild is safer than a green build
            // on untested code.
            return new ChangedDependencyMatch(UNRESOLVED_GA, "compile");
        }

        // Resolve old dependency tree from old effective model
        DependencyResolutionResult oldResult =
                resolveModelDependencies(oldEffectiveModel, project, session, oldCollectCache, timings);
        if (oldResult == null || oldResult.getDependencyGraph() == null) {
            // Conservative: same posture when the old tree cannot be resolved.
            return new ChangedDependencyMatch(UNRESOLVED_GA, "compile");
        }

        // Collect (GA → version) from both trees and diff
        Map<String, String> oldVersions = collectDependencyVersions(oldResult.getDependencyGraph());
        Map<String, String> newVersions = collectDependencyVersions(newResult.getDependencyGraph());

        // Find changed GAs and determine scope from the new tree
        Map<String, String> newScopes = collectDependencyScopes(newResult.getDependencyGraph());

        String narrowestGa = null;
        String narrowestScope = null;

        for (Map.Entry<String, String> e : oldVersions.entrySet()) {
            if (!Objects.equals(e.getValue(), newVersions.get(e.getKey()))) {
                String ga = e.getKey();
                String scope = newScopes.getOrDefault(ga, "compile");
                logger.debug("Module {} has changed dependency {} (scope={})", key(project), ga, scope);
                if (!"test".equals(scope)) {
                    return new ChangedDependencyMatch(ga, scope);
                }
                if (narrowestScope == null) {
                    narrowestScope = "test";
                    narrowestGa = ga;
                }
            }
        }
        // Check for newly added dependencies
        for (String ga : newVersions.keySet()) {
            if (!oldVersions.containsKey(ga)) {
                String scope = newScopes.getOrDefault(ga, "compile");
                logger.debug("Module {} has new dependency {} (scope={})", key(project), ga, scope);
                if (!"test".equals(scope)) {
                    return new ChangedDependencyMatch(ga, scope);
                }
                if (narrowestScope == null) {
                    narrowestScope = "test";
                    narrowestGa = ga;
                }
            }
        }

        return narrowestScope != null ? new ChangedDependencyMatch(narrowestGa, narrowestScope) : null;
    }

    /**
     * Resolve the current project's dependency tree (cached).
     */
    private DependencyResolutionResult resolveProjectDependencies(
            MavenProject project,
            MavenSession session,
            Map<MavenProject, DependencyResolutionResult> cache,
            Timings timings) {
        DependencyResolutionResult result = cache.get(project);
        if (result != null) {
            timings.increment(Timings.OP_RESOLVE_CACHE_HITS);
            return result;
        }
        try {
            DefaultDependencyResolutionRequest request =
                    new DefaultDependencyResolutionRequest(project, session.getRepositorySession());
            request.setResolutionFilter(COLLECT_ONLY_FILTER);
            result = dependenciesResolver.resolve(request);
        } catch (DependencyResolutionException e) {
            // Conservative: even when the exception carries a partial result, its
            // dependency graph may be incomplete — a missing subtree could silently
            // omit the very dependency that changed, making the old/new diff conclude
            // "no change" when the module IS affected.  Discard the partial result
            // so callers take the conservative "changed" / UNRESOLVED path.
            logger.warn(
                    "Cannot collect dependencies for {}, conservatively treating module as affected: {}",
                    key(project),
                    e.getMessage());
            return null;
        }
        timings.increment(Timings.OP_DEPENDENCY_RESOLVES);
        cache.put(project, result);
        return result;
    }

    /**
     * Resolve the dependency tree from an old effective model.
     * Creates a temporary MavenProject from the model and copies remote repositories
     * from the current project so transitive resolution can reach the same repos.
     */
    private DependencyResolutionResult resolveModelDependencies(
            Model oldModel,
            MavenProject currentProject,
            MavenSession session,
            Map<MavenProject, DependencyResolutionResult> cache,
            Timings timings) {
        DependencyResolutionResult result = cache.get(currentProject);
        if (result != null) {
            timings.increment(Timings.OP_RESOLVE_CACHE_HITS);
            return result;
        }
        try {
            MavenProject tempProject = new MavenProject(currentProject);
            tempProject.setModel(oldModel);
            tempProject.setDependencies(oldModel.getDependencies());
            DefaultDependencyResolutionRequest request =
                    new DefaultDependencyResolutionRequest(tempProject, session.getRepositorySession());
            request.setResolutionFilter(COLLECT_ONLY_FILTER);
            result = dependenciesResolver.resolve(request);
        } catch (DependencyResolutionException e) {
            // Conservative: same posture as resolveProjectDependencies — discard
            // partial results to avoid silently missing changes in an incomplete graph.
            logger.warn(
                    "Cannot collect old dependencies for {}, conservatively treating module as affected: {}",
                    key(currentProject),
                    e.getMessage());
            return null;
        }
        timings.increment(Timings.OP_DEPENDENCY_RESOLVES);
        cache.put(currentProject, result);
        return result;
    }

    /**
     * Walk the dependency graph and collect (GA → version) for all nodes.
     */
    private static Map<String, String> collectDependencyVersions(DependencyNode root) {
        Map<String, String> versions = new LinkedHashMap<>();
        List<DependencyNode> stack = new ArrayList<>(root.getChildren());
        Set<String> visited = new HashSet<>();
        while (!stack.isEmpty()) {
            DependencyNode node = stack.remove(stack.size() - 1);
            Dependency dep = node.getDependency();
            if (dep == null) {
                continue;
            }
            String ga = dep.getArtifact().getGroupId() + ":" + dep.getArtifact().getArtifactId();
            if (!visited.add(ga)) {
                continue;
            }
            versions.put(ga, dep.getArtifact().getVersion());
            stack.addAll(node.getChildren());
        }
        return versions;
    }

    /**
     * Walk the dependency graph and collect (GA → scope) for all nodes.
     */
    private static Map<String, String> collectDependencyScopes(DependencyNode root) {
        Map<String, String> scopes = new LinkedHashMap<>();
        List<DependencyNode> stack = new ArrayList<>(root.getChildren());
        Set<String> visited = new HashSet<>();
        while (!stack.isEmpty()) {
            DependencyNode node = stack.remove(stack.size() - 1);
            Dependency dep = node.getDependency();
            if (dep == null) {
                continue;
            }
            String ga = dep.getArtifact().getGroupId() + ":" + dep.getArtifact().getArtifactId();
            if (!visited.add(ga)) {
                continue;
            }
            scopes.put(ga, dep.getScope() != null ? dep.getScope() : "compile");
            stack.addAll(node.getChildren());
        }
        return scopes;
    }

    /**
     * Derive changed managed dependency GAs from effective models (for report purposes only).
     */
    private Set<String> deriveChangedManagedDeps(
            Map<String, Model> oldEffectiveModels, Map<String, Model> newEffectiveModels) {
        Set<String> changed = new LinkedHashSet<>();
        for (Map.Entry<String, Model> entry : oldEffectiveModels.entrySet()) {
            Model newModel = newEffectiveModels.get(entry.getKey());
            if (newModel != null) {
                changed.addAll(pomChangeAnalyzer.diffDependencies(
                        pomChangeAnalyzer.getManagedDependencies(entry.getValue()),
                        pomChangeAnalyzer.getManagedDependencies(newModel)));
            }
        }
        return changed;
    }

    /**
     * Derive changed managed plugin GAs from effective models (for report purposes only).
     */
    private Set<String> deriveChangedManagedPlugins(
            Map<String, Model> oldEffectiveModels, Map<String, Model> newEffectiveModels) {
        Set<String> changed = new LinkedHashSet<>();
        for (Map.Entry<String, Model> entry : oldEffectiveModels.entrySet()) {
            Model newModel = newEffectiveModels.get(entry.getKey());
            if (newModel != null) {
                changed.addAll(pomChangeAnalyzer.diffManagedPluginVersions(
                        pomChangeAnalyzer.getManagedPlugins(entry.getValue()),
                        pomChangeAnalyzer.getManagedPlugins(newModel)));
            }
        }
        return changed;
    }

    private void writeImpactedLog(ScalpelConfiguration config, Path reactorRoot, Set<MavenProject> affectedModules)
            throws MavenExecutionException {
        String impactedLog = config.getImpactedLog();
        if (impactedLog == null || impactedLog.trim().isEmpty()) {
            return;
        }
        Path logPath = reactorRoot.resolve(impactedLog);
        try {
            Files.createDirectories(logPath.getParent());
            List<String> lines = new ArrayList<>();
            for (MavenProject project : affectedModules) {
                String relPath = relativePath(reactorRoot, project);
                if (relPath.isEmpty()) {
                    // The reactor root's relative path is the empty string; represent it as
                    // "." so an affected root stays visible to CI consumers instead of
                    // disappearing as a blank (or skipped) line (#84).
                    relPath = ".";
                }
                if (!isSafeImpactedLogPath(relPath)) {
                    // The log is one path per line, consumed by CI shell scripts ($(cat ...),
                    // xargs); a module directory name is PR-author-controlled, so a path outside
                    // the documented safe set is skipped rather than escaped (#84).
                    logger.warn(
                            "Scalpel: Skipping module {} in impacted log: path '{}' uses characters outside the"
                                    + " safe set (letters, digits, '-', '_', '.', '/'; see README)",
                            key(project),
                            escapeControlChars(relPath));
                    continue;
                }
                lines.add(relPath);
            }
            Files.write(logPath, lines, StandardCharsets.UTF_8);
            logger.info("Scalpel: Impacted modules written to {}", config.getImpactedLog());
        } catch (IOException e) {
            handleWriteFailure(config, "Failed to write impacted log", e);
        }
    }

    /**
     * Safe character set for impacted-log lines. The log holds one relative module path per line
     * and is consumed by CI shell scripts, so a PR author controlling a module directory name must
     * not be able to inject shell metacharacters, spaces, quotes, glob characters or
     * line/control characters into it. Only {@code [A-Za-z0-9._-/]} is accepted; a leading dash
     * (option injection) and the empty string are rejected too. Control characters and newlines
     * are rejected by construction: nothing outside this set passes. Backslashes never occur
     * ({@link #relativePath} normalizes separators to '/') and would be rejected like any other
     * character.
     */
    static boolean isSafeImpactedLogPath(String path) {
        if (path == null || path.isEmpty() || path.charAt(0) == '-') {
            return false;
        }
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '-'
                    || c == '_'
                    || c == '.'
                    || c == '/';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }

    /**
     * Escapes control characters (newlines included) so a rejected path cannot forge extra
     * lines in the skip warning itself.
     */
    private static String escapeControlChars(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c)) {
                escaped.append("\\u%04x".formatted((int) c));
            } else {
                escaped.append(c);
            }
        }
        return escaped.toString();
    }

    /**
     * Honours failSafe on report/log write failures: with failSafe=true the build continues with a
     * warning, otherwise the write failure fails the build as before.
     */
    private void handleWriteFailure(ScalpelConfiguration config, String message, IOException e)
            throws MavenExecutionException {
        if (config.isFailSafe()) {
            logger.warn("Scalpel: {} (failSafe=true, continuing build): {}", message, e.toString());
        } else {
            throw new MavenExecutionException("Scalpel: " + message, e);
        }
    }

    /**
     * Overwrites the configured reportFile with a minimal failed-status document so a previous
     * run's report cannot be mistaken for current results by CI. Only called on failSafe bail-out
     * paths; write failures here are logged and swallowed (the build continues by design).
     */
    private void writeFailedStatusReport(ScalpelConfiguration config, Path reactorRoot, String reason) {
        writeStatusReport(config, reactorRoot, "failed", reason);
    }

    /**
     * Overwrites the configured reportFile with a minimal status-only document ("failed" for
     * bail-outs, "skipped" for deliberate non-analysis paths) so a previous run's report cannot
     * be mistaken for current results by CI. Write failures are logged and swallowed (the build
     * continues by design).
     */
    private void writeStatusReport(ScalpelConfiguration config, Path reactorRoot, String status, String reason) {
        String baseBranch = config.getBaseBranch();
        try {
            ScalpelReport report = ScalpelReport.builder()
                    .baseBranch(baseBranch != null ? baseBranch : "(unconfigured)")
                    .status(status)
                    .reason(reason)
                    .fullBuildTriggered(true)
                    .build();
            report.writeToFile(reactorRoot, config.getReportFile());
            logger.warn(
                    "Scalpel: Analysis did not complete (status={}, reason={}), report at {} overwritten",
                    status,
                    reason,
                    config.getReportFile());
        } catch (Exception e) {
            // Status reports must never fail the build; that is their entire job.
            logger.warn("Scalpel: Could not overwrite report with {} status: {}", status, e.toString());
        }
    }

    private void writeFullBuildReport(
            ScalpelConfiguration config, Path reactorRoot, String triggerFile, Set<String> changedFiles)
            throws MavenExecutionException {
        ScalpelReport report = ScalpelReport.builder()
                .baseBranch(config.getBaseBranch())
                .fullBuildTriggered(true)
                .triggerFile(triggerFile)
                .changedFiles(changedFiles)
                .build();
        try {
            report.writeToFile(reactorRoot, config.getReportFile());
            logger.info("Scalpel: Report written to {}", config.getReportFile());
        } catch (IOException e) {
            handleWriteFailure(config, "Failed to write report", e);
        }
    }

    private void writeReport(
            ScalpelConfiguration config,
            Path reactorRoot,
            List<MavenProject> allProjects,
            AnalysisContext ctx,
            Timings timings,
            long analysisStartNano)
            throws MavenExecutionException {
        ScalpelReport.Builder builder = ScalpelReport.builder()
                .baseBranch(config.getBaseBranch())
                .fullBuildTriggered(false)
                .changedFiles(ctx.changedFiles)
                .changedProperties(ctx.changedProperties)
                .changedManagedDependencies(ctx.changedManagedDepGAs)
                .changedManagedPlugins(ctx.changedManagedPluginGAs)
                .unmatchedPomPaths(ctx.unmatchedPomPaths)
                .timings(timings, millisSince(analysisStartNano));

        logger.debug(
                "Building report: {} directly affected, {} transitively affected, trim result has {} upstream / {} downstream / {} downstream-test",
                ctx.directlyAffected.size(),
                ctx.transitivelyAffected.size(),
                ctx.trimResult != null ? ctx.trimResult.getUpstreamOnly().size() : 0,
                ctx.trimResult != null ? ctx.trimResult.getDownstreamOnly().size() : 0,
                ctx.trimResult != null ? ctx.trimResult.getDownstreamTestOnly().size() : 0);

        addDirectlyAffectedModules(builder, ctx, reactorRoot);
        addTransitivelyAffectedModules(builder, ctx, config, reactorRoot);
        int excludedUpstream = addTrimResultModules(builder, ctx, config, reactorRoot);
        builder.excludedUpstreamCount(excludedUpstream);
        addSkippedModules(builder, allProjects, ctx, reactorRoot);

        try {
            ScalpelReport report = builder.build();
            report.writeToFile(reactorRoot, config.getReportFile());
            logger.info("Scalpel: Report written to {}", config.getReportFile());
        } catch (IOException e) {
            handleWriteFailure(config, "Failed to write report", e);
        }
    }

    /**
     * Enumerates reactor modules that were left out of the build set, so a reviewer of a
     * green trimmed build can see exactly what was skipped and why. A module is skipped
     * when it is neither directly nor transitively affected, and not part of the trim
     * build set (upstream prerequisites and downstream dependents are built, not skipped).
     */
    private void addSkippedModules(
            ScalpelReport.Builder builder, List<MavenProject> allProjects, AnalysisContext ctx, Path reactorRoot) {
        Set<MavenProject> included = new LinkedHashSet<>(ctx.directlyAffected);
        included.addAll(ctx.transitivelyAffected.keySet());
        if (ctx.trimResult != null) {
            // Use the filtered build set when available (trim mode with includePaths),
            // so modules removed by the includePaths scope check appear in skippedModules.
            if (ctx.filteredBuildSet != null) {
                included.addAll(ctx.filteredBuildSet);
            } else {
                included.addAll(ctx.trimResult.getBuildSet());
            }
        }
        for (MavenProject project : allProjects) {
            if (!included.contains(project)) {
                String path = relativePath(reactorRoot, project);
                builder.addSkippedModule(new ScalpelReport.SkippedModule(
                        project.getGroupId(), project.getArtifactId(), path, ScalpelReport.SKIP_REASON_NOT_AFFECTED));
            }
        }
    }

    private void addDirectlyAffectedModules(ScalpelReport.Builder builder, AnalysisContext ctx, Path reactorRoot) {
        for (MavenProject project : ctx.directlyAffected) {
            String path = relativePath(reactorRoot, project);
            List<String> reasons = new ArrayList<>();
            String sourceSet = null;
            if (ctx.affectedBySource.contains(project)) {
                if (ctx.testOnlyBySource.contains(project)) {
                    reasons.add(ScalpelReport.REASON_TEST_CHANGE);
                    sourceSet = "test";
                } else {
                    reasons.add(ScalpelReport.REASON_SOURCE_CHANGE);
                    sourceSet = "main";
                }
            }
            if (ctx.affectedByPom.contains(project)) {
                reasons.add(ScalpelReport.REASON_POM_CHANGE);
            }
            if (ctx.forceIncluded.contains(project)) {
                reasons.add(ScalpelReport.REASON_FORCE_BUILD);
            }
            if (logger.isDebugEnabled()) {
                logger.debug("Report: {} -> category=DIRECT, reasons={}", key(project), reasons);
            }
            builder.addAffectedModule(ScalpelReport.AffectedModule.moduleBuilder(
                            project.getGroupId(), project.getArtifactId(), path, reasons)
                    .category(ScalpelReport.CATEGORY_DIRECT)
                    .sourceSet(sourceSet)
                    .evidence(ctx.evidence.get(project))
                    .build());
        }
    }

    private void addTransitivelyAffectedModules(
            ScalpelReport.Builder builder, AnalysisContext ctx, ScalpelConfiguration config, Path reactorRoot) {
        for (Map.Entry<MavenProject, List<String>> entry : ctx.transitivelyAffected.entrySet()) {
            MavenProject project = entry.getKey();
            String path = relativePath(reactorRoot, project);
            String category = null;
            String testsSkippedReason = null;
            if (ctx.trimResult != null) {
                if (ctx.trimResult.getUpstreamOnly().contains(project)) {
                    category = ScalpelReport.CATEGORY_UPSTREAM;
                } else if (ctx.trimResult.getDownstreamOnly().contains(project)
                        || ctx.trimResult.getDownstreamTestOnly().contains(project)) {
                    category = ScalpelReport.CATEGORY_DOWNSTREAM;
                    if (matchesDownstreamExclusion(project, config.getSkipTestsForDownstreamModules())) {
                        testsSkippedReason = ScalpelReport.REASON_EXCLUDED_DOWNSTREAM;
                    }
                }
            }
            if (category == null) {
                category = ScalpelReport.CATEGORY_TRANSITIVE;
            }
            logger.debug("Report: {} -> category={}, reasons={}", key(project), category, entry.getValue());
            builder.addAffectedModule(ScalpelReport.AffectedModule.moduleBuilder(
                            project.getGroupId(), project.getArtifactId(), path, entry.getValue())
                    .category(category)
                    .testsSkippedReason(testsSkippedReason)
                    .evidence(ctx.evidence.get(project))
                    .build());
        }
    }

    /**
     * Adds downstream modules from the trim result to the report and returns the number of
     * upstream build-prerequisite modules that were excluded.
     */
    private int addTrimResultModules(
            ScalpelReport.Builder builder, AnalysisContext ctx, ScalpelConfiguration config, Path reactorRoot) {
        if (ctx.trimResult == null) {
            return 0;
        }
        // Upstream modules are build-order prerequisites, not genuinely affected by the change.
        // Including them in affectedModules inflates the report (e.g. a sync-point module like
        // camel-allcomponents that depends on everything pulls the entire reactor as UPSTREAM).
        // Log the count for transparency but don't include them in the report.
        int upstreamCount = 0;
        for (MavenProject project : ctx.trimResult.getUpstreamOnly()) {
            if (!ctx.directlyAffected.contains(project) && !ctx.transitivelyAffected.containsKey(project)) {
                upstreamCount++;
                if (logger.isDebugEnabled()) {
                    logger.debug(
                            "Excluding upstream build-prerequisite {} from report (not genuinely affected)",
                            key(project));
                }
            }
        }
        if (upstreamCount > 0) {
            logger.info(
                    "Scalpel: {} upstream build-prerequisite modules excluded from report (use trim/skip-tests mode for full build set)",
                    upstreamCount);
        }
        addDownstreamModules(
                builder,
                ctx,
                config,
                reactorRoot,
                ctx.trimResult.getDownstreamOnly(),
                ScalpelReport.REASON_DOWNSTREAM_DEPENDENT);
        addDownstreamModules(
                builder,
                ctx,
                config,
                reactorRoot,
                ctx.trimResult.getDownstreamTestOnly(),
                ScalpelReport.REASON_DOWNSTREAM_TEST);
        return upstreamCount;
    }

    private void addDownstreamModules(
            ScalpelReport.Builder builder,
            AnalysisContext ctx,
            ScalpelConfiguration config,
            Path reactorRoot,
            Set<MavenProject> downstreamProjects,
            String reason) {
        List<PathMatcher> includeMatchers = compileGlobMatchers(config.getIncludePaths());
        for (MavenProject project : downstreamProjects) {
            if (!ctx.directlyAffected.contains(project) && !ctx.transitivelyAffected.containsKey(project)) {
                // Skip downstream modules outside includePaths scope
                if (!includeMatchers.isEmpty() && !matchesIncludePaths(project, includeMatchers, reactorRoot)) {
                    if (logger.isDebugEnabled()) {
                        logger.debug("Excluding downstream module {} from report (outside includePaths)", key(project));
                    }
                    continue;
                }
                String path = relativePath(reactorRoot, project);
                String testsSkippedReason =
                        matchesDownstreamExclusion(project, config.getSkipTestsForDownstreamModules())
                                ? ScalpelReport.REASON_EXCLUDED_DOWNSTREAM
                                : null;
                if (logger.isDebugEnabled()) {
                    logger.debug("Report: {} -> category=DOWNSTREAM, reason={}", key(project), reason);
                }
                builder.addAffectedModule(ScalpelReport.AffectedModule.moduleBuilder(
                                project.getGroupId(), project.getArtifactId(), path, List.of(reason))
                        .category(ScalpelReport.CATEGORY_DOWNSTREAM)
                        .testsSkippedReason(testsSkippedReason)
                        .evidence(ctx.evidence.get(project))
                        .build());
            }
        }
    }

    private static String relativePath(Path reactorRoot, MavenProject project) {
        return reactorRoot
                .toAbsolutePath()
                .normalize()
                .relativize(project.getBasedir().toPath().toAbsolutePath().normalize())
                .toString()
                .replace('\\', '/');
    }

    private void applyPerCategoryArgs(TrimResult trimResult, ScalpelConfiguration config) {
        for (String arg : config.getUpstreamArgs()) {
            String[] parts = arg.split("=", 2);
            if (parts.length == 2) {
                for (MavenProject project : trimResult.getUpstreamOnly()) {
                    project.getProperties().setProperty(parts[0], parts[1]);
                }
            } else {
                logger.warn("Scalpel: Malformed upstreamArgs entry '{}', expected key=value format", arg);
            }
        }
        for (String arg : config.getDownstreamArgs()) {
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

    private String matchesForceBuild(MavenProject project, List<String> patterns) {
        for (String pattern : patterns) {
            // artifactId is PR-author-controlled input (including length): match through the
            // cached, input-bounded matcher, never String.matches()
            if (regexMatcher.matches(project.getArtifactId(), pattern, "forceBuildModules", logger)) {
                logger.debug("Scalpel: Force-including module {} (matches {})", key(project), pattern);
                return pattern;
            }
        }
        return null;
    }

    /**
     * Assembles explain-mode evidence per module: the specific input (changed file, property,
     * managed dep/plugin GA, force pattern, upstream/downstream relationship) that put each
     * module into the affected/build set.
     */
    private static Map<MavenProject, List<String>> buildEvidence(
            Map<MavenProject, Set<String>> triggeringFiles,
            Map<MavenProject, Set<String>> pomEvidence,
            Map<MavenProject, List<String>> transitiveEvidence,
            Set<MavenProject> forceIncluded,
            Map<MavenProject, String> forceBuildPatterns) {
        Map<MavenProject, List<String>> evidence = new LinkedHashMap<>();
        for (Map.Entry<MavenProject, Set<String>> entry : triggeringFiles.entrySet()) {
            evidence.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).addAll(entry.getValue());
        }
        for (Map.Entry<MavenProject, Set<String>> entry : pomEvidence.entrySet()) {
            evidence.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).addAll(entry.getValue());
        }
        for (Map.Entry<MavenProject, List<String>> entry : transitiveEvidence.entrySet()) {
            evidence.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).addAll(entry.getValue());
        }
        for (MavenProject project : forceIncluded) {
            String pattern = forceBuildPatterns.get(project);
            evidence.computeIfAbsent(project, k -> new ArrayList<>())
                    .add("forced by forceBuildModules pattern " + pattern);
        }
        return evidence;
    }

    private void mergeTrimReasons(Map<MavenProject, List<String>> evidence, TrimResult trimResult) {
        for (Map.Entry<MavenProject, List<String>> entry :
                trimResult.getBuildReasons().entrySet()) {
            evidence.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).addAll(entry.getValue());
        }
    }

    /**
     * Logs a per-module BUILD/SKIP decision with the specific evidence (explain mode).
     */
    private void logExplainDecisions(
            List<MavenProject> allProjects, Set<MavenProject> buildSet, Map<MavenProject, List<String>> evidence) {
        for (MavenProject project : allProjects) {
            if (buildSet.contains(project)) {
                List<String> items = evidence.get(project);
                String because = items == null || items.isEmpty()
                        ? "affected (no specific input recorded)"
                        : String.join("; ", items);
                logger.info("Scalpel explain: BUILD {} because: {}", key(project), because);
            } else {
                logger.info("Scalpel explain: SKIP {} (not affected by changeset)", key(project));
            }
        }
    }

    private void skipTestsOnAll(List<MavenProject> projects) {
        for (MavenProject project : projects) {
            project.getProperties().setProperty(MAVEN_TEST_SKIP, "true");
        }
    }
}
