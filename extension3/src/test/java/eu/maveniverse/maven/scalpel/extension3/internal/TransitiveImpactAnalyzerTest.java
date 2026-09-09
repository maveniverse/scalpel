/*
 * Copyright (c) Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.scalpel.extension3.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import eu.maveniverse.maven.scalpel.core.Timings;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Model;
import org.apache.maven.project.DependencyResolutionException;
import org.apache.maven.project.DependencyResolutionResult;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectDependenciesResolver;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.DefaultDependencyNode;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TransitiveImpactAnalyzerTest {

    @TempDir
    Path tempDir;

    private ProjectDependenciesResolver resolver;
    private PomChangeAnalyzer pomChangeAnalyzer;
    private TransitiveImpactAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        resolver = mock(ProjectDependenciesResolver.class);
        pomChangeAnalyzer = mock(PomChangeAnalyzer.class);
        analyzer = new TransitiveImpactAnalyzer(resolver, pomChangeAnalyzer);
    }

    @Test
    void collectDependencyVersions_collectsFromTree() {
        DependencyNode root = new DefaultDependencyNode((Dependency) null);
        DependencyNode child1 = createDependencyNode("com.example", "dep-a", "1.0", "compile");
        DependencyNode child2 = createDependencyNode("com.example", "dep-b", "2.0", "test");
        root.setChildren(List.of(child1, child2));

        Map<String, String> versions = TransitiveImpactAnalyzer.collectDependencyVersions(root);

        assertEquals(2, versions.size());
        assertEquals("1.0", versions.get("com.example:dep-a"));
        assertEquals("2.0", versions.get("com.example:dep-b"));
    }

    @Test
    void collectDependencyVersions_handlesNestedNodes() {
        DependencyNode root = new DefaultDependencyNode((Dependency) null);
        DependencyNode child = createDependencyNode("com.example", "dep-a", "1.0", "compile");
        DependencyNode grandchild = createDependencyNode("org.other", "lib", "3.0", "compile");
        child.setChildren(List.of(grandchild));
        root.setChildren(List.of(child));

        Map<String, String> versions = TransitiveImpactAnalyzer.collectDependencyVersions(root);

        assertEquals(2, versions.size());
        assertEquals("1.0", versions.get("com.example:dep-a"));
        assertEquals("3.0", versions.get("org.other:lib"));
    }

    @Test
    void collectDependencyVersions_deduplicatesByGA() {
        DependencyNode root = new DefaultDependencyNode((Dependency) null);
        DependencyNode child1 = createDependencyNode("com.example", "dep-a", "1.0", "compile");
        DependencyNode child2 = createDependencyNode("com.example", "dep-a", "2.0", "compile");
        root.setChildren(List.of(child1, child2));

        Map<String, String> versions = TransitiveImpactAnalyzer.collectDependencyVersions(root);

        assertEquals(1, versions.size());
        // LIFO traversal: child2 (dep-a:2.0) is popped first, first-encountered wins
        assertEquals("2.0", versions.get("com.example:dep-a"));
    }

    @Test
    void collectDependencyScopes_collectsFromTree() {
        DependencyNode root = new DefaultDependencyNode((Dependency) null);
        DependencyNode child1 = createDependencyNode("com.example", "dep-a", "1.0", "compile");
        DependencyNode child2 = createDependencyNode("com.example", "dep-b", "2.0", "test");
        root.setChildren(List.of(child1, child2));

        Map<String, String> scopes = TransitiveImpactAnalyzer.collectDependencyScopes(root);

        assertEquals(2, scopes.size());
        assertEquals("compile", scopes.get("com.example:dep-a"));
        assertEquals("test", scopes.get("com.example:dep-b"));
    }

    @Test
    void collectDependencyScopes_emptyScopeFallsThrough() {
        // Aether's Dependency normalizes null scope to ""; verify the code handles
        // that case gracefully (the getOrDefault("compile") path in the caller
        // handles any missing entries, not this method).
        DependencyNode root = new DefaultDependencyNode((Dependency) null);
        DependencyNode child = createDependencyNode("com.example", "dep-a", "1.0", null);
        root.setChildren(List.of(child));

        Map<String, String> scopes = TransitiveImpactAnalyzer.collectDependencyScopes(root);

        // Aether's Dependency constructor normalizes null -> ""; the method preserves that.
        assertEquals("", scopes.get("com.example:dep-a"));
    }

    @Test
    void collectDependencyVersions_emptyTree() {
        DependencyNode root = new DefaultDependencyNode((Dependency) null);
        root.setChildren(List.of());

        Map<String, String> versions = TransitiveImpactAnalyzer.collectDependencyVersions(root);

        assertTrue(versions.isEmpty());
    }

    @Test
    void deriveChangedManagedDeps_detectsChanges() {
        Map<String, Model> oldModels = Map.of("pom.xml", new Model());
        Map<String, Model> newModels = Map.of("pom.xml", new Model());

        List<org.apache.maven.model.Dependency> oldDeps = List.of();
        List<org.apache.maven.model.Dependency> newDeps = List.of();
        when(pomChangeAnalyzer.getManagedDependencies(any())).thenReturn(oldDeps, newDeps);
        when(pomChangeAnalyzer.diffDependencies(oldDeps, newDeps)).thenReturn(Set.of("com.example:changed-dep"));

        Set<String> changed = analyzer.deriveChangedManagedDeps(oldModels, newModels);

        assertEquals(Set.of("com.example:changed-dep"), changed);
    }

    @Test
    void deriveChangedManagedDeps_emptyModels() {
        Set<String> changed = analyzer.deriveChangedManagedDeps(Map.of(), Map.of());
        assertTrue(changed.isEmpty());
    }

    @Test
    void deriveChangedManagedPlugins_detectsChanges() {
        Map<String, Model> oldModels = Map.of("pom.xml", new Model());
        Map<String, Model> newModels = Map.of("pom.xml", new Model());

        List<org.apache.maven.model.Plugin> oldPlugins = List.of();
        List<org.apache.maven.model.Plugin> newPlugins = List.of();
        when(pomChangeAnalyzer.getManagedPlugins(any())).thenReturn(oldPlugins, newPlugins);
        when(pomChangeAnalyzer.diffManagedPluginVersions(oldPlugins, newPlugins))
                .thenReturn(Set.of("org.apache.maven.plugins:maven-compiler-plugin"));

        Set<String> changed = analyzer.deriveChangedManagedPlugins(oldModels, newModels);

        assertEquals(Set.of("org.apache.maven.plugins:maven-compiler-plugin"), changed);
    }

    @Test
    void computeTransitivelyAffected_skipsWhenNoEffectiveModels() {
        MavenSession session = mock(MavenSession.class);
        List<MavenProject> allProjects = List.of();
        Set<MavenProject> directlyAffected = Set.of();
        Map<MavenProject, List<String>> returnEvidence = new LinkedHashMap<>();

        Map<MavenProject, List<String>> result = analyzer.computeTransitivelyAffected(
                allProjects,
                directlyAffected,
                new TransitiveImpactAnalyzer.EffectiveModels(Map.of(), Map.of()),
                new TransitiveImpactAnalyzer.ResolutionContext(
                        tempDir, session, new LinkedHashMap<>(), new LinkedHashMap<>(), new Timings()),
                false,
                returnEvidence);

        assertTrue(result.isEmpty());
    }

    @Test
    void resolveProjectDependencies_returnsCachedResult() {
        MavenProject project = mock(MavenProject.class);
        when(project.getGroupId()).thenReturn("com.example");
        when(project.getArtifactId()).thenReturn("module-a");
        MavenSession session = mock(MavenSession.class);
        DependencyResolutionResult cachedResult = mock(DependencyResolutionResult.class);
        Map<MavenProject, DependencyResolutionResult> cache = new LinkedHashMap<>();
        cache.put(project, cachedResult);
        Timings timings = new Timings();

        DependencyResolutionResult result = analyzer.resolveProjectDependencies(project, session, cache, timings);

        assertEquals(cachedResult, result);
    }

    @Test
    void resolveProjectDependencies_returnsNullOnException() throws Exception {
        MavenProject project = mock(MavenProject.class);
        when(project.getGroupId()).thenReturn("com.example");
        when(project.getArtifactId()).thenReturn("module-a");
        MavenSession session = mock(MavenSession.class);
        RepositorySystemSession repoSession = mock(RepositorySystemSession.class);
        when(session.getRepositorySession()).thenReturn(repoSession);
        DependencyResolutionResult partialResult = mock(DependencyResolutionResult.class);
        when(partialResult.getUnresolvedDependencies()).thenReturn(List.of());
        // Build the exception eagerly, outside the when() chain, so the mock
        // interaction in DependencyResolutionException's constructor doesn't
        // interfere with Mockito's stubbing state.
        DependencyResolutionException ex = new DependencyResolutionException(partialResult, "test failure", null);
        when(resolver.resolve(any())).thenThrow(ex);
        Timings timings = new Timings();

        DependencyResolutionResult result =
                analyzer.resolveProjectDependencies(project, session, new LinkedHashMap<>(), timings);

        assertNull(result);
    }

    private DependencyNode createDependencyNode(String groupId, String artifactId, String version, String scope) {
        DefaultArtifact artifact = new DefaultArtifact(groupId, artifactId, "jar", version);
        Dependency dep = new Dependency(artifact, scope);
        DefaultDependencyNode node = new DefaultDependencyNode(dep);
        node.setChildren(new ArrayList<>());
        return node;
    }
}
