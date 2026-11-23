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
import org.apache.maven.model.Exclusion;
import org.apache.maven.model.Extension;
import org.apache.maven.model.Plugin;
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
            // Edges only due to dependencies bringing no content are not followed downstream: a change
            // of the upstream module cannot reach the downstream module through them. They are kept
            // upstream (alsoMake), as building more upstream modules is harmless.
            List<MavenProject> downstreams = new ArrayList<>();
            for (MavenProject ds : graph.getDownstreamProjects(project, false)) {
                if (dependsOnlyThroughNoContentDependencies(ds, project)) {
                    if (logger.isDebugEnabled()) {
                        logger.debug(
                                "Not following edge {} -> {}: only due to dependencies bringing no content",
                                key(project),
                                key(ds));
                    }
                } else {
                    downstreams.add(ds);
                }
            }
            directDownstream.put(project, downstreams);
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
     * Returns true if {@code downstream} has a direct dependency on the test-jar of
     * {@code upstream}, declared either as {@code <type>test-jar</type>} or as
     * {@code <classifier>tests</classifier>} with the default {@code jar} type. Both forms
     * resolve the same artifact. Package-private so {@link SkipTestsApplier} can reuse this
     * check when deciding whether a skip-test candidate's test-compile must be preserved.
     */
    boolean hasTestJarDependency(MavenProject downstream, MavenProject upstream) {
        String groupId = upstream.getGroupId();
        String artifactId = upstream.getArtifactId();
        for (Dependency dep : downstream.getDependencies()) {
            if (groupId.equals(dep.getGroupId()) && artifactId.equals(dep.getArtifactId()) && isTestJar(dep)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns true if the reactor edge from {@code upstream} to {@code downstream} only exists because of
     * dependencies bringing no content to {@code downstream}: dependencies on the {@code pom} artifact of
     * {@code upstream} with a wildcard exclusion ({@code *:*}). A POM artifact has no content of its own,
     * and the wildcard exclusion removes all its dependencies: such dependencies are typically used to make
     * sure a BOM is built before the modules importing it, without affecting their classpath.
     * <p>
     * Only an {@code upstream} module with the {@code pom} packaging is considered: for another packaging
     * (e.g. {@code jar}), such a dependency is typically used to make sure the module is built first because
     * {@code downstream} uses it in a way Maven does not see when ordering the reactor, e.g. as an annotation
     * processor ({@code annotationProcessorPaths}) or as an artifact resolved by a plugin: a change of
     * {@code upstream} does reach {@code downstream} through it.
     * <p>
     * To stay conservative, the edge is only considered as bringing no content if {@code upstream} is not
     * the parent of {@code downstream} and is not used as a build plugin, a dependency of a build plugin, or an
     * extension by {@code downstream}, as these relationships also create reactor edges.
     */
    static boolean dependsOnlyThroughNoContentDependencies(MavenProject downstream, MavenProject upstream) {
        if (!"pom".equals(upstream.getPackaging())) {
            return false;
        }
        String groupId = upstream.getGroupId();
        String artifactId = upstream.getArtifactId();
        if (downstream.getParent() != null
                && groupId.equals(downstream.getParent().getGroupId())
                && artifactId.equals(downstream.getParent().getArtifactId())) {
            return false;
        }
        if (downstream.getBuild() != null) {
            for (Plugin plugin : downstream.getBuild().getPlugins()) {
                if (groupId.equals(plugin.getGroupId()) && artifactId.equals(plugin.getArtifactId())) {
                    return false;
                }
                // The dependencies of the build plugins also create reactor edges (see ProjectSorter)
                for (Dependency dep : plugin.getDependencies()) {
                    if (groupId.equals(dep.getGroupId()) && artifactId.equals(dep.getArtifactId())) {
                        return false;
                    }
                }
            }
            for (Extension extension : downstream.getBuild().getExtensions()) {
                if (groupId.equals(extension.getGroupId()) && artifactId.equals(extension.getArtifactId())) {
                    return false;
                }
            }
        }
        boolean found = false;
        for (Dependency dep : downstream.getDependencies()) {
            if (groupId.equals(dep.getGroupId()) && artifactId.equals(dep.getArtifactId())) {
                if (!bringsNoContent(dep)) {
                    return false;
                }
                found = true;
            }
        }
        return found;
    }

    private static boolean bringsNoContent(Dependency dep) {
        if (!"pom".equals(dep.getType())) {
            return false;
        }
        for (Exclusion exclusion : dep.getExclusions()) {
            if ("*".equals(exclusion.getGroupId()) && "*".equals(exclusion.getArtifactId())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isTestJar(Dependency dep) {
        return "test-jar".equals(dep.getType()) || ("jar".equals(dep.getType()) && "tests".equals(dep.getClassifier()));
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
