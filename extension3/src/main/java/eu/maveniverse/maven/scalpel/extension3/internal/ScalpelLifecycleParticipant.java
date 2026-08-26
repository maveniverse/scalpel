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
import java.util.ArrayList;
import java.util.HashSet;
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

        try {
            // Collect ALL reactor POM paths
            Set<String> allPomPaths = new LinkedHashSet<>();
            for (MavenProject project : allProjects) {
                Path pomPath = project.getFile().toPath().toAbsolutePath().normalize();
                Path relativePom = reactorRoot.toAbsolutePath().normalize().relativize(pomPath);
                allPomPaths.add(relativePom.toString().replace('\\', '/'));
            }

            // Detect changes
            ChangeDetectionResult result = scalpelCore.detectChanges(reactorRoot, config, allPomPaths);
            if (result == null) {
                if (config.isModeReport()) {
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
                if (config.isModeReport()) {
                    writeStatusReport(config, reactorRoot, "skipped", "no changes detected");
                }
                return;
            }

            logger.info("Scalpel: {} changed files detected", changedFiles.size());

            // Check disable triggers (bail out entirely if any changed file matches)
            if (matchesDisableTrigger(changedFiles, config)) {
                if (config.isModeReport()) {
                    writeStatusReport(config, reactorRoot, "skipped", "disabled by disableTriggers match");
                }
                return;
            }

            // Filter out excluded paths
            changedFiles = filterExcludedPaths(changedFiles, config);
            if (changedFiles.isEmpty()) {
                logger.info("Scalpel: All changed files excluded by path filters, building all modules");
                if (config.isModeReport()) {
                    writeStatusReport(config, reactorRoot, "skipped", "all changed files excluded by path filters");
                }
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
            ModuleMapper.Result sourceResult =
                    moduleMapper.mapToProjectsClassified(sourceChanges, allProjects, reactorRoot, config.isExplain());
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
            Map<MavenProject, Set<String>> pomEvidence = Map.of();
            Set<String> unmatchedPomPaths = new LinkedHashSet<>();
            if (!pomChanges.isEmpty()) {
                logger.debug("POM changes detected: {}", pomChanges);
                try {
                    PomChangeAnalyzer.Result pomResult = pomChangeAnalyzer.analyzeChanges(
                            pomChanges,
                            result.getOldPomContents(),
                            allProjects,
                            reactorRoot,
                            config.getMaxResourceFileSize(),
                            config.isExplain());
                    affectedByPom = pomResult.getAffectedProjects();
                    changedManagedDepGAs = pomResult.getChangedManagedDependencyGAs();
                    changedManagedPluginGAs = pomResult.getChangedManagedPluginGAs();
                    changedProperties = pomResult.getChangedProperties();
                    pomEvidence = pomResult.getEvidence();
                    unmatchedPomPaths.addAll(pomResult.getUnmatchedPomPaths());
                } catch (Exception e) {
                    if (config.isFailSafe()) {
                        logger.warn("Scalpel: Error analyzing POM changes, building all modules: {}", e.getMessage());
                        logger.debug("POM analysis error details", e);
                        if (config.isModeReport()) {
                            writeFailedStatusReport(config, reactorRoot, "error analyzing POM changes");
                        }
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

            if (directlyAffected.isEmpty() && changedManagedDepGAs.isEmpty() && changedManagedPluginGAs.isEmpty()) {
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

            if (!directlyAffected.isEmpty()) {
                logger.info(
                        "Scalpel: {} modules directly affected: {}", directlyAffected.size(), keys(directlyAffected));
            }

            // Single shared cache for dependency collection results — used by both
            // computeTransitivelyAffected and applySkipTests to avoid resolving the same
            // module twice.
            Map<MavenProject, DependencyResolutionResult> collectCache = new LinkedHashMap<>();

            // Compute transitively affected modules (via changed managed deps/plugins)
            Map<MavenProject, List<String>> transitiveEvidence = new LinkedHashMap<>();
            Map<MavenProject, List<String>> transitivelyAffected = computeTransitivelyAffected(
                    allProjects,
                    directlyAffected,
                    changedManagedDepGAs,
                    changedManagedPluginGAs,
                    session,
                    collectCache,
                    config.isExplain(),
                    transitiveEvidence);

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
                    if (config.isModeReport()) {
                        writeReport(
                                config,
                                reactorRoot,
                                AnalysisContext.empty(
                                        changedFiles,
                                        changedProperties,
                                        changedManagedDepGAs,
                                        changedManagedPluginGAs));
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

            if (config.isModeReport()) {
                // Compute upstream/downstream categorization for report enrichment
                // Use directlyAffected here (not allAffected) to preserve correct DOWNSTREAM categorization;
                // transitively affected modules are added to the report separately via addTransitivelyAffectedModules
                TrimResult trimResult = directlyAffected.isEmpty()
                        ? null
                        : reactorTrimmer.computeBuildSet(
                                directlyAffected, testOnlyModules, session.getProjectDependencyGraph(), config);
                if (trimResult != null && config.isExplain()) {
                    mergeTrimReasons(evidence, trimResult);
                }

                writeReport(
                        config,
                        reactorRoot,
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
                                .build());
                if (config.isExplain()) {
                    Set<MavenProject> reportedModules = new LinkedHashSet<>(allAffected);
                    if (trimResult != null) {
                        reportedModules.addAll(trimResult.getDownstreamOnly());
                        reportedModules.addAll(trimResult.getDownstreamTestOnly());
                    }
                    logExplainDecisions(allProjects, reportedModules, evidence);
                }
                return;
            }

            // Compute full build set with upstream/downstream
            TrimResult trimResult = reactorTrimmer.computeBuildSet(
                    allAffected, testOnlyModules, session.getProjectDependencyGraph(), config);
            if (config.isExplain()) {
                mergeTrimReasons(evidence, trimResult);
            }

            if (config.isModeSkipTests()) {
                applySkipTests(
                        session,
                        allProjects,
                        trimResult,
                        config,
                        changedManagedDepGAs,
                        changedManagedPluginGAs,
                        includeMatchers,
                        reactorRoot,
                        collectCache);
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
                if (config.isModeReport()) {
                    writeFailedStatusReport(config, reactorRoot, "unexpected error: " + e.getMessage());
                }
                return;
            }
            throw new MavenExecutionException("Scalpel: " + e.getMessage(), e);
        }
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

    private Map<MavenProject, List<String>> computeTransitivelyAffected(
            List<MavenProject> allProjects,
            Set<MavenProject> directlyAffected,
            Set<String> changedManagedDepGAs,
            Set<String> changedManagedPluginGAs,
            MavenSession session,
            Map<MavenProject, DependencyResolutionResult> collectCache,
            boolean explain,
            Map<MavenProject, List<String>> returnEvidence) {
        Map<MavenProject, List<String>> transitivelyAffected = new LinkedHashMap<>();
        Map<MavenProject, List<String>> transitiveEvidence = new LinkedHashMap<>();
        if (changedManagedDepGAs.isEmpty() && changedManagedPluginGAs.isEmpty()) {
            logger.debug("Skipping transitive analysis: no changed managed dependencies or plugins to check against");
            return transitivelyAffected;
        }
        logger.debug(
                "Computing transitively affected modules: checking {} non-direct modules against {} changed managed deps and {} changed managed plugins",
                allProjects.size() - directlyAffected.size(),
                changedManagedDepGAs.size(),
                changedManagedPluginGAs.size());
        for (MavenProject project : allProjects) {
            if (directlyAffected.contains(project)) {
                continue;
            }
            TransitiveMatch match = computeTransitiveMatch(
                    project, changedManagedDepGAs, changedManagedPluginGAs, session, collectCache, explain);
            if (!match.reasons.isEmpty()) {
                transitivelyAffected.put(project, match.reasons);
                if (explain) {
                    transitiveEvidence.put(project, match.evidence);
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

    private TransitiveMatch computeTransitiveMatch(
            MavenProject project,
            Set<String> changedManagedDepGAs,
            Set<String> changedManagedPluginGAs,
            MavenSession session,
            Map<MavenProject, DependencyResolutionResult> collectCache,
            boolean explain) {
        List<String> reasons = new ArrayList<>();
        List<String> evidence = explain ? new ArrayList<>() : List.of();
        if (!changedManagedPluginGAs.isEmpty()) {
            String changedPlugin = findChangedPlugin(project, changedManagedPluginGAs);
            if (changedPlugin != null) {
                reasons.add(ScalpelReport.REASON_MANAGED_PLUGIN);
                if (explain) {
                    evidence.add("managed plugin " + changedPlugin);
                }
            }
        }
        if (!changedManagedDepGAs.isEmpty()) {
            ChangedDependencyMatch match =
                    getChangedTransitiveDependencyMatch(project, session, changedManagedDepGAs, collectCache);
            if (match != null) {
                if ("test".equals(match.scope)) {
                    reasons.add(ScalpelReport.REASON_TRANSITIVE_DEPENDENCY_TEST);
                } else {
                    reasons.add(ScalpelReport.REASON_TRANSITIVE_DEPENDENCY);
                }
                if (explain) {
                    evidence.add("managed dep " + match.ga);
                }
            }
        }
        return new TransitiveMatch(reasons, evidence);
    }

    private void applySkipTests(
            MavenSession session,
            List<MavenProject> allProjects,
            TrimResult trimResult,
            ScalpelConfiguration config,
            Set<String> changedManagedDepGAs,
            Set<String> changedManagedPluginGAs,
            List<PathMatcher> includeMatchers,
            Path reactorRoot,
            Map<MavenProject, DependencyResolutionResult> collectCache) {

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
                    session,
                    changedManagedPluginGAs,
                    changedManagedDepGAs,
                    collectCache)) {
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

            // Check effective build plugins against changed managed plugins
            if (!changedManagedPluginGAs.isEmpty() && usesChangedPlugin(project, changedManagedPluginGAs)) {
                testProjects.add(project);
                continue;
            }

            // Check transitive dependencies if managed deps changed
            if (!changedManagedDepGAs.isEmpty()
                    && hasChangedTransitiveDependency(project, session, changedManagedDepGAs, collectCache)) {
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

    private boolean usesChangedPlugin(MavenProject project, Set<String> changedPluginGAs) {
        return findChangedPlugin(project, changedPluginGAs) != null;
    }

    /**
     * Returns the first changed managed plugin GA used by the project, or null.
     */
    private String findChangedPlugin(MavenProject project, Set<String> changedPluginGAs) {
        for (Plugin plugin : project.getBuildPlugins()) {
            String ga = plugin.getGroupId() + ":" + plugin.getArtifactId();
            if (changedPluginGAs.contains(ga)) {
                logger.debug("Module {} uses changed managed plugin {}", key(project), ga);
                return ga;
            }
        }
        return null;
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
            Map<MavenProject, DependencyResolutionResult> collectCache) {
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
                || !hasChangedTransitiveDependency(project, session, changedManagedDepGAs, collectCache);
    }

    private boolean hasChangedTransitiveDependency(
            MavenProject project,
            MavenSession session,
            Set<String> changedGAs,
            Map<MavenProject, DependencyResolutionResult> collectCache) {
        return getChangedTransitiveDependencyMatch(project, session, changedGAs, collectCache) != null;
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
     * Returns the effective scope of the changed transitive dependency, or null if no match.
     * If any matching dependency has compile/runtime/provided scope, returns that scope.
     * If all matching dependencies are test-scoped, returns "test".
     * <p>
     * Uses a reject-all DependencyFilter so that only the dependency graph is collected
     * without downloading any artifact files — we only need GA coordinates and scopes.
     */
    private static final class ChangedDependencyMatch {
        final String ga;
        final String scope;

        ChangedDependencyMatch(String ga, String scope) {
            this.ga = ga;
            this.scope = scope;
        }
    }

    private ChangedDependencyMatch getChangedTransitiveDependencyMatch(
            MavenProject project,
            MavenSession session,
            Set<String> changedGAs,
            Map<MavenProject, DependencyResolutionResult> collectCache) {
        DependencyResolutionResult result = collectCache.get(project);
        if (result == null) {
            try {
                DefaultDependencyResolutionRequest request =
                        new DefaultDependencyResolutionRequest(project, session.getRepositorySession());
                // Reject-all filter: collects the dependency graph without downloading artifacts
                request.setResolutionFilter(COLLECT_ONLY_FILTER);
                result = dependenciesResolver.resolve(request);
            } catch (DependencyResolutionException e) {
                result = e.getResult();
                if (result == null) {
                    logger.debug(
                            "Cannot collect dependencies for {}, no partial results available: {}",
                            key(project),
                            e.getMessage());
                    return null;
                }
                logger.debug(
                        "Partial dependency collection for {}, checking available results: {}",
                        key(project),
                        e.getMessage());
            }
            collectCache.put(project, result);
        }

        // Walk the dependency graph tree to find matching GAs and their scopes.
        // The reject-all filter means getResolvedDependencies() is empty, but
        // getDependencyGraph() still contains the full collected tree.
        DependencyNode root = result.getDependencyGraph();
        if (root == null) {
            return null;
        }
        return findChangedDependency(root, changedGAs, project);
    }

    /**
     * Walks the dependency graph tree to find any dependency matching the changed GAs,
     * returning the match with the narrowest non-test scope (or "test" if only test-scoped matches).
     */
    private ChangedDependencyMatch findChangedDependency(
            DependencyNode root, Set<String> changedGAs, MavenProject project) {
        String narrowestScope = null;
        String narrowestGa = null;
        Set<String> visited = new HashSet<>();
        List<DependencyNode> stack = new ArrayList<>();
        stack.addAll(root.getChildren());
        while (!stack.isEmpty()) {
            DependencyNode node = stack.remove(stack.size() - 1);
            Dependency dep = node.getDependency();
            if (dep == null) {
                continue;
            }
            String ga = dep.getArtifact().getGroupId() + ":" + dep.getArtifact().getArtifactId();
            if (!visited.add(ga)) {
                continue; // already visited this GA
            }
            if (changedGAs.contains(ga)) {
                String scope = dep.getScope();
                logger.debug(
                        "Module {} has transitive dependency on changed managed dep {} (scope={})",
                        key(project),
                        ga,
                        scope);
                if (scope == null || !"test".equals(scope)) {
                    return new ChangedDependencyMatch(ga, scope != null ? scope : "compile");
                }
                if (narrowestScope == null) {
                    narrowestScope = "test";
                    narrowestGa = ga;
                }
            }
            stack.addAll(node.getChildren());
        }
        return narrowestScope != null ? new ChangedDependencyMatch(narrowestGa, narrowestScope) : null;
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
            handleWriteFailure(config, "Failed to write impacted log", e);
        }
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

    private void writeReport(ScalpelConfiguration config, Path reactorRoot, AnalysisContext ctx)
            throws MavenExecutionException {
        ScalpelReport.Builder builder = ScalpelReport.builder()
                .baseBranch(config.getBaseBranch())
                .fullBuildTriggered(false)
                .changedFiles(ctx.changedFiles)
                .changedProperties(ctx.changedProperties)
                .changedManagedDependencies(ctx.changedManagedDepGAs)
                .changedManagedPlugins(ctx.changedManagedPluginGAs)
                .unmatchedPomPaths(ctx.unmatchedPomPaths);

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

        try {
            ScalpelReport report = builder.build();
            report.writeToFile(reactorRoot, config.getReportFile());
            logger.info("Scalpel: Report written to {}", config.getReportFile());
        } catch (IOException e) {
            handleWriteFailure(config, "Failed to write report", e);
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
            try {
                if (project.getArtifactId().matches(pattern)) {
                    logger.debug("Scalpel: Force-including module {} (matches {})", key(project), pattern);
                    return pattern;
                }
            } catch (PatternSyntaxException e) {
                logger.warn("Scalpel: Invalid regex pattern '{}' in forceBuildModules: {}", pattern, e.getMessage());
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
