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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import eu.maveniverse.maven.scalpel.core.ChangeDetectionResult;
import eu.maveniverse.maven.scalpel.core.ScalpelCore;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.project.DefaultDependencyResolutionRequest;
import org.apache.maven.project.DependencyResolutionException;
import org.apache.maven.project.DependencyResolutionResult;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectDependenciesResolver;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

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
        String oldParentPom = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <modules><module>module-a</module><module>module-b</module><module>module-c</module></modules>
                  <properties>
                    <lib.version>1.0</lib.version>
                  </properties>
                  <dependencyManagement><dependencies>
                    <dependency>
                      <groupId>commons-lang</groupId>
                      <artifactId>commons-lang</artifactId>
                      <version>${lib.version}</version>
                    </dependency>
                  </dependencies></dependencyManagement>
                </project>
                """;

        // New parent POM (after property change)
        String newParentPom = oldParentPom.replace("<lib.version>1.0</lib.version>", "<lib.version>2.0</lib.version>");
        writePom(root, "pom.xml", newParentPom);

        // module-a: directly uses managed dep commons-lang
        String moduleAPom = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>module-a</artifactId>
                  <dependencies>
                    <dependency><groupId>commons-lang</groupId><artifactId>commons-lang</artifactId></dependency>
                  </dependencies>
                </project>
                """;
        writePom(root, "module-a/pom.xml", moduleAPom);

        // module-b: depends on module-a (gets commons-lang transitively)
        String moduleBPom = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>module-b</artifactId>
                  <dependencies>
                    <dependency><groupId>com.example</groupId><artifactId>module-a</artifactId><version>1.0</version></dependency>
                  </dependencies>
                </project>
                """;
        writePom(root, "module-b/pom.xml", moduleBPom);

        // module-c: no dependencies
        String moduleCPom = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>module-c</artifactId>
                </project>
                """;
        writePom(root, "module-c/pom.xml", moduleCPom);

        // module-d: no reactor dependency on module-a, but has commons-lang transitively (genuine)
        String moduleDPom = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>module-d</artifactId>
                </project>
                """;
        writePom(root, "module-d/pom.xml", moduleDPom);

        // module-e: dependency resolution fails, should NOT appear in report
        String moduleEPom = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>module-e</artifactId>
                </project>
                """;
        writePom(root, "module-e/pom.xml", moduleEPom);

        // Build MavenProject objects
        MavenProject parentProject = createProject("com.example", "parent", "1.0", root, "pom.xml", newParentPom);
        parentProject.getModel().setPackaging("pom");
        MavenProject moduleA = createProject("com.example", "module-a", "1.0", root, "module-a/pom.xml", moduleAPom);
        moduleA.setParent(parentProject);
        MavenProject moduleB = createProject("com.example", "module-b", "1.0", root, "module-b/pom.xml", moduleBPom);
        moduleB.setParent(parentProject);
        // Add dependency on module-a to module-b's effective model
        Dependency moduleBDep = new Dependency();
        moduleBDep.setGroupId("com.example");
        moduleBDep.setArtifactId("module-a");
        moduleBDep.setVersion("1.0");
        moduleB.getDependencies().add(moduleBDep);
        MavenProject moduleC = createProject("com.example", "module-c", "1.0", root, "module-c/pom.xml", moduleCPom);
        moduleC.setParent(parentProject);
        MavenProject moduleD = createProject("com.example", "module-d", "1.0", root, "module-d/pom.xml", moduleDPom);
        moduleD.setParent(parentProject);
        MavenProject moduleE = createProject("com.example", "module-e", "1.0", root, "module-e/pom.xml", moduleEPom);
        moduleE.setParent(parentProject);

        List<MavenProject> allProjects = List.of(parentProject, moduleA, moduleB, moduleC, moduleD, moduleE);

        // Mock ScalpelCore to return changed files
        Set<String> changedFiles = new LinkedHashSet<>();
        changedFiles.add("pom.xml");
        Map<String, byte[]> oldPoms = new HashMap<>();
        oldPoms.put("pom.xml", oldParentPom.getBytes(StandardCharsets.UTF_8));
        ChangeDetectionResult detectionResult = new ChangeDetectionResult(changedFiles, oldPoms);
        when(scalpelCore.detectChanges(any(), any(), any())).thenReturn(detectionResult);

        // commons-lang dependency used for transitive resolution
        org.eclipse.aether.graph.Dependency commonsLangDep = new org.eclipse.aether.graph.Dependency(
                new DefaultArtifact("commons-lang", "commons-lang", "jar", "2.0"), "compile");

        // Route resolution calls:
        // module-b and module-d: resolution succeeds, has commons-lang
        // module-e: resolution fails with empty partial results (simulates unresolvable deps)
        // others: resolution succeeds, no matching deps
        when(dependenciesResolver.resolve(any(DefaultDependencyResolutionRequest.class)))
                .thenAnswer(invocation -> {
                    DefaultDependencyResolutionRequest req = invocation.getArgument(0);
                    String aid = req.getMavenProject().getArtifactId();
                    if ("module-b".equals(aid) || "module-d".equals(aid)) {
                        DependencyResolutionResult res = mock(DependencyResolutionResult.class);
                        when(res.getResolvedDependencies()).thenReturn(List.of(commonsLangDep));
                        return res;
                    }
                    if ("module-e".equals(aid)) {
                        DependencyResolutionResult partial = mock(DependencyResolutionResult.class);
                        when(partial.getResolvedDependencies()).thenReturn(List.of());
                        throw new DependencyResolutionException(partial, "Cannot resolve", new Exception());
                    }
                    DependencyResolutionResult empty = mock(DependencyResolutionResult.class);
                    when(empty.getResolvedDependencies()).thenReturn(List.of());
                    return empty;
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

        // Graph: module-b is downstream of module-a; module-d and module-e are NOT downstream
        ProjectDependencyGraph graph = mock(ProjectDependencyGraph.class);
        when(graph.getDownstreamProjects(any(), anyBoolean())).thenReturn(List.of());
        when(graph.getDownstreamProjects(moduleA, true)).thenReturn(List.of(moduleB));
        when(graph.getUpstreamProjects(any(), anyBoolean())).thenReturn(List.of());
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

        // module-a should be directly affected (POM_CHANGE) with DIRECT category
        assertTrue(moduleHasReason(json, "module-a", "POM_CHANGE"), "module-a should have POM_CHANGE reason");
        assertTrue(moduleHasField(json, "module-a", "category", "DIRECT"), "module-a should have DIRECT category");

        // module-b should be transitively affected with DOWNSTREAM category
        assertTrue(
                moduleHasReason(json, "module-b", "TRANSITIVE_DEPENDENCY"),
                "module-b should have TRANSITIVE_DEPENDENCY reason");
        assertTrue(
                moduleHasField(json, "module-b", "category", "DOWNSTREAM"),
                "module-b should have DOWNSTREAM category (downstream of module-a)");

        // module-c should NOT be in the report (no deps at all)
        assertFalse(modulePresent(json, "module-c"), "module-c should NOT be in report");

        // module-d should be in the report with TRANSITIVE category (genuine transitive dep, not downstream)
        assertTrue(
                moduleHasReason(json, "module-d", "TRANSITIVE_DEPENDENCY"),
                "module-d should have TRANSITIVE_DEPENDENCY reason");
        assertTrue(
                moduleHasField(json, "module-d", "category", "TRANSITIVE"),
                "module-d should have TRANSITIVE category (not downstream, but genuinely uses changed dep)");

        // module-e should NOT be in the report (resolution failed, dep not found in partial results)
        assertFalse(
                modulePresent(json, "module-e"),
                "module-e should NOT be in report (resolution failed, no matching dep in partial results)");

        // changedManagedDependencies should list the GA whose version changed via property
        assertTrue(
                json.contains("\"commons-lang:commons-lang\""),
                "changedManagedDependencies should contain commons-lang:commons-lang");
    }

    @Test
    void reportMode_managedPluginChange() throws Exception {
        Path root = tempDir.resolve("project");
        Files.createDirectories(root);

        String oldParentPom = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <modules><module>module-a</module><module>module-b</module></modules>
                  <properties>
                    <compiler.version>3.11.0</compiler.version>
                  </properties>
                  <build><pluginManagement><plugins>
                    <plugin>
                      <groupId>org.apache.maven.plugins</groupId>
                      <artifactId>maven-compiler-plugin</artifactId>
                      <version>${compiler.version}</version>
                    </plugin>
                  </plugins></pluginManagement></build>
                </project>
                """;

        String newParentPom = oldParentPom.replace(
                "<compiler.version>3.11.0</compiler.version>", "<compiler.version>3.12.0</compiler.version>");
        writePom(root, "pom.xml", newParentPom);

        // module-a: no plugins
        String moduleAPom = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>module-a</artifactId>
                </project>
                """;
        writePom(root, "module-a/pom.xml", moduleAPom);

        // module-b: uses maven-compiler-plugin
        String moduleBPom = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>module-b</artifactId>
                  <build><plugins>
                    <plugin>
                      <groupId>org.apache.maven.plugins</groupId>
                      <artifactId>maven-compiler-plugin</artifactId>
                    </plugin>
                  </plugins></build>
                </project>
                """;
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

        List<MavenProject> allProjects = List.of(parentProject, moduleA, moduleB);

        Set<String> changedFiles = new LinkedHashSet<>();
        changedFiles.add("pom.xml");
        Map<String, byte[]> oldPoms = new HashMap<>();
        oldPoms.put("pom.xml", oldParentPom.getBytes(StandardCharsets.UTF_8));
        when(scalpelCore.detectChanges(any(), any(), any()))
                .thenReturn(new ChangeDetectionResult(changedFiles, oldPoms));

        // No transitive deps to resolve
        DependencyResolutionResult emptyResolution = mock(DependencyResolutionResult.class);
        when(emptyResolution.getResolvedDependencies()).thenReturn(List.of());
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
        when(graph2.getDownstreamProjects(any(), anyBoolean())).thenReturn(List.of());
        when(graph2.getUpstreamProjects(any(), anyBoolean())).thenReturn(List.of());
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

        // changedManagedPlugins should list the GA whose version changed via property
        assertTrue(
                json.contains("\"org.apache.maven.plugins:maven-compiler-plugin\""),
                "changedManagedPlugins should contain maven-compiler-plugin");
    }

    @Test
    void reportMode_parentPomInSubdirectory_changedManagedDependenciesPopulated() throws Exception {
        // Camel-like structure: root aggregator at pom.xml, parent POM at parent/pom.xml
        Path root = tempDir.resolve("project");
        Files.createDirectories(root);

        // Root aggregator
        String rootPom = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>root</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <modules><module>parent</module><module>module-a</module><module>module-b</module></modules>
                </project>
                """;
        writePom(root, "pom.xml", rootPom);

        // Old parent POM (before property change)
        String oldParentPom = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <properties>
                    <lib.version>1.0</lib.version>
                  </properties>
                  <dependencyManagement><dependencies>
                    <dependency>
                      <groupId>org.example</groupId>
                      <artifactId>managed-lib</artifactId>
                      <version>${lib.version}</version>
                    </dependency>
                  </dependencies></dependencyManagement>
                </project>
                """;

        // New parent POM (after property change)
        String newParentPom = oldParentPom.replace("<lib.version>1.0</lib.version>", "<lib.version>2.0</lib.version>");
        writePom(root, "parent/pom.xml", newParentPom);

        // module-a: uses managed dep
        String moduleAPom = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>module-a</artifactId>
                  <dependencies>
                    <dependency><groupId>org.example</groupId><artifactId>managed-lib</artifactId></dependency>
                  </dependencies>
                </project>
                """;
        writePom(root, "module-a/pom.xml", moduleAPom);

        // module-b: no deps
        String moduleBPom = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>module-b</artifactId>
                </project>
                """;
        writePom(root, "module-b/pom.xml", moduleBPom);

        // Build MavenProject objects
        MavenProject rootProject = createProject("com.example", "root", "1.0", root, "pom.xml", rootPom);
        rootProject.getModel().setPackaging("pom");

        MavenProject parentProject =
                createProject("com.example", "parent", "1.0", root, "parent/pom.xml", newParentPom);
        parentProject.getModel().setPackaging("pom");
        parentProject.setParent(rootProject);

        MavenProject moduleA = createProject("com.example", "module-a", "1.0", root, "module-a/pom.xml", moduleAPom);
        moduleA.setParent(parentProject);

        MavenProject moduleB = createProject("com.example", "module-b", "1.0", root, "module-b/pom.xml", moduleBPom);
        moduleB.setParent(parentProject);

        List<MavenProject> allProjects = List.of(rootProject, parentProject, moduleA, moduleB);

        // Mock ScalpelCore: only parent/pom.xml changed
        Set<String> changedFiles = new LinkedHashSet<>();
        changedFiles.add("parent/pom.xml");
        Map<String, byte[]> oldPoms = new HashMap<>();
        oldPoms.put("parent/pom.xml", oldParentPom.getBytes(StandardCharsets.UTF_8));
        when(scalpelCore.detectChanges(any(), any(), any()))
                .thenReturn(new ChangeDetectionResult(changedFiles, oldPoms));

        // No transitive deps to resolve for this test
        DependencyResolutionResult emptyResolution = mock(DependencyResolutionResult.class);
        when(emptyResolution.getResolvedDependencies()).thenReturn(List.of());
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
        when(graph.getDownstreamProjects(any(), anyBoolean())).thenReturn(List.of());
        when(graph.getUpstreamProjects(any(), anyBoolean())).thenReturn(List.of());
        when(graph.getSortedProjects()).thenReturn(allProjects);
        when(session.getProjectDependencyGraph()).thenReturn(graph);

        participant.afterProjectsRead(session);

        Path reportFile = root.resolve("target/scalpel-report.json");
        assertTrue(Files.exists(reportFile), "Report file should be created");

        String json = new String(Files.readAllBytes(reportFile), StandardCharsets.UTF_8);

        // module-a should be directly affected (POM_CHANGE via managed dep)
        assertTrue(moduleHasReason(json, "module-a", "POM_CHANGE"), "module-a should have POM_CHANGE reason");

        // module-b should NOT be affected
        assertFalse(modulePresent(json, "module-b"), "module-b should NOT be in report");

        // changedManagedDependencies should list the GA whose version changed via property
        assertTrue(
                json.contains("\"org.example:managed-lib\""),
                "changedManagedDependencies should contain org.example:managed-lib");

        // changedProperties should include the property
        assertTrue(json.contains("\"lib.version\""), "changedProperties should contain lib.version");
    }

    @Test
    void reportMode_testOnlySourceChangeProducesTestChangeReason() throws Exception {
        Path root = tempDir.resolve("project");
        Files.createDirectories(root);

        String parentPom = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <modules><module>module-a</module><module>module-b</module></modules>
                </project>
                """;
        writePom(root, "pom.xml", parentPom);

        String moduleAPom = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>module-a</artifactId>
                </project>
                """;
        writePom(root, "module-a/pom.xml", moduleAPom);

        String moduleBPom = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>module-b</artifactId>
                </project>
                """;
        writePom(root, "module-b/pom.xml", moduleBPom);

        MavenProject parentProject = createProject("com.example", "parent", "1.0", root, "pom.xml", parentPom);
        parentProject.getModel().setPackaging("pom");
        MavenProject moduleA = createProject("com.example", "module-a", "1.0", root, "module-a/pom.xml", moduleAPom);
        moduleA.setParent(parentProject);
        MavenProject moduleB = createProject("com.example", "module-b", "1.0", root, "module-b/pom.xml", moduleBPom);
        moduleB.setParent(parentProject);

        List<MavenProject> allProjects = List.of(parentProject, moduleA, moduleB);

        // module-a: test-only change (src/test/), module-b: main source change
        Set<String> changedFiles = new LinkedHashSet<>();
        changedFiles.add("module-a/src/test/java/com/example/MyTest.java");
        changedFiles.add("module-b/src/main/java/com/example/Service.java");
        when(scalpelCore.detectChanges(any(), any(), any()))
                .thenReturn(new ChangeDetectionResult(changedFiles, new HashMap<>()));

        DependencyResolutionResult emptyResolution = mock(DependencyResolutionResult.class);
        when(emptyResolution.getResolvedDependencies()).thenReturn(List.of());
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
        when(graph.getDownstreamProjects(any(), anyBoolean())).thenReturn(List.of());
        when(graph.getUpstreamProjects(any(), anyBoolean())).thenReturn(List.of());
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
                moduleHasSourceSet(json, "module-a", "test"),
                "module-a should have sourceSet=test (only test files changed)");
        assertTrue(
                moduleHasReason(json, "module-b", "SOURCE_CHANGE"),
                "module-b should have SOURCE_CHANGE reason (main source changed)");
        assertTrue(
                moduleHasSourceSet(json, "module-b", "main"),
                "module-b should have sourceSet=main (main source changed)");
    }

    @Test
    void reportMode_testScopedTransitiveDependencyProducesTestReason() throws Exception {
        Path root = tempDir.resolve("project");
        Files.createDirectories(root);

        String oldParentPom = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <modules><module>module-a</module><module>module-b</module></modules>
                  <properties>
                    <lib.version>1.0</lib.version>
                  </properties>
                  <dependencyManagement><dependencies>
                    <dependency>
                      <groupId>commons-lang</groupId>
                      <artifactId>commons-lang</artifactId>
                      <version>${lib.version}</version>
                    </dependency>
                  </dependencies></dependencyManagement>
                </project>
                """;

        String newParentPom = oldParentPom.replace("<lib.version>1.0</lib.version>", "<lib.version>2.0</lib.version>");
        writePom(root, "pom.xml", newParentPom);

        // module-a: uses managed dep directly
        String moduleAPom = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>module-a</artifactId>
                  <dependencies>
                    <dependency><groupId>commons-lang</groupId><artifactId>commons-lang</artifactId></dependency>
                  </dependencies>
                </project>
                """;
        writePom(root, "module-a/pom.xml", moduleAPom);

        // module-b: depends on module-a via test scope, gets commons-lang only via test scope transitively
        String moduleBPom = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>module-b</artifactId>
                  <dependencies>
                    <dependency><groupId>com.example</groupId><artifactId>module-a</artifactId><version>1.0</version><scope>test</scope></dependency>
                  </dependencies>
                </project>
                """;
        writePom(root, "module-b/pom.xml", moduleBPom);

        MavenProject parentProject = createProject("com.example", "parent", "1.0", root, "pom.xml", newParentPom);
        parentProject.getModel().setPackaging("pom");
        MavenProject moduleA = createProject("com.example", "module-a", "1.0", root, "module-a/pom.xml", moduleAPom);
        moduleA.setParent(parentProject);
        MavenProject moduleB = createProject("com.example", "module-b", "1.0", root, "module-b/pom.xml", moduleBPom);
        moduleB.setParent(parentProject);
        // Add test-scoped dependency on module-a to module-b's effective model
        Dependency dep = new Dependency();
        dep.setGroupId("com.example");
        dep.setArtifactId("module-a");
        dep.setVersion("1.0");
        dep.setScope("test");
        moduleB.getDependencies().add(dep);

        List<MavenProject> allProjects = List.of(parentProject, moduleA, moduleB);

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
        when(moduleBResolution.getResolvedDependencies()).thenReturn(List.of(testDep));

        when(dependenciesResolver.resolve(any(DefaultDependencyResolutionRequest.class)))
                .thenAnswer(invocation -> {
                    DefaultDependencyResolutionRequest req = invocation.getArgument(0);
                    if ("module-b".equals(req.getMavenProject().getArtifactId())) {
                        return moduleBResolution;
                    }
                    DependencyResolutionResult empty = mock(DependencyResolutionResult.class);
                    when(empty.getResolvedDependencies()).thenReturn(List.of());
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

        // Graph: module-b is downstream of module-a
        ProjectDependencyGraph graph = mock(ProjectDependencyGraph.class);
        when(graph.getDownstreamProjects(any(), anyBoolean())).thenReturn(List.of());
        when(graph.getDownstreamProjects(moduleA, true)).thenReturn(List.of(moduleB));
        when(graph.getUpstreamProjects(any(), anyBoolean())).thenReturn(List.of());
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
        assertTrue(
                moduleHasField(json, "module-b", "category", "DOWNSTREAM"), "module-b should have DOWNSTREAM category");
    }

    @Test
    void reportMode_downstreamTestScopedModuleProducesDownstreamTestReason() throws Exception {
        Path root = tempDir.resolve("project");
        Files.createDirectories(root);

        String parentPom = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <modules><module>module-a</module><module>module-b</module></modules>
                </project>
                """;
        writePom(root, "pom.xml", parentPom);

        String moduleAPom = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>module-a</artifactId>
                </project>
                """;
        writePom(root, "module-a/pom.xml", moduleAPom);

        // module-b depends on module-a via test scope
        String moduleBPom = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>module-b</artifactId>
                  <dependencies>
                    <dependency><groupId>com.example</groupId><artifactId>module-a</artifactId><version>1.0</version><scope>test</scope></dependency>
                  </dependencies>
                </project>
                """;
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

        List<MavenProject> allProjects = List.of(parentProject, moduleA, moduleB);

        // module-a has a source change
        Set<String> changedFiles = new LinkedHashSet<>();
        changedFiles.add("module-a/src/main/java/com/example/Foo.java");
        when(scalpelCore.detectChanges(any(), any(), any()))
                .thenReturn(new ChangeDetectionResult(changedFiles, new HashMap<>()));

        DependencyResolutionResult emptyResolution = mock(DependencyResolutionResult.class);
        when(emptyResolution.getResolvedDependencies()).thenReturn(List.of());
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
        when(graph.getDownstreamProjects(moduleA, true)).thenReturn(List.of(moduleB));
        when(graph.getDownstreamProjects(moduleB, true)).thenReturn(List.of());
        when(graph.getUpstreamProjects(any(), anyBoolean())).thenReturn(List.of());
        when(graph.getSortedProjects()).thenReturn(allProjects);
        when(session.getProjectDependencyGraph()).thenReturn(graph);

        participant.afterProjectsRead(session);

        Path reportFile = root.resolve("target/scalpel-report.json");
        assertTrue(Files.exists(reportFile));

        String json = new String(Files.readAllBytes(reportFile), StandardCharsets.UTF_8);
        assertTrue(moduleHasReason(json, "module-a", "SOURCE_CHANGE"), "module-a should have SOURCE_CHANGE");
        assertTrue(
                moduleHasSourceSet(json, "module-a", "main"),
                "module-a should have sourceSet=main (main source changed)");
        assertTrue(
                moduleHasReason(json, "module-b", "DOWNSTREAM_TEST"),
                "module-b should have DOWNSTREAM_TEST (test-scoped downstream of module-a)");
        assertFalse(
                moduleHasAnySourceSet(json, "module-b"),
                "module-b should NOT have sourceSet (downstream, not direct source change)");
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

        List<MavenProject> allProjects = List.of(parentProject, moduleA, moduleB);

        Set<String> changedFiles = new LinkedHashSet<>();
        changedFiles.add("module-a/src/main/java/Foo.java");
        when(scalpelCore.detectChanges(any(), any(), any()))
                .thenReturn(new ChangeDetectionResult(changedFiles, new HashMap<>()));
        setupEmptyDependencyResolution();

        MavenSession session = createSimpleSession(root, allProjects, "trim");

        participant.afterProjectsRead(session);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MavenProject>> captor = ArgumentCaptor.forClass(List.class);
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

        List<MavenProject> allProjects = List.of(parentProject, moduleA, moduleB);

        Set<String> changedFiles = new LinkedHashSet<>();
        changedFiles.add("module-a/src/main/java/Foo.java");
        when(scalpelCore.detectChanges(any(), any(), any()))
                .thenReturn(new ChangeDetectionResult(changedFiles, new HashMap<>()));
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

        List<MavenProject> allProjects = List.of(parentProject, moduleA, moduleB);

        Set<String> changedFiles = new LinkedHashSet<>();
        changedFiles.add("module-a/src/main/java/Foo.java");
        when(scalpelCore.detectChanges(any(), any(), any()))
                .thenReturn(new ChangeDetectionResult(changedFiles, new HashMap<>()));
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

        List<MavenProject> allProjects = List.of(parentProject, moduleA);

        Set<String> changedFiles = new LinkedHashSet<>();
        changedFiles.add(".github/workflows/ci.yml");
        changedFiles.add("module-a/src/main/java/Foo.java");
        when(scalpelCore.detectChanges(any(), any(), any()))
                .thenReturn(new ChangeDetectionResult(changedFiles, new HashMap<>()));
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

        List<MavenProject> allProjects = List.of(parentProject, moduleA, moduleB);

        Set<String> changedFiles = new LinkedHashSet<>();
        changedFiles.add("module-a/README.md");
        changedFiles.add("module-b/src/main/java/Foo.java");
        when(scalpelCore.detectChanges(any(), any(), any()))
                .thenReturn(new ChangeDetectionResult(changedFiles, new HashMap<>()));
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

    @Test
    void reportMode_bomImportScopeDetected() throws Exception {
        // BOM module defines managed dep commons-lang. module-a imports the BOM and uses it.
        // module-b depends on module-a (gets commons-lang transitively).
        // module-c has no relationship to the BOM.
        // When BOM's managed dep version changes, module-a should be directly affected
        // and module-b should be transitively affected.
        Path root = tempDir.resolve("project");
        Files.createDirectories(root);

        // Parent POM (no managed deps — those are in the BOM)
        String parentPom = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <modules><module>bom</module><module>module-a</module><module>module-b</module><module>module-c</module></modules>
                </project>
                """;
        writePom(root, "pom.xml", parentPom);

        // Old BOM POM (before version change)
        String oldBomPom = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>bom</artifactId>
                  <packaging>pom</packaging>
                  <properties><lib.version>1.0</lib.version></properties>
                  <dependencyManagement><dependencies>
                    <dependency><groupId>commons-lang</groupId><artifactId>commons-lang</artifactId><version>${lib.version}</version></dependency>
                  </dependencies></dependencyManagement>
                </project>
                """;

        // New BOM POM (after version bump)
        String newBomPom = oldBomPom.replace("<lib.version>1.0</lib.version>", "<lib.version>2.0</lib.version>");
        writePom(root, "bom/pom.xml", newBomPom);

        // module-a: imports BOM, uses commons-lang
        String moduleAPom = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>module-a</artifactId>
                  <dependencyManagement><dependencies>
                    <dependency><groupId>com.example</groupId><artifactId>bom</artifactId><version>${project.version}</version><type>pom</type><scope>import</scope></dependency>
                  </dependencies></dependencyManagement>
                  <dependencies>
                    <dependency><groupId>commons-lang</groupId><artifactId>commons-lang</artifactId></dependency>
                  </dependencies>
                </project>
                """;
        writePom(root, "module-a/pom.xml", moduleAPom);

        // module-b: depends on module-a (gets commons-lang transitively)
        String moduleBPom = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>module-b</artifactId>
                  <dependencies>
                    <dependency><groupId>com.example</groupId><artifactId>module-a</artifactId><version>1.0</version></dependency>
                  </dependencies>
                </project>
                """;
        writePom(root, "module-b/pom.xml", moduleBPom);

        // module-c: no dependencies
        String moduleCPom = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>module-c</artifactId>
                </project>
                """;
        writePom(root, "module-c/pom.xml", moduleCPom);

        // Build MavenProject objects
        MavenProject parentProject = createProject("com.example", "parent", "1.0", root, "pom.xml", parentPom);
        parentProject.getModel().setPackaging("pom");
        MavenProject bomProject = createProject("com.example", "bom", "1.0", root, "bom/pom.xml", newBomPom);
        bomProject.getModel().setPackaging("pom");
        bomProject.setParent(parentProject);
        MavenProject moduleA = createProject("com.example", "module-a", "1.0", root, "module-a/pom.xml", moduleAPom);
        moduleA.setParent(parentProject);
        MavenProject moduleB = createProject("com.example", "module-b", "1.0", root, "module-b/pom.xml", moduleBPom);
        moduleB.setParent(parentProject);
        // Add dependency on module-a to module-b's effective model
        Dependency moduleBDep = new Dependency();
        moduleBDep.setGroupId("com.example");
        moduleBDep.setArtifactId("module-a");
        moduleBDep.setVersion("1.0");
        moduleB.getDependencies().add(moduleBDep);
        MavenProject moduleC = createProject("com.example", "module-c", "1.0", root, "module-c/pom.xml", moduleCPom);
        moduleC.setParent(parentProject);

        List<MavenProject> allProjects = List.of(parentProject, bomProject, moduleA, moduleB, moduleC);

        // Mock ScalpelCore to return changed BOM POM
        Set<String> changedFiles = new LinkedHashSet<>();
        changedFiles.add("bom/pom.xml");
        Map<String, byte[]> oldPoms = new HashMap<>();
        oldPoms.put("bom/pom.xml", oldBomPom.getBytes(StandardCharsets.UTF_8));
        when(scalpelCore.detectChanges(any(), any(), any()))
                .thenReturn(new ChangeDetectionResult(changedFiles, oldPoms));

        // Mock dependency resolution: module-b has commons-lang transitively
        DependencyResolutionResult moduleBResolution = mock(DependencyResolutionResult.class);
        org.eclipse.aether.graph.Dependency commonsLangDep = new org.eclipse.aether.graph.Dependency(
                new DefaultArtifact("commons-lang", "commons-lang", "jar", "2.0"), "compile");
        when(moduleBResolution.getResolvedDependencies()).thenReturn(List.of(commonsLangDep));

        // Other modules: no matching deps
        DependencyResolutionResult emptyResolution = mock(DependencyResolutionResult.class);
        when(emptyResolution.getResolvedDependencies()).thenReturn(List.of());

        when(dependenciesResolver.resolve(any(DefaultDependencyResolutionRequest.class)))
                .thenAnswer(invocation -> {
                    DefaultDependencyResolutionRequest req = invocation.getArgument(0);
                    if ("module-b".equals(req.getMavenProject().getArtifactId())) {
                        return moduleBResolution;
                    }
                    return emptyResolution;
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

        // Graph: module-b is downstream of module-a
        ProjectDependencyGraph graph = mock(ProjectDependencyGraph.class);
        when(graph.getDownstreamProjects(any(), anyBoolean())).thenReturn(List.of());
        when(graph.getDownstreamProjects(moduleA, true)).thenReturn(List.of(moduleB));
        when(graph.getUpstreamProjects(any(), anyBoolean())).thenReturn(List.of());
        when(graph.getSortedProjects()).thenReturn(allProjects);
        when(session.getProjectDependencyGraph()).thenReturn(graph);

        participant.afterProjectsRead(session);

        Path reportFile = root.resolve("target/scalpel-report.json");
        assertTrue(Files.exists(reportFile), "Report file should be created");

        String json = new String(Files.readAllBytes(reportFile), StandardCharsets.UTF_8);

        // module-a should be directly affected (uses changed managed dep from imported BOM)
        assertTrue(
                moduleHasReason(json, "module-a", "POM_CHANGE"),
                "module-a should have POM_CHANGE reason (imports BOM with changed managed dep)");

        // module-b should be transitively affected with DOWNSTREAM category
        assertTrue(
                moduleHasReason(json, "module-b", "TRANSITIVE_DEPENDENCY"),
                "module-b should have TRANSITIVE_DEPENDENCY reason");
        assertTrue(
                moduleHasField(json, "module-b", "category", "DOWNSTREAM"), "module-b should have DOWNSTREAM category");

        // module-c should NOT be in the report
        assertFalse(modulePresent(json, "module-c"), "module-c should NOT be in report");

        // The report should show the changed managed dependency GA
        assertTrue(json.contains("commons-lang:commons-lang"), "Report should include changed managed dep GA");
    }

    @Test
    void skipTestsMode_excludedDownstreamModulesHaveTestsSkipped() throws Exception {
        // module-a is directly changed, module-b and module-c are downstream.
        // module-b is in the exclusion list, module-c is not.
        Path root = tempDir.resolve("project");
        Files.createDirectories(root);

        String parentPom = simpleParentPom("module-a", "module-b", "module-c");
        writePom(root, "pom.xml", parentPom);
        String moduleAPom = simpleChildPom("module-a");
        writePom(root, "module-a/pom.xml", moduleAPom);
        String moduleBPom = simpleChildPomWithDep("module-b", "module-a");
        writePom(root, "module-b/pom.xml", moduleBPom);
        String moduleCPom = simpleChildPomWithDep("module-c", "module-a");
        writePom(root, "module-c/pom.xml", moduleCPom);

        MavenProject parentProject = createProject("com.example", "parent", "1.0", root, "pom.xml", parentPom);
        parentProject.getModel().setPackaging("pom");
        MavenProject moduleA = createProject("com.example", "module-a", "1.0", root, "module-a/pom.xml", moduleAPom);
        moduleA.setParent(parentProject);
        MavenProject moduleB = createProject("com.example", "module-b", "1.0", root, "module-b/pom.xml", moduleBPom);
        moduleB.setParent(parentProject);
        MavenProject moduleC = createProject("com.example", "module-c", "1.0", root, "module-c/pom.xml", moduleCPom);
        moduleC.setParent(parentProject);

        List<MavenProject> allProjects = List.of(parentProject, moduleA, moduleB, moduleC);

        Set<String> changedFiles = new LinkedHashSet<>();
        changedFiles.add("module-a/src/main/java/Foo.java");
        when(scalpelCore.detectChanges(any(), any(), any()))
                .thenReturn(new ChangeDetectionResult(changedFiles, new HashMap<>()));
        setupEmptyDependencyResolution();

        MavenSession session = createSimpleSession(root, allProjects, "skip-tests");
        session.getSystemProperties().setProperty("scalpel.skipTestsForDownstreamModules", "module-b");

        // Graph: module-b and module-c are downstream of module-a
        ProjectDependencyGraph graph = mock(ProjectDependencyGraph.class);
        when(graph.getDownstreamProjects(moduleA, true)).thenReturn(List.of(moduleB, moduleC));
        when(graph.getDownstreamProjects(moduleB, true)).thenReturn(List.of());
        when(graph.getDownstreamProjects(moduleC, true)).thenReturn(List.of());
        when(graph.getDownstreamProjects(parentProject, true)).thenReturn(List.of());
        when(graph.getUpstreamProjects(any(), anyBoolean())).thenReturn(List.of());
        when(graph.getSortedProjects()).thenReturn(allProjects);
        when(session.getProjectDependencyGraph()).thenReturn(graph);

        participant.afterProjectsRead(session);

        // module-b (excluded downstream) should have tests skipped
        assertEquals(
                "true",
                moduleB.getProperties().getProperty("maven.test.skip"),
                "module-b should have maven.test.skip=true (excluded downstream)");
        // module-c (downstream, not excluded) should NOT have tests skipped
        assertNotEquals(
                "true",
                moduleC.getProperties().getProperty("maven.test.skip"),
                "module-c should NOT have maven.test.skip=true (not excluded)");
    }

    @Test
    void skipTestsMode_directModuleOverridesDownstreamExclusion() throws Exception {
        // module-a has source changes (DIRECT) and is also in the exclusion list.
        // DIRECT should win — tests should still run.
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

        List<MavenProject> allProjects = List.of(parentProject, moduleA, moduleB);

        Set<String> changedFiles = new LinkedHashSet<>();
        changedFiles.add("module-a/src/main/java/Foo.java");
        when(scalpelCore.detectChanges(any(), any(), any()))
                .thenReturn(new ChangeDetectionResult(changedFiles, new HashMap<>()));
        setupEmptyDependencyResolution();

        MavenSession session = createSimpleSession(root, allProjects, "skip-tests");
        // module-a is in the exclusion list but also directly changed
        session.getSystemProperties().setProperty("scalpel.skipTestsForDownstreamModules", "module-a");

        ProjectDependencyGraph graph = mock(ProjectDependencyGraph.class);
        when(graph.getDownstreamProjects(any(), anyBoolean())).thenReturn(List.of());
        when(graph.getUpstreamProjects(any(), anyBoolean())).thenReturn(List.of());
        when(graph.getSortedProjects()).thenReturn(allProjects);
        when(session.getProjectDependencyGraph()).thenReturn(graph);

        participant.afterProjectsRead(session);

        // module-a is DIRECT, so tests should NOT be skipped even though it's in exclusion list
        assertNotEquals(
                "true",
                moduleA.getProperties().getProperty("maven.test.skip"),
                "module-a should NOT have tests skipped (DIRECT overrides exclusion)");
    }

    @Test
    void skipTestsMode_groupIdColonArtifactIdExclusionPattern() throws Exception {
        // Test that groupId:artifactId pattern matching works
        Path root = tempDir.resolve("project");
        Files.createDirectories(root);

        String parentPom = simpleParentPom("module-a", "module-b");
        writePom(root, "pom.xml", parentPom);
        String moduleAPom = simpleChildPom("module-a");
        writePom(root, "module-a/pom.xml", moduleAPom);
        String moduleBPom = simpleChildPomWithDep("module-b", "module-a");
        writePom(root, "module-b/pom.xml", moduleBPom);

        MavenProject parentProject = createProject("com.example", "parent", "1.0", root, "pom.xml", parentPom);
        parentProject.getModel().setPackaging("pom");
        MavenProject moduleA = createProject("com.example", "module-a", "1.0", root, "module-a/pom.xml", moduleAPom);
        moduleA.setParent(parentProject);
        MavenProject moduleB = createProject("com.example", "module-b", "1.0", root, "module-b/pom.xml", moduleBPom);
        moduleB.setParent(parentProject);

        List<MavenProject> allProjects = List.of(parentProject, moduleA, moduleB);

        Set<String> changedFiles = new LinkedHashSet<>();
        changedFiles.add("module-a/src/main/java/Foo.java");
        when(scalpelCore.detectChanges(any(), any(), any()))
                .thenReturn(new ChangeDetectionResult(changedFiles, new HashMap<>()));
        setupEmptyDependencyResolution();

        MavenSession session = createSimpleSession(root, allProjects, "skip-tests");
        session.getSystemProperties().setProperty("scalpel.skipTestsForDownstreamModules", "com.example:module-b");

        ProjectDependencyGraph graph = mock(ProjectDependencyGraph.class);
        when(graph.getDownstreamProjects(moduleA, true)).thenReturn(List.of(moduleB));
        when(graph.getDownstreamProjects(moduleB, true)).thenReturn(List.of());
        when(graph.getDownstreamProjects(parentProject, true)).thenReturn(List.of());
        when(graph.getUpstreamProjects(any(), anyBoolean())).thenReturn(List.of());
        when(graph.getSortedProjects()).thenReturn(allProjects);
        when(session.getProjectDependencyGraph()).thenReturn(graph);

        participant.afterProjectsRead(session);

        assertEquals(
                "true",
                moduleB.getProperties().getProperty("maven.test.skip"),
                "module-b should have tests skipped (matched by groupId:artifactId)");
    }

    @Test
    void reportMode_excludedDownstreamHasTestsSkippedReason() throws Exception {
        Path root = tempDir.resolve("project");
        Files.createDirectories(root);

        String parentPom = simpleParentPom("module-a", "module-b");
        writePom(root, "pom.xml", parentPom);
        String moduleAPom = simpleChildPom("module-a");
        writePom(root, "module-a/pom.xml", moduleAPom);
        String moduleBPom = simpleChildPomWithDep("module-b", "module-a");
        writePom(root, "module-b/pom.xml", moduleBPom);

        MavenProject parentProject = createProject("com.example", "parent", "1.0", root, "pom.xml", parentPom);
        parentProject.getModel().setPackaging("pom");
        MavenProject moduleA = createProject("com.example", "module-a", "1.0", root, "module-a/pom.xml", moduleAPom);
        moduleA.setParent(parentProject);
        MavenProject moduleB = createProject("com.example", "module-b", "1.0", root, "module-b/pom.xml", moduleBPom);
        moduleB.setParent(parentProject);

        List<MavenProject> allProjects = List.of(parentProject, moduleA, moduleB);

        Set<String> changedFiles = new LinkedHashSet<>();
        changedFiles.add("module-a/src/main/java/Foo.java");
        when(scalpelCore.detectChanges(any(), any(), any()))
                .thenReturn(new ChangeDetectionResult(changedFiles, new HashMap<>()));
        setupEmptyDependencyResolution();

        MavenSession session = createSimpleSession(root, allProjects, "report");
        session.getSystemProperties().setProperty("scalpel.skipTestsForDownstreamModules", "module-b");

        ProjectDependencyGraph graph = mock(ProjectDependencyGraph.class);
        when(graph.getDownstreamProjects(moduleA, true)).thenReturn(List.of(moduleB));
        when(graph.getDownstreamProjects(moduleB, true)).thenReturn(List.of());
        when(graph.getDownstreamProjects(parentProject, true)).thenReturn(List.of());
        when(graph.getUpstreamProjects(any(), anyBoolean())).thenReturn(List.of());
        when(graph.getSortedProjects()).thenReturn(allProjects);
        when(session.getProjectDependencyGraph()).thenReturn(graph);

        participant.afterProjectsRead(session);

        Path reportFile = root.resolve("target/scalpel-report.json");
        assertTrue(Files.exists(reportFile));
        String json = new String(Files.readAllBytes(reportFile), StandardCharsets.UTF_8);
        assertTrue(moduleHasReason(json, "module-a", "SOURCE_CHANGE"), "module-a should have SOURCE_CHANGE");
        assertTrue(
                moduleHasField(json, "module-b", "testsSkippedReason", "EXCLUDED_DOWNSTREAM"),
                "module-b should have testsSkippedReason=EXCLUDED_DOWNSTREAM in report");
    }

    @Test
    void reportMode_downstreamTestOnlyExcludedHasTestsSkippedReason() throws Exception {
        // module-b depends on module-a via test scope (downstream-test-only).
        // module-b is in the exclusion list — should get testsSkippedReason.
        Path root = tempDir.resolve("project");
        Files.createDirectories(root);

        String parentPom = simpleParentPom("module-a", "module-b");
        writePom(root, "pom.xml", parentPom);
        String moduleAPom = simpleChildPom("module-a");
        writePom(root, "module-a/pom.xml", moduleAPom);
        String moduleBPom = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>module-b</artifactId>
                  <dependencies><dependency><groupId>com.example</groupId><artifactId>module-a</artifactId><version>1.0</version><scope>test</scope></dependency></dependencies>
                </project>
                """;
        writePom(root, "module-b/pom.xml", moduleBPom);

        MavenProject parentProject = createProject("com.example", "parent", "1.0", root, "pom.xml", parentPom);
        parentProject.getModel().setPackaging("pom");
        MavenProject moduleA = createProject("com.example", "module-a", "1.0", root, "module-a/pom.xml", moduleAPom);
        moduleA.setParent(parentProject);
        MavenProject moduleB = createProject("com.example", "module-b", "1.0", root, "module-b/pom.xml", moduleBPom);
        moduleB.setParent(parentProject);
        Dependency dep = new Dependency();
        dep.setGroupId("com.example");
        dep.setArtifactId("module-a");
        dep.setVersion("1.0");
        dep.setScope("test");
        moduleB.getDependencies().add(dep);

        List<MavenProject> allProjects = List.of(parentProject, moduleA, moduleB);

        Set<String> changedFiles = new LinkedHashSet<>();
        changedFiles.add("module-a/src/main/java/Foo.java");
        when(scalpelCore.detectChanges(any(), any(), any()))
                .thenReturn(new ChangeDetectionResult(changedFiles, new HashMap<>()));
        setupEmptyDependencyResolution();

        MavenSession session = createSimpleSession(root, allProjects, "report");
        session.getSystemProperties().setProperty("scalpel.skipTestsForDownstreamModules", "module-b");

        ProjectDependencyGraph graph = mock(ProjectDependencyGraph.class);
        when(graph.getDownstreamProjects(moduleA, true)).thenReturn(List.of(moduleB));
        when(graph.getDownstreamProjects(moduleB, true)).thenReturn(List.of());
        when(graph.getDownstreamProjects(parentProject, true)).thenReturn(List.of());
        when(graph.getUpstreamProjects(any(), anyBoolean())).thenReturn(List.of());
        when(graph.getSortedProjects()).thenReturn(allProjects);
        when(session.getProjectDependencyGraph()).thenReturn(graph);

        participant.afterProjectsRead(session);

        Path reportFile = root.resolve("target/scalpel-report.json");
        assertTrue(Files.exists(reportFile));
        String json = new String(Files.readAllBytes(reportFile), StandardCharsets.UTF_8);
        assertTrue(
                moduleHasReason(json, "module-b", "DOWNSTREAM_TEST"),
                "module-b should have DOWNSTREAM_TEST reason (test-scoped downstream)");
        assertTrue(
                moduleHasField(json, "module-b", "testsSkippedReason", "EXCLUDED_DOWNSTREAM"),
                "module-b should have testsSkippedReason=EXCLUDED_DOWNSTREAM");
    }

    @Test
    void reportMode_transitivelyAffectedDownstreamExcludedHasTestsSkippedReason() throws Exception {
        // module-b is downstream of module-a AND transitively affected by a changed managed dependency.
        // module-b is in the exclusion list — should get testsSkippedReason even though it's handled
        // by addTransitivelyAffectedModules rather than addDownstreamModules.
        Path root = tempDir.resolve("project");
        Files.createDirectories(root);

        String oldParentPom = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <modules><module>module-a</module><module>module-b</module></modules>
                  <properties><lib.version>1.0</lib.version></properties>
                  <dependencyManagement><dependencies>
                    <dependency><groupId>commons-lang</groupId><artifactId>commons-lang</artifactId><version>${lib.version}</version></dependency>
                  </dependencies></dependencyManagement>
                </project>
                """;

        String newParentPom = oldParentPom.replace("<lib.version>1.0</lib.version>", "<lib.version>2.0</lib.version>");
        writePom(root, "pom.xml", newParentPom);
        String moduleAPom = simpleChildPom("module-a");
        writePom(root, "module-a/pom.xml", moduleAPom);
        String moduleBPom = simpleChildPomWithDep("module-b", "module-a");
        writePom(root, "module-b/pom.xml", moduleBPom);

        MavenProject parentProject = createProject("com.example", "parent", "1.0", root, "pom.xml", newParentPom);
        parentProject.getModel().setPackaging("pom");
        MavenProject moduleA = createProject("com.example", "module-a", "1.0", root, "module-a/pom.xml", moduleAPom);
        moduleA.setParent(parentProject);
        MavenProject moduleB = createProject("com.example", "module-b", "1.0", root, "module-b/pom.xml", moduleBPom);
        moduleB.setParent(parentProject);

        List<MavenProject> allProjects = List.of(parentProject, moduleA, moduleB);

        Set<String> changedFiles = new LinkedHashSet<>();
        changedFiles.add("pom.xml");
        changedFiles.add("module-a/src/main/java/Foo.java");
        Map<String, byte[]> oldPoms = new HashMap<>();
        oldPoms.put("pom.xml", oldParentPom.getBytes(StandardCharsets.UTF_8));
        when(scalpelCore.detectChanges(any(), any(), any()))
                .thenReturn(new ChangeDetectionResult(changedFiles, oldPoms));

        // module-b has commons-lang transitively (makes it transitively affected)
        DependencyResolutionResult moduleBResolution = mock(DependencyResolutionResult.class);
        org.eclipse.aether.graph.Dependency commonsLangDep = new org.eclipse.aether.graph.Dependency(
                new DefaultArtifact("commons-lang", "commons-lang", "jar", "2.0"), "compile");
        when(moduleBResolution.getResolvedDependencies()).thenReturn(List.of(commonsLangDep));
        DependencyResolutionResult emptyResolution = mock(DependencyResolutionResult.class);
        when(emptyResolution.getResolvedDependencies()).thenReturn(List.of());
        when(dependenciesResolver.resolve(any(DefaultDependencyResolutionRequest.class)))
                .thenAnswer(invocation -> {
                    DefaultDependencyResolutionRequest req = invocation.getArgument(0);
                    if ("module-b".equals(req.getMavenProject().getArtifactId())) {
                        return moduleBResolution;
                    }
                    return emptyResolution;
                });

        MavenSession session = createSimpleSession(root, allProjects, "report");
        session.getSystemProperties().setProperty("scalpel.skipTestsForDownstreamModules", "module-b");

        ProjectDependencyGraph graph = mock(ProjectDependencyGraph.class);
        when(graph.getDownstreamProjects(moduleA, true)).thenReturn(List.of(moduleB));
        when(graph.getDownstreamProjects(moduleB, true)).thenReturn(List.of());
        when(graph.getDownstreamProjects(parentProject, true)).thenReturn(List.of());
        when(graph.getUpstreamProjects(any(), anyBoolean())).thenReturn(List.of());
        when(graph.getSortedProjects()).thenReturn(allProjects);
        when(session.getProjectDependencyGraph()).thenReturn(graph);

        participant.afterProjectsRead(session);

        Path reportFile = root.resolve("target/scalpel-report.json");
        assertTrue(Files.exists(reportFile));
        String json = new String(Files.readAllBytes(reportFile), StandardCharsets.UTF_8);
        assertTrue(
                moduleHasReason(json, "module-b", "TRANSITIVE_DEPENDENCY"),
                "module-b should have TRANSITIVE_DEPENDENCY reason");
        assertTrue(
                moduleHasField(json, "module-b", "testsSkippedReason", "EXCLUDED_DOWNSTREAM"),
                "module-b should have testsSkippedReason=EXCLUDED_DOWNSTREAM even when transitively affected");
    }

    @Test
    void skipTestsMode_excludedDownstreamWithChangedPluginStillRunsTests() throws Exception {
        // module-b is downstream and in the exclusion list, but also uses a changed managed plugin.
        // The safety guard should prevent test skipping.
        Path root = tempDir.resolve("project");
        Files.createDirectories(root);

        String oldParentPom = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <modules><module>module-a</module><module>module-b</module></modules>
                  <properties><compiler.version>3.11.0</compiler.version></properties>
                  <build><pluginManagement><plugins>
                    <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>${compiler.version}</version></plugin>
                  </plugins></pluginManagement></build>
                </project>
                """;

        String newParentPom = oldParentPom.replace(
                "<compiler.version>3.11.0</compiler.version>", "<compiler.version>3.12.0</compiler.version>");
        writePom(root, "pom.xml", newParentPom);
        String moduleAPom = simpleChildPom("module-a");
        writePom(root, "module-a/pom.xml", moduleAPom);
        String moduleBPom = simpleChildPomWithDep("module-b", "module-a");
        writePom(root, "module-b/pom.xml", moduleBPom);

        MavenProject parentProject = createProject("com.example", "parent", "1.0", root, "pom.xml", newParentPom);
        parentProject.getModel().setPackaging("pom");
        Build parentBuild = new Build();
        parentProject.getModel().setBuild(parentBuild);
        MavenProject moduleA = createProject("com.example", "module-a", "1.0", root, "module-a/pom.xml", moduleAPom);
        moduleA.setParent(parentProject);
        MavenProject moduleB = createProject("com.example", "module-b", "1.0", root, "module-b/pom.xml", moduleBPom);
        moduleB.setParent(parentProject);
        // module-b uses the changed managed plugin
        Build build = new Build();
        Plugin compilerPlugin = new Plugin();
        compilerPlugin.setGroupId("org.apache.maven.plugins");
        compilerPlugin.setArtifactId("maven-compiler-plugin");
        build.addPlugin(compilerPlugin);
        moduleB.getModel().setBuild(build);

        List<MavenProject> allProjects = List.of(parentProject, moduleA, moduleB);

        // Changed files: parent POM (property change) + module-a source
        Set<String> changedFiles = new LinkedHashSet<>();
        changedFiles.add("pom.xml");
        changedFiles.add("module-a/src/main/java/Foo.java");
        Map<String, byte[]> oldPoms = new HashMap<>();
        oldPoms.put("pom.xml", oldParentPom.getBytes(StandardCharsets.UTF_8));
        when(scalpelCore.detectChanges(any(), any(), any()))
                .thenReturn(new ChangeDetectionResult(changedFiles, oldPoms));
        setupEmptyDependencyResolution();

        MavenSession session = createSimpleSession(root, allProjects, "skip-tests");
        session.getSystemProperties().setProperty("scalpel.skipTestsForDownstreamModules", "module-b");

        // module-b is downstream of module-a
        ProjectDependencyGraph graph = mock(ProjectDependencyGraph.class);
        when(graph.getDownstreamProjects(moduleA, true)).thenReturn(List.of(moduleB));
        when(graph.getDownstreamProjects(moduleB, true)).thenReturn(List.of());
        when(graph.getDownstreamProjects(parentProject, true)).thenReturn(List.of());
        when(graph.getUpstreamProjects(any(), anyBoolean())).thenReturn(List.of());
        when(graph.getSortedProjects()).thenReturn(allProjects);
        when(session.getProjectDependencyGraph()).thenReturn(graph);

        participant.afterProjectsRead(session);

        // module-b is excluded downstream BUT also uses a changed managed plugin
        // Safety guard: tests should NOT be skipped
        assertNotEquals(
                "true",
                moduleB.getProperties().getProperty("maven.test.skip"),
                "module-b should NOT have tests skipped (uses changed managed plugin, safety guard)");
    }

    @Test
    void reportMode_forceBuildModulesIncludesMatchingModule() throws Exception {
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

        List<MavenProject> allProjects = List.of(parentProject, moduleA, moduleB);

        // Only module-a has a source change
        Set<String> changedFiles = new LinkedHashSet<>();
        changedFiles.add("module-a/src/main/java/Foo.java");
        when(scalpelCore.detectChanges(any(), any(), any()))
                .thenReturn(new ChangeDetectionResult(changedFiles, new HashMap<String, byte[]>()));
        setupEmptyDependencyResolution();

        MavenSession session = createSimpleSession(root, allProjects, "report");
        // Force module-b to be included even though it has no changes
        session.getSystemProperties().setProperty("scalpel.forceBuildModules", "module-b");

        participant.afterProjectsRead(session);

        Path reportFile = root.resolve("target/scalpel-report.json");
        assertTrue(Files.exists(reportFile));
        String json = new String(Files.readAllBytes(reportFile), StandardCharsets.UTF_8);
        assertTrue(modulePresent(json, "module-a"), "module-a should be in report");
        assertTrue(modulePresent(json, "module-b"), "module-b should be force-included");
        assertTrue(moduleHasReason(json, "module-b", "FORCE_BUILD"), "module-b should have FORCE_BUILD reason");
    }

    @Test
    void reportMode_excludePathsFiltersChanges() throws Exception {
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

        List<MavenProject> allProjects = List.of(parentProject, moduleA);

        // Changes include an excluded path and a module source
        Set<String> changedFiles = new LinkedHashSet<>();
        changedFiles.add("README.md");
        changedFiles.add("module-a/src/main/java/Foo.java");
        when(scalpelCore.detectChanges(any(), any(), any()))
                .thenReturn(new ChangeDetectionResult(changedFiles, new HashMap<String, byte[]>()));
        setupEmptyDependencyResolution();

        MavenSession session = createSimpleSession(root, allProjects, "report");
        session.getSystemProperties().setProperty("scalpel.excludePaths", "*.md");

        participant.afterProjectsRead(session);

        Path reportFile = root.resolve("target/scalpel-report.json");
        assertTrue(Files.exists(reportFile));
        String json = new String(Files.readAllBytes(reportFile), StandardCharsets.UTF_8);
        // module-a should still be affected (its source file is not excluded)
        assertTrue(modulePresent(json, "module-a"));
        // README.md should be filtered from changedFiles
        assertFalse(json.contains("README.md"), "README.md should be excluded from changed files");
    }

    @Test
    void reportMode_disableTriggerCausesFullBuild() throws Exception {
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

        List<MavenProject> allProjects = List.of(parentProject, moduleA, moduleB);

        // A CI config file changed, matching the disable trigger
        Set<String> changedFiles = new LinkedHashSet<>();
        changedFiles.add(".github/workflows/ci.yml");
        when(scalpelCore.detectChanges(any(), any(), any()))
                .thenReturn(new ChangeDetectionResult(changedFiles, new HashMap<String, byte[]>()));
        setupEmptyDependencyResolution();

        MavenSession session = createSimpleSession(root, allProjects, "report");
        session.getSystemProperties().setProperty("scalpel.fullBuildTriggers", ".github/**");

        participant.afterProjectsRead(session);

        Path reportFile = root.resolve("target/scalpel-report.json");
        assertTrue(Files.exists(reportFile));
        String json = new String(Files.readAllBytes(reportFile), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"fullBuildTriggered\": true"), "Full build should be triggered");
    }

    @Test
    void reportMode_nullDetectionResultSkipsProcessing() throws Exception {
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

        List<MavenProject> allProjects = List.of(parentProject, moduleA);

        // Null detection result (e.g., no git repo or no base branch)
        when(scalpelCore.detectChanges(any(), any(), any())).thenReturn(null);
        setupEmptyDependencyResolution();

        MavenSession session = createSimpleSession(root, allProjects, "report");

        participant.afterProjectsRead(session);

        // No report should be written when detection returns null
        Path reportFile = root.resolve("target/scalpel-report.json");
        assertFalse(Files.exists(reportFile), "Report file should not be created when detection returns null");
    }

    @Test
    void disabled_doesNothing() throws Exception {
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

        List<MavenProject> allProjects = List.of(parentProject, moduleA);

        MavenSession session = createSimpleSession(root, allProjects, "trim");
        session.getSystemProperties().setProperty("scalpel.enabled", "false");

        participant.afterProjectsRead(session);

        // No report, no trimming, no test skipping
        Path reportFile = root.resolve("target/scalpel-report.json");
        assertFalse(Files.exists(reportFile), "No report should be created when disabled");
    }

    @Test
    void disableOnSelectedProjects_withPlActive() throws Exception {
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

        List<MavenProject> allProjects = List.of(parentProject, moduleA);

        MavenSession session = createSimpleSession(root, allProjects, "trim");
        session.getSystemProperties().setProperty("scalpel.disableOnSelectedProjects", "true");

        // Simulate -pl by setting selected projects
        when(session.getRequest().getSelectedProjects()).thenReturn(List.of("module-a"));

        participant.afterProjectsRead(session);

        // Should not process — no report, no trimming
        Path reportFile = root.resolve("target/scalpel-report.json");
        assertFalse(Files.exists(reportFile));
    }

    @Test
    void noChanges_withBuildAllIfNoChanges() throws Exception {
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

        List<MavenProject> allProjects = List.of(parentProject, moduleA);

        // No changed files
        when(scalpelCore.detectChanges(any(), any(), any()))
                .thenReturn(new ChangeDetectionResult(new LinkedHashSet<String>(), new HashMap<String, byte[]>()));
        setupEmptyDependencyResolution();

        MavenSession session = createSimpleSession(root, allProjects, "trim");
        session.getSystemProperties().setProperty("scalpel.buildAllIfNoChanges", "true");

        participant.afterProjectsRead(session);

        // Should return early, building all (no trimming applied)
        Path reportFile = root.resolve("target/scalpel-report.json");
        assertFalse(Files.exists(reportFile));
    }

    @Test
    void allFilesExcludedByPathFilters_buildsAll() throws Exception {
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

        List<MavenProject> allProjects = List.of(parentProject, moduleA);

        // Only .md files changed
        Set<String> changedFiles = new LinkedHashSet<>();
        changedFiles.add("README.md");
        changedFiles.add("CHANGELOG.md");
        when(scalpelCore.detectChanges(any(), any(), any()))
                .thenReturn(new ChangeDetectionResult(changedFiles, new HashMap<String, byte[]>()));
        setupEmptyDependencyResolution();

        MavenSession session = createSimpleSession(root, allProjects, "trim");
        session.getSystemProperties().setProperty("scalpel.excludePaths", "*.md");

        participant.afterProjectsRead(session);

        // All files excluded → builds all modules (no trimming)
        Path reportFile = root.resolve("target/scalpel-report.json");
        assertFalse(Files.exists(reportFile));
    }

    @Test
    void fullBuildTrigger_inTrimMode_doesNotTrim() throws Exception {
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

        List<MavenProject> allProjects = List.of(parentProject, moduleA, moduleB);

        Set<String> changedFiles = new LinkedHashSet<>();
        changedFiles.add(".github/workflows/ci.yml");
        changedFiles.add("module-a/src/main/java/Foo.java");
        when(scalpelCore.detectChanges(any(), any(), any()))
                .thenReturn(new ChangeDetectionResult(changedFiles, new HashMap<String, byte[]>()));
        setupEmptyDependencyResolution();

        MavenSession session = createSimpleSession(root, allProjects, "trim");
        session.getSystemProperties().setProperty("scalpel.fullBuildTriggers", ".github/**");

        participant.afterProjectsRead(session);

        // In trim mode, full build trigger means no trimming (no setProjects called)
        // No report file should be created (not report mode)
        Path reportFile = root.resolve("target/scalpel-report.json");
        assertFalse(Files.exists(reportFile));
    }

    @Test
    void pomAnalysisError_failSafeTrue_buildsAll() throws Exception {
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

        List<MavenProject> allProjects = List.of(parentProject, moduleA);

        // Provide invalid old POM content to trigger a parse error
        Set<String> changedFiles = new LinkedHashSet<>();
        changedFiles.add("pom.xml");
        Map<String, byte[]> oldPoms = new HashMap<>();
        oldPoms.put("pom.xml", "<<<INVALID XML>>>".getBytes(StandardCharsets.UTF_8));
        when(scalpelCore.detectChanges(any(), any(), any()))
                .thenReturn(new ChangeDetectionResult(changedFiles, oldPoms));
        setupEmptyDependencyResolution();

        MavenSession session = createSimpleSession(root, allProjects, "trim");
        session.getSystemProperties().setProperty("scalpel.failSafe", "true");

        participant.afterProjectsRead(session);

        // failSafe=true → should not throw, just return (build all)
        Path reportFile = root.resolve("target/scalpel-report.json");
        assertFalse(Files.exists(reportFile));
    }

    @Test
    void noModulesAffected_skipTestsMode_skipsAllTests() throws Exception {
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

        List<MavenProject> allProjects = List.of(parentProject, moduleA, moduleB);

        // Change a file that doesn't map to any module (e.g. root-level non-pom file)
        Set<String> changedFiles = new LinkedHashSet<>();
        changedFiles.add(".gitignore");
        when(scalpelCore.detectChanges(any(), any(), any()))
                .thenReturn(new ChangeDetectionResult(changedFiles, new HashMap<String, byte[]>()));
        setupEmptyDependencyResolution();

        MavenSession session = createSimpleSession(root, allProjects, "skip-tests");

        participant.afterProjectsRead(session);

        // All modules should have tests skipped when no modules are affected
        assertEquals(
                "true",
                moduleA.getProperties().getProperty("maven.test.skip"),
                "module-a should have tests skipped (no modules affected)");
        assertEquals(
                "true",
                moduleB.getProperties().getProperty("maven.test.skip"),
                "module-b should have tests skipped (no modules affected)");
    }

    @Test
    void impactedLog_writesAffectedModulePaths() throws Exception {
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

        List<MavenProject> allProjects = List.of(parentProject, moduleA, moduleB);

        Set<String> changedFiles = new LinkedHashSet<>();
        changedFiles.add("module-a/src/main/java/Foo.java");
        when(scalpelCore.detectChanges(any(), any(), any()))
                .thenReturn(new ChangeDetectionResult(changedFiles, new HashMap<String, byte[]>()));
        setupEmptyDependencyResolution();

        MavenSession session = createSimpleSession(root, allProjects, "report");
        session.getSystemProperties().setProperty("scalpel.impactedLog", "target/scalpel-impacted.log");

        participant.afterProjectsRead(session);

        Path logFile = root.resolve("target/scalpel-impacted.log");
        assertTrue(Files.exists(logFile), "Impacted log file should be created");
        String content = new String(java.nio.file.Files.readAllBytes(logFile), StandardCharsets.UTF_8);
        assertTrue(content.contains("module-a"), "Impacted log should contain module-a");
    }

    @Test
    void skipTestsMode_skipTestsForUpstream_skipsUpstreamTests() throws Exception {
        Path root = tempDir.resolve("project");
        Files.createDirectories(root);

        String parentPom = simpleParentPom("module-a", "module-b");
        writePom(root, "pom.xml", parentPom);
        String moduleAPom = simpleChildPom("module-a");
        writePom(root, "module-a/pom.xml", moduleAPom);
        String moduleBPom = simpleChildPomWithDep("module-b", "module-a");
        writePom(root, "module-b/pom.xml", moduleBPom);

        MavenProject parentProject = createProject("com.example", "parent", "1.0", root, "pom.xml", parentPom);
        parentProject.getModel().setPackaging("pom");
        MavenProject moduleA = createProject("com.example", "module-a", "1.0", root, "module-a/pom.xml", moduleAPom);
        moduleA.setParent(parentProject);
        MavenProject moduleB = createProject("com.example", "module-b", "1.0", root, "module-b/pom.xml", moduleBPom);
        moduleB.setParent(parentProject);

        List<MavenProject> allProjects = List.of(parentProject, moduleA, moduleB);

        // module-b has source changes, module-a is upstream
        Set<String> changedFiles = new LinkedHashSet<>();
        changedFiles.add("module-b/src/main/java/Foo.java");
        when(scalpelCore.detectChanges(any(), any(), any()))
                .thenReturn(new ChangeDetectionResult(changedFiles, new HashMap<String, byte[]>()));
        setupEmptyDependencyResolution();

        MavenSession session = createSimpleSession(root, allProjects, "skip-tests");
        session.getSystemProperties().setProperty("scalpel.skipTestsForUpstream", "true");
        session.getSystemProperties().setProperty("scalpel.alsoMake", "true");

        // Graph: module-a is upstream of module-b
        ProjectDependencyGraph graph = mock(ProjectDependencyGraph.class);
        when(graph.getDownstreamProjects(any(), anyBoolean())).thenReturn(List.of());
        when(graph.getUpstreamProjects(moduleB, true)).thenReturn(List.of(moduleA));
        when(graph.getUpstreamProjects(moduleA, true)).thenReturn(List.of());
        when(graph.getUpstreamProjects(parentProject, true)).thenReturn(List.of());
        when(graph.getSortedProjects()).thenReturn(allProjects);
        when(session.getProjectDependencyGraph()).thenReturn(graph);

        participant.afterProjectsRead(session);

        // module-a (upstream) should have tests skipped
        assertEquals(
                "true",
                moduleA.getProperties().getProperty("maven.test.skip"),
                "module-a (upstream) should have tests skipped");
        // module-b (directly affected) should NOT have tests skipped
        assertNotEquals(
                "true",
                moduleB.getProperties().getProperty("maven.test.skip"),
                "module-b (directly affected) should run tests");
    }

    @Test
    void trimMode_applyPerCategoryArgs() throws Exception {
        Path root = tempDir.resolve("project");
        Files.createDirectories(root);

        String parentPom = simpleParentPom("module-a", "module-b", "module-c");
        writePom(root, "pom.xml", parentPom);
        String moduleAPom = simpleChildPom("module-a");
        writePom(root, "module-a/pom.xml", moduleAPom);
        String moduleBPom = simpleChildPomWithDep("module-b", "module-a");
        writePom(root, "module-b/pom.xml", moduleBPom);
        String moduleCPom = simpleChildPom("module-c");
        writePom(root, "module-c/pom.xml", moduleCPom);

        MavenProject parentProject = createProject("com.example", "parent", "1.0", root, "pom.xml", parentPom);
        parentProject.getModel().setPackaging("pom");
        MavenProject moduleA = createProject("com.example", "module-a", "1.0", root, "module-a/pom.xml", moduleAPom);
        moduleA.setParent(parentProject);
        MavenProject moduleB = createProject("com.example", "module-b", "1.0", root, "module-b/pom.xml", moduleBPom);
        moduleB.setParent(parentProject);
        MavenProject moduleC = createProject("com.example", "module-c", "1.0", root, "module-c/pom.xml", moduleCPom);
        moduleC.setParent(parentProject);

        List<MavenProject> allProjects = List.of(parentProject, moduleA, moduleB, moduleC);

        // module-b has source changes
        Set<String> changedFiles = new LinkedHashSet<>();
        changedFiles.add("module-b/src/main/java/Foo.java");
        when(scalpelCore.detectChanges(any(), any(), any()))
                .thenReturn(new ChangeDetectionResult(changedFiles, new HashMap<String, byte[]>()));
        setupEmptyDependencyResolution();

        MavenSession session = createSimpleSession(root, allProjects, "trim");
        session.getSystemProperties().setProperty("scalpel.alsoMake", "true");
        session.getSystemProperties().setProperty("scalpel.alsoMakeDependents", "true");
        session.getSystemProperties().setProperty("scalpel.upstreamArgs", "skipITs=true");
        session.getSystemProperties().setProperty("scalpel.downstreamArgs", "quick=true");

        // Graph: module-a is upstream of module-b, module-c is downstream of module-b
        ProjectDependencyGraph graph = mock(ProjectDependencyGraph.class);
        when(graph.getUpstreamProjects(moduleB, true)).thenReturn(List.of(moduleA));
        when(graph.getUpstreamProjects(moduleA, true)).thenReturn(List.of());
        when(graph.getUpstreamProjects(moduleC, true)).thenReturn(List.of());
        when(graph.getUpstreamProjects(parentProject, true)).thenReturn(List.of());
        when(graph.getDownstreamProjects(moduleB, true)).thenReturn(List.of(moduleC));
        when(graph.getDownstreamProjects(moduleA, true)).thenReturn(List.of());
        when(graph.getDownstreamProjects(moduleC, true)).thenReturn(List.of());
        when(graph.getDownstreamProjects(parentProject, true)).thenReturn(List.of());
        when(graph.getSortedProjects()).thenReturn(allProjects);
        when(session.getProjectDependencyGraph()).thenReturn(graph);

        participant.afterProjectsRead(session);

        // module-a (upstream) should have upstream args applied
        assertEquals(
                "true", moduleA.getProperties().getProperty("skipITs"), "module-a (upstream) should have skipITs=true");
        // module-c (downstream) should have downstream args applied
        assertEquals(
                "true", moduleC.getProperties().getProperty("quick"), "module-c (downstream) should have quick=true");
        // module-b (directly affected) should NOT have either arg
        assertNotEquals(
                "true", moduleB.getProperties().getProperty("skipITs"), "module-b should not have upstream args");
    }

    @Test
    void skipTestsMode_changedManagedPluginOnNonBuildsetModule_runsTests() throws Exception {
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
                + "  <properties><compiler.version>3.11.0</compiler.version></properties>\n"
                + "  <build><pluginManagement><plugins>\n"
                + "    <plugin><groupId>org.apache.maven.plugins</groupId>"
                + "<artifactId>maven-compiler-plugin</artifactId>"
                + "<version>${compiler.version}</version></plugin>\n"
                + "  </plugins></pluginManagement></build>\n"
                + "</project>\n";
        String newParentPom = oldParentPom.replace(
                "<compiler.version>3.11.0</compiler.version>", "<compiler.version>3.12.0</compiler.version>");
        writePom(root, "pom.xml", newParentPom);

        // module-a: no source change, no direct POM change, but uses the changed plugin
        String moduleAPom = "<?xml version=\"1.0\"?>\n<project>\n  <modelVersion>4.0.0</modelVersion>\n"
                + "  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>\n"
                + "  <artifactId>module-a</artifactId>\n"
                + "  <build><plugins><plugin><groupId>org.apache.maven.plugins</groupId>"
                + "<artifactId>maven-compiler-plugin</artifactId></plugin></plugins></build>\n</project>\n";
        writePom(root, "module-a/pom.xml", moduleAPom);
        // module-b: no involvement at all
        String moduleBPom = simpleChildPom("module-b");
        writePom(root, "module-b/pom.xml", moduleBPom);

        MavenProject parentProject = createProject("com.example", "parent", "1.0", root, "pom.xml", newParentPom);
        parentProject.getModel().setPackaging("pom");
        Build parentBuild = new Build();
        parentProject.getModel().setBuild(parentBuild);
        MavenProject moduleA = createProject("com.example", "module-a", "1.0", root, "module-a/pom.xml", moduleAPom);
        moduleA.setParent(parentProject);
        // module-a uses maven-compiler-plugin
        Build buildA = new Build();
        Plugin compilerPlugin = new Plugin();
        compilerPlugin.setGroupId("org.apache.maven.plugins");
        compilerPlugin.setArtifactId("maven-compiler-plugin");
        buildA.addPlugin(compilerPlugin);
        moduleA.getModel().setBuild(buildA);
        MavenProject moduleB = createProject("com.example", "module-b", "1.0", root, "module-b/pom.xml", moduleBPom);
        moduleB.setParent(parentProject);

        List<MavenProject> allProjects = List.of(parentProject, moduleA, moduleB);

        // Only parent POM changed
        Set<String> changedFiles = new LinkedHashSet<>();
        changedFiles.add("pom.xml");
        Map<String, byte[]> oldPoms = new HashMap<>();
        oldPoms.put("pom.xml", oldParentPom.getBytes(StandardCharsets.UTF_8));
        when(scalpelCore.detectChanges(any(), any(), any()))
                .thenReturn(new ChangeDetectionResult(changedFiles, oldPoms));
        setupEmptyDependencyResolution();

        MavenSession session = createSimpleSession(root, allProjects, "skip-tests");

        participant.afterProjectsRead(session);

        // module-a uses the changed managed plugin — tests should NOT be skipped
        assertNotEquals(
                "true",
                moduleA.getProperties().getProperty("maven.test.skip"),
                "module-a should run tests (uses changed managed plugin)");
        // module-b has no involvement — tests should be skipped
        assertEquals(
                "true", moduleB.getProperties().getProperty("maven.test.skip"), "module-b should have tests skipped");
    }

    @Test
    void disableTrigger_inTrimMode_doesNotTrimOrReport() throws Exception {
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

        List<MavenProject> allProjects = List.of(parentProject, moduleA);

        Set<String> changedFiles = new LinkedHashSet<>();
        changedFiles.add("Jenkinsfile");
        changedFiles.add("module-a/src/main/java/Foo.java");
        when(scalpelCore.detectChanges(any(), any(), any()))
                .thenReturn(new ChangeDetectionResult(changedFiles, new HashMap<String, byte[]>()));
        setupEmptyDependencyResolution();

        MavenSession session = createSimpleSession(root, allProjects, "trim");
        session.getSystemProperties().setProperty("scalpel.disableTriggers", "Jenkinsfile");

        participant.afterProjectsRead(session);

        // Disable trigger matched → scalpel bails out entirely (no trimming)
        Path reportFile = root.resolve("target/scalpel-report.json");
        assertFalse(Files.exists(reportFile));
    }

    @Test
    void reportMode_upstreamModulesExcludedFromReport() throws Exception {
        Path root = tempDir.resolve("project");
        Files.createDirectories(root);

        String parentPom = simpleParentPom("module-a", "module-b");
        writePom(root, "pom.xml", parentPom);
        String moduleAPom = simpleChildPom("module-a");
        writePom(root, "module-a/pom.xml", moduleAPom);
        String moduleBPom = simpleChildPomWithDep("module-b", "module-a");
        writePom(root, "module-b/pom.xml", moduleBPom);

        MavenProject parentProject = createProject("com.example", "parent", "1.0", root, "pom.xml", parentPom);
        parentProject.getModel().setPackaging("pom");
        MavenProject moduleA = createProject("com.example", "module-a", "1.0", root, "module-a/pom.xml", moduleAPom);
        moduleA.setParent(parentProject);
        MavenProject moduleB = createProject("com.example", "module-b", "1.0", root, "module-b/pom.xml", moduleBPom);
        moduleB.setParent(parentProject);

        List<MavenProject> allProjects = List.of(parentProject, moduleA, moduleB);

        // module-b has source changes, module-a is upstream
        Set<String> changedFiles = new LinkedHashSet<>();
        changedFiles.add("module-b/src/main/java/Foo.java");
        when(scalpelCore.detectChanges(any(), any(), any()))
                .thenReturn(new ChangeDetectionResult(changedFiles, new HashMap<String, byte[]>()));
        setupEmptyDependencyResolution();

        MavenSession session = createSimpleSession(root, allProjects, "report");
        session.getSystemProperties().setProperty("scalpel.alsoMake", "true");

        // Graph: module-a is upstream of module-b
        ProjectDependencyGraph graph = mock(ProjectDependencyGraph.class);
        when(graph.getUpstreamProjects(moduleB, true)).thenReturn(List.of(moduleA));
        when(graph.getUpstreamProjects(moduleA, true)).thenReturn(List.of());
        when(graph.getUpstreamProjects(parentProject, true)).thenReturn(List.of());
        when(graph.getDownstreamProjects(any(), anyBoolean())).thenReturn(List.of());
        when(graph.getSortedProjects()).thenReturn(allProjects);
        when(session.getProjectDependencyGraph()).thenReturn(graph);

        participant.afterProjectsRead(session);

        Path reportFile = root.resolve("target/scalpel-report.json");
        assertTrue(Files.exists(reportFile));
        String json = new String(java.nio.file.Files.readAllBytes(reportFile), StandardCharsets.UTF_8);
        assertTrue(moduleHasReason(json, "module-b", "SOURCE_CHANGE"), "module-b should have SOURCE_CHANGE");
        // Fix #39: upstream modules (build prerequisites) are excluded from the report.
        // module-a is only in the build set because module-b depends on it — it is not
        // genuinely affected by the change. Including it inflates affectedModules.
        assertFalse(
                modulePresent(json, "module-a"),
                "module-a should NOT be in report (it's a build prerequisite, not affected by the change)");
    }

    // --- Helper methods ---

    private void setupEmptyDependencyResolution() throws Exception {
        DependencyResolutionResult emptyResolution = mock(DependencyResolutionResult.class);
        when(emptyResolution.getResolvedDependencies()).thenReturn(List.of());
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
        when(graph.getDownstreamProjects(any(), anyBoolean())).thenReturn(List.of());
        when(graph.getUpstreamProjects(any(), anyBoolean())).thenReturn(List.of());
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
        return """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>""" + artifactId + """
                </artifactId>
                </project>
                """;
    }

    private String simpleChildPomWithDep(String artifactId, String depArtifactId) {
        return """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>"""
                + artifactId
                + "</artifactId>\n"
                + "  <dependencies><dependency><groupId>com.example</groupId><artifactId>"
                + depArtifactId
                + """
                </artifactId><version>1.0</version></dependency></dependencies>
                </project>
                """;
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

    private String extractModuleBlock(String json, String artifactId) {
        String marker = "\"artifactId\": \"" + artifactId + "\"";
        int idx = json.indexOf(marker);
        if (idx < 0) {
            return null;
        }
        int start = json.lastIndexOf("{", idx);
        int end = json.indexOf("}", idx);
        if (start < 0 || end < 0) {
            return null;
        }
        return json.substring(start, end + 1);
    }

    private boolean moduleHasReason(String json, String artifactId, String reason) {
        String block = extractModuleBlock(json, artifactId);
        return block != null && block.contains("\"" + reason + "\"");
    }

    private boolean moduleHasAnySourceSet(String json, String artifactId) {
        String block = extractModuleBlock(json, artifactId);
        return block != null && block.contains("\"sourceSet\":");
    }

    private boolean moduleHasSourceSet(String json, String artifactId, String sourceSet) {
        String block = extractModuleBlock(json, artifactId);
        return block != null && block.contains("\"sourceSet\": \"" + sourceSet + "\"");
    }

    private boolean moduleHasField(String json, String artifactId, String field, String value) {
        String block = extractModuleBlock(json, artifactId);
        return block != null && block.contains("\"" + field + "\": \"" + value + "\"");
    }

    private Model parseModel(String xml) {
        try {
            return new MavenXpp3Reader().read(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse POM XML: " + xml.substring(0, Math.min(xml.length(), 100)), e);
        }
    }

    /**
     * Reproduce scalpel#39: Camel-like structure where kafka-version property is defined
     * in parent POM but NOT used in parent's dependencyManagement. It's only used directly
     * in 3 child modules' dependencies. With alsoMake=true (default), the report should
     * contain DIRECT + DOWNSTREAM + UPSTREAM but NOT inflate with unrelated modules.
     */
    @Test
    void reportMode_camelLike_kafkaVersionInChildDepsOnly() throws Exception {
        Path root = tempDir.resolve("project");
        Files.createDirectories(root);

        // Root aggregator (like Camel's root pom.xml)
        StringBuilder rootModules = new StringBuilder();
        rootModules.append("<module>parent</module>");
        rootModules.append("<module>camel-core</module>");
        rootModules.append("<module>camel-kafka</module>");
        rootModules.append("<module>camel-debezium</module>");
        rootModules.append("<module>camel-ibm</module>");
        for (int i = 1; i <= 20; i++) {
            rootModules.append("<module>camel-other-").append(i).append("</module>");
        }
        String rootPom = "<?xml version=\"1.0\"?>\n<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <groupId>org.apache.camel</groupId>\n"
                + "  <artifactId>camel</artifactId>\n"
                + "  <version>4.21.0-SNAPSHOT</version>\n"
                + "  <packaging>pom</packaging>\n"
                + "  <modules>" + rootModules + "</modules>\n"
                + "</project>\n";
        writePom(root, "pom.xml", rootPom);

        // Parent POM (like Camel's parent/pom.xml) - has kafka-version property
        // but does NOT use it in dependencyManagement.
        // Has a large dependencyManagement with camel-* modules using ${project.version}
        String oldParentPom = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>org.apache.camel</groupId>
                    <artifactId>camel</artifactId>
                    <version>4.21.0-SNAPSHOT</version>
                  </parent>
                  <artifactId>camel-parent</artifactId>
                  <packaging>pom</packaging>
                  <properties>
                    <kafka-version>4.3.1</kafka-version>
                    <commons-lang-version>3.14.0</commons-lang-version>
                  </properties>
                  <dependencyManagement><dependencies>
                    <dependency>
                      <groupId>org.apache.camel</groupId>
                      <artifactId>camel-core</artifactId>
                      <version>${project.version}</version>
                    </dependency>
                    <dependency>
                      <groupId>org.apache.camel</groupId>
                      <artifactId>camel-kafka</artifactId>
                      <version>${project.version}</version>
                    </dependency>
                    <dependency>
                      <groupId>commons-lang</groupId>
                      <artifactId>commons-lang</artifactId>
                      <version>${commons-lang-version}</version>
                    </dependency>
                  </dependencies></dependencyManagement>
                </project>
                """;
        String newParentPom =
                oldParentPom.replace("<kafka-version>4.3.1</kafka-version>", "<kafka-version>4.3.0</kafka-version>");
        writePom(root, "parent/pom.xml", newParentPom);

        // camel-core: no kafka dependency
        String corePom = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>org.apache.camel</groupId><artifactId>camel-parent</artifactId><version>4.21.0-SNAPSHOT</version></parent>
                  <artifactId>camel-core</artifactId>
                </project>
                """;
        writePom(root, "camel-core/pom.xml", corePom);

        // camel-kafka: directly uses ${kafka-version}
        String kafkaPom = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>org.apache.camel</groupId><artifactId>camel-parent</artifactId><version>4.21.0-SNAPSHOT</version></parent>
                  <artifactId>camel-kafka</artifactId>
                  <dependencies>
                    <dependency>
                      <groupId>org.apache.camel</groupId>
                      <artifactId>camel-core</artifactId>
                    </dependency>
                    <dependency>
                      <groupId>org.apache.kafka</groupId>
                      <artifactId>kafka-clients</artifactId>
                      <version>${kafka-version}</version>
                    </dependency>
                  </dependencies>
                </project>
                """;
        writePom(root, "camel-kafka/pom.xml", kafkaPom);

        // camel-debezium: also uses ${kafka-version}
        String debeziumPom = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>org.apache.camel</groupId><artifactId>camel-parent</artifactId><version>4.21.0-SNAPSHOT</version></parent>
                  <artifactId>camel-debezium</artifactId>
                  <dependencies>
                    <dependency>
                      <groupId>org.apache.kafka</groupId>
                      <artifactId>kafka-clients</artifactId>
                      <version>${kafka-version}</version>
                    </dependency>
                  </dependencies>
                </project>
                """;
        writePom(root, "camel-debezium/pom.xml", debeziumPom);

        // camel-ibm: also uses ${kafka-version}
        String ibmPom = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>org.apache.camel</groupId><artifactId>camel-parent</artifactId><version>4.21.0-SNAPSHOT</version></parent>
                  <artifactId>camel-ibm</artifactId>
                  <dependencies>
                    <dependency>
                      <groupId>org.apache.kafka</groupId>
                      <artifactId>kafka-clients</artifactId>
                      <version>${kafka-version}</version>
                    </dependency>
                  </dependencies>
                </project>
                """;
        writePom(root, "camel-ibm/pom.xml", ibmPom);

        // 20 "other" modules: no kafka dependency, depend on camel-core
        for (int i = 1; i <= 20; i++) {
            String otherPom = """
                    <?xml version="1.0"?>
                    <project>
                      <modelVersion>4.0.0</modelVersion>
                      <parent><groupId>org.apache.camel</groupId><artifactId>camel-parent</artifactId><version>4.21.0-SNAPSHOT</version></parent>
                      <artifactId>camel-other-PLACEHOLDER</artifactId>
                      <dependencies>
                        <dependency><groupId>org.apache.camel</groupId><artifactId>camel-core</artifactId></dependency>
                      </dependencies>
                    </project>
                    """.replace("PLACEHOLDER", String.valueOf(i));
            writePom(root, "camel-other-" + i + "/pom.xml", otherPom);
        }

        // Build MavenProject objects
        MavenProject rootProject =
                createProject("org.apache.camel", "camel", "4.21.0-SNAPSHOT", root, "pom.xml", rootPom);
        rootProject.getModel().setPackaging("pom");

        MavenProject parentProject = createProject(
                "org.apache.camel", "camel-parent", "4.21.0-SNAPSHOT", root, "parent/pom.xml", newParentPom);
        parentProject.getModel().setPackaging("pom");
        parentProject.setParent(rootProject);

        MavenProject coreModule =
                createProject("org.apache.camel", "camel-core", "4.21.0-SNAPSHOT", root, "camel-core/pom.xml", corePom);
        coreModule.setParent(parentProject);

        MavenProject kafkaModule = createProject(
                "org.apache.camel", "camel-kafka", "4.21.0-SNAPSHOT", root, "camel-kafka/pom.xml", kafkaPom);
        kafkaModule.setParent(parentProject);

        MavenProject debeziumModule = createProject(
                "org.apache.camel", "camel-debezium", "4.21.0-SNAPSHOT", root, "camel-debezium/pom.xml", debeziumPom);
        debeziumModule.setParent(parentProject);

        MavenProject ibmModule =
                createProject("org.apache.camel", "camel-ibm", "4.21.0-SNAPSHOT", root, "camel-ibm/pom.xml", ibmPom);
        ibmModule.setParent(parentProject);

        List<MavenProject> otherModules = new java.util.ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            String otherPom = new String(
                    Files.readAllBytes(root.resolve("camel-other-" + i + "/pom.xml")), StandardCharsets.UTF_8);
            MavenProject other = createProject(
                    "org.apache.camel",
                    "camel-other-" + i,
                    "4.21.0-SNAPSHOT",
                    root,
                    "camel-other-" + i + "/pom.xml",
                    otherPom);
            other.setParent(parentProject);
            otherModules.add(other);
        }

        List<MavenProject> allProjects = new java.util.ArrayList<>();
        allProjects.add(rootProject);
        allProjects.add(parentProject);
        allProjects.add(coreModule);
        allProjects.add(kafkaModule);
        allProjects.add(debeziumModule);
        allProjects.add(ibmModule);
        allProjects.addAll(otherModules);

        // Mock ScalpelCore: only parent/pom.xml changed
        Set<String> changedFiles = new LinkedHashSet<>();
        changedFiles.add("parent/pom.xml");
        Map<String, byte[]> oldPoms = new HashMap<>();
        oldPoms.put("parent/pom.xml", oldParentPom.getBytes(StandardCharsets.UTF_8));
        when(scalpelCore.detectChanges(any(), any(), any()))
                .thenReturn(new ChangeDetectionResult(changedFiles, oldPoms));

        // No transitive dep resolution matches (changedManagedDepGAs should be empty anyway)
        setupEmptyDependencyResolution();

        // Session
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
        when(session.getRepositorySession()).thenReturn(mock(org.eclipse.aether.RepositorySystemSession.class));

        // Dependency graph: kafka/debezium/ibm depend on core, other-N depend on core
        // Some other-N depend on camel-kafka (downstream)
        ProjectDependencyGraph graph = mock(ProjectDependencyGraph.class);
        when(graph.getDownstreamProjects(any(), anyBoolean())).thenReturn(List.of());
        when(graph.getUpstreamProjects(any(), anyBoolean())).thenReturn(List.of());
        // camel-kafka has 3 downstream dependents: other-1, other-2, other-3
        when(graph.getDownstreamProjects(kafkaModule, true))
                .thenReturn(List.of(otherModules.get(0), otherModules.get(1), otherModules.get(2)));
        // camel-core is upstream of kafka and all others
        when(graph.getUpstreamProjects(kafkaModule, true)).thenReturn(List.of(coreModule));
        when(graph.getUpstreamProjects(debeziumModule, true)).thenReturn(List.of(coreModule));
        when(graph.getUpstreamProjects(ibmModule, true)).thenReturn(List.of(coreModule));
        for (MavenProject other : otherModules) {
            when(graph.getUpstreamProjects(other, true)).thenReturn(List.of(coreModule));
        }
        when(graph.getSortedProjects()).thenReturn(allProjects);
        when(session.getProjectDependencyGraph()).thenReturn(graph);

        // Run
        participant.afterProjectsRead(session);

        // Verify report
        Path reportFile = root.resolve("target/scalpel-report.json");
        assertTrue(Files.exists(reportFile), "Report file should be created");
        String json = new String(Files.readAllBytes(reportFile), StandardCharsets.UTF_8);

        // Print the full report for debugging
        System.out.println("=== SCALPEL REPORT (Camel-like scenario) ===");
        System.out.println(json);
        System.out.println("=== END REPORT ===");

        // Count modules by category
        int directCount = 0, downstreamCount = 0, upstreamCount = 0, transitiveCount = 0, otherCount = 0;
        // Simple counting by looking for category fields
        for (String line : json.split("\n")) {
            if (line.contains("\"category\":")) {
                if (line.contains("\"DIRECT\"")) directCount++;
                else if (line.contains("\"DOWNSTREAM\"")) downstreamCount++;
                else if (line.contains("\"UPSTREAM\"")) upstreamCount++;
                else if (line.contains("\"TRANSITIVE\"")) transitiveCount++;
                else otherCount++;
            }
        }
        System.out.println("Category counts: DIRECT=" + directCount + " DOWNSTREAM=" + downstreamCount + " UPSTREAM="
                + upstreamCount + " TRANSITIVE=" + transitiveCount + " OTHER=" + otherCount);

        // Verify: 3 modules should be DIRECT (camel-kafka, camel-debezium, camel-ibm)
        assertTrue(moduleHasField(json, "camel-kafka", "category", "DIRECT"), "camel-kafka should be DIRECT");
        assertTrue(moduleHasField(json, "camel-debezium", "category", "DIRECT"), "camel-debezium should be DIRECT");
        assertTrue(moduleHasField(json, "camel-ibm", "category", "DIRECT"), "camel-ibm should be DIRECT");

        // camel-other-1,2,3 should be DOWNSTREAM (downstream of camel-kafka)
        assertTrue(
                moduleHasField(json, "camel-other-1", "category", "DOWNSTREAM"), "camel-other-1 should be DOWNSTREAM");
        assertTrue(
                moduleHasField(json, "camel-other-2", "category", "DOWNSTREAM"), "camel-other-2 should be DOWNSTREAM");
        assertTrue(
                moduleHasField(json, "camel-other-3", "category", "DOWNSTREAM"), "camel-other-3 should be DOWNSTREAM");

        // camel-core is a build prerequisite (upstream), NOT genuinely affected.
        // After the fix for #39, UPSTREAM modules are excluded from the report.
        assertFalse(
                modulePresent(json, "camel-core"),
                "camel-core should NOT be in report (it's a build prerequisite, not affected by kafka-version)");

        // KEY ASSERTION: other-4 through other-20 should NOT be in the report!
        // They don't reference kafka-version and aren't downstream of affected modules.
        for (int i = 4; i <= 20; i++) {
            assertFalse(
                    modulePresent(json, "camel-other-" + i),
                    "camel-other-" + i + " should NOT be in report (no kafka dep, not downstream)");
        }

        // Total should be 3 DIRECT + 3 DOWNSTREAM = 6 (no UPSTREAM in report after #39 fix)
        assertEquals(3, directCount, "Should have 3 DIRECT modules");
        assertEquals(3, downstreamCount, "Should have 3 DOWNSTREAM modules");
        assertEquals(0, upstreamCount, "UPSTREAM modules should be excluded from report");
        assertEquals(0, transitiveCount, "Should have 0 TRANSITIVE modules (changedManagedDepGAs is empty)");
    }

    /**
     * Reproduce scalpel#39: The real inflation mechanism.
     *
     * Camel has a "camel-allcomponents" sync-point module that depends on ALL ~459 component
     * modules. When kafka-version changes, camel-kafka becomes DIRECT. Since camel-allcomponents
     * depends on camel-kafka, it becomes DOWNSTREAM. Then alsoMake=true computes
     * getUpstreamProjects(camel-allcomponents, true) which returns ALL ~459 components.
     * Those 459 components are added as UPSTREAM to the report, inflating affectedModules
     * from ~45 useful entries to 649.
     *
     * This test demonstrates the problem: a sync-point module that depends on everything
     * causes the upstream closure to pull the entire reactor into the report.
     */
    @Test
    void reportMode_camelLike_allcomponentsSyncPoint_causesUpstreamInflation() throws Exception {
        Path root = tempDir.resolve("project");
        Files.createDirectories(root);

        // Parent POM with kafka-version property (NOT used in dependencyManagement)
        String oldParentPom = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.apache.camel</groupId>
                  <artifactId>camel-parent</artifactId>
                  <version>4.21.0-SNAPSHOT</version>
                  <packaging>pom</packaging>
                  <properties>
                    <kafka-version>4.3.1</kafka-version>
                  </properties>
                  <dependencyManagement><dependencies>
                    <dependency>
                      <groupId>org.apache.camel</groupId>
                      <artifactId>camel-core</artifactId>
                      <version>${project.version}</version>
                    </dependency>
                  </dependencies></dependencyManagement>
                </project>
                """;
        String newParentPom =
                oldParentPom.replace("<kafka-version>4.3.1</kafka-version>", "<kafka-version>4.3.0</kafka-version>");
        writePom(root, "parent/pom.xml", newParentPom);

        // Root aggregator POM
        StringBuilder rootModules = new StringBuilder();
        rootModules.append("<module>parent</module><module>camel-core</module>");
        rootModules.append("<module>camel-kafka</module><module>camel-allcomponents</module>");
        for (int i = 1; i <= 30; i++) {
            rootModules.append("<module>camel-comp-").append(i).append("</module>");
        }
        String rootPom = "<?xml version=\"1.0\"?>\n<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <groupId>org.apache.camel</groupId>\n<artifactId>camel</artifactId>\n"
                + "  <version>4.21.0-SNAPSHOT</version>\n<packaging>pom</packaging>\n"
                + "  <modules>" + rootModules + "</modules>\n</project>\n";
        writePom(root, "pom.xml", rootPom);

        // camel-core: no kafka dep
        String corePom = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>org.apache.camel</groupId><artifactId>camel-parent</artifactId><version>4.21.0-SNAPSHOT</version></parent>
                  <artifactId>camel-core</artifactId>
                </project>
                """;
        writePom(root, "camel-core/pom.xml", corePom);

        // camel-kafka: uses ${kafka-version} → DIRECT
        String kafkaPom = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>org.apache.camel</groupId><artifactId>camel-parent</artifactId><version>4.21.0-SNAPSHOT</version></parent>
                  <artifactId>camel-kafka</artifactId>
                  <dependencies>
                    <dependency><groupId>org.apache.kafka</groupId><artifactId>kafka-clients</artifactId><version>${kafka-version}</version></dependency>
                  </dependencies>
                </project>
                """;
        writePom(root, "camel-kafka/pom.xml", kafkaPom);

        // 30 component modules: no kafka dep, depend on camel-core
        for (int i = 1; i <= 30; i++) {
            String compPom = """
                    <?xml version="1.0"?>
                    <project>
                      <modelVersion>4.0.0</modelVersion>
                      <parent><groupId>org.apache.camel</groupId><artifactId>camel-parent</artifactId><version>4.21.0-SNAPSHOT</version></parent>
                      <artifactId>camel-comp-PLACEHOLDER</artifactId>
                    </project>
                    """.replace("PLACEHOLDER", String.valueOf(i));
            writePom(root, "camel-comp-" + i + "/pom.xml", compPom);
        }

        // camel-allcomponents: sync-point that depends on ALL components + camel-kafka
        StringBuilder allcompDeps = new StringBuilder();
        allcompDeps.append(
                "<dependency><groupId>org.apache.camel</groupId><artifactId>camel-kafka</artifactId></dependency>");
        for (int i = 1; i <= 30; i++) {
            allcompDeps
                    .append("<dependency><groupId>org.apache.camel</groupId><artifactId>camel-comp-")
                    .append(i)
                    .append("</artifactId></dependency>");
        }
        String allcompPom = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>org.apache.camel</groupId><artifactId>camel-parent</artifactId><version>4.21.0-SNAPSHOT</version></parent>
                  <artifactId>camel-allcomponents</artifactId>
                  <packaging>pom</packaging>
                  <dependencies>DEPS_PLACEHOLDER</dependencies>
                </project>
                """.replace("DEPS_PLACEHOLDER", allcompDeps.toString());
        writePom(root, "camel-allcomponents/pom.xml", allcompPom);

        // Build MavenProject objects
        MavenProject rootProject =
                createProject("org.apache.camel", "camel", "4.21.0-SNAPSHOT", root, "pom.xml", rootPom);
        rootProject.getModel().setPackaging("pom");

        MavenProject parentProject = createProject(
                "org.apache.camel", "camel-parent", "4.21.0-SNAPSHOT", root, "parent/pom.xml", newParentPom);
        parentProject.getModel().setPackaging("pom");
        parentProject.setParent(rootProject);

        MavenProject coreModule =
                createProject("org.apache.camel", "camel-core", "4.21.0-SNAPSHOT", root, "camel-core/pom.xml", corePom);
        coreModule.setParent(parentProject);

        MavenProject kafkaModule = createProject(
                "org.apache.camel", "camel-kafka", "4.21.0-SNAPSHOT", root, "camel-kafka/pom.xml", kafkaPom);
        kafkaModule.setParent(parentProject);

        List<MavenProject> compModules = new java.util.ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            String compPomStr = new String(
                    Files.readAllBytes(root.resolve("camel-comp-" + i + "/pom.xml")), StandardCharsets.UTF_8);
            MavenProject comp = createProject(
                    "org.apache.camel",
                    "camel-comp-" + i,
                    "4.21.0-SNAPSHOT",
                    root,
                    "camel-comp-" + i + "/pom.xml",
                    compPomStr);
            comp.setParent(parentProject);
            compModules.add(comp);
        }

        MavenProject allcompModule = createProject(
                "org.apache.camel",
                "camel-allcomponents",
                "4.21.0-SNAPSHOT",
                root,
                "camel-allcomponents/pom.xml",
                allcompPom);
        allcompModule.getModel().setPackaging("pom");
        allcompModule.setParent(parentProject);

        List<MavenProject> allProjects = new java.util.ArrayList<>();
        allProjects.add(rootProject);
        allProjects.add(parentProject);
        allProjects.add(coreModule);
        allProjects.add(kafkaModule);
        allProjects.addAll(compModules);
        allProjects.add(allcompModule);

        // Mock ScalpelCore: only parent/pom.xml changed
        Set<String> changedFiles = new LinkedHashSet<>();
        changedFiles.add("parent/pom.xml");
        Map<String, byte[]> oldPoms = new HashMap<>();
        oldPoms.put("parent/pom.xml", oldParentPom.getBytes(StandardCharsets.UTF_8));
        when(scalpelCore.detectChanges(any(), any(), any()))
                .thenReturn(new ChangeDetectionResult(changedFiles, oldPoms));
        setupEmptyDependencyResolution();

        // Session
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
        when(session.getRepositorySession()).thenReturn(mock(org.eclipse.aether.RepositorySystemSession.class));

        // Dependency graph simulating the real Camel structure:
        // - camel-kafka is downstream-ed by camel-allcomponents (allcomponents depends on kafka)
        // - camel-allcomponents upstream includes ALL 30 comp modules + camel-kafka + camel-core
        ProjectDependencyGraph graph = mock(ProjectDependencyGraph.class);
        when(graph.getDownstreamProjects(any(), anyBoolean())).thenReturn(List.of());
        when(graph.getUpstreamProjects(any(), anyBoolean())).thenReturn(List.of());

        // camel-kafka's downstream includes camel-allcomponents (the sync point depends on it)
        when(graph.getDownstreamProjects(kafkaModule, true)).thenReturn(List.of(allcompModule));

        // camel-allcomponents upstream = ALL components + kafka + core (it depends on everything)
        List<MavenProject> allcompUpstream = new java.util.ArrayList<>();
        allcompUpstream.add(kafkaModule);
        allcompUpstream.add(coreModule);
        allcompUpstream.addAll(compModules);
        when(graph.getUpstreamProjects(allcompModule, true)).thenReturn(allcompUpstream);

        // Each component has camel-core as upstream
        when(graph.getUpstreamProjects(kafkaModule, true)).thenReturn(List.of(coreModule));
        for (MavenProject comp : compModules) {
            when(graph.getUpstreamProjects(comp, true)).thenReturn(List.of(coreModule));
        }
        when(graph.getSortedProjects()).thenReturn(allProjects);
        when(session.getProjectDependencyGraph()).thenReturn(graph);

        // Run
        participant.afterProjectsRead(session);

        // Read report
        Path reportFile = root.resolve("target/scalpel-report.json");
        assertTrue(Files.exists(reportFile), "Report file should be created");
        String json = new String(Files.readAllBytes(reportFile), StandardCharsets.UTF_8);

        // Count modules by category
        int directCount = 0, downstreamCount = 0, upstreamCount = 0, transitiveCount = 0;
        for (String line : json.split("\n")) {
            if (line.contains("\"category\":")) {
                if (line.contains("\"DIRECT\"")) directCount++;
                else if (line.contains("\"DOWNSTREAM\"")) downstreamCount++;
                else if (line.contains("\"UPSTREAM\"")) upstreamCount++;
                else if (line.contains("\"TRANSITIVE\"")) transitiveCount++;
            }
        }

        System.out.println("=== SCALPEL#39 FIX VERIFICATION ===");
        System.out.println("DIRECT=" + directCount + " DOWNSTREAM=" + downstreamCount + " UPSTREAM=" + upstreamCount
                + " TRANSITIVE=" + transitiveCount);
        System.out.println(
                "Total affectedModules = " + (directCount + downstreamCount + upstreamCount + transitiveCount));
        System.out.println("=== END ===");

        // Only camel-kafka is DIRECT (references ${kafka-version})
        assertEquals(1, directCount, "Only camel-kafka should be DIRECT");
        assertTrue(moduleHasField(json, "camel-kafka", "category", "DIRECT"));

        // camel-allcomponents is DOWNSTREAM (depends on camel-kafka)
        assertEquals(1, downstreamCount, "camel-allcomponents should be DOWNSTREAM");
        assertTrue(moduleHasField(json, "camel-allcomponents", "category", "DOWNSTREAM"));

        // FIX: UPSTREAM modules should NOT be in the report.
        // Before the fix, all 30 comp modules + camel-core would appear as UPSTREAM (31 total).
        // After the fix, they are excluded — they're build-order prerequisites, not affected modules.
        assertEquals(
                0,
                upstreamCount,
                "UPSTREAM modules should be excluded from report (they are build prerequisites, not affected modules)");

        // Verify no unrelated modules leaked into the report
        for (int i = 1; i <= 30; i++) {
            assertFalse(
                    modulePresent(json, "camel-comp-" + i),
                    "camel-comp-" + i + " should NOT be in report (not affected by kafka-version change)");
        }
        assertFalse(
                modulePresent(json, "camel-core"),
                "camel-core should NOT be in report (it's a build prerequisite, not affected)");

        // Total should be exactly 2: 1 DIRECT + 1 DOWNSTREAM
        int total = directCount + downstreamCount + upstreamCount + transitiveCount;
        assertEquals(2, total, "Report should contain only genuinely affected modules");
    }
}
