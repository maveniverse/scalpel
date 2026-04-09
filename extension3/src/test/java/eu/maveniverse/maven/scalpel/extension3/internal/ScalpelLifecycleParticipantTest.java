/*
 * Copyright (c) Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.scalpel.extension3.internal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import eu.maveniverse.maven.scalpel.core.ChangeDetectionResult;
import eu.maveniverse.maven.scalpel.core.ScalpelCore;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.execution.ProjectDependencyGraph;
import org.apache.maven.model.Build;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.project.DefaultDependencyResolutionRequest;
import org.apache.maven.project.DependencyResolutionResult;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectDependenciesResolver;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScalpelLifecycleParticipantTest {

    @TempDir
    Path tempDir;

    private ScalpelCore scalpelCore;
    private ProjectDependenciesResolver dependenciesResolver;
    private ScalpelLifecycleParticipant participant;

    @BeforeEach
    void setUp() {
        scalpelCore = mock(ScalpelCore.class);
        dependenciesResolver = mock(ProjectDependenciesResolver.class);
        participant = new ScalpelLifecycleParticipant(
                scalpelCore, new ModuleMapper(), new PomChangeAnalyzer(), new ReactorTrimmer(), dependenciesResolver);
    }

    @Test
    void reportMode_includesTransitivelyAffectedModules() throws Exception {
        // Setup: parent POM manages commons-lang with property-based version
        // module-a declares commons-lang (directly affected)
        // module-b depends on module-a (transitively affected via dependency resolution)
        // module-c has no deps (not affected)
        Path root = tempDir.resolve("project");
        Files.createDirectories(root);

        // Old parent POM (before property change)
        String oldParentPom = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <groupId>com.example</groupId>\n"
                + "  <artifactId>parent</artifactId>\n"
                + "  <version>1.0</version>\n"
                + "  <packaging>pom</packaging>\n"
                + "  <modules><module>module-a</module><module>module-b</module><module>module-c</module></modules>\n"
                + "  <properties>\n"
                + "    <lib.version>1.0</lib.version>\n"
                + "  </properties>\n"
                + "  <dependencyManagement><dependencies>\n"
                + "    <dependency>\n"
                + "      <groupId>commons-lang</groupId>\n"
                + "      <artifactId>commons-lang</artifactId>\n"
                + "      <version>${lib.version}</version>\n"
                + "    </dependency>\n"
                + "  </dependencies></dependencyManagement>\n"
                + "</project>\n";

        // New parent POM (after property change)
        String newParentPom = oldParentPom.replace("<lib.version>1.0</lib.version>", "<lib.version>2.0</lib.version>");
        writePom(root, "pom.xml", newParentPom);

        // module-a: directly uses managed dep commons-lang
        String moduleAPom = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>\n"
                + "  <artifactId>module-a</artifactId>\n"
                + "  <dependencies>\n"
                + "    <dependency><groupId>commons-lang</groupId><artifactId>commons-lang</artifactId></dependency>\n"
                + "  </dependencies>\n"
                + "</project>\n";
        writePom(root, "module-a/pom.xml", moduleAPom);

        // module-b: depends on module-a (gets commons-lang transitively)
        String moduleBPom = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>\n"
                + "  <artifactId>module-b</artifactId>\n"
                + "  <dependencies>\n"
                + "    <dependency><groupId>com.example</groupId><artifactId>module-a</artifactId><version>1.0</version></dependency>\n"
                + "  </dependencies>\n"
                + "</project>\n";
        writePom(root, "module-b/pom.xml", moduleBPom);

        // module-c: no dependencies
        String moduleCPom = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>\n"
                + "  <artifactId>module-c</artifactId>\n"
                + "</project>\n";
        writePom(root, "module-c/pom.xml", moduleCPom);

        // Build MavenProject objects
        MavenProject parentProject = createProject("com.example", "parent", "1.0", root, "pom.xml", newParentPom);
        parentProject.getModel().setPackaging("pom");
        MavenProject moduleA = createProject("com.example", "module-a", "1.0", root, "module-a/pom.xml", moduleAPom);
        moduleA.setParent(parentProject);
        MavenProject moduleB = createProject("com.example", "module-b", "1.0", root, "module-b/pom.xml", moduleBPom);
        moduleB.setParent(parentProject);
        MavenProject moduleC = createProject("com.example", "module-c", "1.0", root, "module-c/pom.xml", moduleCPom);
        moduleC.setParent(parentProject);

        List<MavenProject> allProjects = Arrays.asList(parentProject, moduleA, moduleB, moduleC);

        // Mock ScalpelCore to return changed files
        Set<String> changedFiles = new LinkedHashSet<>();
        changedFiles.add("pom.xml");
        Map<String, byte[]> oldPoms = new HashMap<>();
        oldPoms.put("pom.xml", oldParentPom.getBytes(StandardCharsets.UTF_8));
        ChangeDetectionResult detectionResult = new ChangeDetectionResult(changedFiles, oldPoms);
        when(scalpelCore.detectChanges(any(), any(), any())).thenReturn(detectionResult);

        // Mock dependency resolution: module-b has commons-lang transitively
        DependencyResolutionResult moduleBResolution = mock(DependencyResolutionResult.class);
        org.eclipse.aether.graph.Dependency commonsLangDep = new org.eclipse.aether.graph.Dependency(
                new DefaultArtifact("commons-lang", "commons-lang", "jar", "2.0"), "compile");
        when(moduleBResolution.getResolvedDependencies()).thenReturn(Collections.singletonList(commonsLangDep));

        // Mock dependency resolution: module-c has no matching deps
        DependencyResolutionResult moduleCResolution = mock(DependencyResolutionResult.class);
        when(moduleCResolution.getResolvedDependencies())
                .thenReturn(Collections.<org.eclipse.aether.graph.Dependency>emptyList());

        // Route resolution calls based on project
        when(dependenciesResolver.resolve(any(DefaultDependencyResolutionRequest.class)))
                .thenAnswer(invocation -> {
                    DefaultDependencyResolutionRequest req = invocation.getArgument(0);
                    if ("module-b".equals(req.getMavenProject().getArtifactId())) {
                        return moduleBResolution;
                    }
                    return moduleCResolution;
                });

        // Mock MavenSession
        MavenSession session = mock(MavenSession.class);
        Properties sysProps = new Properties();
        when(session.getSystemProperties()).thenReturn(sysProps);
        when(session.getUserProperties()).thenReturn(new Properties());
        when(session.getProjects()).thenReturn(allProjects);
        MavenExecutionRequest execRequest = mock(MavenExecutionRequest.class);
        when(execRequest.getMultiModuleProjectDirectory()).thenReturn(root.toFile());
        when(session.getRequest()).thenReturn(execRequest);
        when(session.getRepositorySession()).thenReturn(mock(RepositorySystemSession.class));
        ProjectDependencyGraph graph = mock(ProjectDependencyGraph.class);
        when(graph.getDownstreamProjects(any(), anyBoolean())).thenReturn(Collections.emptyList());
        when(graph.getUpstreamProjects(any(), anyBoolean())).thenReturn(Collections.emptyList());
        when(graph.getSortedProjects()).thenReturn(allProjects);
        when(session.getProjectDependencyGraph()).thenReturn(graph);

        // Set report mode
        sysProps.setProperty("scalpel.mode", "report");
        sysProps.setProperty("scalpel.baseBranch", "base");

        // Run
        participant.afterProjectsRead(session);

        // Verify report file
        Path reportFile = root.resolve("target/scalpel-report.json");
        assertTrue(Files.exists(reportFile), "Report file should be created");

        String json = new String(Files.readAllBytes(reportFile), StandardCharsets.UTF_8);

        // module-a should be directly affected (POM_CHANGE)
        assertTrue(moduleHasReason(json, "module-a", "POM_CHANGE"), "module-a should have POM_CHANGE reason");

        // module-b should be transitively affected
        assertTrue(
                moduleHasReason(json, "module-b", "TRANSITIVE_DEPENDENCY"),
                "module-b should have TRANSITIVE_DEPENDENCY reason");

        // module-c should NOT be in the report
        assertFalse(modulePresent(json, "module-c"), "module-c should NOT be in report");
    }

    @Test
    void reportMode_managedPluginChange() throws Exception {
        Path root = tempDir.resolve("project");
        Files.createDirectories(root);

        String oldParentPom = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <groupId>com.example</groupId>\n"
                + "  <artifactId>parent</artifactId>\n"
                + "  <version>1.0</version>\n"
                + "  <packaging>pom</packaging>\n"
                + "  <modules><module>module-a</module><module>module-b</module></modules>\n"
                + "  <properties>\n"
                + "    <compiler.version>3.11.0</compiler.version>\n"
                + "  </properties>\n"
                + "  <build><pluginManagement><plugins>\n"
                + "    <plugin>\n"
                + "      <groupId>org.apache.maven.plugins</groupId>\n"
                + "      <artifactId>maven-compiler-plugin</artifactId>\n"
                + "      <version>${compiler.version}</version>\n"
                + "    </plugin>\n"
                + "  </plugins></pluginManagement></build>\n"
                + "</project>\n";

        String newParentPom = oldParentPom.replace(
                "<compiler.version>3.11.0</compiler.version>", "<compiler.version>3.12.0</compiler.version>");
        writePom(root, "pom.xml", newParentPom);

        // module-a: no plugins
        String moduleAPom = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>\n"
                + "  <artifactId>module-a</artifactId>\n"
                + "</project>\n";
        writePom(root, "module-a/pom.xml", moduleAPom);

        // module-b: uses maven-compiler-plugin
        String moduleBPom = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>\n"
                + "  <artifactId>module-b</artifactId>\n"
                + "  <build><plugins>\n"
                + "    <plugin>\n"
                + "      <groupId>org.apache.maven.plugins</groupId>\n"
                + "      <artifactId>maven-compiler-plugin</artifactId>\n"
                + "    </plugin>\n"
                + "  </plugins></build>\n"
                + "</project>\n";
        writePom(root, "module-b/pom.xml", moduleBPom);

        MavenProject parentProject = createProject("com.example", "parent", "1.0", root, "pom.xml", newParentPom);
        parentProject.getModel().setPackaging("pom");
        // Add the managed plugin to the parent model for child resolution
        Build parentBuild = new Build();
        parentProject.getModel().setBuild(parentBuild);

        MavenProject moduleA = createProject("com.example", "module-a", "1.0", root, "module-a/pom.xml", moduleAPom);
        moduleA.setParent(parentProject);

        MavenProject moduleB = createProject("com.example", "module-b", "1.0", root, "module-b/pom.xml", moduleBPom);
        moduleB.setParent(parentProject);
        // Set build plugins on the effective model so usesChangedPlugin can find them
        Build build = new Build();
        Plugin compilerPlugin = new Plugin();
        compilerPlugin.setGroupId("org.apache.maven.plugins");
        compilerPlugin.setArtifactId("maven-compiler-plugin");
        build.addPlugin(compilerPlugin);
        moduleB.getModel().setBuild(build);

        List<MavenProject> allProjects = Arrays.asList(parentProject, moduleA, moduleB);

        Set<String> changedFiles = new LinkedHashSet<>();
        changedFiles.add("pom.xml");
        Map<String, byte[]> oldPoms = new HashMap<>();
        oldPoms.put("pom.xml", oldParentPom.getBytes(StandardCharsets.UTF_8));
        when(scalpelCore.detectChanges(any(), any(), any()))
                .thenReturn(new ChangeDetectionResult(changedFiles, oldPoms));

        // No transitive deps to resolve
        DependencyResolutionResult emptyResolution = mock(DependencyResolutionResult.class);
        when(emptyResolution.getResolvedDependencies())
                .thenReturn(Collections.<org.eclipse.aether.graph.Dependency>emptyList());
        when(dependenciesResolver.resolve(any(DefaultDependencyResolutionRequest.class)))
                .thenReturn(emptyResolution);

        MavenSession session = mock(MavenSession.class);
        Properties sysProps = new Properties();
        sysProps.setProperty("scalpel.mode", "report");
        sysProps.setProperty("scalpel.baseBranch", "base");
        when(session.getSystemProperties()).thenReturn(sysProps);
        when(session.getUserProperties()).thenReturn(new Properties());
        when(session.getProjects()).thenReturn(allProjects);
        MavenExecutionRequest execRequest = mock(MavenExecutionRequest.class);
        when(execRequest.getMultiModuleProjectDirectory()).thenReturn(root.toFile());
        when(session.getRequest()).thenReturn(execRequest);
        when(session.getRepositorySession()).thenReturn(mock(RepositorySystemSession.class));
        ProjectDependencyGraph graph2 = mock(ProjectDependencyGraph.class);
        when(graph2.getDownstreamProjects(any(), anyBoolean())).thenReturn(Collections.emptyList());
        when(graph2.getUpstreamProjects(any(), anyBoolean())).thenReturn(Collections.emptyList());
        when(graph2.getSortedProjects()).thenReturn(allProjects);
        when(session.getProjectDependencyGraph()).thenReturn(graph2);

        participant.afterProjectsRead(session);

        Path reportFile = root.resolve("target/scalpel-report.json");
        assertTrue(Files.exists(reportFile), "Report file should be created");

        String json = new String(Files.readAllBytes(reportFile), StandardCharsets.UTF_8);
        assertTrue(
                moduleHasReason(json, "module-b", "POM_CHANGE"),
                "module-b should have POM_CHANGE reason (PomChangeAnalyzer detects managed plugin use)");
        assertFalse(modulePresent(json, "module-a"), "module-a should NOT be in report (no plugin, no dep change)");
    }

    @Test
    void reportMode_testOnlySourceChangeProducesTestChangeReason() throws Exception {
        Path root = tempDir.resolve("project");
        Files.createDirectories(root);

        String parentPom = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <groupId>com.example</groupId>\n"
                + "  <artifactId>parent</artifactId>\n"
                + "  <version>1.0</version>\n"
                + "  <packaging>pom</packaging>\n"
                + "  <modules><module>module-a</module><module>module-b</module></modules>\n"
                + "</project>\n";
        writePom(root, "pom.xml", parentPom);

        String moduleAPom = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>\n"
                + "  <artifactId>module-a</artifactId>\n"
                + "</project>\n";
        writePom(root, "module-a/pom.xml", moduleAPom);

        String moduleBPom = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>\n"
                + "  <artifactId>module-b</artifactId>\n"
                + "</project>\n";
        writePom(root, "module-b/pom.xml", moduleBPom);

        MavenProject parentProject = createProject("com.example", "parent", "1.0", root, "pom.xml", parentPom);
        parentProject.getModel().setPackaging("pom");
        MavenProject moduleA = createProject("com.example", "module-a", "1.0", root, "module-a/pom.xml", moduleAPom);
        moduleA.setParent(parentProject);
        MavenProject moduleB = createProject("com.example", "module-b", "1.0", root, "module-b/pom.xml", moduleBPom);
        moduleB.setParent(parentProject);

        List<MavenProject> allProjects = Arrays.asList(parentProject, moduleA, moduleB);

        // module-a: test-only change (src/test/), module-b: main source change
        Set<String> changedFiles = new LinkedHashSet<>();
        changedFiles.add("module-a/src/test/java/com/example/MyTest.java");
        changedFiles.add("module-b/src/main/java/com/example/Service.java");
        when(scalpelCore.detectChanges(any(), any(), any()))
                .thenReturn(new ChangeDetectionResult(changedFiles, new HashMap<String, byte[]>()));

        DependencyResolutionResult emptyResolution = mock(DependencyResolutionResult.class);
        when(emptyResolution.getResolvedDependencies())
                .thenReturn(Collections.<org.eclipse.aether.graph.Dependency>emptyList());
        when(dependenciesResolver.resolve(any(DefaultDependencyResolutionRequest.class)))
                .thenReturn(emptyResolution);

        MavenSession session = mock(MavenSession.class);
        Properties sysProps = new Properties();
        sysProps.setProperty("scalpel.mode", "report");
        sysProps.setProperty("scalpel.baseBranch", "base");
        when(session.getSystemProperties()).thenReturn(sysProps);
        when(session.getUserProperties()).thenReturn(new Properties());
        when(session.getProjects()).thenReturn(allProjects);
        MavenExecutionRequest execRequest = mock(MavenExecutionRequest.class);
        when(execRequest.getMultiModuleProjectDirectory()).thenReturn(root.toFile());
        when(session.getRequest()).thenReturn(execRequest);
        when(session.getRepositorySession()).thenReturn(mock(RepositorySystemSession.class));
        ProjectDependencyGraph graph = mock(ProjectDependencyGraph.class);
        when(graph.getDownstreamProjects(any(), anyBoolean())).thenReturn(Collections.emptyList());
        when(graph.getUpstreamProjects(any(), anyBoolean())).thenReturn(Collections.emptyList());
        when(graph.getSortedProjects()).thenReturn(allProjects);
        when(session.getProjectDependencyGraph()).thenReturn(graph);

        participant.afterProjectsRead(session);

        Path reportFile = root.resolve("target/scalpel-report.json");
        assertTrue(Files.exists(reportFile), "Report file should be created");

        String json = new String(Files.readAllBytes(reportFile), StandardCharsets.UTF_8);
        assertTrue(
                moduleHasReason(json, "module-a", "TEST_CHANGE"),
                "module-a should have TEST_CHANGE reason (only test files changed)");
        assertTrue(
                moduleHasReason(json, "module-b", "SOURCE_CHANGE"),
                "module-b should have SOURCE_CHANGE reason (main source changed)");
    }

    @Test
    void reportMode_testScopedTransitiveDependencyProducesTestReason() throws Exception {
        Path root = tempDir.resolve("project");
        Files.createDirectories(root);

        String oldParentPom = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <groupId>com.example</groupId>\n"
                + "  <artifactId>parent</artifactId>\n"
                + "  <version>1.0</version>\n"
                + "  <packaging>pom</packaging>\n"
                + "  <modules><module>module-a</module><module>module-b</module></modules>\n"
                + "  <properties>\n"
                + "    <lib.version>1.0</lib.version>\n"
                + "  </properties>\n"
                + "  <dependencyManagement><dependencies>\n"
                + "    <dependency>\n"
                + "      <groupId>commons-lang</groupId>\n"
                + "      <artifactId>commons-lang</artifactId>\n"
                + "      <version>${lib.version}</version>\n"
                + "    </dependency>\n"
                + "  </dependencies></dependencyManagement>\n"
                + "</project>\n";

        String newParentPom = oldParentPom.replace("<lib.version>1.0</lib.version>", "<lib.version>2.0</lib.version>");
        writePom(root, "pom.xml", newParentPom);

        // module-a: uses managed dep directly
        String moduleAPom = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>\n"
                + "  <artifactId>module-a</artifactId>\n"
                + "  <dependencies>\n"
                + "    <dependency><groupId>commons-lang</groupId><artifactId>commons-lang</artifactId></dependency>\n"
                + "  </dependencies>\n"
                + "</project>\n";
        writePom(root, "module-a/pom.xml", moduleAPom);

        // module-b: gets commons-lang only via test scope transitively
        String moduleBPom = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>\n"
                + "  <artifactId>module-b</artifactId>\n"
                + "</project>\n";
        writePom(root, "module-b/pom.xml", moduleBPom);

        MavenProject parentProject = createProject("com.example", "parent", "1.0", root, "pom.xml", newParentPom);
        parentProject.getModel().setPackaging("pom");
        MavenProject moduleA = createProject("com.example", "module-a", "1.0", root, "module-a/pom.xml", moduleAPom);
        moduleA.setParent(parentProject);
        MavenProject moduleB = createProject("com.example", "module-b", "1.0", root, "module-b/pom.xml", moduleBPom);
        moduleB.setParent(parentProject);

        List<MavenProject> allProjects = Arrays.asList(parentProject, moduleA, moduleB);

        Set<String> changedFiles = new LinkedHashSet<>();
        changedFiles.add("pom.xml");
        Map<String, byte[]> oldPoms = new HashMap<>();
        oldPoms.put("pom.xml", oldParentPom.getBytes(StandardCharsets.UTF_8));
        when(scalpelCore.detectChanges(any(), any(), any()))
                .thenReturn(new ChangeDetectionResult(changedFiles, oldPoms));

        // module-b: commons-lang is only via test scope
        DependencyResolutionResult moduleBResolution = mock(DependencyResolutionResult.class);
        org.eclipse.aether.graph.Dependency testDep = new org.eclipse.aether.graph.Dependency(
                new DefaultArtifact("commons-lang", "commons-lang", "jar", "2.0"), "test");
        when(moduleBResolution.getResolvedDependencies()).thenReturn(Collections.singletonList(testDep));

        when(dependenciesResolver.resolve(any(DefaultDependencyResolutionRequest.class)))
                .thenAnswer(invocation -> {
                    DefaultDependencyResolutionRequest req = invocation.getArgument(0);
                    if ("module-b".equals(req.getMavenProject().getArtifactId())) {
                        return moduleBResolution;
                    }
                    DependencyResolutionResult empty = mock(DependencyResolutionResult.class);
                    when(empty.getResolvedDependencies())
                            .thenReturn(Collections.<org.eclipse.aether.graph.Dependency>emptyList());
                    return empty;
                });

        MavenSession session = mock(MavenSession.class);
        Properties sysProps = new Properties();
        sysProps.setProperty("scalpel.mode", "report");
        sysProps.setProperty("scalpel.baseBranch", "base");
        when(session.getSystemProperties()).thenReturn(sysProps);
        when(session.getUserProperties()).thenReturn(new Properties());
        when(session.getProjects()).thenReturn(allProjects);
        MavenExecutionRequest execRequest = mock(MavenExecutionRequest.class);
        when(execRequest.getMultiModuleProjectDirectory()).thenReturn(root.toFile());
        when(session.getRequest()).thenReturn(execRequest);
        when(session.getRepositorySession()).thenReturn(mock(RepositorySystemSession.class));
        ProjectDependencyGraph graph = mock(ProjectDependencyGraph.class);
        when(graph.getDownstreamProjects(any(), anyBoolean())).thenReturn(Collections.emptyList());
        when(graph.getUpstreamProjects(any(), anyBoolean())).thenReturn(Collections.emptyList());
        when(graph.getSortedProjects()).thenReturn(allProjects);
        when(session.getProjectDependencyGraph()).thenReturn(graph);

        participant.afterProjectsRead(session);

        Path reportFile = root.resolve("target/scalpel-report.json");
        assertTrue(Files.exists(reportFile));

        String json = new String(Files.readAllBytes(reportFile), StandardCharsets.UTF_8);
        assertTrue(moduleHasReason(json, "module-a", "POM_CHANGE"));
        assertTrue(
                moduleHasReason(json, "module-b", "TRANSITIVE_DEPENDENCY_TEST"),
                "module-b should have TRANSITIVE_DEPENDENCY_TEST (test-scoped transitive dep)");
    }

    @Test
    void reportMode_downstreamTestScopedModuleProducesDownstreamTestReason() throws Exception {
        Path root = tempDir.resolve("project");
        Files.createDirectories(root);

        String parentPom = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <groupId>com.example</groupId>\n"
                + "  <artifactId>parent</artifactId>\n"
                + "  <version>1.0</version>\n"
                + "  <packaging>pom</packaging>\n"
                + "  <modules><module>module-a</module><module>module-b</module></modules>\n"
                + "</project>\n";
        writePom(root, "pom.xml", parentPom);

        String moduleAPom = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>\n"
                + "  <artifactId>module-a</artifactId>\n"
                + "</project>\n";
        writePom(root, "module-a/pom.xml", moduleAPom);

        // module-b depends on module-a via test scope
        String moduleBPom = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>\n"
                + "  <artifactId>module-b</artifactId>\n"
                + "  <dependencies>\n"
                + "    <dependency><groupId>com.example</groupId><artifactId>module-a</artifactId><version>1.0</version><scope>test</scope></dependency>\n"
                + "  </dependencies>\n"
                + "</project>\n";
        writePom(root, "module-b/pom.xml", moduleBPom);

        MavenProject parentProject = createProject("com.example", "parent", "1.0", root, "pom.xml", parentPom);
        parentProject.getModel().setPackaging("pom");
        MavenProject moduleA = createProject("com.example", "module-a", "1.0", root, "module-a/pom.xml", moduleAPom);
        moduleA.setParent(parentProject);
        MavenProject moduleB = createProject("com.example", "module-b", "1.0", root, "module-b/pom.xml", moduleBPom);
        moduleB.setParent(parentProject);
        // Add test-scoped dependency on module-a to module-b's model
        Dependency dep = new Dependency();
        dep.setGroupId("com.example");
        dep.setArtifactId("module-a");
        dep.setVersion("1.0");
        dep.setScope("test");
        moduleB.getDependencies().add(dep);

        List<MavenProject> allProjects = Arrays.asList(parentProject, moduleA, moduleB);

        // module-a has a source change
        Set<String> changedFiles = new LinkedHashSet<>();
        changedFiles.add("module-a/src/main/java/com/example/Foo.java");
        when(scalpelCore.detectChanges(any(), any(), any()))
                .thenReturn(new ChangeDetectionResult(changedFiles, new HashMap<String, byte[]>()));

        DependencyResolutionResult emptyResolution = mock(DependencyResolutionResult.class);
        when(emptyResolution.getResolvedDependencies())
                .thenReturn(Collections.<org.eclipse.aether.graph.Dependency>emptyList());
        when(dependenciesResolver.resolve(any(DefaultDependencyResolutionRequest.class)))
                .thenReturn(emptyResolution);

        MavenSession session = mock(MavenSession.class);
        Properties sysProps = new Properties();
        sysProps.setProperty("scalpel.mode", "report");
        sysProps.setProperty("scalpel.baseBranch", "base");
        when(session.getSystemProperties()).thenReturn(sysProps);
        when(session.getUserProperties()).thenReturn(new Properties());
        when(session.getProjects()).thenReturn(allProjects);
        MavenExecutionRequest execRequest = mock(MavenExecutionRequest.class);
        when(execRequest.getMultiModuleProjectDirectory()).thenReturn(root.toFile());
        when(session.getRequest()).thenReturn(execRequest);
        when(session.getRepositorySession()).thenReturn(mock(RepositorySystemSession.class));

        // Graph: module-b is downstream of module-a
        ProjectDependencyGraph graph = mock(ProjectDependencyGraph.class);
        when(graph.getDownstreamProjects(moduleA, true)).thenReturn(Collections.singletonList(moduleB));
        when(graph.getDownstreamProjects(moduleB, true)).thenReturn(Collections.<MavenProject>emptyList());
        when(graph.getUpstreamProjects(any(), anyBoolean())).thenReturn(Collections.emptyList());
        when(graph.getSortedProjects()).thenReturn(allProjects);
        when(session.getProjectDependencyGraph()).thenReturn(graph);

        participant.afterProjectsRead(session);

        Path reportFile = root.resolve("target/scalpel-report.json");
        assertTrue(Files.exists(reportFile));

        String json = new String(Files.readAllBytes(reportFile), StandardCharsets.UTF_8);
        assertTrue(moduleHasReason(json, "module-a", "SOURCE_CHANGE"), "module-a should have SOURCE_CHANGE");
        assertTrue(
                moduleHasReason(json, "module-b", "DOWNSTREAM_TEST"),
                "module-b should have DOWNSTREAM_TEST (test-scoped downstream of module-a)");
    }

    @Test
    void trimMode_removesUnaffectedModules() throws Exception {
        Path root = tempDir.resolve("project");
        Files.createDirectories(root);

        String parentPom = simpleParentPom("module-a", "module-b");
        writePom(root, "pom.xml", parentPom);
        String moduleAPom = simpleChildPom("module-a");
        writePom(root, "module-a/pom.xml", moduleAPom);
        String moduleBPom = simpleChildPom("module-b");
        writePom(root, "module-b/pom.xml", moduleBPom);

        MavenProject parentProject = createProject("com.example", "parent", "1.0", root, "pom.xml", parentPom);
        parentProject.getModel().setPackaging("pom");
        MavenProject moduleA = createProject("com.example", "module-a", "1.0", root, "module-a/pom.xml", moduleAPom);
        moduleA.setParent(parentProject);
        MavenProject moduleB = createProject("com.example", "module-b", "1.0", root, "module-b/pom.xml", moduleBPom);
        moduleB.setParent(parentProject);

        List<MavenProject> allProjects = Arrays.asList(parentProject, moduleA, moduleB);

        Set<String> changedFiles = new LinkedHashSet<>();
        changedFiles.add("module-a/src/main/java/Foo.java");
        when(scalpelCore.detectChanges(any(), any(), any()))
                .thenReturn(new ChangeDetectionResult(changedFiles, new HashMap<String, byte[]>()));
        setupEmptyDependencyResolution();

        MavenSession session = createSimpleSession(root, allProjects, "trim");

        participant.afterProjectsRead(session);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<MavenProject>> captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(session).setProjects(captor.capture());
        List<MavenProject> trimmed = captor.getValue();
        assertTrue(trimmed.contains(moduleA), "module-a should be in trimmed reactor");
        assertFalse(trimmed.contains(moduleB), "module-b should NOT be in trimmed reactor");
    }

    @Test
    void skipTestsMode_skipsUnaffectedModules() throws Exception {
        Path root = tempDir.resolve("project");
        Files.createDirectories(root);

        String parentPom = simpleParentPom("module-a", "module-b");
        writePom(root, "pom.xml", parentPom);
        String moduleAPom = simpleChildPom("module-a");
        writePom(root, "module-a/pom.xml", moduleAPom);
        String moduleBPom = simpleChildPom("module-b");
        writePom(root, "module-b/pom.xml", moduleBPom);

        MavenProject parentProject = createProject("com.example", "parent", "1.0", root, "pom.xml", parentPom);
        parentProject.getModel().setPackaging("pom");
        MavenProject moduleA = createProject("com.example", "module-a", "1.0", root, "module-a/pom.xml", moduleAPom);
        moduleA.setParent(parentProject);
        MavenProject moduleB = createProject("com.example", "module-b", "1.0", root, "module-b/pom.xml", moduleBPom);
        moduleB.setParent(parentProject);

        List<MavenProject> allProjects = Arrays.asList(parentProject, moduleA, moduleB);

        Set<String> changedFiles = new LinkedHashSet<>();
        changedFiles.add("module-a/src/main/java/Foo.java");
        when(scalpelCore.detectChanges(any(), any(), any()))
                .thenReturn(new ChangeDetectionResult(changedFiles, new HashMap<String, byte[]>()));
        setupEmptyDependencyResolution();

        MavenSession session = createSimpleSession(root, allProjects, "skip-tests");

        participant.afterProjectsRead(session);

        assertTrue(
                "true".equals(moduleB.getProperties().getProperty("maven.test.skip")),
                "module-b should have maven.test.skip=true");
    }

    @Test
    void reportMode_forceBuildModulesIncludesMatching() throws Exception {
        Path root = tempDir.resolve("project");
        Files.createDirectories(root);

        String parentPom = simpleParentPom("module-a", "module-b");
        writePom(root, "pom.xml", parentPom);
        String moduleAPom = simpleChildPom("module-a");
        writePom(root, "module-a/pom.xml", moduleAPom);
        String moduleBPom = simpleChildPom("module-b");
        writePom(root, "module-b/pom.xml", moduleBPom);

        MavenProject parentProject = createProject("com.example", "parent", "1.0", root, "pom.xml", parentPom);
        parentProject.getModel().setPackaging("pom");
        MavenProject moduleA = createProject("com.example", "module-a", "1.0", root, "module-a/pom.xml", moduleAPom);
        moduleA.setParent(parentProject);
        MavenProject moduleB = createProject("com.example", "module-b", "1.0", root, "module-b/pom.xml", moduleBPom);
        moduleB.setParent(parentProject);

        List<MavenProject> allProjects = Arrays.asList(parentProject, moduleA, moduleB);

        Set<String> changedFiles = new LinkedHashSet<>();
        changedFiles.add("module-a/src/main/java/Foo.java");
        when(scalpelCore.detectChanges(any(), any(), any()))
                .thenReturn(new ChangeDetectionResult(changedFiles, new HashMap<String, byte[]>()));
        setupEmptyDependencyResolution();

        MavenSession session = createSimpleSession(root, allProjects, "report");
        session.getSystemProperties().setProperty("scalpel.forceBuildModules", "module-b");

        participant.afterProjectsRead(session);

        Path reportFile = root.resolve("target/scalpel-report.json");
        assertTrue(Files.exists(reportFile));
        String json = new String(Files.readAllBytes(reportFile), StandardCharsets.UTF_8);
        assertTrue(moduleHasReason(json, "module-b", "FORCE_BUILD"), "module-b should have FORCE_BUILD reason");
    }

    @Test
    void reportMode_fullBuildTriggerCreatesFullBuildReport() throws Exception {
        Path root = tempDir.resolve("project");
        Files.createDirectories(root);

        String parentPom = simpleParentPom("module-a");
        writePom(root, "pom.xml", parentPom);
        String moduleAPom = simpleChildPom("module-a");
        writePom(root, "module-a/pom.xml", moduleAPom);

        MavenProject parentProject = createProject("com.example", "parent", "1.0", root, "pom.xml", parentPom);
        parentProject.getModel().setPackaging("pom");
        MavenProject moduleA = createProject("com.example", "module-a", "1.0", root, "module-a/pom.xml", moduleAPom);
        moduleA.setParent(parentProject);

        List<MavenProject> allProjects = Arrays.asList(parentProject, moduleA);

        Set<String> changedFiles = new LinkedHashSet<>();
        changedFiles.add(".github/workflows/ci.yml");
        changedFiles.add("module-a/src/main/java/Foo.java");
        when(scalpelCore.detectChanges(any(), any(), any()))
                .thenReturn(new ChangeDetectionResult(changedFiles, new HashMap<String, byte[]>()));
        setupEmptyDependencyResolution();

        MavenSession session = createSimpleSession(root, allProjects, "report");
        session.getSystemProperties().setProperty("scalpel.fullBuildTriggers", ".github/**");

        participant.afterProjectsRead(session);

        Path reportFile = root.resolve("target/scalpel-report.json");
        assertTrue(Files.exists(reportFile));
        String json = new String(Files.readAllBytes(reportFile), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"fullBuildTriggered\": true"), "Report should indicate full build triggered");
    }

    @Test
    void reportMode_excludePathsFiltersChangedFiles() throws Exception {
        Path root = tempDir.resolve("project");
        Files.createDirectories(root);

        String parentPom = simpleParentPom("module-a", "module-b");
        writePom(root, "pom.xml", parentPom);
        String moduleAPom = simpleChildPom("module-a");
        writePom(root, "module-a/pom.xml", moduleAPom);
        String moduleBPom = simpleChildPom("module-b");
        writePom(root, "module-b/pom.xml", moduleBPom);

        MavenProject parentProject = createProject("com.example", "parent", "1.0", root, "pom.xml", parentPom);
        parentProject.getModel().setPackaging("pom");
        MavenProject moduleA = createProject("com.example", "module-a", "1.0", root, "module-a/pom.xml", moduleAPom);
        moduleA.setParent(parentProject);
        MavenProject moduleB = createProject("com.example", "module-b", "1.0", root, "module-b/pom.xml", moduleBPom);
        moduleB.setParent(parentProject);

        List<MavenProject> allProjects = Arrays.asList(parentProject, moduleA, moduleB);

        Set<String> changedFiles = new LinkedHashSet<>();
        changedFiles.add("module-a/README.md");
        changedFiles.add("module-b/src/main/java/Foo.java");
        when(scalpelCore.detectChanges(any(), any(), any()))
                .thenReturn(new ChangeDetectionResult(changedFiles, new HashMap<String, byte[]>()));
        setupEmptyDependencyResolution();

        MavenSession session = createSimpleSession(root, allProjects, "report");
        session.getSystemProperties().setProperty("scalpel.excludePaths", "**/*.md");

        participant.afterProjectsRead(session);

        Path reportFile = root.resolve("target/scalpel-report.json");
        assertTrue(Files.exists(reportFile));
        String json = new String(Files.readAllBytes(reportFile), StandardCharsets.UTF_8);
        assertTrue(modulePresent(json, "module-b"), "module-b should be in report");
        assertFalse(modulePresent(json, "module-a"), "module-a should NOT be in report (excluded .md)");
    }

    // --- Helper methods ---

    private void setupEmptyDependencyResolution() throws Exception {
        DependencyResolutionResult emptyResolution = mock(DependencyResolutionResult.class);
        when(emptyResolution.getResolvedDependencies())
                .thenReturn(Collections.<org.eclipse.aether.graph.Dependency>emptyList());
        when(dependenciesResolver.resolve(any(DefaultDependencyResolutionRequest.class)))
                .thenReturn(emptyResolution);
    }

    private MavenSession createSimpleSession(Path root, List<MavenProject> allProjects, String mode) {
        MavenSession session = mock(MavenSession.class);
        Properties sysProps = new Properties();
        sysProps.setProperty("scalpel.mode", mode);
        sysProps.setProperty("scalpel.baseBranch", "base");
        when(session.getSystemProperties()).thenReturn(sysProps);
        when(session.getUserProperties()).thenReturn(new Properties());
        when(session.getProjects()).thenReturn(allProjects);
        MavenExecutionRequest execRequest = mock(MavenExecutionRequest.class);
        when(execRequest.getMultiModuleProjectDirectory()).thenReturn(root.toFile());
        when(session.getRequest()).thenReturn(execRequest);
        when(session.getRepositorySession()).thenReturn(mock(RepositorySystemSession.class));
        ProjectDependencyGraph graph = mock(ProjectDependencyGraph.class);
        when(graph.getDownstreamProjects(any(), anyBoolean())).thenReturn(Collections.emptyList());
        when(graph.getUpstreamProjects(any(), anyBoolean())).thenReturn(Collections.emptyList());
        when(graph.getSortedProjects()).thenReturn(allProjects);
        when(session.getProjectDependencyGraph()).thenReturn(graph);
        return session;
    }

    private String simpleParentPom(String... modules) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\"?>\n<project>\n  <modelVersion>4.0.0</modelVersion>\n");
        sb.append("  <groupId>com.example</groupId>\n  <artifactId>parent</artifactId>\n  <version>1.0</version>\n");
        sb.append("  <packaging>pom</packaging>\n  <modules>");
        for (String m : modules) {
            sb.append("<module>").append(m).append("</module>");
        }
        sb.append("</modules>\n</project>\n");
        return sb.toString();
    }

    private String simpleChildPom(String artifactId) {
        return "<?xml version=\"1.0\"?>\n<project>\n  <modelVersion>4.0.0</modelVersion>\n"
                + "  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>\n"
                + "  <artifactId>" + artifactId + "</artifactId>\n</project>\n";
    }

    private void writePom(Path root, String relativePath, String content) throws Exception {
        Path pomFile = root.resolve(relativePath);
        Files.createDirectories(pomFile.getParent());
        Files.write(pomFile, content.getBytes(StandardCharsets.UTF_8));
    }

    private MavenProject createProject(
            String groupId, String artifactId, String version, Path root, String relativePom, String pomXml) {
        Model model = new Model();
        model.setGroupId(groupId);
        model.setArtifactId(artifactId);
        model.setVersion(version);
        File pomFile = root.resolve(relativePom).toFile();
        model.setPomFile(pomFile);
        MavenProject project = new MavenProject(model);
        project.setFile(pomFile);
        project.setOriginalModel(parseModel(pomXml));
        return project;
    }

    private boolean modulePresent(String json, String artifactId) {
        return json.contains("\"artifactId\": \"" + artifactId + "\"");
    }

    private boolean moduleHasReason(String json, String artifactId, String reason) {
        String marker = "\"artifactId\": \"" + artifactId + "\"";
        int idx = json.indexOf(marker);
        if (idx < 0) {
            return false;
        }
        int start = json.lastIndexOf("{", idx);
        int end = json.indexOf("}", idx);
        if (start < 0 || end < 0) {
            return false;
        }
        String block = json.substring(start, end + 1);
        return block.contains("\"" + reason + "\"");
    }

    private Model parseModel(String xml) {
        try {
            return new org.apache.maven.model.io.xpp3.MavenXpp3Reader()
                    .read(new java.io.ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse POM XML: " + xml.substring(0, Math.min(xml.length(), 100)), e);
        }
    }
}
