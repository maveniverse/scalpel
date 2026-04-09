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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import eu.maveniverse.maven.scalpel.core.ScalpelConfiguration;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import org.apache.maven.execution.ProjectDependencyGraph;
import org.apache.maven.model.Model;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReactorTrimmerTest {

    private ReactorTrimmer reactorTrimmer;

    @BeforeEach
    void setUp() {
        reactorTrimmer = new ReactorTrimmer();
    }

    private MavenProject createProject(String groupId, String artifactId) {
        Model model = new Model();
        model.setGroupId(groupId);
        model.setArtifactId(artifactId);
        model.setVersion("1.0");
        return new MavenProject(model);
    }

    private ScalpelConfiguration configWithAlsoMake(boolean alsoMake, boolean alsoMakeDependents) {
        Properties sys = new Properties();
        sys.setProperty("scalpel.alsoMake", String.valueOf(alsoMake));
        sys.setProperty("scalpel.alsoMakeDependents", String.valueOf(alsoMakeDependents));
        sys.setProperty("scalpel.baseBranch", "origin/main");
        return ScalpelConfiguration.fromProperties(sys, new Properties());
    }

    // ---------------------------------------------------------------
    // TrimResult basic structure
    // ---------------------------------------------------------------

    @Test
    void computeBuildSet_onlyDirectlyAffected_whenBothFlagsDisabled() {
        MavenProject moduleA = createProject("com.example", "module-a");
        MavenProject moduleB = createProject("com.example", "module-b");
        MavenProject moduleC = createProject("com.example", "module-c");

        Set<MavenProject> directlyAffected = new LinkedHashSet<>(Collections.singletonList(moduleB));

        ProjectDependencyGraph graph = mock(ProjectDependencyGraph.class);
        when(graph.getSortedProjects()).thenReturn(Arrays.asList(moduleA, moduleB, moduleC));
        // Even though graph has relationships, they should not be traversed
        when(graph.getDownstreamProjects(any(), anyBoolean())).thenReturn(Collections.singletonList(moduleC));
        when(graph.getUpstreamProjects(any(), anyBoolean())).thenReturn(Collections.singletonList(moduleA));

        ScalpelConfiguration config = configWithAlsoMake(false, false);
        TrimResult result = reactorTrimmer.computeBuildSet(directlyAffected, graph, config);

        assertEquals(1, result.getBuildSet().size());
        assertTrue(result.getBuildSet().contains(moduleB));
        assertTrue(result.getUpstreamOnly().isEmpty());
        assertTrue(result.getDownstreamOnly().isEmpty());
        assertEquals(directlyAffected, result.getDirectlyAffected());
    }

    @Test
    void computeBuildSet_includesDownstreamWhenAlsoMakeDependents() {
        MavenProject moduleA = createProject("com.example", "module-a");
        MavenProject moduleB = createProject("com.example", "module-b");

        Set<MavenProject> directlyAffected = new LinkedHashSet<>(Collections.singletonList(moduleA));

        ProjectDependencyGraph graph = mock(ProjectDependencyGraph.class);
        when(graph.getSortedProjects()).thenReturn(Arrays.asList(moduleA, moduleB));
        when(graph.getDownstreamProjects(moduleA, true)).thenReturn(Collections.singletonList(moduleB));
        when(graph.getUpstreamProjects(any(), anyBoolean())).thenReturn(Collections.emptyList());

        ScalpelConfiguration config = configWithAlsoMake(false, true);
        TrimResult result = reactorTrimmer.computeBuildSet(directlyAffected, graph, config);

        assertEquals(2, result.getBuildSet().size());
        assertTrue(result.getBuildSet().contains(moduleA));
        assertTrue(result.getBuildSet().contains(moduleB));
        assertTrue(result.getDownstreamOnly().contains(moduleB));
        assertTrue(result.getUpstreamOnly().isEmpty());
    }

    @Test
    void computeBuildSet_includesUpstreamWhenAlsoMake() {
        MavenProject moduleA = createProject("com.example", "module-a");
        MavenProject moduleB = createProject("com.example", "module-b");

        Set<MavenProject> directlyAffected = new LinkedHashSet<>(Collections.singletonList(moduleB));

        ProjectDependencyGraph graph = mock(ProjectDependencyGraph.class);
        when(graph.getSortedProjects()).thenReturn(Arrays.asList(moduleA, moduleB));
        when(graph.getDownstreamProjects(any(), anyBoolean())).thenReturn(Collections.emptyList());
        when(graph.getUpstreamProjects(moduleB, true)).thenReturn(Collections.singletonList(moduleA));

        ScalpelConfiguration config = configWithAlsoMake(true, false);
        TrimResult result = reactorTrimmer.computeBuildSet(directlyAffected, graph, config);

        assertEquals(2, result.getBuildSet().size());
        assertTrue(result.getBuildSet().contains(moduleA));
        assertTrue(result.getBuildSet().contains(moduleB));
        assertTrue(result.getUpstreamOnly().contains(moduleA));
        assertTrue(result.getDownstreamOnly().isEmpty());
    }

    @Test
    void computeBuildSet_bothFlags_correctCategorization() {
        MavenProject moduleA = createProject("com.example", "module-a"); // upstream
        MavenProject moduleB = createProject("com.example", "module-b"); // directly affected
        MavenProject moduleC = createProject("com.example", "module-c"); // downstream

        Set<MavenProject> directlyAffected = new LinkedHashSet<>(Collections.singletonList(moduleB));

        ProjectDependencyGraph graph = mock(ProjectDependencyGraph.class);
        when(graph.getSortedProjects()).thenReturn(Arrays.asList(moduleA, moduleB, moduleC));
        when(graph.getDownstreamProjects(moduleB, true)).thenReturn(Collections.singletonList(moduleC));
        when(graph.getDownstreamProjects(moduleA, true)).thenReturn(Collections.emptyList());
        when(graph.getDownstreamProjects(moduleC, true)).thenReturn(Collections.emptyList());
        when(graph.getUpstreamProjects(moduleB, true)).thenReturn(Collections.singletonList(moduleA));
        when(graph.getUpstreamProjects(moduleC, true)).thenReturn(Collections.singletonList(moduleB));
        when(graph.getUpstreamProjects(moduleA, true)).thenReturn(Collections.emptyList());

        ScalpelConfiguration config = configWithAlsoMake(true, true);
        TrimResult result = reactorTrimmer.computeBuildSet(directlyAffected, graph, config);

        assertEquals(3, result.getBuildSet().size());

        // Categorization checks
        assertTrue(result.getDirectlyAffected().contains(moduleB));
        assertFalse(result.getDirectlyAffected().contains(moduleA));
        assertFalse(result.getDirectlyAffected().contains(moduleC));

        assertTrue(result.getDownstreamOnly().contains(moduleC));
        assertFalse(result.getDownstreamOnly().contains(moduleA));
        assertFalse(result.getDownstreamOnly().contains(moduleB));

        assertTrue(result.getUpstreamOnly().contains(moduleA));
        assertFalse(result.getUpstreamOnly().contains(moduleB));
        assertFalse(result.getUpstreamOnly().contains(moduleC));
    }

    @Test
    void computeBuildSet_directlyAffectedNotAddedToDownstreamOnly() {
        // module-a is directly affected; graph also returns it as downstream of itself (edge case)
        MavenProject moduleA = createProject("com.example", "module-a");
        MavenProject moduleB = createProject("com.example", "module-b");

        Set<MavenProject> directlyAffected = new LinkedHashSet<>(Arrays.asList(moduleA, moduleB));

        ProjectDependencyGraph graph = mock(ProjectDependencyGraph.class);
        when(graph.getSortedProjects()).thenReturn(Arrays.asList(moduleA, moduleB));
        // Even if both modules appear as downstream of each other, neither should be in downstreamOnly
        when(graph.getDownstreamProjects(moduleA, true)).thenReturn(Collections.singletonList(moduleB));
        when(graph.getDownstreamProjects(moduleB, true)).thenReturn(Collections.singletonList(moduleA));
        when(graph.getUpstreamProjects(any(), anyBoolean())).thenReturn(Collections.emptyList());

        ScalpelConfiguration config = configWithAlsoMake(false, true);
        TrimResult result = reactorTrimmer.computeBuildSet(directlyAffected, graph, config);

        // Both are directly affected, so neither should appear in downstreamOnly
        assertTrue(result.getDownstreamOnly().isEmpty(), "directly affected modules should not be in downstreamOnly");
    }

    @Test
    void computeBuildSet_directlyAffectedNotAddedToUpstreamOnly() {
        // module-a is directly affected; graph returns it as upstream of module-b (which is also directly affected)
        MavenProject moduleA = createProject("com.example", "module-a");
        MavenProject moduleB = createProject("com.example", "module-b");

        Set<MavenProject> directlyAffected = new LinkedHashSet<>(Arrays.asList(moduleA, moduleB));

        ProjectDependencyGraph graph = mock(ProjectDependencyGraph.class);
        when(graph.getSortedProjects()).thenReturn(Arrays.asList(moduleA, moduleB));
        when(graph.getDownstreamProjects(any(), anyBoolean())).thenReturn(Collections.emptyList());
        when(graph.getUpstreamProjects(moduleB, true)).thenReturn(Collections.singletonList(moduleA));
        when(graph.getUpstreamProjects(moduleA, true)).thenReturn(Collections.emptyList());

        ScalpelConfiguration config = configWithAlsoMake(true, false);
        TrimResult result = reactorTrimmer.computeBuildSet(directlyAffected, graph, config);

        assertTrue(result.getUpstreamOnly().isEmpty(), "directly affected modules should not be in upstreamOnly");
    }

    @Test
    void computeBuildSet_downstreamModuleNotAddedToUpstreamOnly() {
        // module-c is downstream of module-b (directly affected)
        // module-c also appears as upstream of something — should NOT be added to upstreamOnly since it's already downstream
        MavenProject moduleA = createProject("com.example", "module-a");
        MavenProject moduleB = createProject("com.example", "module-b");
        MavenProject moduleC = createProject("com.example", "module-c");

        Set<MavenProject> directlyAffected = new LinkedHashSet<>(Collections.singletonList(moduleB));

        ProjectDependencyGraph graph = mock(ProjectDependencyGraph.class);
        when(graph.getSortedProjects()).thenReturn(Arrays.asList(moduleA, moduleB, moduleC));
        when(graph.getDownstreamProjects(moduleB, true)).thenReturn(Collections.singletonList(moduleC));
        when(graph.getDownstreamProjects(moduleA, true)).thenReturn(Collections.emptyList());
        when(graph.getDownstreamProjects(moduleC, true)).thenReturn(Collections.emptyList());
        // module-c appears as upstream of something (unusual but tests the guard)
        when(graph.getUpstreamProjects(moduleB, true)).thenReturn(Collections.emptyList());
        when(graph.getUpstreamProjects(moduleC, true)).thenReturn(Collections.emptyList());
        when(graph.getUpstreamProjects(moduleA, true)).thenReturn(Collections.emptyList());

        ScalpelConfiguration config = configWithAlsoMake(true, true);
        TrimResult result = reactorTrimmer.computeBuildSet(directlyAffected, graph, config);

        assertTrue(result.getDownstreamOnly().contains(moduleC));
        assertFalse(result.getUpstreamOnly().contains(moduleC), "downstream module should not also appear in upstreamOnly");
    }

    @Test
    void computeBuildSet_buildSetOrderFollowsReactorOrder() {
        MavenProject moduleA = createProject("com.example", "module-a");
        MavenProject moduleB = createProject("com.example", "module-b");
        MavenProject moduleC = createProject("com.example", "module-c");

        // Reactor order: A → B → C (build order)
        Set<MavenProject> directlyAffected = new LinkedHashSet<>(Collections.singletonList(moduleC));

        ProjectDependencyGraph graph = mock(ProjectDependencyGraph.class);
        // Sorted projects defines the reactor order
        when(graph.getSortedProjects()).thenReturn(Arrays.asList(moduleA, moduleB, moduleC));
        when(graph.getDownstreamProjects(any(), anyBoolean())).thenReturn(Collections.emptyList());
        when(graph.getUpstreamProjects(moduleC, true)).thenReturn(Arrays.asList(moduleA, moduleB));
        when(graph.getUpstreamProjects(moduleA, true)).thenReturn(Collections.emptyList());
        when(graph.getUpstreamProjects(moduleB, true)).thenReturn(Collections.emptyList());

        ScalpelConfiguration config = configWithAlsoMake(true, false);
        TrimResult result = reactorTrimmer.computeBuildSet(directlyAffected, graph, config);

        List<MavenProject> buildSet = result.getBuildSet();
        assertEquals(3, buildSet.size());
        // Order should follow reactor: A, B, C
        assertEquals(moduleA, buildSet.get(0));
        assertEquals(moduleB, buildSet.get(1));
        assertEquals(moduleC, buildSet.get(2));
    }

    @Test
    void trimResult_getters_returnCorrectValues() {
        MavenProject moduleA = createProject("com.example", "module-a");
        MavenProject moduleB = createProject("com.example", "module-b");
        MavenProject moduleC = createProject("com.example", "module-c");

        List<MavenProject> buildSet = Arrays.asList(moduleA, moduleB, moduleC);
        Set<MavenProject> directlyAffected = new LinkedHashSet<>(Collections.singletonList(moduleB));
        Set<MavenProject> upstreamOnly = new LinkedHashSet<>(Collections.singletonList(moduleA));
        Set<MavenProject> downstreamOnly = new LinkedHashSet<>(Collections.singletonList(moduleC));

        TrimResult trimResult = new TrimResult(buildSet, directlyAffected, upstreamOnly, downstreamOnly);

        assertEquals(buildSet, trimResult.getBuildSet());
        assertEquals(directlyAffected, trimResult.getDirectlyAffected());
        assertEquals(upstreamOnly, trimResult.getUpstreamOnly());
        assertEquals(downstreamOnly, trimResult.getDownstreamOnly());
    }

    @Test
    void computeBuildSet_emptyDirectlyAffected_returnsEmptyBuildSet() {
        MavenProject moduleA = createProject("com.example", "module-a");

        Set<MavenProject> directlyAffected = Collections.emptySet();

        ProjectDependencyGraph graph = mock(ProjectDependencyGraph.class);
        when(graph.getSortedProjects()).thenReturn(Collections.singletonList(moduleA));
        when(graph.getDownstreamProjects(any(), anyBoolean())).thenReturn(Collections.emptyList());
        when(graph.getUpstreamProjects(any(), anyBoolean())).thenReturn(Collections.emptyList());

        ScalpelConfiguration config = configWithAlsoMake(true, true);
        TrimResult result = reactorTrimmer.computeBuildSet(directlyAffected, graph, config);

        assertTrue(result.getBuildSet().isEmpty());
        assertTrue(result.getUpstreamOnly().isEmpty());
        assertTrue(result.getDownstreamOnly().isEmpty());
    }
}