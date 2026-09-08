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

import eu.maveniverse.maven.scalpel.core.ChangeDetectionResult;
import eu.maveniverse.maven.scalpel.core.ScalpelConfiguration;
import eu.maveniverse.maven.scalpel.core.ScalpelCore;
import eu.maveniverse.maven.scalpel.core.ScalpelException;
import eu.maveniverse.maven.scalpel.core.Timings;
import eu.maveniverse.maven.scalpel.core.Version;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Model;
import org.apache.maven.project.DependencyResolutionResult;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectDependenciesResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
@Named
class ScalpelLifecycleParticipant extends AbstractMavenLifecycleParticipant {

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final ScalpelCore scalpelCore;
    private final ModuleMapper moduleMapper;
    private final PomChangeAnalyzer pomChangeAnalyzer;
    private final ReactorTrimmer reactorTrimmer;

    // Internal collaborators (not DI components)
    private final ChangedFileClassifier changedFileClassifier;
    private final ForceBuildMatcher forceBuildMatcher;
    private final TransitiveImpactAnalyzer transitiveImpactAnalyzer;
    private final SkipTestsApplier skipTestsApplier;
    private final ReportAssembler reportAssembler;
    private final ImpactedLogWriter impactedLogWriter;

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

        this.changedFileClassifier = new ChangedFileClassifier();
        this.forceBuildMatcher = new ForceBuildMatcher();
        this.transitiveImpactAnalyzer = new TransitiveImpactAnalyzer(dependenciesResolver, pomChangeAnalyzer);
        this.skipTestsApplier = new SkipTestsApplier(reactorTrimmer, transitiveImpactAnalyzer);
        this.reportAssembler = new ReportAssembler();
        this.impactedLogWriter = new ImpactedLogWriter(reportAssembler);
    }

    @Override
    public void afterProjectsRead(MavenSession session) throws MavenExecutionException {
        ScalpelConfiguration config;
        try {
            config = ScalpelConfiguration.fromProperties(session.getSystemProperties(), session.getUserProperties());
        } catch (Exception e) {
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
                if (config.isModeShadow()) {
                    reportAssembler.writeShadowStatus(
                            session.getRequest()
                                    .getMultiModuleProjectDirectory()
                                    .toPath(),
                            "skipped",
                            "disabled by -pl project selection");
                }
                return;
            }
        }

        logger.info("Scalpel {} activated (mode={})", version, config.getMode());
        logger.debug("Configuration: {}", config);

        Path reactorRoot = session.getRequest().getMultiModuleProjectDirectory().toPath();
        // Normalize the reactor root once — hoisted out of all per-project loops (#113)
        Path normalizedRoot = reactorRoot.toAbsolutePath().normalize();
        List<MavenProject> allProjects = session.getProjects();

        Timings timings = new Timings();
        long analysisStartNano = System.nanoTime();
        try {
            // Collect ALL reactor POM paths
            Set<String> allPomPaths = new LinkedHashSet<>();
            for (MavenProject project : allProjects) {
                Path pomPath = project.getFile().toPath().toAbsolutePath().normalize();
                Path relativePom = normalizedRoot.relativize(pomPath);
                allPomPaths.add(relativePom.toString().replace('\\', '/'));
            }

            // Detect changes
            ChangeDetectionResult result = scalpelCore.detectChanges(reactorRoot, config, allPomPaths, timings);
            if (result == null) {
                if (config.isPassiveMode()) {
                    String skipReason = scalpelCore.getLastDetectionSkipReason();
                    if (skipReason != null) {
                        reportAssembler.writeStatusReport(config, reactorRoot, "skipped", skipReason);
                    } else {
                        reportAssembler.writeStatusReport(
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
                    reportAssembler.writeStatusReport(config, reactorRoot, "skipped", "no changes detected");
                }
                return;
            }

            logger.info("Scalpel: {} changed files detected", changedFiles.size());

            // Check disable triggers
            if (changedFileClassifier.matchesDisableTrigger(changedFiles, config)) {
                if (config.isPassiveMode()) {
                    reportAssembler.writeStatusReport(
                            config, reactorRoot, "skipped", "disabled by disableTriggers match");
                }
                return;
            }

            // Filter out excluded paths
            changedFiles = changedFileClassifier.filterExcludedPaths(changedFiles, config);
            if (changedFiles.isEmpty()) {
                logger.info("Scalpel: All changed files excluded by path filters, building all modules");
                if (config.isPassiveMode()) {
                    reportAssembler.writeStatusReport(
                            config, reactorRoot, "skipped", "all changed files excluded by path filters");
                }
                return;
            }

            // Check full build triggers
            String triggerFile = changedFileClassifier.findFullBuildTrigger(changedFiles, config);
            if (triggerFile != null) {
                if (config.isPassiveMode()) {
                    reportAssembler.writeFullBuildReport(config, reactorRoot, triggerFile, changedFiles);
                }
                return;
            }

            // Separate POM changes from source changes
            ChangedFileClassifier.ClassificationResult classification =
                    changedFileClassifier.classifyChanges(changedFiles);

            // Map source changes to modules
            ModuleMapper.Result sourceResult;
            timings.start(Timings.PHASE_MODULE_MAPPING);
            try {
                sourceResult = moduleMapper.mapToProjectsClassified(
                        classification.sourceChanges, allProjects, reactorRoot, config.isExplain());
            } finally {
                timings.stop(Timings.PHASE_MODULE_MAPPING);
            }
            Set<MavenProject> affectedBySource = sourceResult.getAllAffected();
            logger.debug("Modules affected by source changes: {}", keys(affectedBySource));
            if (!sourceResult.getTestOnlyAffected().isEmpty()) {
                logger.debug(
                        "Test-only modules (no downstream propagation): {}", keys(sourceResult.getTestOnlyAffected()));
            }

            // Analyze POM changes
            Set<MavenProject> affectedByPom = new LinkedHashSet<>();
            Map<String, Model> oldEffectiveModels = Map.of();
            Map<String, Model> newEffectiveModels = Map.of();
            Set<String> changedProperties = new LinkedHashSet<>();
            Map<MavenProject, Set<String>> pomEvidence = Map.of();
            Set<String> unmatchedPomPaths = new LinkedHashSet<>();
            if (!classification.pomChanges.isEmpty()) {
                logger.debug("POM changes detected: {}", classification.pomChanges);
                try {
                    PomChangeAnalyzer.Result pomResult;
                    timings.start(Timings.PHASE_POM_ANALYSIS);
                    try {
                        pomResult = pomChangeAnalyzer.analyzeChanges(
                                classification.pomChanges,
                                result.getOldPomContents(),
                                allProjects,
                                reactorRoot,
                                config.isExplain(),
                                new PomChangeAnalyzer.ModelResolutionContext(
                                        session.getSystemProperties(),
                                        session.getUserProperties(),
                                        session.getRepositorySession(),
                                        allProjects.get(0).getRemoteProjectRepositories()),
                                config.getExcludeChanges(),
                                config.getIncludeChanges());
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
                            reportAssembler.writeFailedStatusReport(config, reactorRoot, "error analyzing POM changes");
                        }
                        return;
                    } else {
                        throw new MavenExecutionException("Scalpel: Error analyzing POM changes", e);
                    }
                }
                logger.debug("Modules affected by POM changes: {}", keys(affectedByPom));
            }

            // Derive changed managed dep/plugin GAs
            Set<String> changedManagedDepGAs =
                    transitiveImpactAnalyzer.deriveChangedManagedDeps(oldEffectiveModels, newEffectiveModels);
            Set<String> changedManagedPluginGAs =
                    transitiveImpactAnalyzer.deriveChangedManagedPlugins(oldEffectiveModels, newEffectiveModels);

            // Combine directly affected
            Set<MavenProject> directlyAffected = new LinkedHashSet<>();
            directlyAffected.addAll(affectedBySource);
            directlyAffected.addAll(affectedByPom);

            // Compute test-only modules
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
                    String pattern = forceBuildMatcher.matchesForceBuild(project, config.getForceBuildModules());
                    if (pattern != null) {
                        directlyAffected.add(project);
                        forceIncluded.add(project);
                        forceBuildPatterns.put(project, pattern);
                    }
                }
            }

            testOnlyModules.removeAll(forceIncluded);

            ChangesetContext cctx = new ChangesetContext(
                    config,
                    normalizedRoot,
                    allProjects,
                    changedFiles,
                    changedProperties,
                    changedManagedDepGAs,
                    changedManagedPluginGAs,
                    unmatchedPomPaths,
                    timings,
                    analysisStartNano);

            if (handleNoAffectedModules(
                    directlyAffected.isEmpty() && oldEffectiveModels.isEmpty(),
                    "no modules affected by changes",
                    cctx)) {
                return;
            }

            if (!directlyAffected.isEmpty()) {
                logger.info(
                        "Scalpel: {} modules directly affected: {}", directlyAffected.size(), keys(directlyAffected));
            }

            // Shared caches for dependency collection results
            Map<MavenProject, DependencyResolutionResult> collectCache = new LinkedHashMap<>();
            Map<MavenProject, DependencyResolutionResult> oldCollectCache = new LinkedHashMap<>();
            TransitiveImpactAnalyzer.ResolutionContext rctx = new TransitiveImpactAnalyzer.ResolutionContext(
                    normalizedRoot, session, collectCache, oldCollectCache, timings);
            TransitiveImpactAnalyzer.EffectiveModels models =
                    new TransitiveImpactAnalyzer.EffectiveModels(oldEffectiveModels, newEffectiveModels);

            // Compute transitively affected modules
            Map<MavenProject, List<String>> transitiveEvidence = new LinkedHashMap<>();
            Map<MavenProject, List<String>> transitivelyAffected;
            timings.start(Timings.PHASE_TRANSITIVE_RESOLVE);
            try {
                transitivelyAffected = transitiveImpactAnalyzer.computeTransitivelyAffected(
                        allProjects, directlyAffected, models, rctx, config.isExplain(), transitiveEvidence);
            } finally {
                timings.stop(Timings.PHASE_TRANSITIVE_RESOLVE);
            }

            // Explain-mode evidence
            Map<MavenProject, List<String>> evidence = config.isExplain()
                    ? buildEvidence(
                            sourceResult.getTriggeringFiles(),
                            pomEvidence,
                            transitiveEvidence,
                            forceIncluded,
                            forceBuildPatterns)
                    : Map.of();

            if (handleNoAffectedModules(
                    directlyAffected.isEmpty() && transitivelyAffected.isEmpty(),
                    "no modules affected by changes",
                    cctx)) {
                return;
            }

            // Include transitively affected modules
            Set<MavenProject> allAffected = new LinkedHashSet<>(directlyAffected);
            allAffected.addAll(transitivelyAffected.keySet());

            // Apply includePaths module filter
            List<PathMatcher> includeMatchers = ChangedFileClassifier.compileGlobMatchers(config.getIncludePaths());
            if (!includeMatchers.isEmpty()) {
                int beforeCount = allAffected.size();
                directlyAffected.removeIf(
                        p -> !ChangedFileClassifier.matchesIncludePaths(p, includeMatchers, normalizedRoot));
                transitivelyAffected
                        .keySet()
                        .removeIf(p -> !ChangedFileClassifier.matchesIncludePaths(p, includeMatchers, normalizedRoot));
                testOnlyModules.retainAll(directlyAffected);
                forceIncluded.retainAll(directlyAffected);

                allAffected = new LinkedHashSet<>(directlyAffected);
                allAffected.addAll(transitivelyAffected.keySet());

                int removedCount = beforeCount - allAffected.size();
                if (removedCount > 0) {
                    logger.info("Scalpel: {} modules excluded by includePaths filters", removedCount);
                }

                if (handleNoAffectedModules(allAffected.isEmpty(), "no modules match includePaths filters", cctx)) {
                    return;
                }
            }

            // Write impacted module log if configured
            if (config.getImpactedLog() != null) {
                impactedLogWriter.writeImpactedLog(config, normalizedRoot, allAffected);
            }

            if (config.isPassiveMode()) {
                // Compute upstream/downstream categorization for report enrichment
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

                reportAssembler.writeReport(
                        config,
                        normalizedRoot,
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
                    java.util.function.Function<MavenProject, String> moduleKey = project -> {
                        String path = ChangedFileClassifier.relativePath(normalizedRoot, project);
                        // The root aggregator relativizes to the empty string; report it as
                        // "." like the impacted log does (#84) so every module has a name.
                        return path.isEmpty() ? "." : path;
                    };
                    List<MavenProject> decisionBuildSet = decision.getBuildSet();
                    if (!includeMatchers.isEmpty()) {
                        Set<MavenProject> affected = allAffected;
                        decisionBuildSet = new ArrayList<>(decisionBuildSet);
                        decisionBuildSet.removeIf(project -> !affected.contains(project)
                                && !decision.getUpstreamOnly().contains(project)
                                && !ChangedFileClassifier.matchesIncludePaths(
                                        project, includeMatchers, normalizedRoot));
                    }
                    for (MavenProject project : decisionBuildSet) {
                        wouldHaveBuilt.add(moduleKey.apply(project));
                    }
                    Set<String> wouldHaveSkipped = new LinkedHashSet<>();
                    for (MavenProject project : allProjects) {
                        String path = moduleKey.apply(project);
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
                                    moduleKey));
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
                    skipTestsApplier.applySkipTests(allProjects, trimResult, config, models, includeMatchers, rctx);
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
                    Set<MavenProject> finalAllAffected = allAffected;
                    buildSet = new ArrayList<>(buildSet);
                    buildSet.removeIf(p -> !finalAllAffected.contains(p)
                            && !trimResult.getUpstreamOnly().contains(p)
                            && !ChangedFileClassifier.matchesIncludePaths(p, includeMatchers, normalizedRoot));
                }
                logger.info(
                        "Scalpel: Building {} of {} modules: {}", buildSet.size(), allProjects.size(), keys(buildSet));
                session.setProjects(buildSet);
                // Apply per-category args in trim mode
                skipTestsApplier.applyPerCategoryArgs(trimResult, config);
                if (config.isExplain()) {
                    logExplainDecisions(allProjects, new LinkedHashSet<>(buildSet), evidence);
                }
                // Write the same JSON report as report mode
                reportAssembler.writeReport(
                        config,
                        normalizedRoot,
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
                if (config.isModeShadow()) {
                    reportAssembler.writeShadowStatus(reactorRoot, "failed", e.getMessage());
                }
                return;
            }
            throw new MavenExecutionException("Scalpel: " + e.getMessage(), e);
        } catch (Exception e) {
            if (config.isFailSafe()) {
                logger.warn("Scalpel: Unexpected error, building all modules: {}", e.getMessage());
                logger.debug("Unexpected error details", e);
                if (config.isPassiveMode()) {
                    reportAssembler.writeFailedStatusReport(config, reactorRoot, "unexpected error: " + e.getMessage());
                }
                return;
            }
            throw new MavenExecutionException("Scalpel: " + e.getMessage(), e);
        } finally {
            logAnalysisSummary(timings, analysisStartNano);
        }
    }

    /**
     * Bundles the changeset-level context needed by {@link #handleNoAffectedModules}.
     */
    record ChangesetContext(
            ScalpelConfiguration config,
            Path reactorRoot,
            List<MavenProject> allProjects,
            Set<String> changedFiles,
            Set<String> changedProperties,
            Set<String> changedManagedDepGAs,
            Set<String> changedManagedPluginGAs,
            Set<String> unmatchedPomPaths,
            Timings timings,
            long analysisStartNano) {}

    /**
     * Handles the triplicated 'no affected modules' pattern. Returns {@code true} if the
     * condition was met and the caller should return early.
     */
    private boolean handleNoAffectedModules(boolean noAffectedCondition, String reason, ChangesetContext cctx)
            throws MavenExecutionException {
        if (!noAffectedCondition) {
            return false;
        }
        logger.info("Scalpel: No modules affected by changes");
        if (cctx.config().isPassiveMode()) {
            if (cctx.config().isModeShadow()) {
                reportAssembler.writeShadowStatus(cctx.reactorRoot(), "skipped", reason);
            }
            reportAssembler.writeReport(
                    cctx.config(),
                    cctx.reactorRoot(),
                    cctx.allProjects(),
                    AnalysisContext.empty(
                            cctx.changedFiles(),
                            cctx.changedProperties(),
                            cctx.changedManagedDepGAs(),
                            cctx.changedManagedPluginGAs(),
                            cctx.unmatchedPomPaths()),
                    cctx.timings(),
                    cctx.analysisStartNano());
        } else if (cctx.config().isModeSkipTests()) {
            skipTestsApplier.skipTestsOnAll(cctx.allProjects());
        }
        return true;
    }

    /**
     * One greppable INFO line answering "how long did Scalpel take and where did it go".
     */
    private void logAnalysisSummary(Timings timings, long analysisStartNano) {
        StringBuilder line = new StringBuilder("Scalpel: analysis took ")
                .append(ReportAssembler.millisSince(analysisStartNano))
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

    /**
     * Assembles explain-mode evidence per module.
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

    // ---- Static forwarders for backward compatibility with existing tests ----

    static String normalizeGlobPattern(String pattern) {
        return ChangedFileClassifier.normalizeGlobPattern(pattern);
    }

    static boolean isSafeImpactedLogPath(String path) {
        return ImpactedLogWriter.isSafeImpactedLogPath(path);
    }
}
