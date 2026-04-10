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
import eu.maveniverse.maven.scalpel.core.ScalpelReport;
import eu.maveniverse.maven.scalpel.core.Version;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.PatternSyntaxException;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.project.DefaultDependencyResolutionRequest;
import org.apache.maven.project.DependencyResolutionException;
import org.apache.maven.project.DependencyResolutionResult;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectDependenciesResolver;
import org.eclipse.aether.graph.Dependency;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
@Named
class ScalpelLifecycleParticipant extends AbstractMavenLifecycleParticipant {

    private static final String MAVEN_TEST_SKIP = "maven.test.skip";
    private static final String GLOB_PREFIX = "glob:";

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final ScalpelCore scalpelCore;
    private final ModuleMapper moduleMapper;
    private final PomChangeAnalyzer pomChangeAnalyzer;
    private final ReactorTrimmer reactorTrimmer;
    private final ProjectDependenciesResolver dependenciesResolver;

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
        ScalpelConfiguration config =
                ScalpelConfiguration.fromProperties(session.getSystemProperties(), session.getUserProperties());

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

        try {
            // Collect ALL reactor POM paths
            Set<String> allPomPaths = new LinkedHashSet<>();
            for (MavenProject project : allProjects) {
                Path pomPath = project.getFile().toPath().toAbsolutePath().normalize();
                Path relativePom = reactorRoot.toAbsolutePath().normalize().relativize(pomPath);
                allPomPaths.add(relativePom.toString());
            }

            // Detect changes
            ChangeDetectionResult result = scalpelCore.detectChanges(reactorRoot, config, allPomPaths);
            if (result == null) {
                return;
            }

            Set<String> changedFiles = result.getChangedFiles();
            if (changedFiles.isEmpty()) {
                if (config.isBuildAllIfNoChanges()) {
                    logger.info("Scalpel: No changes detected, building all modules (buildAllIfNoChanges=true)");
                }
                return;
            }

            logger.info("Scalpel: {} changed files detected", changedFiles.size());

            // Check disable triggers (bail out entirely if any changed file matches)
            if (matchesDisableTrigger(changedFiles, config)) {
                return;
            }

            // Filter out excluded paths
            changedFiles = filterExcludedPaths(changedFiles, config);
            if (changedFiles.isEmpty()) {
                logger.info("Scalpel: All changed files excluded by path filters, building all modules");
                return;
            }

            // Check full build triggers
            String triggerFile = findFullBuildTrigger(changedFiles, config);
            if (triggerFile != null) {
                if (config.isModeReport()) {
                    writeFullBuildReport(config, reactorRoot, triggerFile, changedFiles);
                }
                return;
            }

            // Separate POM changes from source changes
            Set<String> pomChanges = new LinkedHashSet<>();
            Set<String> sourceChanges = new LinkedHashSet<>();
            for (String file : changedFiles) {
                if (file.endsWith("/pom.xml") || file.equals("pom.xml")) {
                    pomChanges.add(file);
                } else {
                    sourceChanges.add(file);
                }
            }

            // Map source changes to modules (classifying test-only vs main changes)
            ModuleMapper.Result sourceResult =
                    moduleMapper.mapToProjectsClassified(sourceChanges, allProjects, reactorRoot);
            Set<MavenProject> affectedBySource = sourceResult.getAllAffected();
            logger.debug("Modules affected by source changes: {}", keys(affectedBySource));
            if (!sourceResult.getTestOnlyAffected().isEmpty()) {
                logger.debug(
                        "Test-only modules (no downstream propagation): {}", keys(sourceResult.getTestOnlyAffected()));
            }

            // Analyze POM changes directly (no model building needed)
            Set<MavenProject> affectedByPom = new LinkedHashSet<>();
            Set<String> changedManagedDepGAs = new LinkedHashSet<>();
            Set<String> changedManagedPluginGAs = new LinkedHashSet<>();
            Set<String> changedProperties = new LinkedHashSet<>();
            if (!pomChanges.isEmpty()) {
                logger.debug("POM changes detected: {}", pomChanges);
                try {
                    PomChangeAnalyzer.Result pomResult = pomChangeAnalyzer.analyzeChanges(
                            pomChanges, result.getOldPomContents(), allProjects, reactorRoot);
                    affectedByPom = pomResult.getAffectedProjects();
                    changedManagedDepGAs = pomResult.getChangedManagedDependencyGAs();
                    changedManagedPluginGAs = pomResult.getChangedManagedPluginGAs();
                    changedProperties = pomResult.getChangedProperties();
                } catch (Exception e) {
                    if (config.isFailSafe()) {
                        logger.warn("Scalpel: Error analyzing POM changes, building all modules: {}", e.getMessage());
                        logger.debug("POM analysis error details", e);
                        return;
                    } else {
                        throw new MavenExecutionException("Scalpel: Error analyzing POM changes", e);
                    }
                }
                logger.debug("Modules affected by POM changes: {}", keys(affectedByPom));
                if (!changedManagedDepGAs.isEmpty()) {
                    logger.debug("Changed managed dependency GAs: {}", changedManagedDepGAs);
                }
                if (!changedManagedPluginGAs.isEmpty()) {
                    logger.debug("Changed managed plugin GAs: {}", changedManagedPluginGAs);
                }
            }

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
            if (!config.getForceBuildModules().isEmpty()) {
                for (MavenProject project : allProjects) {
                    if (directlyAffected.contains(project)) {
                        continue;
                    }
                    for (String pattern : config.getForceBuildModules()) {
                        try {
                            if (project.getArtifactId().matches(pattern)) {
                                directlyAffected.add(project);
                                forceIncluded.add(project);
                                logger.debug("Scalpel: Force-including module {} (matches {})", key(project), pattern);
                                break;
                            }
                        } catch (PatternSyntaxException e) {
                            logger.warn(
                                    "Scalpel: Invalid regex pattern '{}' in forceBuildModules: {}",
                                    pattern,
                                    e.getMessage());
                        }
                    }
                }
            }

            // Force-included modules are not test-only
            testOnlyModules.removeAll(forceIncluded);

            if (directlyAffected.isEmpty()) {
                logger.info("Scalpel: No modules affected by changes");
                if (config.isModeReport()) {
                    writeReport(
                            config,
                            reactorRoot,
                            AnalysisContext.empty(
                                    changedFiles, changedProperties, changedManagedDepGAs, changedManagedPluginGAs));
                } else if (config.isModeSkipTests()) {
                    skipTestsOnAll(allProjects);
                }
                return;
            }

            logger.info("Scalpel: {} modules directly affected: {}", directlyAffected.size(), keys(directlyAffected));

            // Write impacted module log if configured
            if (config.getImpactedLog() != null) {
                writeImpactedLog(config, reactorRoot, directlyAffected);
            }

            if (config.isModeReport()) {
                // Compute upstream/downstream categorization for report enrichment
                TrimResult trimResult = reactorTrimmer.computeBuildSet(
                        directlyAffected, testOnlyModules, session.getProjectDependencyGraph(), config);

                Map<MavenProject, List<String>> transitivelyAffected = computeTransitivelyAffected(
                        allProjects, directlyAffected, changedManagedDepGAs, changedManagedPluginGAs, session);

                writeReport(
                        config,
                        reactorRoot,
                        new AnalysisContext(
                                changedFiles,
                                changedProperties,
                                changedManagedDepGAs,
                                changedManagedPluginGAs,
                                directlyAffected,
                                affectedBySource,
                                sourceResult.getTestOnlyAffected(),
                                affectedByPom,
                                forceIncluded,
                                transitivelyAffected,
                                trimResult));
                return;
            }

            // Compute full build set with upstream/downstream
            TrimResult trimResult = reactorTrimmer.computeBuildSet(
                    directlyAffected, testOnlyModules, session.getProjectDependencyGraph(), config);

            if (config.isModeSkipTests()) {
                applySkipTests(session, allProjects, trimResult, config, changedManagedDepGAs, changedManagedPluginGAs);
            } else {
                // trim mode: remove unaffected projects from reactor
                logger.info(
                        "Scalpel: Building {} of {} modules: {}",
                        trimResult.getBuildSet().size(),
                        allProjects.size(),
                        keys(trimResult.getBuildSet()));
                session.setProjects(trimResult.getBuildSet());
                // Apply per-category args in trim mode
                applyPerCategoryArgs(trimResult, config);
            }

        } catch (ScalpelException e) {
            throw new MavenExecutionException("Scalpel: " + e.getMessage(), e);
        }
    }

    private boolean matchesDisableTrigger(Set<String> changedFiles, ScalpelConfiguration config) {
        for (String pattern : config.getDisableTriggers()) {
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher(GLOB_PREFIX + pattern);
            for (String changedFile : changedFiles) {
                if (matcher.matches(Paths.get(changedFile))) {
                    logger.info(
                            "Scalpel: Disabled due to change in {} (matches disable trigger {})", changedFile, pattern);
                    return true;
                }
            }
        }
        return false;
    }

    private Set<String> filterExcludedPaths(Set<String> changedFiles, ScalpelConfiguration config) {
        if (config.getExcludePaths().isEmpty()) {
            return changedFiles;
        }
        List<PathMatcher> excludeMatchers = new ArrayList<>();
        for (String pattern : config.getExcludePaths()) {
            excludeMatchers.add(FileSystems.getDefault().getPathMatcher(GLOB_PREFIX + pattern));
        }
        Set<String> filtered = new LinkedHashSet<>();
        for (String file : changedFiles) {
            boolean excluded = false;
            for (PathMatcher matcher : excludeMatchers) {
                if (matcher.matches(Paths.get(file))) {
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

    private String findFullBuildTrigger(Set<String> changedFiles, ScalpelConfiguration config) {
        for (String pattern : config.getFullBuildTriggers()) {
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher(GLOB_PREFIX + pattern);
            for (String changedFile : changedFiles) {
                if (matcher.matches(Paths.get(changedFile))) {
                    logger.info("Scalpel: Full build triggered by change to {} (matches {})", changedFile, pattern);
                    return changedFile;
                }
            }
        }
        return null;
    }

    private Map<MavenProject, List<String>> computeTransitivelyAffected(
            List<MavenProject> allProjects,
            Set<MavenProject> directlyAffected,
            Set<String> changedManagedDepGAs,
            Set<String> changedManagedPluginGAs,
            MavenSession session) {
        Map<MavenProject, List<String>> transitivelyAffected = new LinkedHashMap<>();
        if (changedManagedDepGAs.isEmpty() && changedManagedPluginGAs.isEmpty()) {
            return transitivelyAffected;
        }
        Map<MavenProject, DependencyResolutionResult> resolveCache = new LinkedHashMap<>();
        for (MavenProject project : allProjects) {
            if (directlyAffected.contains(project)) {
                continue;
            }
            List<String> reasons = new ArrayList<>();
            if (!changedManagedPluginGAs.isEmpty() && usesChangedPlugin(project, changedManagedPluginGAs)) {
                reasons.add(ScalpelReport.REASON_MANAGED_PLUGIN);
            }
            if (!changedManagedDepGAs.isEmpty()) {
                String depScope =
                        getChangedTransitiveDependencyScope(project, session, changedManagedDepGAs, resolveCache);
                if (depScope != null) {
                    if ("test".equals(depScope)) {
                        reasons.add(ScalpelReport.REASON_TRANSITIVE_DEPENDENCY_TEST);
                    } else {
                        reasons.add(ScalpelReport.REASON_TRANSITIVE_DEPENDENCY);
                    }
                }
            }
            if (!reasons.isEmpty()) {
                transitivelyAffected.put(project, reasons);
            }
        }
        if (!transitivelyAffected.isEmpty()) {
            logger.info(
                    "Scalpel: {} modules transitively affected: {}",
                    transitivelyAffected.size(),
                    keys(transitivelyAffected.keySet()));
        }
        return transitivelyAffected;
    }

    private void applySkipTests(
            MavenSession session,
            List<MavenProject> allProjects,
            TrimResult trimResult,
            ScalpelConfiguration config,
            Set<String> changedManagedDepGAs,
            Set<String> changedManagedPluginGAs) {

        Set<MavenProject> buildSetLookup = new LinkedHashSet<>(trimResult.getBuildSet());
        List<MavenProject> testProjects = new ArrayList<>();
        List<MavenProject> skippedProjects = new ArrayList<>();
        Map<MavenProject, DependencyResolutionResult> resolveCache = new LinkedHashMap<>();

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
                    session,
                    changedManagedPluginGAs,
                    changedManagedDepGAs,
                    resolveCache)) {
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

            // Check effective build plugins against changed managed plugins
            if (!changedManagedPluginGAs.isEmpty() && usesChangedPlugin(project, changedManagedPluginGAs)) {
                testProjects.add(project);
                continue;
            }

            // Check transitive dependencies if managed deps changed
            if (!changedManagedDepGAs.isEmpty()
                    && hasChangedTransitiveDependency(project, session, changedManagedDepGAs, resolveCache)) {
                testProjects.add(project);
                continue;
            }

            // Skip tests on this project
            project.getProperties().setProperty(MAVEN_TEST_SKIP, "true");
            skippedProjects.add(project);
        }

        // Apply per-category args
        applyPerCategoryArgs(trimResult, config);

        logger.info(
                "Scalpel: Testing {} of {} modules, skipping tests on {} modules: {}",
                testProjects.size(),
                allProjects.size(),
                skippedProjects.size(),
                keys(skippedProjects));
    }

    private boolean usesChangedPlugin(MavenProject project, Set<String> changedPluginGAs) {
        for (Plugin plugin : project.getBuildPlugins()) {
            String ga = plugin.getGroupId() + ":" + plugin.getArtifactId();
            if (changedPluginGAs.contains(ga)) {
                logger.debug("Module {} uses changed managed plugin {}", key(project), ga);
                return true;
            }
        }
        return false;
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
            MavenSession session,
            Set<String> changedManagedPluginGAs,
            Set<String> changedManagedDepGAs,
            Map<MavenProject, DependencyResolutionResult> resolveCache) {
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
        // Safety guard: don't skip tests if the module also has changed managed plugins or deps
        if (!changedManagedPluginGAs.isEmpty() && usesChangedPlugin(project, changedManagedPluginGAs)) {
            return false;
        }
        return changedManagedDepGAs.isEmpty()
                || !hasChangedTransitiveDependency(project, session, changedManagedDepGAs, resolveCache);
    }

    private boolean hasChangedTransitiveDependency(
            MavenProject project,
            MavenSession session,
            Set<String> changedGAs,
            Map<MavenProject, DependencyResolutionResult> resolveCache) {
        return getChangedTransitiveDependencyScope(project, session, changedGAs, resolveCache) != null;
    }

    /**
     * Returns the effective scope of the changed transitive dependency, or null if no match.
     * If any matching dependency has compile/runtime/provided scope, returns that scope.
     * If all matching dependencies are test-scoped, returns "test".
     */
    private String getChangedTransitiveDependencyScope(
            MavenProject project,
            MavenSession session,
            Set<String> changedGAs,
            Map<MavenProject, DependencyResolutionResult> resolveCache) {
        try {
            DependencyResolutionResult result = resolveCache.get(project);
            if (result == null) {
                DefaultDependencyResolutionRequest request =
                        new DefaultDependencyResolutionRequest(project, session.getRepositorySession());
                result = dependenciesResolver.resolve(request);
                resolveCache.put(project, result);
            }

            String narrowestScope = null;
            for (Dependency dep : result.getResolvedDependencies()) {
                String ga =
                        dep.getArtifact().getGroupId() + ":" + dep.getArtifact().getArtifactId();
                if (changedGAs.contains(ga)) {
                    String scope = dep.getScope();
                    logger.debug(
                            "Module {} has transitive dependency on changed managed dep {} (scope={})",
                            key(project),
                            ga,
                            scope);
                    // Non-test scope means production code is affected
                    if (scope == null || !"test".equals(scope)) {
                        return scope != null ? scope : "compile";
                    }
                    narrowestScope = "test";
                }
            }
            return narrowestScope;
        } catch (DependencyResolutionException e) {
            // Conservative: if we can't resolve, don't skip tests
            logger.debug("Cannot resolve dependencies for {}, not skipping tests: {}", key(project), e.getMessage());
            return "compile";
        }
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
                lines.add(relativePath(reactorRoot, project));
            }
            Files.write(logPath, lines, StandardCharsets.UTF_8);
            logger.info("Scalpel: Impacted modules written to {}", config.getImpactedLog());
        } catch (IOException e) {
            throw new MavenExecutionException("Scalpel: Failed to write impacted log", e);
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
            throw new MavenExecutionException("Scalpel: Failed to write report", e);
        }
    }

    private void writeReport(ScalpelConfiguration config, Path reactorRoot, AnalysisContext ctx)
            throws MavenExecutionException {
        ScalpelReport.Builder builder = ScalpelReport.builder()
                .baseBranch(config.getBaseBranch())
                .fullBuildTriggered(false)
                .changedFiles(ctx.changedFiles)
                .changedProperties(ctx.changedProperties)
                .changedManagedDependencies(ctx.changedManagedDepGAs)
                .changedManagedPlugins(ctx.changedManagedPluginGAs);

        addDirectlyAffectedModules(builder, ctx, reactorRoot);
        addTransitivelyAffectedModules(builder, ctx, reactorRoot);
        addTrimResultModules(builder, ctx, config, reactorRoot);

        try {
            ScalpelReport report = builder.build();
            report.writeToFile(reactorRoot, config.getReportFile());
            logger.info("Scalpel: Report written to {}", config.getReportFile());
        } catch (IOException e) {
            throw new MavenExecutionException("Scalpel: Failed to write report", e);
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
            builder.addAffectedModule(ScalpelReport.AffectedModule.moduleBuilder(
                            project.getGroupId(), project.getArtifactId(), path, reasons)
                    .category(ScalpelReport.CATEGORY_DIRECT)
                    .sourceSet(sourceSet)
                    .build());
        }
    }

    private void addTransitivelyAffectedModules(ScalpelReport.Builder builder, AnalysisContext ctx, Path reactorRoot) {
        for (Map.Entry<MavenProject, List<String>> entry : ctx.transitivelyAffected.entrySet()) {
            MavenProject project = entry.getKey();
            String path = relativePath(reactorRoot, project);
            String category = null;
            if (ctx.trimResult != null) {
                if (ctx.trimResult.getUpstreamOnly().contains(project)) {
                    category = ScalpelReport.CATEGORY_UPSTREAM;
                } else if (ctx.trimResult.getDownstreamOnly().contains(project)
                        || ctx.trimResult.getDownstreamTestOnly().contains(project)) {
                    category = ScalpelReport.CATEGORY_DOWNSTREAM;
                }
            }
            builder.addAffectedModule(ScalpelReport.AffectedModule.moduleBuilder(
                            project.getGroupId(), project.getArtifactId(), path, entry.getValue())
                    .category(category)
                    .build());
        }
    }

    private void addTrimResultModules(
            ScalpelReport.Builder builder, AnalysisContext ctx, ScalpelConfiguration config, Path reactorRoot) {
        if (ctx.trimResult == null) {
            return;
        }
        for (MavenProject project : ctx.trimResult.getUpstreamOnly()) {
            if (!ctx.directlyAffected.contains(project) && !ctx.transitivelyAffected.containsKey(project)) {
                String path = relativePath(reactorRoot, project);
                builder.addAffectedModule(ScalpelReport.AffectedModule.moduleBuilder(
                                project.getGroupId(),
                                project.getArtifactId(),
                                path,
                                Collections.singletonList(ScalpelReport.REASON_UPSTREAM_DEPENDENCY))
                        .category(ScalpelReport.CATEGORY_UPSTREAM)
                        .build());
            }
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
    }

    private void addDownstreamModules(
            ScalpelReport.Builder builder,
            AnalysisContext ctx,
            ScalpelConfiguration config,
            Path reactorRoot,
            Set<MavenProject> downstreamProjects,
            String reason) {
        for (MavenProject project : downstreamProjects) {
            if (!ctx.directlyAffected.contains(project) && !ctx.transitivelyAffected.containsKey(project)) {
                String path = relativePath(reactorRoot, project);
                String testsSkippedReason =
                        matchesDownstreamExclusion(project, config.getSkipTestsForDownstreamModules())
                                ? ScalpelReport.REASON_EXCLUDED_DOWNSTREAM
                                : null;
                builder.addAffectedModule(ScalpelReport.AffectedModule.moduleBuilder(
                                project.getGroupId(), project.getArtifactId(), path, Collections.singletonList(reason))
                        .category(ScalpelReport.CATEGORY_DOWNSTREAM)
                        .testsSkippedReason(testsSkippedReason)
                        .build());
            }
        }
    }

    private static String relativePath(Path reactorRoot, MavenProject project) {
        return reactorRoot
                .toAbsolutePath()
                .normalize()
                .relativize(project.getBasedir().toPath().toAbsolutePath().normalize())
                .toString();
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

    private void skipTestsOnAll(List<MavenProject> projects) {
        for (MavenProject project : projects) {
            project.getProperties().setProperty(MAVEN_TEST_SKIP, "true");
        }
    }
}
