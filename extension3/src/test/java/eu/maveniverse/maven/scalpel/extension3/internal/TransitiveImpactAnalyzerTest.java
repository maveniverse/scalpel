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
    void computeTransitivelyAffected_testOnlyProjects_notSeededIntoReactorPropagation() throws Exception {
        // Module A has a test-only POM dep change (testOnlyProjects = {A}).
        // Module B depends on A (compile scope).
        // B must NOT appear in the result: A's compile-time output is unchanged,
        // so unrestricted reactor propagation must not traverse through A.
        MavenSession session = mock(MavenSession.class);
        RepositorySystemSession repoSession = mock(RepositorySystemSession.class);
        when(session.getRepositorySession()).thenReturn(repoSession);

        MavenProject moduleA = mock(MavenProject.class);
        when(moduleA.getGroupId()).thenReturn("com.example");
        when(moduleA.getArtifactId()).thenReturn("module-a");

        // Module B's file must be set so computeTransitiveMatch can derive the relative path
        // used as the key in the effective models map.
        java.nio.file.Path moduleBPom = tempDir.resolve("module-b/pom.xml");
        java.nio.file.Files.createDirectories(moduleBPom.getParent());
        java.nio.file.Files.createFile(moduleBPom);

        MavenProject moduleB = mock(MavenProject.class);
        when(moduleB.getGroupId()).thenReturn("com.example");
        when(moduleB.getArtifactId()).thenReturn("module-b");
        when(moduleB.getFile()).thenReturn(moduleBPom.toFile());

        // Build module-b's dependency graph: it depends on module-a at compile scope.
        DependencyNode moduleANode = createDependencyNode("com.example", "module-a", "1.0", "compile");
        DependencyNode bRoot = new DefaultDependencyNode((Dependency) null);
        bRoot.setChildren(List.of(moduleANode));

        DependencyResolutionResult bResult = mock(DependencyResolutionResult.class);
        when(bResult.getDependencyGraph()).thenReturn(bRoot);

        // Pre-populate the resolution cache for module-b so resolver.resolve() is never called.
        Map<MavenProject, DependencyResolutionResult> collectCache = new LinkedHashMap<>();
        collectCache.put(moduleB, bResult);

        // Old dependency tree for module-b also has module-a (same as new → no change → first pass silent).
        // In a real reactor, module-a was already a compile dep of module-b before the POM change;
        // module-a's test-only POM dep change does not alter its compile-time output,
        // so module-b's old and new dependency trees are identical.
        DependencyNode moduleANodeOld = createDependencyNode("com.example", "module-a", "1.0", "compile");
        DependencyNode bRootOld = new DefaultDependencyNode((Dependency) null);
        bRootOld.setChildren(List.of(moduleANodeOld));
        DependencyResolutionResult bOldResult = mock(DependencyResolutionResult.class);
        when(bOldResult.getDependencyGraph()).thenReturn(bRootOld);
        Map<MavenProject, DependencyResolutionResult> oldCollectCache = new LinkedHashMap<>();
        oldCollectCache.put(moduleB, bOldResult);

        // Effective models: non-empty so the analyzer enters both passes.
        // Module B's old and new models are identical → first pass finds no dependency change.
        Model emptyModel = new Model();
        Map<String, Model> oldModels = Map.of("module-b/pom.xml", emptyModel);
        Map<String, Model> newModels = Map.of("module-b/pom.xml", emptyModel);
        when(pomChangeAnalyzer.getManagedDependencies(any())).thenReturn(List.of());
        when(pomChangeAnalyzer.getManagedPlugins(any())).thenReturn(List.of());
        when(pomChangeAnalyzer.diffDependencies(any(), any())).thenReturn(Set.of());
        when(pomChangeAnalyzer.diffManagedPluginVersions(any(), any())).thenReturn(Set.of());
        when(pomChangeAnalyzer.getEffectivePlugins(any())).thenReturn(List.of());

        Set<MavenProject> directlyAffected = Set.of(moduleA);
        Set<MavenProject> testOnlyProjects = Set.of(moduleA);
        Map<MavenProject, List<String>> returnEvidence = new LinkedHashMap<>();

        Map<MavenProject, List<String>> result = analyzer.computeTransitivelyAffected(
                List.of(moduleA, moduleB),
                directlyAffected,
                testOnlyProjects,
                new TransitiveImpactAnalyzer.EffectiveModels(oldModels, newModels),
                new TransitiveImpactAnalyzer.ResolutionContext(
                        tempDir, session, collectCache, oldCollectCache, new Timings()),
                false,
                returnEvidence);

        assertFalse(
                result.containsKey(moduleB),
                "module-b must NOT be transitively affected: module-a is test-only and "
                        + "must not seed unrestricted reactor propagation");
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
                Set.of(),
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

    @Test
    void computeTransitivelyAffected_testOnlyProjectDoesNotPropagateToCompileConsumer()
            throws DependencyResolutionException, java.io.IOException {
        // Module A is directly affected but test-only (e.g. POM-only test-dep change).
        // Module B depends on A at compile scope.
        // Expected: B must NOT appear in transitivelyAffected because A is test-only.
        // The EffectiveModels map must be non-empty so computeTransitivelyAffected does NOT
        // early-return at the "if (models.oldModels().isEmpty())" guard — otherwise the
        // testOnlyProjects filter logic is never exercised and the assertion passes vacuously.
        MavenSession session = mock(MavenSession.class);
        RepositorySystemSession repoSession = mock(RepositorySystemSession.class);
        when(session.getRepositorySession()).thenReturn(repoSession);

        // Create module A and B projects. Module B's pom.xml must exist on disk so that
        // TransitiveImpactAnalyzer can derive its relative path for the effective-models lookup.
        java.nio.file.Path moduleBPom = tempDir.resolve("module-b/pom.xml");
        java.nio.file.Files.createDirectories(moduleBPom.getParent());
        java.nio.file.Files.createFile(moduleBPom);

        MavenProject projectA = mock(MavenProject.class);
        when(projectA.getGroupId()).thenReturn("com.example");
        when(projectA.getArtifactId()).thenReturn("module-a");
        when(projectA.getVersion()).thenReturn("1.0");
        when(projectA.getFile()).thenReturn(tempDir.resolve("module-a/pom.xml").toFile());

        MavenProject projectB = mock(MavenProject.class);
        when(projectB.getGroupId()).thenReturn("com.example");
        when(projectB.getArtifactId()).thenReturn("module-b");
        when(projectB.getVersion()).thenReturn("1.0");
        when(projectB.getFile()).thenReturn(moduleBPom.toFile());

        // Module B's resolved dependency tree includes A at compile scope.
        // Pre-populate the resolution cache so resolver.resolve() is never called.
        DependencyNode moduleANode = createDependencyNode("com.example", "module-a", "1.0", "compile");
        DependencyNode rootBNode = new DefaultDependencyNode((org.eclipse.aether.graph.Dependency) null);
        rootBNode.setChildren(List.of(moduleANode));

        DependencyResolutionResult resolutionResultB = mock(DependencyResolutionResult.class);
        when(resolutionResultB.getUnresolvedDependencies()).thenReturn(List.of());
        when(resolutionResultB.getDependencyGraph()).thenReturn(rootBNode);

        // Old dep tree for B is identical (module-a's test-only POM change doesn't alter compile output).
        DependencyNode moduleANodeOld = createDependencyNode("com.example", "module-a", "1.0", "compile");
        DependencyNode bRootOld = new DefaultDependencyNode((org.eclipse.aether.graph.Dependency) null);
        bRootOld.setChildren(List.of(moduleANodeOld));
        DependencyResolutionResult bOldResult = mock(DependencyResolutionResult.class);
        when(bOldResult.getDependencyGraph()).thenReturn(bRootOld);

        Map<MavenProject, DependencyResolutionResult> collectCache = new LinkedHashMap<>();
        collectCache.put(projectB, resolutionResultB);
        Map<MavenProject, DependencyResolutionResult> oldCollectCache = new LinkedHashMap<>();
        oldCollectCache.put(projectB, bOldResult);

        // Non-empty EffectiveModels so the analyzer enters both passes (not early-return).
        // Module B's old and new models are identical — first pass finds no dependency change.
        Model emptyModel = new Model();
        Map<String, Model> oldModels = Map.of("module-b/pom.xml", emptyModel);
        Map<String, Model> newModels = Map.of("module-b/pom.xml", emptyModel);
        when(pomChangeAnalyzer.getManagedDependencies(any())).thenReturn(List.of());
        when(pomChangeAnalyzer.getManagedPlugins(any())).thenReturn(List.of());
        when(pomChangeAnalyzer.diffDependencies(any(), any())).thenReturn(Set.of());
        when(pomChangeAnalyzer.diffManagedPluginVersions(any(), any())).thenReturn(Set.of());
        when(pomChangeAnalyzer.getEffectivePlugins(any())).thenReturn(List.of());

        List<MavenProject> allProjects = List.of(projectA, projectB);
        Set<MavenProject> directlyAffected = Set.of(projectA);
        // A is test-only: it must not seed affectedGAs for compile-time propagation
        Set<MavenProject> testOnlyProjects = Set.of(projectA);
        Map<MavenProject, List<String>> returnEvidence = new LinkedHashMap<>();

        Map<MavenProject, List<String>> result = analyzer.computeTransitivelyAffected(
                allProjects,
                directlyAffected,
                testOnlyProjects,
                new TransitiveImpactAnalyzer.EffectiveModels(oldModels, newModels),
                new TransitiveImpactAnalyzer.ResolutionContext(
                        tempDir, session, collectCache, oldCollectCache, new Timings()),
                false,
                returnEvidence);

        assertFalse(
                result.containsKey(projectB), "module-b must NOT be transitively affected when module-a is test-only");
    }

    private DependencyNode createDependencyNode(String groupId, String artifactId, String version, String scope) {
        DefaultArtifact artifact = new DefaultArtifact(groupId, artifactId, "jar", version);
        Dependency dep = new Dependency(artifact, scope);
        DefaultDependencyNode node = new DefaultDependencyNode(dep);
        node.setChildren(new ArrayList<>());
        return node;
    }
}
