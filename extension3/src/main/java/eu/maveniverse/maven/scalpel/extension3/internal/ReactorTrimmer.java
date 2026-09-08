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
            // Two-phase multi-source BFS over the forward edge set.
            //
            // Phase 1: BFS from non-test-only sources — no test-jar filtering.
            //          These nodes and all their transitive downstream are included
            //          unconditionally.
            //
            // Phase 2: BFS from test-only sources — hasTestJarDependency enforced
            //          at every hop against the original test-only source.  Nodes
            //          already visited in Phase 1 are treated as safe (their
            //          descendants were already queued without restriction).
            //
            // Splitting into two phases avoids the processing-order race where a
            // test-only path through the single queue could permanently exclude
            // descendants of a node that is also reachable via a non-test-only path.
            Queue<MavenProject> queue = new ArrayDeque<>();
            Set<MavenProject> visited = new LinkedHashSet<>(directlyAffected);

            // --- Phase 1: non-test-only sources ---
            for (MavenProject project : directlyAffected) {
                if (testOnlyProjects.contains(project)) {
                    continue; // handled in Phase 2
                }
                for (MavenProject ds : directDownstream.getOrDefault(project, List.of())) {
                    if (visited.add(ds)) {
                        addDownstream(ds, project, buildSet, downstreamOnly, downstreamTestOnly, buildReasons, config);
                        queue.add(ds);
                    }
                }
            }
            // Drain queue: all transitive downstream from non-test-only sources
            while (!queue.isEmpty()) {
                MavenProject current = queue.poll();
                for (MavenProject ds : directDownstream.getOrDefault(current, List.of())) {
                    if (visited.add(ds)) {
                        addDownstream(ds, current, buildSet, downstreamOnly, downstreamTestOnly, buildReasons, config);
                        queue.add(ds);
                    }
                }
            }

            // --- Phase 2: test-only sources ---
            // Track which test-only source(s) reached each node so we can enforce
            // hasTestJarDependency at every hop against the original source(s).
            Map<MavenProject, Set<MavenProject>> testOnlyOrigins = new HashMap<>();
            for (MavenProject project : directlyAffected) {
                if (!testOnlyProjects.contains(project)) {
                    continue; // handled in Phase 1
                }
                for (MavenProject ds : directDownstream.getOrDefault(project, List.of())) {
                    if (visited.contains(ds)) {
                        // Already reached via a non-test-only path (or another test-only
                        // source) — descendants are already queued from Phase 1, so skip.
                        continue;
                    }
                    if (!hasTestJarDependency(ds, project)) {
                        if (logger.isDebugEnabled()) {
                            logger.debug(
                                    "Skipping downstream {} of test-only module {} (no test-jar dependency)",
                                    key(ds),
                                    key(project));
                        }
                    } else {
                        visited.add(ds);
                        testOnlyOrigins
                                .computeIfAbsent(ds, k -> new LinkedHashSet<>())
                                .add(project);
                        addDownstream(ds, project, buildSet, downstreamOnly, downstreamTestOnly, buildReasons, config);
                        queue.add(ds);
                    }
                }
            }
            // Continue BFS for transitive downstream of test-only sources.
            // At each hop, hasTestJarDependency(ds, origin) is enforced against
            // the original test-only source(s) — matching the old DFS semantics.
            while (!queue.isEmpty()) {
                MavenProject current = queue.poll();
                Set<MavenProject> currentTestOnlyOrigins = testOnlyOrigins.get(current);
                if (currentTestOnlyOrigins == null) {
                    // This node entered the queue from Phase 1 (no test-only origins).
                    // Should not happen after Phase 1 drain, but guard defensively.
                    continue;
                }
                for (MavenProject ds : directDownstream.getOrDefault(current, List.of())) {
                    if (visited.contains(ds)) {
                        // Already visited — either from Phase 1 (safe) or from an
                        // earlier test-only path.  No need to revisit.
                        continue;
                    }
                    boolean hasTestJar = false;
                    for (MavenProject origin : currentTestOnlyOrigins) {
                        if (hasTestJarDependency(ds, origin)) {
                            hasTestJar = true;
                            break;
                        }
                    }
                    if (!hasTestJar) {
                        if (logger.isDebugEnabled()) {
                            logger.debug(
                                    "Skipping transitive downstream {} (no test-jar dependency on test-only sources)",
                                    key(ds));
                        }
                    } else {
                        visited.add(ds);
                        testOnlyOrigins
                                .computeIfAbsent(ds, k -> new LinkedHashSet<>())
                                .addAll(currentTestOnlyOrigins);
                        addDownstream(ds, current, buildSet, downstreamOnly, downstreamTestOnly, buildReasons, config);
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

    /**
     * Add a downstream project to the build set, classify its scope, log, and record the reason.
     * Extracted to eliminate duplication across the four BFS entry points (Phase 1 seed/drain,
     * Phase 2 seed/drain).
     */
    private void addDownstream(
            MavenProject downstream,
            MavenProject cause,
            Set<MavenProject> buildSet,
            Set<MavenProject> downstreamOnly,
            Set<MavenProject> downstreamTestOnly,
            Map<MavenProject, List<String>> buildReasons,
            ScalpelConfiguration config) {
        buildSet.add(downstream);
        if (config.isExplain()) {
            addReason(buildReasons, downstream, "downstream of " + key(cause));
        }
        String scope = getDependencyScope(downstream, cause);
        if ("test".equals(scope)) {
            if (logger.isDebugEnabled()) {
                logger.debug("Adding test-scoped downstream {} of {}", key(downstream), key(cause));
            }
            downstreamTestOnly.add(downstream);
        } else {
            if (logger.isDebugEnabled()) {
                logger.debug("Adding downstream dependent {} of {}", key(downstream), key(cause));
            }
            downstreamOnly.add(downstream);
        }
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
