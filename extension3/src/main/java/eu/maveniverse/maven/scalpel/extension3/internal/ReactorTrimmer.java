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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.inject.Named;
import javax.inject.Singleton;
import org.apache.maven.execution.ProjectDependencyGraph;
import org.apache.maven.project.MavenProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
@Named
class ReactorTrimmer {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    public List<MavenProject> computeBuildSet(
            Set<MavenProject> directlyAffected, ProjectDependencyGraph graph, ScalpelConfiguration config) {

        Set<MavenProject> buildSet = new LinkedHashSet<>(directlyAffected);

        if (config.isAlsoMakeDependents()) {
            for (MavenProject project : new ArrayList<>(directlyAffected)) {
                List<MavenProject> downstream = graph.getDownstreamProjects(project, true);
                if (!downstream.isEmpty()) {
                    logger.debug("Adding downstream dependents of {}: {}", key(project), keys(downstream));
                    buildSet.addAll(downstream);
                }
            }
        }

        if (config.isAlsoMake()) {
            for (MavenProject project : new ArrayList<>(buildSet)) {
                List<MavenProject> upstream = graph.getUpstreamProjects(project, true);
                if (!upstream.isEmpty()) {
                    logger.debug("Adding upstream dependencies of {}: {}", key(project), keys(upstream));
                    buildSet.addAll(upstream);
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

        return result;
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
