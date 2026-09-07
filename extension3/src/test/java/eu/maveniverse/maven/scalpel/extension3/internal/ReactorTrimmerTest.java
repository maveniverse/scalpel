/*
 * Copyright (c) Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.scalpel.extension3.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.maveniverse.maven.scalpel.core.ScalpelConfiguration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.apache.maven.execution.ProjectDependencyGraph;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReactorTrimmerTest {

    private ReactorTrimmer trimmer;

    @BeforeEach
    void setUp() {
        trimmer = new ReactorTrimmer();
    }

    @Test
    void computeBuildSet_testScopedDownstreamClassifiedAsTestOnly() {
        // A -> B (test scope): B should be classified as downstreamTestOnly
        MavenProject projectA = createProject("com.example", "module-a");
        MavenProject projectB = createProject("com.example", "module-b");
        addDependency(projectB, "com.example", "module-a", "test");

        List<MavenProject> sortedProjects = List.of(projectA, projectB);
        Map<MavenProject, List<MavenProject>> downstreamMap = new HashMap<>();
        downstreamMap.put(projectA, List.of(projectB));

        ProjectDependencyGraph graph = new TestDependencyGraph(sortedProjects, downstreamMap, Map.of());

        Set<MavenProject> directlyAffected = new LinkedHashSet<>(Set.of(projectA));
        ScalpelConfiguration config = configWith(true, true);

        TrimResult result = trimmer.computeBuildSet(directlyAffected, Set.of(), graph, config);

        assertTrue(result.getBuildSet().contains(projectA));
        assertTrue(result.getBuildSet().contains(projectB));
        assertTrue(
                result.getDownstreamTestOnly().contains(projectB),
                "Test-scoped downstream should be in downstreamTestOnly");
        assertFalse(
                result.getDownstreamOnly().contains(projectB),
                "Test-scoped downstream should NOT be in downstreamOnly");
    }

    @Test
    void computeBuildSet_compileScopedDownstreamClassifiedAsRegular() {
        // A -> B (compile scope): B should be in downstreamOnly
        MavenProject projectA = createProject("com.example", "module-a");
        MavenProject projectB = createProject("com.example", "module-b");
        addDependency(projectB, "com.example", "module-a", "compile");

        List<MavenProject> sortedProjects = List.of(projectA, projectB);
        Map<MavenProject, List<MavenProject>> downstreamMap = new HashMap<>();
        downstreamMap.put(projectA, List.of(projectB));

        ProjectDependencyGraph graph = new TestDependencyGraph(sortedProjects, downstreamMap, Map.of());

        Set<MavenProject> directlyAffected = new LinkedHashSet<>(Set.of(projectA));
        ScalpelConfiguration config = configWith(true, true);

        TrimResult result = trimmer.computeBuildSet(directlyAffected, Set.of(), graph, config);

        assertTrue(
                result.getDownstreamOnly().contains(projectB), "Compile-scoped downstream should be in downstreamOnly");
        assertTrue(result.getDownstreamTestOnly().isEmpty(), "No test-only downstream expected");
    }

    @Test
    void computeBuildSet_testOnlyModuleOnlyPropagatesViaTestJar() {
        // A is test-only, B depends on A with test-jar type, C depends on A without test-jar
        MavenProject projectA = createProject("com.example", "module-a");
        MavenProject projectB = createProject("com.example", "module-b");
        MavenProject projectC = createProject("com.example", "module-c");
        addTestJarDependency(projectB, "com.example", "module-a");
        addDependency(projectC, "com.example", "module-a", "compile");

        List<MavenProject> sortedProjects = List.of(projectA, projectB, projectC);
        Map<MavenProject, List<MavenProject>> downstreamMap = new HashMap<>();
        downstreamMap.put(projectA, List.of(projectB, projectC));

        ProjectDependencyGraph graph = new TestDependencyGraph(sortedProjects, downstreamMap, Map.of());

        Set<MavenProject> directlyAffected = new LinkedHashSet<>(Set.of(projectA));
        Set<MavenProject> testOnlyProjects = new LinkedHashSet<>(Set.of(projectA));
        ScalpelConfiguration config = configWith(true, true);

        TrimResult result = trimmer.computeBuildSet(directlyAffected, testOnlyProjects, graph, config);

        assertTrue(
                result.getBuildSet().contains(projectB),
                "B has test-jar dependency on test-only A - should be included");
        assertFalse(
                result.getBuildSet().contains(projectC),
                "C has regular dependency on test-only A - should NOT be included");
    }

    @Test
    void computeBuildSet_upstreamIncludedWithAlsoMake() {
        // A depends on B: if A is affected, B should be upstream
        MavenProject projectA = createProject("com.example", "module-a");
        MavenProject projectB = createProject("com.example", "module-b");

        List<MavenProject> sortedProjects = List.of(projectB, projectA);
        Map<MavenProject, List<MavenProject>> upstreamMap = new HashMap<>();
        upstreamMap.put(projectA, List.of(projectB));

        ProjectDependencyGraph graph = new TestDependencyGraph(sortedProjects, Map.of(), upstreamMap);

        Set<MavenProject> directlyAffected = new LinkedHashSet<>(Set.of(projectA));
        ScalpelConfiguration config = configWith(true, false); // alsoMake=true, alsoMakeDependents=false

        TrimResult result = trimmer.computeBuildSet(directlyAffected, Set.of(), graph, config);

        assertTrue(result.getBuildSet().contains(projectB));
        assertTrue(result.getUpstreamOnly().contains(projectB));
    }

    @Test
    void computeBuildSet_noDownstreamWhenDisabled() {
        MavenProject projectA = createProject("com.example", "module-a");
        MavenProject projectB = createProject("com.example", "module-b");

        List<MavenProject> sortedProjects = List.of(projectA, projectB);
        Map<MavenProject, List<MavenProject>> downstreamMap = new HashMap<>();
        downstreamMap.put(projectA, List.of(projectB));

        ProjectDependencyGraph graph = new TestDependencyGraph(sortedProjects, downstreamMap, Map.of());

        Set<MavenProject> directlyAffected = new LinkedHashSet<>(Set.of(projectA));
        ScalpelConfiguration config = configWith(false, false); // both disabled

        TrimResult result = trimmer.computeBuildSet(directlyAffected, Set.of(), graph, config);

        assertEquals(1, result.getBuildSet().size());
        assertTrue(result.getBuildSet().contains(projectA));
        assertFalse(result.getBuildSet().contains(projectB));
    }

    @Test
    void computeBuildSet_backwardCompatibleOverload() {
        MavenProject projectA = createProject("com.example", "module-a");
        List<MavenProject> sortedProjects = List.of(projectA);

        ProjectDependencyGraph graph = new TestDependencyGraph(sortedProjects, Map.of(), Map.of());

        Set<MavenProject> directlyAffected = new LinkedHashSet<>(Set.of(projectA));
        ScalpelConfiguration config = configWith(true, true);

        // Use the 3-arg overload (no testOnlyProjects)
        TrimResult result = trimmer.computeBuildSet(directlyAffected, graph, config);

        assertTrue(result.getBuildSet().contains(projectA));
        assertTrue(result.getDownstreamTestOnly().isEmpty());
    }

    @Test
    void computeBuildSet_buildOrderPreserved() {
        MavenProject projectA = createProject("com.example", "module-a");
        MavenProject projectB = createProject("com.example", "module-b");
        MavenProject projectC = createProject("com.example", "module-c");
        addDependency(projectC, "com.example", "module-a", "compile");

        // Sorted order: A, B, C
        List<MavenProject> sortedProjects = List.of(projectA, projectB, projectC);
        Map<MavenProject, List<MavenProject>> downstreamMap = new HashMap<>();
        downstreamMap.put(projectA, List.of(projectC));

        ProjectDependencyGraph graph = new TestDependencyGraph(sortedProjects, downstreamMap, Map.of());

        Set<MavenProject> directlyAffected = new LinkedHashSet<>(Set.of(projectA));
        ScalpelConfiguration config = configWith(true, true);

        TrimResult result = trimmer.computeBuildSet(directlyAffected, Set.of(), graph, config);

        // B should not be in the build set, only A and C
        assertEquals(List.of(projectA, projectC), result.getBuildSet());
    }

    @Test
    void computeBuildSet_transitiveDownstreamViaBFS() {
        // Chain: A -> B -> C -> D (each directly downstream of the previous)
        MavenProject projectA = createProject("com.example", "module-a");
        MavenProject projectB = createProject("com.example", "module-b");
        MavenProject projectC = createProject("com.example", "module-c");
        MavenProject projectD = createProject("com.example", "module-d");
        addDependency(projectB, "com.example", "module-a", "compile");
        addDependency(projectC, "com.example", "module-b", "compile");
        addDependency(projectD, "com.example", "module-c", "compile");

        List<MavenProject> sortedProjects = List.of(projectA, projectB, projectC, projectD);
        Map<MavenProject, List<MavenProject>> downstreamMap = new HashMap<>();
        // Direct edges only
        downstreamMap.put(projectA, List.of(projectB));
        downstreamMap.put(projectB, List.of(projectC));
        downstreamMap.put(projectC, List.of(projectD));

        ProjectDependencyGraph graph = new TestDependencyGraph(sortedProjects, downstreamMap, Map.of());

        Set<MavenProject> directlyAffected = new LinkedHashSet<>(Set.of(projectA));
        ScalpelConfiguration config = configWith(false, true); // alsoMakeDependents=true

        TrimResult result = trimmer.computeBuildSet(directlyAffected, Set.of(), graph, config);

        // BFS should find all transitive downstream: B, C, D
        assertEquals(List.of(projectA, projectB, projectC, projectD), result.getBuildSet());
        assertTrue(result.getDownstreamOnly().contains(projectB));
        assertTrue(result.getDownstreamOnly().contains(projectC));
        assertTrue(result.getDownstreamOnly().contains(projectD));
    }

    @Test
    void computeBuildSet_transitiveUpstreamViaBFS() {
        // Chain: A <- B <- C (each directly upstream of the next)
        MavenProject projectA = createProject("com.example", "module-a");
        MavenProject projectB = createProject("com.example", "module-b");
        MavenProject projectC = createProject("com.example", "module-c");
        addDependency(projectB, "com.example", "module-a", "compile");
        addDependency(projectC, "com.example", "module-b", "compile");

        List<MavenProject> sortedProjects = List.of(projectA, projectB, projectC);
        Map<MavenProject, List<MavenProject>> upstreamMap = new HashMap<>();
        // Direct edges only
        upstreamMap.put(projectC, List.of(projectB));
        upstreamMap.put(projectB, List.of(projectA));

        ProjectDependencyGraph graph = new TestDependencyGraph(sortedProjects, Map.of(), upstreamMap);

        Set<MavenProject> directlyAffected = new LinkedHashSet<>(Set.of(projectC));
        ScalpelConfiguration config = configWith(true, false); // alsoMake=true

        TrimResult result = trimmer.computeBuildSet(directlyAffected, Set.of(), graph, config);

        // BFS should find all transitive upstream: B and A
        assertEquals(List.of(projectA, projectB, projectC), result.getBuildSet());
        assertTrue(result.getUpstreamOnly().contains(projectA));
        assertTrue(result.getUpstreamOnly().contains(projectB));
    }

    @Test
    void computeBuildSet_diamondGraphNoDuplicates() {
        // Diamond: A -> B, A -> C, B -> D, C -> D
        MavenProject projectA = createProject("com.example", "module-a");
        MavenProject projectB = createProject("com.example", "module-b");
        MavenProject projectC = createProject("com.example", "module-c");
        MavenProject projectD = createProject("com.example", "module-d");
        addDependency(projectB, "com.example", "module-a", "compile");
        addDependency(projectC, "com.example", "module-a", "compile");
        addDependency(projectD, "com.example", "module-b", "compile");
        addDependency(projectD, "com.example", "module-c", "compile");

        List<MavenProject> sortedProjects = List.of(projectA, projectB, projectC, projectD);
        Map<MavenProject, List<MavenProject>> downstreamMap = new HashMap<>();
        downstreamMap.put(projectA, List.of(projectB, projectC));
        downstreamMap.put(projectB, List.of(projectD));
        downstreamMap.put(projectC, List.of(projectD));

        ProjectDependencyGraph graph = new TestDependencyGraph(sortedProjects, downstreamMap, Map.of());

        Set<MavenProject> directlyAffected = new LinkedHashSet<>(Set.of(projectA));
        ScalpelConfiguration config = configWith(false, true); // alsoMakeDependents=true

        TrimResult result = trimmer.computeBuildSet(directlyAffected, Set.of(), graph, config);

        // All should be in the build set, D only once
        assertEquals(List.of(projectA, projectB, projectC, projectD), result.getBuildSet());
        assertEquals(3, result.getDownstreamOnly().size());
    }

    // --- Helper methods ---

    private MavenProject createProject(String groupId, String artifactId) {
        Model model = new Model();
        model.setGroupId(groupId);
        model.setArtifactId(artifactId);
        model.setVersion("1.0");
        return new MavenProject(model);
    }

    private void addDependency(MavenProject project, String groupId, String artifactId, String scope) {
        Dependency dep = new Dependency();
        dep.setGroupId(groupId);
        dep.setArtifactId(artifactId);
        dep.setScope(scope);
        project.getDependencies().add(dep);
    }

    private void addTestJarDependency(MavenProject project, String groupId, String artifactId) {
        Dependency dep = new Dependency();
        dep.setGroupId(groupId);
        dep.setArtifactId(artifactId);
        dep.setType("test-jar");
        project.getDependencies().add(dep);
    }

    private ScalpelConfiguration configWith(boolean alsoMake, boolean alsoMakeDependents) {
        Properties props = new Properties();
        props.setProperty("scalpel.alsoMake", String.valueOf(alsoMake));
        props.setProperty("scalpel.alsoMakeDependents", String.valueOf(alsoMakeDependents));
        props.setProperty("scalpel.baseBranch", "origin/main");
        return ScalpelConfiguration.fromProperties(props, new Properties());
    }

    /**
     * Simple test implementation of ProjectDependencyGraph.
     */
    private static class TestDependencyGraph implements ProjectDependencyGraph {
        private final List<MavenProject> sortedProjects;
        private final Map<MavenProject, List<MavenProject>> downstreamMap;
        private final Map<MavenProject, List<MavenProject>> upstreamMap;

        TestDependencyGraph(
                List<MavenProject> sortedProjects,
                Map<MavenProject, List<MavenProject>> downstreamMap,
                Map<MavenProject, List<MavenProject>> upstreamMap) {
            this.sortedProjects = sortedProjects;
            this.downstreamMap = downstreamMap;
            this.upstreamMap = upstreamMap;
        }

        @Override
        public List<MavenProject> getAllProjects() {
            return sortedProjects;
        }

        @Override
        public List<MavenProject> getSortedProjects() {
            return sortedProjects;
        }

        @Override
        public List<MavenProject> getDownstreamProjects(MavenProject project, boolean transitive) {
            List<MavenProject> result = downstreamMap.get(project);
            return result != null ? result : new ArrayList<>();
        }

        @Override
        public List<MavenProject> getUpstreamProjects(MavenProject project, boolean transitive) {
            List<MavenProject> result = upstreamMap.get(project);
            return result != null ? result : new ArrayList<>();
        }
    }
}
