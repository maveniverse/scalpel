/*
 * Copyright (c) Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.scalpel.extension3.internal;

import static eu.maveniverse.maven.scalpel.extension3.internal.Projects.key;

import eu.maveniverse.maven.scalpel.core.ScalpelConfiguration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
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
        return computeBuildSet(directlyAffected, Set.of(), graph, config);
    }

    public TrimResult computeBuildSet(
            Set<MavenProject> directlyAffected,
            Set<MavenProject> testOnlyProjects,
            ProjectDependencyGraph graph,
            ScalpelConfiguration config) {

        List<MavenProject> sortedProjects = graph.getSortedProjects();

        // Build forward and reverse adjacency maps once from direct (non-transitive) edges.
        // This replaces per-project calls to graph.getDownstreamProjects(p, true) /
        // graph.getUpstreamProjects(p, true), each of which performs a fresh uncached DFS.
        Map<MavenProject, List<MavenProject>> directDownstream = new HashMap<>(sortedProjects.size());
        Map<MavenProject, List<MavenProject>> directUpstream = new HashMap<>(sortedProjects.size());
        for (MavenProject project : sortedProjects) {
            directDownstream.put(project, graph.getDownstreamProjects(project, false));
            directUpstream.put(project, graph.getUpstreamProjects(project, false));
        }

        Set<MavenProject> buildSet = new LinkedHashSet<>(directlyAffected);
        Set<MavenProject> downstreamOnly = new LinkedHashSet<>();
        Set<MavenProject> downstreamTestOnly = new LinkedHashSet<>();
        Set<MavenProject> upstreamOnly = new LinkedHashSet<>();
        Map<MavenProject, List<String>> buildReasons = new LinkedHashMap<>();

        if (config.isAlsoMakeDependents()) {
            // Multi-source BFS over the forward edge set: start from all directly-affected
            // projects and walk the transitive downstream closure in one pass.
            Queue<MavenProject> queue = new ArrayDeque<>();
            // Track which (source, downstream) pairs should be filtered for test-only
            // We need to remember which "source" triggered each downstream addition
            // so we can check test-jar dependency correctly.
            // Strategy: BFS from each directly-affected project, but share visited set.
            Set<MavenProject> visited = new LinkedHashSet<>(directlyAffected);
            for (MavenProject project : directlyAffected) {
                boolean isTestOnly = testOnlyProjects.contains(project);
                for (MavenProject ds : directDownstream.getOrDefault(project, List.of())) {
                    if (visited.contains(ds)) {
                        continue;
                    }
                    if (isTestOnly && !hasTestJarDependency(ds, project)) {
                        logger.debug(
                                "Skipping downstream {} of test-only module {} (no test-jar dependency)",
                                key(ds),
                                key(project));
                        continue;
                    }
                    visited.add(ds);
                    buildSet.add(ds);
                    if (config.isExplain()) {
                        addReason(buildReasons, ds, "downstream of " + key(project));
                    }
                    String scope = getDependencyScope(ds, project);
                    if ("test".equals(scope)) {
                        logger.debug("Adding test-scoped downstream {} of {}", key(ds), key(project));
                        downstreamTestOnly.add(ds);
                    } else {
                        logger.debug("Adding downstream dependent {} of {}", key(ds), key(project));
                        downstreamOnly.add(ds);
                    }
                    queue.add(ds);
                }
            }
            // Continue BFS for transitive downstream (non-test-only filtering applies
            // only at the first hop from a directly-affected test-only project)
            while (!queue.isEmpty()) {
                MavenProject current = queue.poll();
                for (MavenProject ds : directDownstream.getOrDefault(current, List.of())) {
                    if (visited.add(ds)) {
                        buildSet.add(ds);
                        if (config.isExplain()) {
                            addReason(buildReasons, ds, "downstream of " + key(current));
                        }
                        String scope = getDependencyScope(ds, current);
                        if ("test".equals(scope)) {
                            logger.debug("Adding test-scoped downstream {} of {}", key(ds), key(current));
                            downstreamTestOnly.add(ds);
                        } else {
                            logger.debug("Adding downstream dependent {} of {}", key(ds), key(current));
                            downstreamOnly.add(ds);
                        }
                        queue.add(ds);
                    }
                }
            }
        }

        if (config.isAlsoMake()) {
            // Multi-source BFS over the reverse edge set: start from the entire build set
            // (directly affected + downstream) and walk the transitive upstream closure.
            Queue<MavenProject> queue = new ArrayDeque<>(buildSet);
            Set<MavenProject> visited = new LinkedHashSet<>(buildSet);
            while (!queue.isEmpty()) {
                MavenProject current = queue.poll();
                for (MavenProject us : directUpstream.getOrDefault(current, List.of())) {
                    if (visited.add(us)) {
                        if (!directlyAffected.contains(us)
                                && !downstreamOnly.contains(us)
                                && !downstreamTestOnly.contains(us)) {
                            upstreamOnly.add(us);
                            if (config.isExplain()) {
                                addReason(buildReasons, us, "upstream of " + key(current));
                            }
                            if (logger.isDebugEnabled()) {
                                logger.debug("Adding upstream dependency {} of {}", key(us), key(current));
                            }
                        }
                        buildSet.add(us);
                        queue.add(us);
                    }
                }
            }
        }

        logger.debug(
                "Build set computed: {} total ({} direct, {} downstream, {} downstream-test, {} upstream)",
                buildSet.size(),
                directlyAffected.size(),
                downstreamOnly.size(),
                downstreamTestOnly.size(),
                upstreamOnly.size());

        // Sort in reactor build order
        List<MavenProject> result = new ArrayList<>();
        for (MavenProject project : sortedProjects) {
            if (buildSet.contains(project)) {
                result.add(project);
            }
        }

        return new TrimResult(result, directlyAffected, upstreamOnly, downstreamOnly, downstreamTestOnly, buildReasons);
    }

    private static void addReason(Map<MavenProject, List<String>> reasons, MavenProject project, String reason) {
        reasons.computeIfAbsent(project, k -> new ArrayList<>()).add(reason);
    }

    /**
     * Returns true if {@code downstream} has a direct {@code <type>test-jar</type>} dependency
     * on {@code upstream}. Package-private so {@link ScalpelLifecycleParticipant} can reuse this
     * check when deciding whether a skip-test candidate's test-compile must be preserved.
     */
    boolean hasTestJarDependency(MavenProject downstream, MavenProject upstream) {
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
}
