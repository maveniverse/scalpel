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
import eu.maveniverse.maven.scalpel.core.Version;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.ArrayList;
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
import org.apache.maven.project.MavenProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
@Named
class ScalpelLifecycleParticipant extends AbstractMavenLifecycleParticipant {

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final ScalpelCore scalpelCore;
    private final ModuleMapper moduleMapper;
    private final OldModelBuilder oldModelBuilder;
    private final EffectiveModelComparator modelComparator;
    private final ReactorTrimmer reactorTrimmer;

    @Inject
    public ScalpelLifecycleParticipant(
            ScalpelCore scalpelCore,
            ModuleMapper moduleMapper,
            OldModelBuilder oldModelBuilder,
            EffectiveModelComparator modelComparator,
            ReactorTrimmer reactorTrimmer) {
        this.scalpelCore = requireNonNull(scalpelCore, "scalpelCore");
        this.moduleMapper = requireNonNull(moduleMapper, "moduleMapper");
        this.oldModelBuilder = requireNonNull(oldModelBuilder, "oldModelBuilder");
        this.modelComparator = requireNonNull(modelComparator, "modelComparator");
        this.reactorTrimmer = requireNonNull(reactorTrimmer, "reactorTrimmer");
    }

    @Override
    public void afterProjectsRead(MavenSession session) throws MavenExecutionException {
        ScalpelConfiguration config =
                ScalpelConfiguration.fromProperties(session.getSystemProperties(), session.getUserProperties());

        if (!config.isEnabled()) {
            logger.info("Scalpel {} is disabled", Version.version());
            return;
        }

        logger.info("Scalpel {} activated", Version.version());
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

            // Handle POM changes via effective model comparison
            Set<MavenProject> affectedByPom = new LinkedHashSet<>();
            if (!pomChanges.isEmpty()) {
                logger.debug("POM changes detected: {}", pomChanges);
                try {
                    Map<String, byte[]> oldPomContents = result.getOldPomContents();

                    if (!oldPomContents.isEmpty()) {
                        Map<String, Model> oldModels =
                                oldModelBuilder.buildOldModels(oldPomContents, session, reactorRoot);

                        // Compare each module's old vs current effective model
                        for (MavenProject project : allProjects) {
                            String key = project.getGroupId() + ":" + project.getArtifactId();
                            Model oldModel = oldModels.get(key);
                            if (oldModel == null) {
                                // New module, it's affected
                                affectedByPom.add(project);
                                logger.debug("New module detected: {}", key);
                            } else if (modelComparator.hasRelevantDifferences(oldModel, project.getModel())) {
                                affectedByPom.add(project);
                                logger.debug("POM changes affect module: {}", key);
                            }
                        }
                    }
                } catch (Exception e) {
                    if (config.isFailSafe()) {
                        logger.warn("Scalpel: Error comparing POM models, building all modules: {}", e.getMessage());
                        logger.debug("POM comparison error details", e);
                        return;
                    } else {
                        throw new MavenExecutionException("Scalpel: Error comparing POM models", e);
                    }
                }
                logger.debug("Modules affected by POM changes: {}", projectKeys(affectedByPom));
            }

            // Combine
            Set<MavenProject> directlyAffected = new LinkedHashSet<>();
            directlyAffected.addAll(affectedBySource);
            directlyAffected.addAll(affectedByPom);

            if (directlyAffected.isEmpty()) {
                logger.info("Scalpel: No modules affected by changes");
                return;
            }

            logger.info(
                    "Scalpel: {} modules directly affected: {}",
                    directlyAffected.size(),
                    projectKeys(directlyAffected));

            // Compute full build set with upstream/downstream
            List<MavenProject> trimmedList =
                    reactorTrimmer.computeBuildSet(directlyAffected, session.getProjectDependencyGraph(), config);

            logger.info(
                    "Scalpel: Building {} of {} modules: {}",
                    trimmedList.size(),
                    allProjects.size(),
                    projectKeys(trimmedList));

            session.setProjects(trimmedList);

        } catch (ScalpelException e) {
            throw new MavenExecutionException("Scalpel: " + e.getMessage(), e);
        }
    }

    private static List<String> projectKeys(Iterable<MavenProject> projects) {
        List<String> keys = new ArrayList<>();
        for (MavenProject project : projects) {
            keys.add(project.getGroupId() + ":" + project.getArtifactId());
        }
        return keys;
    }
}
