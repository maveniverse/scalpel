/*
 * Copyright (c) Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.scalpel.extension3.internal;

import eu.maveniverse.maven.scalpel.core.ScalpelConfiguration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.inject.Named;
import javax.inject.Singleton;
import org.apache.maven.execution.ProjectDependencyGraph;
import org.apache.maven.model.Dependency;
import org.apache.maven.project.MavenProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
@Named
class ReactorTrimmer {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    public TrimResult computeBuildSet(
            Set<MavenProject> directlyAffected, ProjectDependencyGraph graph, ScalpelConfiguration config) {
        return computeBuildSet(directlyAffected, Collections.<MavenProject>emptySet(), graph, config);
    }

    public TrimResult computeBuildSet(
            Set<MavenProject> directlyAffected,
            Set<MavenProject> testOnlyProjects,
            ProjectDependencyGraph graph,
            ScalpelConfiguration config) {

        Set<MavenProject> buildSet = new LinkedHashSet<>(directlyAffected);
        Set<MavenProject> downstreamOnly = new LinkedHashSet<>();
        Set<MavenProject> downstreamTestOnly = new LinkedHashSet<>();
        Set<MavenProject> upstreamOnly = new LinkedHashSet<>();

        if (config.isAlsoMakeDependents()) {
            for (MavenProject project : new ArrayList<>(directlyAffected)) {
                boolean isTestOnly = testOnlyProjects.contains(project);
                List<MavenProject> downstream = graph.getDownstreamProjects(project, true);
                if (!downstream.isEmpty()) {
                    for (MavenProject ds : downstream) {
                        if (directlyAffected.contains(ds)) {
                            continue;
                        }
                        // For test-only modules, only propagate to test-jar consumers
                        if (isTestOnly && !hasTestJarDependency(ds, project)) {
                            logger.debug(
                                    "Skipping downstream {} of test-only module {} (no test-jar dependency)",
                                    key(ds),
                                    key(project));
                            continue;
                        }
                        if (buildSet.add(ds)) {
                            // Check if downstream depends on the changed module via test scope only
                            String scope = getDependencyScope(ds, project);
                            if ("test".equals(scope)) {
                                logger.debug("Adding test-scoped downstream {} of {}", key(ds), key(project));
                                downstreamTestOnly.add(ds);
                            } else {
                                logger.debug("Adding downstream dependent {} of {}", key(ds), key(project));
                                downstreamOnly.add(ds);
                            }
                        }
                    }
                }
            }
        }

        if (config.isAlsoMake()) {
            for (MavenProject project : new ArrayList<>(buildSet)) {
                List<MavenProject> upstream = graph.getUpstreamProjects(project, true);
                if (!upstream.isEmpty()) {
                    logger.debug("Adding upstream dependencies of {}: {}", key(project), keys(upstream));
                    for (MavenProject us : upstream) {
                        if (!directlyAffected.contains(us)
                                && !downstreamOnly.contains(us)
                                && !downstreamTestOnly.contains(us)
                                && buildSet.add(us)) {
                            upstreamOnly.add(us);
                        }
                    }
                }
            }
        }

        // Sort in reactor build order
        List<MavenProject> sortedProjects = graph.getSortedProjects();
        List<MavenProject> result = new ArrayList<>();
        for (MavenProject project : sortedProjects) {
            if (buildSet.contains(project)) {
                result.add(project);
            }
        }

        return new TrimResult(result, directlyAffected, upstreamOnly, downstreamOnly, downstreamTestOnly);
    }

    private boolean hasTestJarDependency(MavenProject downstream, MavenProject upstream) {
        String groupId = upstream.getGroupId();
        String artifactId = upstream.getArtifactId();
        for (Dependency dep : downstream.getDependencies()) {
            if (groupId.equals(dep.getGroupId())
                    && artifactId.equals(dep.getArtifactId())
                    && "test-jar".equals(dep.getType())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the scope of the direct dependency from downstream on upstream, or null if
     * there is no direct dependency (i.e. the dependency is purely transitive).
     */
    private String getDependencyScope(MavenProject downstream, MavenProject upstream) {
        String groupId = upstream.getGroupId();
        String artifactId = upstream.getArtifactId();
        for (Dependency dep : downstream.getDependencies()) {
            if (groupId.equals(dep.getGroupId()) && artifactId.equals(dep.getArtifactId())) {
                return dep.getScope();
            }
        }
        return null; // transitive dependency
    }

    private static String key(MavenProject project) {
        return project.getGroupId() + ":" + project.getArtifactId();
    }

    private static List<String> keys(List<MavenProject> projects) {
        List<String> keys = new ArrayList<>();
        for (MavenProject project : projects) {
            keys.add(key(project));
        }
        return keys;
    }
}
