/*
 * Copyright (c) Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.scalpel.extension3.internal;

import static java.util.Objects.requireNonNull;

import eu.maveniverse.maven.scalpel.core.ChangeDetectionResult;
import eu.maveniverse.maven.scalpel.core.ScalpelConfiguration;
import eu.maveniverse.maven.scalpel.core.ScalpelCore;
import eu.maveniverse.maven.scalpel.core.ScalpelException;
import eu.maveniverse.maven.scalpel.core.ScalpelReport;
import eu.maveniverse.maven.scalpel.core.Version;
import java.io.IOException;
import java.nio.file.FileSystems;
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

        if (!config.isEnabled()) {
            logger.info("Scalpel {} is disabled", Version.version());
            return;
        }

        logger.info("Scalpel {} activated (mode={})", Version.version(), config.getMode());
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
                return;
            }

            logger.info("Scalpel: {} changed files detected", changedFiles.size());

            // Check full build triggers
            for (String pattern : config.getFullBuildTriggers()) {
                PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern.trim());
                for (String changedFile : changedFiles) {
                    if (matcher.matches(Paths.get(changedFile))) {
                        logger.info("Scalpel: Full build triggered by change to {} (matches {})", changedFile, pattern);
                        if (config.isModeReport()) {
                            writeFullBuildReport(config, reactorRoot, changedFile, changedFiles);
                        }
                        return;
                    }
                }
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

            // Map source changes to modules
            Set<MavenProject> affectedBySource = moduleMapper.mapToProjects(sourceChanges, allProjects, reactorRoot);
            logger.debug("Modules affected by source changes: {}", projectKeys(affectedBySource));

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
                logger.debug("Modules affected by POM changes: {}", projectKeys(affectedByPom));
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

            if (directlyAffected.isEmpty()) {
                logger.info("Scalpel: No modules affected by changes");
                if (config.isModeReport()) {
                    writeReport(
                            config,
                            reactorRoot,
                            changedFiles,
                            changedProperties,
                            changedManagedDepGAs,
                            changedManagedPluginGAs,
                            Collections.<MavenProject>emptySet(),
                            Collections.<MavenProject>emptySet(),
                            Collections.<MavenProject>emptySet(),
                            Collections.<MavenProject, List<String>>emptyMap());
                } else if (config.isModeSkipTests()) {
                    skipTestsOnAll(allProjects);
                }
                return;
            }

            logger.info(
                    "Scalpel: {} modules directly affected: {}",
                    directlyAffected.size(),
                    projectKeys(directlyAffected));

            if (config.isModeReport()) {
                // Check remaining modules for transitive dependency/plugin impact
                Map<MavenProject, List<String>> transitivelyAffected = new LinkedHashMap<>();
                if (!changedManagedDepGAs.isEmpty() || !changedManagedPluginGAs.isEmpty()) {
                    for (MavenProject project : allProjects) {
                        if (directlyAffected.contains(project)) {
                            continue;
                        }
                        List<String> reasons = new ArrayList<>();
                        if (!changedManagedPluginGAs.isEmpty() && usesChangedPlugin(project, changedManagedPluginGAs)) {
                            reasons.add(ScalpelReport.REASON_MANAGED_PLUGIN);
                        }
                        if (!changedManagedDepGAs.isEmpty()
                                && hasChangedTransitiveDependency(project, session, changedManagedDepGAs)) {
                            reasons.add(ScalpelReport.REASON_TRANSITIVE_DEPENDENCY);
                        }
                        if (!reasons.isEmpty()) {
                            transitivelyAffected.put(project, reasons);
                        }
                    }
                    if (!transitivelyAffected.isEmpty()) {
                        logger.info(
                                "Scalpel: {} modules transitively affected: {}",
                                transitivelyAffected.size(),
                                projectKeys(transitivelyAffected.keySet()));
                    }
                }

                writeReport(
                        config,
                        reactorRoot,
                        changedFiles,
                        changedProperties,
                        changedManagedDepGAs,
                        changedManagedPluginGAs,
                        directlyAffected,
                        affectedBySource,
                        affectedByPom,
                        transitivelyAffected);
                return;
            }

            // Compute full build set with upstream/downstream
            List<MavenProject> buildSet =
                    reactorTrimmer.computeBuildSet(directlyAffected, session.getProjectDependencyGraph(), config);

            if (config.isModeSkipTests()) {
                applySkipTests(session, allProjects, buildSet, changedManagedDepGAs, changedManagedPluginGAs);
            } else {
                // trim mode: remove unaffected projects from reactor
                logger.info(
                        "Scalpel: Building {} of {} modules: {}",
                        buildSet.size(),
                        allProjects.size(),
                        projectKeys(buildSet));
                session.setProjects(buildSet);
            }

        } catch (ScalpelException e) {
            throw new MavenExecutionException("Scalpel: " + e.getMessage(), e);
        }
    }

    private void applySkipTests(
            MavenSession session,
            List<MavenProject> allProjects,
            List<MavenProject> buildSet,
            Set<String> changedManagedDepGAs,
            Set<String> changedManagedPluginGAs) {

        Set<MavenProject> buildSetLookup = new LinkedHashSet<>(buildSet);
        List<MavenProject> testProjects = new ArrayList<>(buildSet);
        List<MavenProject> skippedProjects = new ArrayList<>();

        for (MavenProject project : allProjects) {
            if (buildSetLookup.contains(project)) {
                continue; // Already known to need tests
            }

            // Check effective build plugins against changed managed plugins
            if (!changedManagedPluginGAs.isEmpty() && usesChangedPlugin(project, changedManagedPluginGAs)) {
                testProjects.add(project);
                continue;
            }

            // Check transitive dependencies if managed deps changed
            if (!changedManagedDepGAs.isEmpty()
                    && hasChangedTransitiveDependency(project, session, changedManagedDepGAs)) {
                testProjects.add(project);
                continue;
            }

            // Skip tests on this project
            project.getProperties().setProperty("maven.test.skip", "true");
            skippedProjects.add(project);
        }

        logger.info(
                "Scalpel: Testing {} of {} modules, skipping tests on {} modules: {}",
                testProjects.size(),
                allProjects.size(),
                skippedProjects.size(),
                projectKeys(skippedProjects));
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

    private boolean hasChangedTransitiveDependency(MavenProject project, MavenSession session, Set<String> changedGAs) {
        try {
            DefaultDependencyResolutionRequest request =
                    new DefaultDependencyResolutionRequest(project, session.getRepositorySession());
            DependencyResolutionResult result = dependenciesResolver.resolve(request);

            for (Dependency dep : result.getResolvedDependencies()) {
                String ga =
                        dep.getArtifact().getGroupId() + ":" + dep.getArtifact().getArtifactId();
                if (changedGAs.contains(ga)) {
                    logger.debug("Module {} has transitive dependency on changed managed dep {}", key(project), ga);
                    return true;
                }
            }
            return false;
        } catch (DependencyResolutionException e) {
            // Conservative: if we can't resolve, don't skip tests
            logger.debug("Cannot resolve dependencies for {}, not skipping tests: {}", key(project), e.getMessage());
            return true;
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

    private void writeReport(
            ScalpelConfiguration config,
            Path reactorRoot,
            Set<String> changedFiles,
            Set<String> changedProperties,
            Set<String> changedManagedDepGAs,
            Set<String> changedManagedPluginGAs,
            Set<MavenProject> directlyAffected,
            Set<MavenProject> affectedBySource,
            Set<MavenProject> affectedByPom,
            Map<MavenProject, List<String>> transitivelyAffected)
            throws MavenExecutionException {
        ScalpelReport.Builder builder = ScalpelReport.builder()
                .baseBranch(config.getBaseBranch())
                .fullBuildTriggered(false)
                .changedFiles(changedFiles)
                .changedProperties(changedProperties)
                .changedManagedDependencies(changedManagedDepGAs)
                .changedManagedPlugins(changedManagedPluginGAs);

        for (MavenProject project : directlyAffected) {
            String path = relativePath(reactorRoot, project);
            List<String> reasons = new ArrayList<>();
            if (affectedBySource.contains(project)) {
                reasons.add(ScalpelReport.REASON_SOURCE_CHANGE);
            }
            if (affectedByPom.contains(project)) {
                reasons.add(ScalpelReport.REASON_POM_CHANGE);
            }
            builder.addAffectedModule(
                    new ScalpelReport.AffectedModule(project.getGroupId(), project.getArtifactId(), path, reasons));
        }

        for (Map.Entry<MavenProject, List<String>> entry : transitivelyAffected.entrySet()) {
            MavenProject project = entry.getKey();
            String path = relativePath(reactorRoot, project);
            builder.addAffectedModule(new ScalpelReport.AffectedModule(
                    project.getGroupId(), project.getArtifactId(), path, entry.getValue()));
        }

        try {
            ScalpelReport report = builder.build();
            report.writeToFile(reactorRoot, config.getReportFile());
            logger.info("Scalpel: Report written to {}", config.getReportFile());
        } catch (IOException e) {
            throw new MavenExecutionException("Scalpel: Failed to write report", e);
        }
    }

    private static String relativePath(Path reactorRoot, MavenProject project) {
        return reactorRoot
                .toAbsolutePath()
                .normalize()
                .relativize(project.getBasedir().toPath().toAbsolutePath().normalize())
                .toString();
    }

    private void skipTestsOnAll(List<MavenProject> projects) {
        for (MavenProject project : projects) {
            project.getProperties().setProperty("maven.test.skip", "true");
        }
    }

    private static String key(MavenProject project) {
        return project.getGroupId() + ":" + project.getArtifactId();
    }

    private static List<String> projectKeys(Iterable<MavenProject> projects) {
        List<String> keys = new ArrayList<>();
        for (MavenProject project : projects) {
            keys.add(key(project));
        }
        return keys;
    }
}
