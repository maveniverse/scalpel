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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import eu.maveniverse.maven.scalpel.core.ChangeDetectionResult;
import eu.maveniverse.maven.scalpel.core.ScalpelCore;
import eu.maveniverse.maven.scalpel.core.ScalpelException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.execution.ProjectDependencyGraph;
import org.apache.maven.model.Build;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.Profile;
import org.apache.maven.model.Repository;
import org.apache.maven.model.building.DefaultModelBuilderFactory;
import org.apache.maven.model.building.DefaultModelBuildingRequest;
import org.apache.maven.model.building.FileModelSource;
import org.apache.maven.model.building.ModelBuildingException;
import org.apache.maven.model.building.ModelBuildingRequest;
import org.apache.maven.model.building.ModelSource;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.model.resolution.InvalidRepositoryException;
import org.apache.maven.model.resolution.UnresolvableModelException;
import org.apache.maven.project.DefaultDependencyResolutionRequest;
import org.apache.maven.project.DependencyResolutionException;
import org.apache.maven.project.DependencyResolutionResult;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectDependenciesResolver;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.DefaultDependencyNode;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.impl.RemoteRepositoryManager;
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

    /**
     * Maps GAV coordinates ("groupId:artifactId:version") to POM files available in the
     * test reactor. Populated by {@link #createProject} so that both the mock RepositorySystem
     * (for old effective models) and the ReactorModelResolver (for new effective models set
     * by {@link #setEffectiveModel}) can resolve parent POMs and BOM imports.
     */
    private final Map<String, File> reactorPomFiles = new HashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        reactorPomFiles.clear();
        scalpelCore = mock(ScalpelCore.class);
        dependenciesResolver = mock(ProjectDependenciesResolver.class);
        // Configure mock RepositorySystem to resolve POM artifacts from the test reactor.
        // When a GAV matches a reactor project (registered via reactorPomFiles), the mock
        // returns the POM file. This allows buildEffectiveModels to resolve parent POMs
        // and BOM imports in old effective models, matching production behavior.
        RepositorySystem repositorySystem = mock(RepositorySystem.class);
        when(repositorySystem.resolveArtifact(any(), any(org.eclipse.aether.resolution.ArtifactRequest.class)))
                .thenAnswer(invocation -> {
                    org.eclipse.aether.resolution.ArtifactRequest request = invocation.getArgument(1);
                    org.eclipse.aether.artifact.Artifact artifact = request.getArtifact();
                    String key = artifact.getGroupId() + ":" + artifact.getArtifactId() + ":" + artifact.getVersion();
                    File pomFile = reactorPomFiles.get(key);
                    if (pomFile != null && pomFile.exists()) {
                        org.eclipse.aether.resolution.ArtifactResult result =
                                new org.eclipse.aether.resolution.ArtifactResult(request);
                        result.setArtifact(new DefaultArtifact(
                                        artifact.getGroupId(),
                                        artifact.getArtifactId(),
                                        artifact.getClassifier(),
                                        artifact.getExtension(),
                                        artifact.getVersion())
                                .setFile(pomFile));
                        return result;
                    }
                    throw new org.eclipse.aether.resolution.ArtifactResolutionException(
                            List.of(new org.eclipse.aether.resolution.ArtifactResult(request)), "not in test reactor");
                });
        participant = new ScalpelLifecycleParticipant(
                scalpelCore,
                new ModuleMapper(),
                new PomChangeAnalyzer(
                        repositorySystem,
                        mock(RemoteRepositoryManager.class),
                        new org.apache.maven.model.building.DefaultModelBuilderFactory().newInstance()),
                new ReactorTrimmer(),
                dependenciesResolver);
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

        // commons-lang dependency used for transitive resolution (old vs new version)
        org.eclipse.aether.graph.Dependency commonsLangNew = new org.eclipse.aether.graph.Dependency(
                new DefaultArtifact("commons-lang", "commons-lang", "jar", "2.0"), "compile");
        org.eclipse.aether.graph.Dependency commonsLangOld = new org.eclipse.aether.graph.Dependency(
                new DefaultArtifact("commons-lang", "commons-lang", "jar", "1.0"), "compile");

        // Route resolution calls:
        // module-b and module-d: resolution succeeds, has commons-lang
        //   - "new" resolution (real project): commons-lang:2.0
        //   - "old" resolution (temp copy): commons-lang:1.0
        // module-e: resolution fails with empty partial results (simulates unresolvable deps)
        // others: resolution succeeds, no matching deps
        when(dependenciesResolver.resolve(any(DefaultDependencyResolutionRequest.class)))
                .thenAnswer(invocation -> {
                    DefaultDependencyResolutionRequest req = invocation.getArgument(0);
                    MavenProject reqProject = req.getMavenProject();
                    String aid = reqProject.getArtifactId();
                    boolean isOldResolution = allProjects.stream().noneMatch(p -> p == reqProject);
                    if ("module-b".equals(aid) || "module-d".equals(aid)) {
                        DependencyResolutionResult res = mock(DependencyResolutionResult.class);
                        when(res.getDependencyGraph())
                                .thenReturn(createDependencyGraph(isOldResolution ? commonsLangOld : commonsLangNew));
                        return res;
                    }
                    if ("module-e".equals(aid)) {
                        DependencyResolutionResult partial = mock(DependencyResolutionResult.class);
                        when(partial.getDependencyGraph()).thenReturn(createDependencyGraph());
                        throw new DependencyResolutionException(partial, "Cannot resolve", new Exception());
                    }
                    DependencyResolutionResult empty = mock(DependencyResolutionResult.class);
                    when(empty.getDependencyGraph()).thenReturn(createDependencyGraph());
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
        when(emptyResolution.getDependencyGraph()).thenReturn(createDependencyGraph());
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
        when(emptyResolution.getDependencyGraph()).thenReturn(createDependencyGraph());
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
        when(emptyResolution.getDependencyGraph()).thenReturn(createDependencyGraph());
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

        // module-b: commons-lang is only via test scope (old=1.0, new=2.0)
        org.eclipse.aether.graph.Dependency testDepNew = new org.eclipse.aether.graph.Dependency(
                new DefaultArtifact("commons-lang", "commons-lang", "jar", "2.0"), "test");
        org.eclipse.aether.graph.Dependency testDepOld = new org.eclipse.aether.graph.Dependency(
                new DefaultArtifact("commons-lang", "commons-lang", "jar", "1.0"), "test");

        when(dependenciesResolver.resolve(any(DefaultDependencyResolutionRequest.class)))
                .thenAnswer(invocation -> {
                    DefaultDependencyResolutionRequest req = invocation.getArgument(0);
                    MavenProject reqProject = req.getMavenProject();
                    boolean isOldResolution = allProjects.stream().noneMatch(p -> p == reqProject);
                    if ("module-b".equals(reqProject.getArtifactId())) {
                        DependencyResolutionResult res = mock(DependencyResolutionResult.class);
                        when(res.getDependencyGraph())
                                .thenReturn(createDependencyGraph(isOldResolution ? testDepOld : testDepNew));
                        return res;
                    }
                    DependencyResolutionResult empty = mock(DependencyResolutionResult.class);
                    when(empty.getDependencyGraph()).thenReturn(createDependencyGraph());
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
        when(emptyResolution.getDependencyGraph()).thenReturn(createDependencyGraph());
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
    void trimMode_reportListsSkippedModulesWithNotAffectedReason() throws Exception {
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

        Path reportFile = root.resolve("target/scalpel-report.json");
        assertTrue(Files.exists(reportFile), "trim mode should write the JSON report");

        String json = new String(Files.readAllBytes(reportFile), StandardCharsets.UTF_8);

        // module-b was trimmed out of the build: it must be enumerated with a reason
        assertTrue(json.contains("\"skippedModules\""), "report must contain skippedModules array");
        assertTrue(
                skippedModuleHasReason(json, "module-b", "NOT_AFFECTED"),
                "module-b should be in skippedModules with NOT_AFFECTED");
        // the affected module must not appear in the skipped set
        assertFalse(
                skippedModulePresent(json, "module-a"),
                "module-a should NOT be in skippedModules (it is in the build set)");
    }

    @Test
    void trimMode_reportOmitsSkippedModulesWhenNothingSkipped() throws Exception {
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

        // module-a is directly affected; the parent is force-included so nothing is skipped
        Set<String> changedFiles = new LinkedHashSet<>();
        changedFiles.add("module-a/src/main/java/Foo.java");
        when(scalpelCore.detectChanges(any(), any(), any()))
                .thenReturn(new ChangeDetectionResult(changedFiles, new HashMap<>()));
        setupEmptyDependencyResolution();

        MavenSession session = createSimpleSession(root, allProjects, "trim");
        session.getSystemProperties().setProperty("scalpel.forceBuildModules", "parent");

        participant.afterProjectsRead(session);

        Path reportFile = root.resolve("target/scalpel-report.json");
        assertTrue(Files.exists(reportFile), "trim mode should write the JSON report");

        String json = new String(Files.readAllBytes(reportFile), StandardCharsets.UTF_8);
        assertFalse(json.contains("\"skippedModules\""), "skippedModules must be omitted when nothing is skipped");
    }

    private boolean skippedModulePresent(String json, String artifactId) {
        String skippedSection = extractSection(json, "skippedModules");
        return skippedSection != null && skippedSection.contains("\"artifactId\": \"" + artifactId + "\"");
    }

    private boolean skippedModuleHasReason(String json, String artifactId, String reason) {
        String skippedSection = extractSection(json, "skippedModules");
        if (skippedSection == null) {
            return false;
        }
        String marker = "\"artifactId\": \"" + artifactId + "\"";
        int idx = skippedSection.indexOf(marker);
        if (idx < 0) {
            return false;
        }
        int end = skippedSection.indexOf("}", idx);
        if (end < 0) {
            return false;
        }
        return skippedSection.substring(idx, end).contains("\"reason\": \"" + reason + "\"");
    }

    private String extractSection(String json, String sectionName) {
        int start = json.indexOf("\"" + sectionName + "\":");
        if (start < 0) {
            return null;
        }
        int bracketStart = json.indexOf("[", start);
        if (bracketStart < 0) {
            return null;
        }
        int depth = 0;
        for (int i = bracketStart; i < json.length(); i++) {
            if (json.charAt(i) == '[') {
                depth++;
            }
            if (json.charAt(i) == ']') {
                depth--;
            }
            if (depth == 0) {
                return json.substring(bracketStart, i + 1);
            }
        }
        return null;
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
    void skipTestsMode_softensTestSkipForInReactorTestJarConsumer() throws Exception {
        // module-b is the changed module and depends on module-common's main jar only, so
        // module-common becomes upstream-only (skip-test candidate with skipTestsForUpstream).
        // module-a depends on module-b (so it stays in the "will run tests" set as a downstream
        // module) and also consumes module-common's test-jar. If module-common got
        // maven.test.skip=true, its test-compile (and therefore its test-jar) would be
        // suppressed, breaking module-a. Scalpel must soften module-common to skipTests=true
        // instead, which only skips the surefire/failsafe execution.
        Path root = tempDir.resolve("project");
        Files.createDirectories(root);

        String parentPom = simpleParentPom("module-common", "module-a", "module-b");
        writePom(root, "pom.xml", parentPom);
        String moduleCommonPom = simpleChildPom("module-common");
        writePom(root, "module-common/pom.xml", moduleCommonPom);
        String moduleAPom = simpleChildPom("module-a");
        writePom(root, "module-a/pom.xml", moduleAPom);
        String moduleBPom = simpleChildPomWithDep("module-b", "module-common");
        writePom(root, "module-b/pom.xml", moduleBPom);

        MavenProject parentProject = createProject("com.example", "parent", "1.0", root, "pom.xml", parentPom);
        parentProject.getModel().setPackaging("pom");
        MavenProject moduleCommon =
                createProject("com.example", "module-common", "1.0", root, "module-common/pom.xml", moduleCommonPom);
        moduleCommon.setParent(parentProject);
        MavenProject moduleA = createProject("com.example", "module-a", "1.0", root, "module-a/pom.xml", moduleAPom);
        moduleA.setParent(parentProject);
        MavenProject moduleB = createProject("com.example", "module-b", "1.0", root, "module-b/pom.xml", moduleBPom);
        moduleB.setParent(parentProject);

        // module-a depends on module-b (compile) and on module-common's test-jar (test scope)
        Dependency depOnB = new Dependency();
        depOnB.setGroupId("com.example");
        depOnB.setArtifactId("module-b");
        depOnB.setVersion("1.0");
        moduleA.getDependencies().add(depOnB);
        addTestJarDependency(moduleA, "module-common");

        List<MavenProject> allProjects = List.of(parentProject, moduleCommon, moduleA, moduleB);

        Set<String> changedFiles = new LinkedHashSet<>();
        changedFiles.add("module-b/src/main/java/Bar.java");
        when(scalpelCore.detectChanges(any(), any(), any()))
                .thenReturn(new ChangeDetectionResult(changedFiles, new HashMap<>()));
        setupEmptyDependencyResolution();

        MavenSession session = createSimpleSession(root, allProjects, "skip-tests");
        session.getSystemProperties().setProperty("scalpel.skipTestsForUpstream", "true");
        ProjectDependencyGraph graph = session.getProjectDependencyGraph();
        when(graph.getDownstreamProjects(eq(moduleB), anyBoolean())).thenReturn(List.of(moduleA));
        when(graph.getUpstreamProjects(eq(moduleB), anyBoolean())).thenReturn(List.of(moduleCommon));
        when(graph.getUpstreamProjects(eq(moduleA), anyBoolean())).thenReturn(List.of(moduleB, moduleCommon));

        participant.afterProjectsRead(session);

        assertFalse(
                "true".equals(moduleCommon.getProperties().getProperty("maven.test.skip")),
                "module-common must NOT have maven.test.skip=true (its test-jar is consumed in-reactor)");
        assertTrue(
                "true".equals(moduleCommon.getProperties().getProperty("skipTests")),
                "module-common should have skipTests=true (softened skip)");
        assertFalse(
                "true".equals(moduleA.getProperties().getProperty("maven.test.skip")),
                "module-a should run tests (it is a test-jar consumer, not a skip candidate)");
    }

    @Test
    void skipTestsMode_softensChainedTestJarProducersViaFixpointIteration() throws Exception {
        // module-x is the changed module (directly affected, runs tests).
        // module-b is upstream-only (skip candidate) and its test-jar is consumed by module-x.
        // module-a is upstream-only (skip candidate) and its test-jar is consumed by module-b.
        // module-a is ordered BEFORE module-b in the reactor (and therefore in skippedProjects),
        // so on the first while-pass module-b has not been softened yet when module-a is
        // checked, and module-a is NOT softened; only the second pass (after module-b is
        // softened) can soften module-a. A single-pass implementation would leave module-a with
        // maven.test.skip=true, which would break module-b's test-compile.
        Path root = tempDir.resolve("project");
        Files.createDirectories(root);

        String parentPom = simpleParentPom("module-a", "module-b", "module-x");
        writePom(root, "pom.xml", parentPom);
        String moduleAPom = simpleChildPom("module-a");
        writePom(root, "module-a/pom.xml", moduleAPom);
        String moduleBPom = simpleChildPom("module-b");
        writePom(root, "module-b/pom.xml", moduleBPom);
        String moduleXPom = simpleChildPom("module-x");
        writePom(root, "module-x/pom.xml", moduleXPom);

        MavenProject parentProject = createProject("com.example", "parent", "1.0", root, "pom.xml", parentPom);
        parentProject.getModel().setPackaging("pom");
        MavenProject moduleA = createProject("com.example", "module-a", "1.0", root, "module-a/pom.xml", moduleAPom);
        moduleA.setParent(parentProject);
        MavenProject moduleB = createProject("com.example", "module-b", "1.0", root, "module-b/pom.xml", moduleBPom);
        moduleB.setParent(parentProject);
        MavenProject moduleX = createProject("com.example", "module-x", "1.0", root, "module-x/pom.xml", moduleXPom);
        moduleX.setParent(parentProject);

        // module-b consumes module-a's test-jar; module-x consumes module-b's test-jar.
        addTestJarDependency(moduleB, "module-a");

        addTestJarDependency(moduleX, "module-b");

        // Reactor (and sortedProjects) order places module-a before module-b before module-x,
        // which drives the order candidates are visited within skippedProjects.
        List<MavenProject> allProjects = List.of(parentProject, moduleA, moduleB, moduleX);

        Set<String> changedFiles = new LinkedHashSet<>();
        changedFiles.add("module-x/src/main/java/Baz.java");
        when(scalpelCore.detectChanges(any(), any(), any()))
                .thenReturn(new ChangeDetectionResult(changedFiles, new HashMap<>()));
        setupEmptyDependencyResolution();

        MavenSession session = createSimpleSession(root, allProjects, "skip-tests");
        session.getSystemProperties().setProperty("scalpel.skipTestsForUpstream", "true");
        ProjectDependencyGraph graph = session.getProjectDependencyGraph();
        // module-a and module-b are both (transitively) upstream of the changed module-x.
        when(graph.getUpstreamProjects(eq(moduleX), anyBoolean())).thenReturn(List.of(moduleB, moduleA));

        participant.afterProjectsRead(session);

        assertFalse(
                "true".equals(moduleA.getProperties().getProperty("maven.test.skip")),
                "module-a must NOT have maven.test.skip=true (its test-jar is consumed in-reactor via "
                        + "module-b, only reachable on the second fixpoint pass)");
        assertTrue(
                "true".equals(moduleA.getProperties().getProperty("skipTests")),
                "module-a should have skipTests=true (softened skip, second fixpoint pass)");
        assertFalse(
                "true".equals(moduleB.getProperties().getProperty("maven.test.skip")),
                "module-b must NOT have maven.test.skip=true (its test-jar is consumed in-reactor by module-x)");
        assertTrue(
                "true".equals(moduleB.getProperties().getProperty("skipTests")),
                "module-b should have skipTests=true (softened skip, first fixpoint pass)");
    }

    @Test
    void skipTestsMode_softensTestJarProducerOutsideBuildSet() throws Exception {
        // module-c is the changed module (directly affected, in includePaths scope).
        // module-p has no reactor dependency relation to module-c (not upstream/downstream), so
        // it never enters trimResult.getBuildSet() at all: it is only visited by the SECOND loop
        // in applySkipTests (over allProjects, skipping buildSet members), where it gets skipped
        // because it falls outside includePaths scope. module-c still consumes module-p's
        // test-jar, so softenTestJarProducers must reach into that second-loop population and
        // soften module-p, not just first-loop (buildSet) candidates.
        Path root = tempDir.resolve("project");
        Files.createDirectories(root);

        String parentPom = simpleParentPom("module-c", "module-p");
        writePom(root, "pom.xml", parentPom);
        String moduleCPom = simpleChildPom("module-c");
        writePom(root, "module-c/pom.xml", moduleCPom);
        String modulePPom = simpleChildPom("module-p");
        writePom(root, "module-p/pom.xml", modulePPom);

        MavenProject parentProject = createProject("com.example", "parent", "1.0", root, "pom.xml", parentPom);
        parentProject.getModel().setPackaging("pom");
        MavenProject moduleC = createProject("com.example", "module-c", "1.0", root, "module-c/pom.xml", moduleCPom);
        moduleC.setParent(parentProject);
        MavenProject moduleP = createProject("com.example", "module-p", "1.0", root, "module-p/pom.xml", modulePPom);
        moduleP.setParent(parentProject);

        // module-c consumes module-p's test-jar even though module-p has no build-graph relation
        // to module-c.
        addTestJarDependency(moduleC, "module-p");

        List<MavenProject> allProjects = List.of(parentProject, moduleC, moduleP);

        Set<String> changedFiles = new LinkedHashSet<>();
        changedFiles.add("module-c/src/main/java/Foo.java");
        when(scalpelCore.detectChanges(any(), any(), any()))
                .thenReturn(new ChangeDetectionResult(changedFiles, new HashMap<>()));
        setupEmptyDependencyResolution();

        MavenSession session = createSimpleSession(root, allProjects, "skip-tests");
        // module-p is outside includePaths scope; module-c is not, so it stays directly affected.
        session.getSystemProperties().setProperty("scalpel.includePaths", "module-c/**");

        participant.afterProjectsRead(session);

        assertFalse(
                "true".equals(moduleP.getProperties().getProperty("maven.test.skip")),
                "module-p must NOT have maven.test.skip=true (its test-jar is consumed in-reactor by "
                        + "module-c, even though module-p is outside the trimmed build set)");
        assertTrue(
                "true".equals(moduleP.getProperties().getProperty("skipTests")),
                "module-p should have skipTests=true (softened skip reached from the second loop population)");
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

        // Mock dependency resolution: module-b has commons-lang transitively (old=1.0, new=2.0)
        org.eclipse.aether.graph.Dependency commonsLangNew = new org.eclipse.aether.graph.Dependency(
                new DefaultArtifact("commons-lang", "commons-lang", "jar", "2.0"), "compile");
        org.eclipse.aether.graph.Dependency commonsLangOld = new org.eclipse.aether.graph.Dependency(
                new DefaultArtifact("commons-lang", "commons-lang", "jar", "1.0"), "compile");

        when(dependenciesResolver.resolve(any(DefaultDependencyResolutionRequest.class)))
                .thenAnswer(invocation -> {
                    DefaultDependencyResolutionRequest req = invocation.getArgument(0);
                    MavenProject reqProject = req.getMavenProject();
                    boolean isOldResolution = allProjects.stream().noneMatch(p -> p == reqProject);
                    if ("module-b".equals(reqProject.getArtifactId())) {
                        DependencyResolutionResult res = mock(DependencyResolutionResult.class);
                        when(res.getDependencyGraph())
                                .thenReturn(createDependencyGraph(isOldResolution ? commonsLangOld : commonsLangNew));
                        return res;
                    }
                    DependencyResolutionResult empty = mock(DependencyResolutionResult.class);
                    when(empty.getDependencyGraph()).thenReturn(createDependencyGraph());
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

        // module-b has commons-lang transitively (old=1.0, new=2.0)
        org.eclipse.aether.graph.Dependency commonsLangNew = new org.eclipse.aether.graph.Dependency(
                new DefaultArtifact("commons-lang", "commons-lang", "jar", "2.0"), "compile");
        org.eclipse.aether.graph.Dependency commonsLangOld = new org.eclipse.aether.graph.Dependency(
                new DefaultArtifact("commons-lang", "commons-lang", "jar", "1.0"), "compile");
        when(dependenciesResolver.resolve(any(DefaultDependencyResolutionRequest.class)))
                .thenAnswer(invocation -> {
                    DefaultDependencyResolutionRequest req = invocation.getArgument(0);
                    MavenProject reqProject = req.getMavenProject();
                    boolean isOldResolution = allProjects.stream().noneMatch(p -> p == reqProject);
                    if ("module-b".equals(reqProject.getArtifactId())) {
                        DependencyResolutionResult res = mock(DependencyResolutionResult.class);
                        when(res.getDependencyGraph())
                                .thenReturn(createDependencyGraph(isOldResolution ? commonsLangOld : commonsLangNew));
                        return res;
                    }
                    DependencyResolutionResult empty = mock(DependencyResolutionResult.class);
                    when(empty.getDependencyGraph()).thenReturn(createDependencyGraph());
                    return empty;
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
        // module-b declares the compiler plugin (version comes from parent's pluginManagement)
        String moduleBPom = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>module-b</artifactId>
                  <dependencies>
                    <dependency><groupId>com.example</groupId><artifactId>module-a</artifactId><version>1.0</version></dependency>
                  </dependencies>
                  <build><plugins>
                    <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId></plugin>
                  </plugins></build>
                </project>
                """;
        writePom(root, "module-b/pom.xml", moduleBPom);

        MavenProject parentProject = createProject("com.example", "parent", "1.0", root, "pom.xml", newParentPom);
        parentProject.getModel().setPackaging("pom");
        MavenProject moduleA = createProject("com.example", "module-a", "1.0", root, "module-a/pom.xml", moduleAPom);
        moduleA.setParent(parentProject);
        MavenProject moduleB = createProject("com.example", "module-b", "1.0", root, "module-b/pom.xml", moduleBPom);
        moduleB.setParent(parentProject);

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
    void reportMode_includePathsFiltersModules() throws Exception {
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
        changedFiles.add("module-b/src/main/java/Bar.java");
        when(scalpelCore.detectChanges(any(), any(), any()))
                .thenReturn(new ChangeDetectionResult(changedFiles, new HashMap<>()));
        setupEmptyDependencyResolution();

        MavenSession session = createSimpleSession(root, allProjects, "report");
        session.getSystemProperties().setProperty("scalpel.includePaths", "module-a/**");

        participant.afterProjectsRead(session);

        Path reportFile = root.resolve("target/scalpel-report.json");
        assertTrue(Files.exists(reportFile));
        String json = new String(Files.readAllBytes(reportFile), StandardCharsets.UTF_8);
        assertTrue(modulePresent(json, "module-a"), "module-a should be in report (matches includePaths)");
        assertFalse(modulePresent(json, "module-b"), "module-b should NOT be in report (does not match includePaths)");
    }

    @Test
    void reportMode_includePathsMultiplePatterns() throws Exception {
        Path root = tempDir.resolve("project");
        Files.createDirectories(root);

        String parentPom = simpleParentPom("module-a", "module-b", "module-c");
        writePom(root, "pom.xml", parentPom);
        String moduleAPom = simpleChildPom("module-a");
        writePom(root, "module-a/pom.xml", moduleAPom);
        String moduleBPom = simpleChildPom("module-b");
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

        Set<String> changedFiles = new LinkedHashSet<>();
        changedFiles.add("module-a/src/main/java/Foo.java");
        changedFiles.add("module-b/src/main/java/Bar.java");
        changedFiles.add("module-c/src/main/java/Baz.java");
        when(scalpelCore.detectChanges(any(), any(), any()))
                .thenReturn(new ChangeDetectionResult(changedFiles, new HashMap<>()));
        setupEmptyDependencyResolution();

        MavenSession session = createSimpleSession(root, allProjects, "report");
        session.getSystemProperties().setProperty("scalpel.includePaths", "module-a/**,module-c/**");

        participant.afterProjectsRead(session);

        Path reportFile = root.resolve("target/scalpel-report.json");
        assertTrue(Files.exists(reportFile));
        String json = new String(Files.readAllBytes(reportFile), StandardCharsets.UTF_8);
        assertTrue(modulePresent(json, "module-a"), "module-a should be in report");
        assertFalse(modulePresent(json, "module-b"), "module-b should NOT be in report");
        assertTrue(modulePresent(json, "module-c"), "module-c should be in report");
    }

    @Test
    void reportMode_includePathsNotSetIncludesAll() throws Exception {
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
        changedFiles.add("module-b/src/main/java/Bar.java");
        when(scalpelCore.detectChanges(any(), any(), any()))
                .thenReturn(new ChangeDetectionResult(changedFiles, new HashMap<>()));
        setupEmptyDependencyResolution();

        // No includePaths set — all modules should be included
        MavenSession session = createSimpleSession(root, allProjects, "report");

        participant.afterProjectsRead(session);

        Path reportFile = root.resolve("target/scalpel-report.json");
        assertTrue(Files.exists(reportFile));
        String json = new String(Files.readAllBytes(reportFile), StandardCharsets.UTF_8);
        assertTrue(modulePresent(json, "module-a"), "module-a should be in report");
        assertTrue(modulePresent(json, "module-b"), "module-b should be in report");
    }

    @Test
    void reportMode_includePathsCombinedWithExcludePaths() throws Exception {
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
        changedFiles.add("module-a/README.md");
        changedFiles.add("module-b/src/main/java/Bar.java");
        when(scalpelCore.detectChanges(any(), any(), any()))
                .thenReturn(new ChangeDetectionResult(changedFiles, new HashMap<>()));
        setupEmptyDependencyResolution();

        MavenSession session = createSimpleSession(root, allProjects, "report");
        // Include only module-a modules, but exclude .md files from change detection
        session.getSystemProperties().setProperty("scalpel.includePaths", "module-a/**");
        session.getSystemProperties().setProperty("scalpel.excludePaths", "**/*.md");

        participant.afterProjectsRead(session);

        Path reportFile = root.resolve("target/scalpel-report.json");
        assertTrue(Files.exists(reportFile));
        String json = new String(Files.readAllBytes(reportFile), StandardCharsets.UTF_8);
        assertTrue(modulePresent(json, "module-a"), "module-a should be in report (Foo.java matched)");
        assertFalse(modulePresent(json, "module-b"), "module-b should NOT be in report (not in includePaths)");
        assertFalse(json.contains("README.md"), "README.md should be excluded");
    }

    @Test
    void reportMode_includePathsPreservesFullDiffVisibility() throws Exception {
        // Parent POM in a subdirectory changes a managed dependency version.
        // module-a uses that dependency and should be detected as directly affected,
        // even though the POM change (parent/pom.xml) is outside the includePaths scope.
        // This verifies that includePaths filters MODULES, not changed files.
        Path root = tempDir.resolve("project");
        Files.createDirectories(root);

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
        String newParentPom = oldParentPom.replace("<lib.version>1.0</lib.version>", "<lib.version>2.0</lib.version>");
        writePom(root, "parent/pom.xml", newParentPom);

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

        String moduleBPom = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>module-b</artifactId>
                </project>
                """;
        writePom(root, "module-b/pom.xml", moduleBPom);

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

        // Only parent/pom.xml changed — outside the includePaths scope
        Set<String> changedFiles = new LinkedHashSet<>();
        changedFiles.add("parent/pom.xml");
        Map<String, byte[]> oldPoms = new HashMap<>();
        oldPoms.put("parent/pom.xml", oldParentPom.getBytes(StandardCharsets.UTF_8));
        when(scalpelCore.detectChanges(any(), any(), any()))
                .thenReturn(new ChangeDetectionResult(changedFiles, oldPoms));

        DependencyResolutionResult emptyResolution = mock(DependencyResolutionResult.class);
        when(emptyResolution.getDependencyGraph()).thenReturn(createDependencyGraph());
        when(dependenciesResolver.resolve(any(DefaultDependencyResolutionRequest.class)))
                .thenReturn(emptyResolution);

        MavenSession session = mock(MavenSession.class);
        Properties sysProps = new Properties();
        sysProps.setProperty("scalpel.mode", "report");
        sysProps.setProperty("scalpel.baseBranch", "base");
        sysProps.setProperty("scalpel.includePaths", "module-a/**");
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

        // module-a should be in the report: detected as affected by the parent POM change
        // (managed dependency version change), even though the POM change is outside includePaths
        assertTrue(
                modulePresent(json, "module-a"),
                "module-a should be in report (POM change outside scope still propagates)");

        // module-b should NOT be in report (not affected by the managed dep change, not in includePaths)
        assertFalse(modulePresent(json, "module-b"), "module-b should NOT be in report");

        // parent should NOT be in report (filtered by includePaths)
        assertFalse(modulePresent(json, "parent"), "parent should NOT be in report (outside includePaths)");
    }

    @Test
    void trimMode_includePathsFiltersReactor() throws Exception {
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
        changedFiles.add("module-b/src/main/java/Bar.java");
        when(scalpelCore.detectChanges(any(), any(), any()))
                .thenReturn(new ChangeDetectionResult(changedFiles, new HashMap<>()));
        setupEmptyDependencyResolution();

        MavenSession session = createSimpleSession(root, allProjects, "trim");
        session.getSystemProperties().setProperty("scalpel.includePaths", "module-a/**");

        participant.afterProjectsRead(session);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MavenProject>> captor = ArgumentCaptor.forClass(List.class);
        verify(session).setProjects(captor.capture());
        List<MavenProject> trimmed = captor.getValue();
        assertTrue(trimmed.contains(moduleA), "module-a should be in trimmed reactor (matches includePaths)");
        assertFalse(trimmed.contains(moduleB), "module-b should NOT be in trimmed reactor (outside includePaths)");
    }

    @Test
    void skipTestsMode_includePathsFiltersModules() throws Exception {
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
        changedFiles.add("module-b/src/main/java/Bar.java");
        when(scalpelCore.detectChanges(any(), any(), any()))
                .thenReturn(new ChangeDetectionResult(changedFiles, new HashMap<>()));
        setupEmptyDependencyResolution();

        MavenSession session = createSimpleSession(root, allProjects, "skip-tests");
        session.getSystemProperties().setProperty("scalpel.includePaths", "module-a/**");

        participant.afterProjectsRead(session);

        // module-b is affected by source changes but excluded by includePaths,
        // so it should have tests skipped
        assertTrue(
                "true".equals(moduleB.getProperties().getProperty("maven.test.skip")),
                "module-b should have tests skipped (outside includePaths)");
    }

    @Test
    void trimMode_includePathsExcludesDownstreamOutsideScope() throws Exception {
        // module-a has source changes and matches includePaths.
        // module-b depends on module-a (downstream) but is outside includePaths.
        // The trimmed reactor should NOT include module-b.
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

        // Set up dependency graph: module-b is downstream of module-a
        MavenSession session = createSimpleSession(root, allProjects, "trim");
        ProjectDependencyGraph graph = session.getProjectDependencyGraph();
        when(graph.getDownstreamProjects(eq(moduleA), anyBoolean())).thenReturn(List.of(moduleB));
        session.getSystemProperties().setProperty("scalpel.includePaths", "module-a/**");

        participant.afterProjectsRead(session);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MavenProject>> captor = ArgumentCaptor.forClass(List.class);
        verify(session).setProjects(captor.capture());
        List<MavenProject> trimmed = captor.getValue();
        assertTrue(trimmed.contains(moduleA), "module-a should be in trimmed reactor (matches includePaths)");
        assertFalse(
                trimmed.contains(moduleB),
                "module-b should NOT be in trimmed reactor (downstream but outside includePaths)");
    }

    @Test
    void trimMode_includePathsScopeExcludedModuleAppearsInSkippedModules() throws Exception {
        // module-a has source changes and matches includePaths.
        // module-b depends on module-a (downstream) but is outside includePaths.
        // module-b must appear in skippedModules (NOT_AFFECTED) and NOT in affectedModules.
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

        // Set up dependency graph: module-b is downstream of module-a
        MavenSession session = createSimpleSession(root, allProjects, "trim");
        ProjectDependencyGraph graph = session.getProjectDependencyGraph();
        when(graph.getDownstreamProjects(eq(moduleA), anyBoolean())).thenReturn(List.of(moduleB));
        session.getSystemProperties().setProperty("scalpel.includePaths", "module-a/**");

        participant.afterProjectsRead(session);

        Path reportFile = root.resolve("target/scalpel-report.json");
        assertTrue(Files.exists(reportFile), "trim mode should write the JSON report");

        String json = new String(Files.readAllBytes(reportFile), StandardCharsets.UTF_8);

        // module-b was removed from the trimmed reactor by includePaths:
        // it must appear in skippedModules with NOT_AFFECTED
        assertTrue(
                skippedModuleHasReason(json, "module-b", "NOT_AFFECTED"),
                "module-b should be in skippedModules with NOT_AFFECTED (downstream but outside includePaths)");
        // module-a should NOT be in skippedModules (it is in the build set)
        assertFalse(
                skippedModulePresent(json, "module-a"),
                "module-a should NOT be in skippedModules (it is in the filtered build set)");
        // module-b should NOT be in affectedModules (filtered by report-side includePaths)
        assertFalse(
                modulePresent(json, "module-b"), "module-b should NOT be in affectedModules (outside includePaths)");
    }

    @Test
    void reportMode_includePathsExcludesDownstreamOutsideScope() throws Exception {
        // module-a has source changes and matches includePaths.
        // module-b depends on module-a (downstream) but is outside includePaths.
        // The report should NOT include module-b.
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

        // Set up dependency graph: module-b is downstream of module-a
        MavenSession session = createSimpleSession(root, allProjects, "report");
        ProjectDependencyGraph graph = session.getProjectDependencyGraph();
        when(graph.getDownstreamProjects(eq(moduleA), anyBoolean())).thenReturn(List.of(moduleB));
        session.getSystemProperties().setProperty("scalpel.includePaths", "module-a/**");

        participant.afterProjectsRead(session);

        Path reportFile = root.resolve("target/scalpel-report.json");
        assertTrue(Files.exists(reportFile));
        String json = new String(Files.readAllBytes(reportFile), StandardCharsets.UTF_8);
        assertTrue(modulePresent(json, "module-a"), "module-a should be in report (matches includePaths)");
        assertFalse(
                modulePresent(json, "module-b"),
                "module-b should NOT be in report (downstream but outside includePaths)");
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

    /**
     * Issue #89: with scalpel.failSafe=true, an unwritable reportFile must not fail the build.
     * "target" is created as a regular file so that target/scalpel-report.json cannot be written.
     */
    @Test
    void reportMode_failSafeTrue_unwritableReportFile_doesNotThrow() throws Exception {
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
        changedFiles.add("module-a/src/main/java/Foo.java");
        when(scalpelCore.detectChanges(any(), any(), any()))
                .thenReturn(new ChangeDetectionResult(changedFiles, new HashMap<>()));
        setupEmptyDependencyResolution();

        MavenSession session = createSimpleSession(root, allProjects, "report");
        session.getSystemProperties().setProperty("scalpel.failSafe", "true");
        // Make the report location unwritable: "target" exists as a regular file
        Files.write(root.resolve("target"), new byte[0]);

        participant.afterProjectsRead(session);
    }

    @Test
    void reportMode_failSafeFalse_unwritableReportFile_throws() throws Exception {
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
        changedFiles.add("module-a/src/main/java/Foo.java");
        when(scalpelCore.detectChanges(any(), any(), any()))
                .thenReturn(new ChangeDetectionResult(changedFiles, new HashMap<>()));
        setupEmptyDependencyResolution();

        MavenSession session = createSimpleSession(root, allProjects, "report");
        session.getSystemProperties().setProperty("scalpel.failSafe", "false");
        Files.write(root.resolve("target"), new byte[0]);

        assertThrows(org.apache.maven.MavenExecutionException.class, () -> participant.afterProjectsRead(session));
    }

    /**
     * Issue #89: when analysis bails out (detectChanges returns null under failSafe), a previous
     * run's report must be overwritten with a failed-status document, not left stale for CI.
     */
    @Test
    void reportMode_failSafeBailout_overwritesStaleReport() throws Exception {
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

        // Stale report from a previous successful run
        Path reportFile = root.resolve("target/scalpel-report.json");
        Files.createDirectories(reportFile.getParent());
        Files.write(reportFile, "STALE-REPORT".getBytes(StandardCharsets.UTF_8));

        when(scalpelCore.detectChanges(any(), any(), any())).thenReturn(null);
        setupEmptyDependencyResolution();

        MavenSession session = createSimpleSession(root, allProjects, "report");
        session.getSystemProperties().setProperty("scalpel.failSafe", "true");

        participant.afterProjectsRead(session);

        assertTrue(Files.exists(reportFile), "Report file should still exist (overwritten, not deleted)");
        String json = new String(Files.readAllBytes(reportFile), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"status\": \"failed\""), "Overwritten report should carry failed status");
        assertFalse(json.contains("STALE-REPORT"), "Stale content must not survive the bail-out");
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

        // Analysis bailed out: a failed-status report must be written so no stale data survives
        Path reportFile = root.resolve("target/scalpel-report.json");
        assertTrue(Files.exists(reportFile), "Failed-status report should be written when detection returns null");
        String json = new String(Files.readAllBytes(reportFile), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"status\": \"failed\""), "Report should carry failed status");
        assertFalse(modulePresent(json, "module-a"), "No module analysis should be present");
    }

    /**
     * Issue #89: with no changes detected, the stale report from a previous run must be
     * overwritten with a skipped-status document, not left in place for CI.
     */
    @Test
    void reportMode_noChanges_overwritesStaleReportWithSkippedStatus() throws Exception {
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

        Path reportFile = root.resolve("target/scalpel-report.json");
        Files.createDirectories(reportFile.getParent());
        Files.write(reportFile, "STALE-REPORT".getBytes(StandardCharsets.UTF_8));

        when(scalpelCore.detectChanges(any(), any(), any()))
                .thenReturn(new ChangeDetectionResult(new LinkedHashSet<>(), new HashMap<>()));
        setupEmptyDependencyResolution();

        MavenSession session = createSimpleSession(root, allProjects, "report");

        participant.afterProjectsRead(session);

        assertTrue(Files.exists(reportFile), "Report file should still exist (overwritten, not deleted)");
        String json = new String(Files.readAllBytes(reportFile), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"status\": \"skipped\""), "Overwritten report should carry skipped status");
        assertTrue(json.contains("no changes detected"), "Overwritten report should carry accurate reason");
        assertFalse(json.contains("STALE-REPORT"), "Stale content must not survive the bail-out");
    }

    /**
     * Issue #89: when every changed file is excluded by path filters, the stale report from a
     * previous run must be overwritten with a skipped-status document, not left in place for CI.
     */
    @Test
    void reportMode_allPathsExcluded_overwritesStaleReportWithSkippedStatus() throws Exception {
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

        Path reportFile = root.resolve("target/scalpel-report.json");
        Files.createDirectories(reportFile.getParent());
        Files.write(reportFile, "STALE-REPORT".getBytes(StandardCharsets.UTF_8));

        Set<String> changedFiles = new LinkedHashSet<>();
        changedFiles.add("docs/guide.md");
        when(scalpelCore.detectChanges(any(), any(), any()))
                .thenReturn(new ChangeDetectionResult(changedFiles, new HashMap<>()));
        setupEmptyDependencyResolution();

        MavenSession session = createSimpleSession(root, allProjects, "report");
        session.getSystemProperties().setProperty("scalpel.excludePaths", "docs/**");

        participant.afterProjectsRead(session);

        assertTrue(Files.exists(reportFile), "Report file should still exist (overwritten, not deleted)");
        String json = new String(Files.readAllBytes(reportFile), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"status\": \"skipped\""), "Overwritten report should carry skipped status");
        assertTrue(
                json.contains("all changed files excluded by path filters"),
                "Overwritten report should carry accurate reason");
        assertFalse(json.contains("STALE-REPORT"), "Stale content must not survive the bail-out");
    }

    /**
     * Issue #89: when detection was deliberately disabled (disableOnBranch/disableOnBaseBranch),
     * the status document must say skipped with that reason, not failed.
     */
    @Test
    void reportMode_disabledByBranch_writesSkippedStatus() throws Exception {
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

        when(scalpelCore.detectChanges(any(), any(), any())).thenReturn(null);
        when(scalpelCore.getLastDetectionSkipReason()).thenReturn("disabled by disableOnBranch");
        setupEmptyDependencyResolution();

        MavenSession session = createSimpleSession(root, allProjects, "report");

        participant.afterProjectsRead(session);

        Path reportFile = root.resolve("target/scalpel-report.json");
        assertTrue(Files.exists(reportFile), "Status report should be written when detection is disabled");
        String json = new String(Files.readAllBytes(reportFile), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"status\": \"skipped\""), "Deliberate disable should be reported as skipped");
        assertTrue(json.contains("disabled by disableOnBranch"), "Reason should name the disabling config");
        assertFalse(json.contains("\"status\": \"failed\""), "Deliberate disable must not be labelled failed");
    }

    /**
     * Review #137: baseBranch may be null (no explicit config, no CI environment) when detection
     * bails out; the status report must still be written with a placeholder instead of crashing
     * the build with IllegalStateException from the report builder.
     */
    @Test
    void reportMode_failSafeBailout_nullBaseBranch_writesStatusReportWithPlaceholder() throws Exception {
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

        when(scalpelCore.detectChanges(any(), any(), any())).thenReturn(null);
        when(scalpelCore.getLastDetectionSkipReason()).thenReturn("no base branch configured");
        setupEmptyDependencyResolution();

        MavenSession session = createSimpleSession(root, allProjects, "report");
        session.getSystemProperties().remove("scalpel.baseBranch");
        session.getSystemProperties().setProperty("scalpel.failSafe", "true");

        participant.afterProjectsRead(session);

        Path reportFile = root.resolve("target/scalpel-report.json");
        assertTrue(Files.exists(reportFile), "Status report must be written even without a base branch");
        String json = new String(Files.readAllBytes(reportFile), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"status\": \"skipped\""), "Bail-out with skip reason should be reported as skipped");
        assertTrue(
                json.contains("no base branch configured"), "Overwritten report should carry the actual skip reason");
        assertTrue(json.contains("(unconfigured)"), "Placeholder base branch should be recorded");
    }

    /**
     * Review #137: "not a git repository" is a skip (detection deliberately did not run), not a
     * failure; the status document must say skipped with that reason.
     */
    @Test
    void reportMode_notAGitRepository_writesSkippedStatusWithReason() throws Exception {
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

        when(scalpelCore.detectChanges(any(), any(), any())).thenReturn(null);
        when(scalpelCore.getLastDetectionSkipReason()).thenReturn("not a git repository");
        setupEmptyDependencyResolution();

        MavenSession session = createSimpleSession(root, allProjects, "report");

        participant.afterProjectsRead(session);

        Path reportFile = root.resolve("target/scalpel-report.json");
        assertTrue(Files.exists(reportFile), "Status report should be written when detection skips");
        String json = new String(Files.readAllBytes(reportFile), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"status\": \"skipped\""), "Not-a-git-repo should be reported as skipped");
        assertTrue(json.contains("not a git repository"), "Reason should name the skip condition");
        assertFalse(json.contains("\"status\": \"failed\""), "Skip condition must not be labelled failed");
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

        // failSafe=true → should not throw. The invalid old POM is handled conservatively
        // (all dependents marked affected), so the normal trim path runs and, since #91,
        // trim mode writes the JSON report too.
        Path reportFile = root.resolve("target/scalpel-report.json");
        assertTrue(Files.exists(reportFile));
    }

    @Test
    void scalpelException_failSafeTrue_buildsAll() throws Exception {
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

        // ScalpelCore throws ScalpelException (e.g. merge-base unavailable with failSafe=false in core,
        // but the lifecycle participant should still respect its own failSafe check)
        when(scalpelCore.detectChanges(any(), any(), any()))
                .thenThrow(new ScalpelException("Could not find merge base between origin/main and HEAD"));
        setupEmptyDependencyResolution();

        MavenSession session = createSimpleSession(root, allProjects, "trim");
        session.getSystemProperties().setProperty("scalpel.failSafe", "true");

        // Should not throw — failSafe=true should catch ScalpelException gracefully
        participant.afterProjectsRead(session);

        Path reportFile = root.resolve("target/scalpel-report.json");
        assertFalse(Files.exists(reportFile));
    }

    @Test
    void scalpelException_failSafeDisabled_throws() throws Exception {
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

        when(scalpelCore.detectChanges(any(), any(), any()))
                .thenThrow(new ScalpelException("Could not find merge base between origin/main and HEAD"));
        setupEmptyDependencyResolution();

        MavenSession session = createSimpleSession(root, allProjects, "trim");
        session.getSystemProperties().setProperty("scalpel.failSafe", "false");

        // Should throw MavenExecutionException when failSafe is disabled
        assertThrows(
                MavenExecutionException.class,
                () -> participant.afterProjectsRead(session),
                "Should throw MavenExecutionException when ScalpelException occurs with failSafe disabled");
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
    void impactedLog_skipsModulePathsWithShellMetacharacters() throws Exception {
        Path root = tempDir.resolve("project");
        Files.createDirectories(root);

        // module-safe: ordinary directory name; module;rm and module$(id): directory names a
        // PR author controls, containing shell metacharacters that make $(cat ...) / xargs
        // consumption of the log injection-prone
        String parentPom = simpleParentPom("module-safe", "module;rm", "module$(id)");
        writePom(root, "pom.xml", parentPom);
        String safePom = simpleChildPom("module-safe");
        writePom(root, "module-safe/pom.xml", safePom);
        String semiPom = simpleChildPom("module-semi");
        writePom(root, "module;rm/pom.xml", semiPom);
        String cmdSubPom = simpleChildPom("module-cmdsub");
        writePom(root, "module$(id)/pom.xml", cmdSubPom);

        MavenProject parentProject = createProject("com.example", "parent", "1.0", root, "pom.xml", parentPom);
        parentProject.getModel().setPackaging("pom");
        MavenProject moduleSafe =
                createProject("com.example", "module-safe", "1.0", root, "module-safe/pom.xml", safePom);
        moduleSafe.setParent(parentProject);
        MavenProject moduleSemi =
                createProject("com.example", "module-semi", "1.0", root, "module;rm/pom.xml", semiPom);
        moduleSemi.setParent(parentProject);
        MavenProject moduleCmdSub =
                createProject("com.example", "module-cmdsub", "1.0", root, "module$(id)/pom.xml", cmdSubPom);
        moduleCmdSub.setParent(parentProject);

        List<MavenProject> allProjects = List.of(parentProject, moduleSafe, moduleSemi, moduleCmdSub);

        Set<String> changedFiles = new LinkedHashSet<>();
        changedFiles.add("module-safe/src/main/java/Foo.java");
        changedFiles.add("module;rm/src/main/java/Foo.java");
        changedFiles.add("module$(id)/src/main/java/Foo.java");
        when(scalpelCore.detectChanges(any(), any(), any()))
                .thenReturn(new ChangeDetectionResult(changedFiles, new HashMap<String, byte[]>()));
        setupEmptyDependencyResolution();

        MavenSession session = createSimpleSession(root, allProjects, "report");
        session.getSystemProperties().setProperty("scalpel.impactedLog", "target/scalpel-impacted.log");

        String stderr = runCapturingStdErr(() -> participant.afterProjectsRead(session));

        Path logFile = root.resolve("target/scalpel-impacted.log");
        assertTrue(Files.exists(logFile), "Impacted log file should be created even when entries are skipped");
        List<String> lines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
        assertEquals(List.of("module-safe"), lines, "Impacted log must contain only the safe module path");
        assertFalse(lines.toString().contains("module;rm"), "Path with ';' must not reach the impacted log");
        assertFalse(lines.toString().contains("module$(id)"), "Path with '$' must not reach the impacted log");
        assertTrue(
                stderr.contains("module;rm"),
                "A WARN naming the skipped module path 'module;rm' should be emitted, got: " + stderr);
        assertTrue(
                stderr.contains("module$(id)"),
                "A WARN naming the skipped module path 'module$(id)' should be emitted, got: " + stderr);
    }

    @Test
    void impactedLog_newlineInModulePathCannotForgeEntries() throws Exception {
        Path root = tempDir.resolve("project");
        Files.createDirectories(root);

        // A newline inside a module directory name would, written verbatim, produce a forged
        // extra entry ("forge") on its own line in the log
        String evilDir = "evil\nforge";
        String parentPom = simpleParentPom("module-safe");
        writePom(root, "pom.xml", parentPom);
        String safePom = simpleChildPom("module-safe");
        writePom(root, "module-safe/pom.xml", safePom);
        String evilPom = simpleChildPom("module-evil");
        writePom(root, evilDir + "/pom.xml", evilPom);

        MavenProject parentProject = createProject("com.example", "parent", "1.0", root, "pom.xml", parentPom);
        parentProject.getModel().setPackaging("pom");
        MavenProject moduleSafe =
                createProject("com.example", "module-safe", "1.0", root, "module-safe/pom.xml", safePom);
        moduleSafe.setParent(parentProject);
        MavenProject moduleEvil =
                createProject("com.example", "module-evil", "1.0", root, evilDir + "/pom.xml", evilPom);
        moduleEvil.setParent(parentProject);

        List<MavenProject> allProjects = List.of(parentProject, moduleSafe, moduleEvil);

        Set<String> changedFiles = new LinkedHashSet<>();
        changedFiles.add("module-safe/src/main/java/Foo.java");
        changedFiles.add(evilDir + "/src/main/java/Foo.java");
        when(scalpelCore.detectChanges(any(), any(), any()))
                .thenReturn(new ChangeDetectionResult(changedFiles, new HashMap<String, byte[]>()));
        setupEmptyDependencyResolution();

        MavenSession session = createSimpleSession(root, allProjects, "report");
        session.getSystemProperties().setProperty("scalpel.impactedLog", "target/scalpel-impacted.log");

        String stderr = runCapturingStdErr(() -> participant.afterProjectsRead(session));

        Path logFile = root.resolve("target/scalpel-impacted.log");
        assertTrue(Files.exists(logFile), "Impacted log file should be created even when entries are skipped");
        List<String> lines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
        assertEquals(List.of("module-safe"), lines, "Impacted log must contain only the safe module path");
        assertFalse(lines.contains("forge"), "Newline in a module path must not forge extra log entries");
        assertTrue(
                stderr.contains("impacted log"),
                "A WARN about the skipped newline-containing path should be emitted, got: " + stderr);
    }

    @Test
    void impactedLog_notConfigured_writesNoFile() throws Exception {
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
        changedFiles.add("module-a/src/main/java/Foo.java");
        when(scalpelCore.detectChanges(any(), any(), any()))
                .thenReturn(new ChangeDetectionResult(changedFiles, new HashMap<String, byte[]>()));
        setupEmptyDependencyResolution();

        MavenSession session = createSimpleSession(root, allProjects, "report");

        participant.afterProjectsRead(session);

        assertFalse(
                Files.exists(root.resolve("target/scalpel-impacted.log")),
                "No impacted log must be written when scalpel.impactedLog is unset");
    }

    @Test
    void impactedLog_blank_writesNoFile() throws Exception {
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
        changedFiles.add("module-a/src/main/java/Foo.java");
        when(scalpelCore.detectChanges(any(), any(), any()))
                .thenReturn(new ChangeDetectionResult(changedFiles, new HashMap<String, byte[]>()));
        setupEmptyDependencyResolution();

        MavenSession session = createSimpleSession(root, allProjects, "report");
        session.getSystemProperties().setProperty("scalpel.impactedLog", "   ");

        participant.afterProjectsRead(session);

        assertFalse(
                Files.exists(root.resolve("target/scalpel-impacted.log")),
                "A blank scalpel.impactedLog value must not produce a file");
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
        String json = new String(Files.readAllBytes(reportFile), StandardCharsets.UTF_8);
        assertTrue(moduleHasReason(json, "module-b", "SOURCE_CHANGE"), "module-b should have SOURCE_CHANGE");
        // Fix #39: upstream modules (build prerequisites) are excluded from the report.
        // module-a is only in the build set because module-b depends on it — it is not
        // genuinely affected by the change. Including it inflates affectedModules.
        assertFalse(
                modulePresent(json, "module-a"),
                "module-a should NOT be in report (it's a build prerequisite, not affected by the change)");
        // Verify the excludedUpstreamCount is present in the JSON report
        assertTrue(
                json.contains("\"excludedUpstreamCount\": 1"),
                "Report should show 1 excluded upstream module (module-a)");
    }

    // ---------------------------------------------------------------
    // Explain mode (#93): per-module decision evidence
    // ---------------------------------------------------------------

    @Test
    void explainMode_directlyAffectedModuleCarriesTriggeringFile() throws Exception {
        Path root = tempDir.resolve("project");
        Files.createDirectories(root);
        String parentPom = simpleParentPom("module-a", "module-b");
        writePom(root, "pom.xml", parentPom);
        String moduleAPom = simpleChildPom("module-a");
        writePom(root, "module-a/pom.xml", moduleAPom);
        writePom(root, "module-b/pom.xml", simpleChildPom("module-b"));

        MavenProject parentProject = createProject("com.example", "parent", "1.0", root, "pom.xml", parentPom);
        parentProject.getModel().setPackaging("pom");
        MavenProject moduleA = createProject("com.example", "module-a", "1.0", root, "module-a/pom.xml", moduleAPom);
        moduleA.setParent(parentProject);
        MavenProject moduleB =
                createProject("com.example", "module-b", "1.0", root, "module-b/pom.xml", simpleChildPom("module-b"));
        moduleB.setParent(parentProject);

        List<MavenProject> allProjects = List.of(parentProject, moduleA, moduleB);
        Set<String> changedFiles = new LinkedHashSet<>();
        changedFiles.add("module-a/src/main/java/Foo.java");
        when(scalpelCore.detectChanges(any(), any(), any()))
                .thenReturn(new ChangeDetectionResult(changedFiles, new HashMap<String, byte[]>()));
        setupEmptyDependencyResolution();

        MavenSession session = createSimpleSession(root, allProjects, "report");
        session.getSystemProperties().setProperty("scalpel.explain", "true");

        participant.afterProjectsRead(session);

        Path reportFile = root.resolve("target/scalpel-report.json");
        assertTrue(Files.exists(reportFile));
        String json = new String(Files.readAllBytes(reportFile), StandardCharsets.UTF_8);
        String block = extractModuleBlock(json, "module-a");
        assertTrue(
                block != null && block.contains("\"module-a/src/main/java/Foo.java\""),
                "module-a evidence must name the triggering changed file");
    }

    @Test
    void explainMode_pomPropertyChildCarriesPropertyEvidence() throws Exception {
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
                  <modules><module>module-x</module><module>module-y</module></modules>
                  <properties>
                    <foo.version>1.0</foo.version>
                  </properties>
                </project>
                """;
        String newParentPom = oldParentPom.replace("<foo.version>1.0</foo.version>", "<foo.version>2.0</foo.version>");
        writePom(root, "pom.xml", newParentPom);

        String moduleXPom = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>module-x</artifactId>
                  <dependencies>
                    <dependency><groupId>org.example</groupId><artifactId>lib</artifactId><version>${foo.version}</version></dependency>
                  </dependencies>
                </project>
                """;
        writePom(root, "module-x/pom.xml", moduleXPom);
        String moduleYPom = simpleChildPom("module-y");
        writePom(root, "module-y/pom.xml", moduleYPom);

        MavenProject parentProject = createProject("com.example", "parent", "1.0", root, "pom.xml", newParentPom);
        parentProject.getModel().setPackaging("pom");
        MavenProject moduleX = createProject("com.example", "module-x", "1.0", root, "module-x/pom.xml", moduleXPom);
        moduleX.setParent(parentProject);
        MavenProject moduleY = createProject("com.example", "module-y", "1.0", root, "module-y/pom.xml", moduleYPom);
        moduleY.setParent(parentProject);

        List<MavenProject> allProjects = List.of(parentProject, moduleX, moduleY);
        Set<String> changedFiles = new LinkedHashSet<>();
        changedFiles.add("pom.xml");
        Map<String, byte[]> oldPoms = new HashMap<>();
        oldPoms.put("pom.xml", oldParentPom.getBytes(StandardCharsets.UTF_8));
        when(scalpelCore.detectChanges(any(), any(), any()))
                .thenReturn(new ChangeDetectionResult(changedFiles, oldPoms));
        setupEmptyDependencyResolution();

        MavenSession session = createSimpleSession(root, allProjects, "report");
        session.getSystemProperties().setProperty("scalpel.explain", "true");

        participant.afterProjectsRead(session);

        Path reportFile = root.resolve("target/scalpel-report.json");
        assertTrue(Files.exists(reportFile));
        String json = new String(Files.readAllBytes(reportFile), StandardCharsets.UTF_8);
        String block = extractModuleBlock(json, "module-x");
        assertTrue(
                block != null
                        && (block.contains("effective dep org.example:lib") || block.contains("property foo.version")),
                "module-x evidence must name the changed dependency or property, block was: " + block);
        assertFalse(modulePresent(json, "module-y"), "module-y does not reference the property, must not be affected");
    }

    @Test
    void explainDisabled_reportHasNoEvidenceField() throws Exception {
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
                  <modules><module>module-x</module></modules>
                  <properties>
                    <foo.version>1.0</foo.version>
                  </properties>
                </project>
                """;
        String newParentPom = oldParentPom.replace("<foo.version>1.0</foo.version>", "<foo.version>2.0</foo.version>");
        writePom(root, "pom.xml", newParentPom);

        String moduleXPom = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>module-x</artifactId>
                  <dependencies>
                    <dependency><groupId>org.example</groupId><artifactId>lib</artifactId><version>${foo.version}</version></dependency>
                  </dependencies>
                </project>
                """;
        writePom(root, "module-x/pom.xml", moduleXPom);

        MavenProject parentProject = createProject("com.example", "parent", "1.0", root, "pom.xml", newParentPom);
        parentProject.getModel().setPackaging("pom");
        MavenProject moduleX = createProject("com.example", "module-x", "1.0", root, "module-x/pom.xml", moduleXPom);
        moduleX.setParent(parentProject);

        List<MavenProject> allProjects = List.of(parentProject, moduleX);
        Set<String> changedFiles = new LinkedHashSet<>();
        changedFiles.add("pom.xml");
        Map<String, byte[]> oldPoms = new HashMap<>();
        oldPoms.put("pom.xml", oldParentPom.getBytes(StandardCharsets.UTF_8));
        when(scalpelCore.detectChanges(any(), any(), any()))
                .thenReturn(new ChangeDetectionResult(changedFiles, oldPoms));
        setupEmptyDependencyResolution();

        MavenSession session = createSimpleSession(root, allProjects, "report");

        participant.afterProjectsRead(session);

        Path reportFile = root.resolve("target/scalpel-report.json");
        assertTrue(Files.exists(reportFile));
        String json = new String(Files.readAllBytes(reportFile), StandardCharsets.UTF_8);
        assertFalse(json.contains("evidence"), "report must be unchanged (no evidence field) when explain=false");
    }

    // --- Helper methods ---

    private void setupEmptyDependencyResolution() throws Exception {
        DependencyResolutionResult emptyResolution = mock(DependencyResolutionResult.class);
        when(emptyResolution.getDependencyGraph()).thenReturn(createDependencyGraph());
        when(dependenciesResolver.resolve(any(DefaultDependencyResolutionRequest.class)))
                .thenReturn(emptyResolution);
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    /**
     * Runs {@code action} with {@code System.err} redirected to an in-memory buffer and returns
     * everything the participant logged there. slf4j-simple resolves {@code System.err} per call
     * (verified against the 1.7.36 jar the build resolves), so swapping the stream captures the
     * WARN output emitted during the run.
     */
    private String runCapturingStdErr(ThrowingRunnable action) throws Exception {
        PrintStream originalErr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            action.run();
        } finally {
            System.setErr(originalErr);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    /**
     * Creates a mock dependency graph root node with the given dependencies as direct children.
     * Each dependency becomes a leaf child node of the root.
     */
    private static DependencyNode createDependencyGraph(org.eclipse.aether.graph.Dependency... deps) {
        DefaultDependencyNode root = new DefaultDependencyNode((org.eclipse.aether.graph.Dependency) null);
        List<DependencyNode> children = new ArrayList<>();
        for (org.eclipse.aether.graph.Dependency dep : deps) {
            children.add(new DefaultDependencyNode(dep));
        }
        root.setChildren(children);
        return root;
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

    private static void addTestJarDependency(MavenProject consumer, String artifactId) {
        Dependency dep = new Dependency();
        dep.setGroupId("com.example");
        dep.setArtifactId(artifactId);
        dep.setVersion("1.0");
        dep.setType("test-jar");
        dep.setScope("test");
        consumer.getDependencies().add(dep);
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
        // Register in reactor so parent and BOM resolution works
        reactorPomFiles.put(groupId + ":" + artifactId + ":" + version, pomFile);
        // Set effective model using ModelBuilder with processPlugins=true and
        // reactor-aware resolver, matching production behavior.
        setEffectiveModel(project, pomXml);
        return project;
    }

    /**
     * Set the effective model on a MavenProject using Maven's ModelBuilder with
     * {@code processPlugins=true} to match production behavior. The ReactorModelResolver
     * resolves parents and BOM imports from the reactor POM files (populated by
     * {@link #createProject}), so the effective model includes parent inheritance,
     * BOM import resolution, lifecycle default plugins, and pluginManagement merging.
     */
    private void setEffectiveModel(MavenProject project, String pomXml) {
        try {
            org.apache.maven.model.building.ModelBuilder modelBuilder = new DefaultModelBuilderFactory().newInstance();
            DefaultModelBuildingRequest request = new DefaultModelBuildingRequest();
            request.setPomFile(project.getFile());
            request.setProcessPlugins(true);
            request.setValidationLevel(ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL);
            request.setModelResolver(new ReactorModelResolver(reactorPomFiles));

            // Activate the same profiles as the project's build
            if (project.getActiveProfiles() != null
                    && !project.getActiveProfiles().isEmpty()) {
                List<String> activeIds = new ArrayList<>();
                for (Profile p : project.getActiveProfiles()) {
                    activeIds.add(p.getId());
                }
                request.setActiveProfileIds(activeIds);
            }

            Model effective = modelBuilder.build(request).getEffectiveModel();
            effective.setPomFile(project.getFile());
            project.setModel(effective);
        } catch (ModelBuildingException e) {
            if (e.getResult() != null && e.getResult().getEffectiveModel() != null) {
                Model effective = e.getResult().getEffectiveModel();
                effective.setPomFile(project.getFile());
                project.setModel(effective);
            } else {
                // Fall back to raw XML parsing (no interpolation)
                Model effective = parseModel(pomXml);
                if (effective.getGroupId() == null) {
                    effective.setGroupId(project.getGroupId());
                }
                if (effective.getArtifactId() == null) {
                    effective.setArtifactId(project.getArtifactId());
                }
                if (effective.getVersion() == null) {
                    effective.setVersion(project.getVersion());
                }
                effective.setPomFile(project.getFile());
                project.setModel(effective);
            }
        }
    }

    /**
     * ModelResolver that resolves parents and BOM imports from the test reactor.
     */
    private static class ReactorModelResolver implements org.apache.maven.model.resolution.ModelResolver {
        private final Map<String, File> reactorPoms;

        ReactorModelResolver(Map<String, File> reactorPoms) {
            this.reactorPoms = reactorPoms;
        }

        @Override
        public ModelSource resolveModel(String groupId, String artifactId, String version)
                throws UnresolvableModelException {
            File f = reactorPoms.get(groupId + ":" + artifactId + ":" + version);
            if (f != null && f.exists()) {
                return new FileModelSource(f);
            }
            throw new UnresolvableModelException("not in reactor", groupId, artifactId, version);
        }

        @Override
        public ModelSource resolveModel(Parent parent) throws UnresolvableModelException {
            return resolveModel(parent.getGroupId(), parent.getArtifactId(), parent.getVersion());
        }

        @Override
        public ModelSource resolveModel(Dependency dependency) throws UnresolvableModelException {
            return resolveModel(dependency.getGroupId(), dependency.getArtifactId(), dependency.getVersion());
        }

        @Override
        public void addRepository(Repository repository) throws InvalidRepositoryException {}

        @Override
        public void addRepository(Repository repository, boolean replace) throws InvalidRepositoryException {}

        @Override
        public org.apache.maven.model.resolution.ModelResolver newCopy() {
            return this;
        }
    }

    private boolean modulePresent(String json, String artifactId) {
        String affectedSection = extractSection(json, "affectedModules");
        return affectedSection != null && affectedSection.contains("\"artifactId\": \"" + artifactId + "\"");
    }

    private String extractModuleBlock(String json, String artifactId) {
        String affectedSection = extractSection(json, "affectedModules");
        if (affectedSection == null) {
            return null;
        }
        String marker = "\"artifactId\": \"" + artifactId + "\"";
        int idx = affectedSection.indexOf(marker);
        if (idx < 0) {
            return null;
        }
        int start = affectedSection.lastIndexOf("{", idx);
        int end = affectedSection.indexOf("}", idx);
        if (start < 0 || end < 0) {
            return null;
        }
        return affectedSection.substring(start, end + 1);
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
     * Verifies that BOM property changes with no directly affected modules still produce
     * a report with transitively affected modules. This covers the scenario fixed in #33:
     * a version property change in a BOM's dependencyManagement should be detected via
     * transitive dependency resolution even when no child module directly references
     * the property in its POM text.
     */
    @Test
    void reportMode_bomPropertyChangeWithTransitiveOnlyAffectedModules() throws Exception {
        Path root = tempDir.resolve("project");
        Files.createDirectories(root);

        String rootPom = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>root</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <modules>
                    <module>bom</module>
                    <module>module-a</module>
                    <module>module-b</module>
                  </modules>
                </project>
                """;
        writePom(root, "pom.xml", rootPom);

        String oldBomPom = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>com.example</groupId>
                    <artifactId>root</artifactId>
                    <version>1.0</version>
                  </parent>
                  <artifactId>bom</artifactId>
                  <packaging>pom</packaging>
                  <properties>
                    <graphql.version>2.18.1</graphql.version>
                  </properties>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>io.smallrye</groupId>
                        <artifactId>smallrye-graphql</artifactId>
                        <version>${graphql.version}</version>
                      </dependency>
                      <dependency>
                        <groupId>io.smallrye</groupId>
                        <artifactId>smallrye-graphql-api</artifactId>
                        <version>${graphql.version}</version>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """;

        String newBomPom = oldBomPom.replace(
                "<graphql.version>2.18.1</graphql.version>", "<graphql.version>2.18.2</graphql.version>");
        writePom(root, "bom/pom.xml", newBomPom);

        // module-a: imports the BOM, gets smallrye-graphql transitively via dependency resolution
        String moduleAPom = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>com.example</groupId>
                    <artifactId>root</artifactId>
                    <version>1.0</version>
                  </parent>
                  <artifactId>module-a</artifactId>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>com.example</groupId>
                        <artifactId>bom</artifactId>
                        <version>${project.version}</version>
                        <type>pom</type>
                        <scope>import</scope>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """;
        writePom(root, "module-a/pom.xml", moduleAPom);

        // module-b: does NOT import the BOM or use graphql
        String moduleBPom = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>com.example</groupId>
                    <artifactId>root</artifactId>
                    <version>1.0</version>
                  </parent>
                  <artifactId>module-b</artifactId>
                </project>
                """;
        writePom(root, "module-b/pom.xml", moduleBPom);

        MavenProject rootProject = createProject("com.example", "root", "1.0", root, "pom.xml", rootPom);
        rootProject.getModel().setPackaging("pom");
        MavenProject bomProject = createProject("com.example", "bom", "1.0", root, "bom/pom.xml", newBomPom);
        bomProject.getModel().setPackaging("pom");
        bomProject.setParent(rootProject);
        MavenProject moduleA = createProject("com.example", "module-a", "1.0", root, "module-a/pom.xml", moduleAPom);
        moduleA.setParent(rootProject);
        MavenProject moduleB = createProject("com.example", "module-b", "1.0", root, "module-b/pom.xml", moduleBPom);
        moduleB.setParent(rootProject);

        List<MavenProject> allProjects = List.of(rootProject, bomProject, moduleA, moduleB);

        Set<String> changedFiles = new LinkedHashSet<>();
        changedFiles.add("bom/pom.xml");
        Map<String, byte[]> oldPoms = new HashMap<>();
        oldPoms.put("bom/pom.xml", oldBomPom.getBytes(StandardCharsets.UTF_8));
        when(scalpelCore.detectChanges(any(), any(), any()))
                .thenReturn(new ChangeDetectionResult(changedFiles, oldPoms));

        // module-a has smallrye-graphql as a transitive dependency (old=2.18.1, new=2.18.2)
        org.eclipse.aether.graph.Dependency graphqlNew = new org.eclipse.aether.graph.Dependency(
                new DefaultArtifact("io.smallrye", "smallrye-graphql", "jar", "2.18.2"), "compile");
        org.eclipse.aether.graph.Dependency graphqlOld = new org.eclipse.aether.graph.Dependency(
                new DefaultArtifact("io.smallrye", "smallrye-graphql", "jar", "2.18.1"), "compile");
        when(dependenciesResolver.resolve(any(DefaultDependencyResolutionRequest.class)))
                .thenAnswer(invocation -> {
                    DefaultDependencyResolutionRequest req = invocation.getArgument(0);
                    MavenProject reqProject = req.getMavenProject();
                    boolean isOldResolution = allProjects.stream().noneMatch(p -> p == reqProject);
                    if ("module-a".equals(reqProject.getArtifactId())) {
                        DependencyResolutionResult res = mock(DependencyResolutionResult.class);
                        when(res.getDependencyGraph())
                                .thenReturn(createDependencyGraph(isOldResolution ? graphqlOld : graphqlNew));
                        return res;
                    }
                    DependencyResolutionResult empty = mock(DependencyResolutionResult.class);
                    when(empty.getDependencyGraph()).thenReturn(createDependencyGraph());
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
        when(graph.getDownstreamProjects(any(), anyBoolean())).thenReturn(List.of());
        when(graph.getUpstreamProjects(any(), anyBoolean())).thenReturn(List.of());
        when(graph.getSortedProjects()).thenReturn(allProjects);
        when(session.getProjectDependencyGraph()).thenReturn(graph);

        participant.afterProjectsRead(session);

        Path reportFile = root.resolve("target/scalpel-report.json");
        assertTrue(Files.exists(reportFile), "Report file should be created");
        String json = new String(Files.readAllBytes(reportFile), StandardCharsets.UTF_8);

        // module-a should be transitively affected (has the changed dep in its resolved deps)
        assertTrue(
                moduleHasReason(json, "module-a", "TRANSITIVE_DEPENDENCY"),
                "module-a should have TRANSITIVE_DEPENDENCY reason (transitive dep on changed managed GA)");
        assertTrue(
                moduleHasField(json, "module-a", "category", "TRANSITIVE"), "module-a should have TRANSITIVE category");

        // module-b should NOT be affected (no dependency on changed GAs)
        assertFalse(modulePresent(json, "module-b"), "module-b should NOT be in report");

        // changedManagedDependencies should include the GAs
        assertTrue(json.contains("\"io.smallrye:smallrye-graphql\""), "Report should include changed managed dep GA");
        assertTrue(
                json.contains("\"io.smallrye:smallrye-graphql-api\""),
                "Report should include changed managed dep GA for api");

        // excludedUpstreamCount should be 0 (no upstream modules in this scenario)
        assertTrue(
                json.contains("\"excludedUpstreamCount\": 0"),
                "excludedUpstreamCount should be 0 when no upstream modules exist");
    }

    /**
     * Reproduce scalpel#39: Camel-like structure where kafka-version property is defined
     * in parent POM but NOT used in parent's dependencyManagement. It's only used directly
     * in 3 child modules' dependencies. The report should contain only DIRECT + DOWNSTREAM
     * modules; upstream build prerequisites are excluded (fix for #39).
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

        // Parent POM: defines kafka-version property but does not use it in managed deps
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

        // camel-kafka: directly references the changed property
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

        // camel-debezium: also references the changed property
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

        // camel-ibm: also references the changed property
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

        List<MavenProject> otherModules = new ArrayList<>();
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

        List<MavenProject> allProjects = new ArrayList<>();
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

        // Verify excludedUpstreamCount: camel-core is the only upstream build prerequisite
        assertTrue(
                json.contains("\"excludedUpstreamCount\": 1"),
                "Report should show 1 excluded upstream module (camel-core)");
    }

    /**
     * Reproduce scalpel#39: The real inflation mechanism.
     *
     * Camel has a "camel-allcomponents" sync-point module that depends on ALL ~459 component
     * modules. When kafka-version changes, camel-kafka becomes DIRECT. Since camel-allcomponents
     * depends on camel-kafka, it becomes DOWNSTREAM. Then alsoMake=true computes
     * getUpstreamProjects(camel-allcomponents, true) which returns ALL ~459 components.
     *
     * This test verifies that upstream build-prerequisite modules are excluded from the report,
     * preventing the sync-point module from inflating affectedModules with hundreds of
     * unrelated upstream dependencies (see scalpel#39).
     */
    @Test
    void reportMode_camelLike_allcomponentsSyncPoint_excludesUpstreamPrerequisites() throws Exception {
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

        List<MavenProject> compModules = new ArrayList<>();
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

        List<MavenProject> allProjects = new ArrayList<>();
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
        List<MavenProject> allcompUpstream = new ArrayList<>();
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

        // Verify excludedUpstreamCount in JSON: 31 upstream modules excluded (30 comp + camel-core)
        assertTrue(
                json.contains("\"excludedUpstreamCount\": 31"),
                "Report should show 31 excluded upstream modules (30 comp modules + camel-core)");
    }

    @Test
    void reportMode_mvnExtensionsXmlDoesNotInflateRootModule() throws Exception {
        // Reproduces issue #39 (reopened): changing .mvn/extensions.xml should NOT cause
        // the root module to be flagged as DIRECT (SOURCE_CHANGE), which would cascade
        // ALL reactor modules as DOWNSTREAM.
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

        // Change both .mvn/extensions.xml AND a real source file in module-a
        Set<String> changedFiles = new LinkedHashSet<>();
        changedFiles.add(".mvn/extensions.xml");
        changedFiles.add("module-a/src/main/java/Foo.java");
        when(scalpelCore.detectChanges(any(), any(), any()))
                .thenReturn(new ChangeDetectionResult(changedFiles, new HashMap<>()));
        setupEmptyDependencyResolution();

        MavenSession session = createSimpleSession(root, allProjects, "report");
        // Override default fullBuildTriggers (.mvn/**) — same as CI config
        session.getSystemProperties().setProperty("scalpel.fullBuildTriggers", "");

        ProjectDependencyGraph graph = mock(ProjectDependencyGraph.class);
        when(graph.getDownstreamProjects(moduleA, true)).thenReturn(List.of(moduleB));
        when(graph.getDownstreamProjects(moduleB, true)).thenReturn(List.of());
        when(graph.getDownstreamProjects(parentProject, true)).thenReturn(List.of(moduleA, moduleB));
        when(graph.getUpstreamProjects(any(), anyBoolean())).thenReturn(List.of());
        when(graph.getSortedProjects()).thenReturn(allProjects);
        when(session.getProjectDependencyGraph()).thenReturn(graph);

        participant.afterProjectsRead(session);

        Path reportFile = root.resolve("target/scalpel-report.json");
        assertTrue(Files.exists(reportFile), "Report file should be created");
        String json = new String(Files.readAllBytes(reportFile), StandardCharsets.UTF_8);

        // .mvn/extensions.xml should still appear in changedFiles (for transparency)
        assertTrue(json.contains(".mvn/extensions.xml"), "changedFiles should include .mvn/extensions.xml");

        // module-a should be DIRECT (real source change)
        assertTrue(moduleHasField(json, "module-a", "category", "DIRECT"), "module-a should be DIRECT");
        assertTrue(moduleHasReason(json, "module-a", "SOURCE_CHANGE"), "module-a should have SOURCE_CHANGE reason");

        // module-b should be DOWNSTREAM (depends on module-a)
        assertTrue(moduleHasField(json, "module-b", "category", "DOWNSTREAM"), "module-b should be DOWNSTREAM");

        // KEY: root/parent module should NOT be in the report — .mvn/ files are build
        // infrastructure and should not flag the root module as SOURCE_CHANGE
        assertFalse(
                modulePresent(json, "parent"),
                "parent/root module should NOT be DIRECT — .mvn/ files are build infrastructure");
    }

    @Test
    void reportMode_testsSkippedBooleanPresentWhenReasonSet() throws Exception {
        // Verifies that the report JSON includes a "testsSkipped": true boolean
        // alongside "testsSkippedReason" for easier CI parsing (jq .testsSkipped == true).
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

        // module-b should have both testsSkipped boolean and testsSkippedReason string
        String moduleBBlock = extractModuleBlock(json, "module-b");
        assertTrue(moduleBBlock != null, "module-b should be in the report");
        assertTrue(
                moduleBBlock.contains("\"testsSkipped\": true"),
                "module-b should have testsSkipped=true boolean for jq compatibility");
        assertTrue(
                moduleBBlock.contains("\"testsSkippedReason\": \"EXCLUDED_DOWNSTREAM\""),
                "module-b should have testsSkippedReason=EXCLUDED_DOWNSTREAM");

        // module-a should NOT have testsSkipped (it's DIRECT, not excluded downstream)
        String moduleABlock = extractModuleBlock(json, "module-a");
        assertTrue(moduleABlock != null, "module-a should be in the report");
        assertFalse(moduleABlock.contains("\"testsSkipped\""), "module-a should NOT have testsSkipped (it's DIRECT)");
    }

    @Test
    void afterProjectsRead_invalidMode_failSafeReturnsNormally() throws Exception {
        // ScalpelConfiguration.fromProperties throws IllegalArgumentException for an invalid
        // scalpel.mode. Since fromProperties is now inside a try block, the build should
        // proceed normally (no exception) when the config fails to parse.
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

        MavenSession session = mock(MavenSession.class);
        Properties sysProps = new Properties();
        sysProps.setProperty("scalpel.mode", "bogus-invalid-mode");
        sysProps.setProperty("scalpel.baseBranch", "base");
        when(session.getSystemProperties()).thenReturn(sysProps);
        when(session.getUserProperties()).thenReturn(new Properties());
        when(session.getProjects()).thenReturn(allProjects);
        MavenExecutionRequest execRequest = mock(MavenExecutionRequest.class);
        when(execRequest.getMultiModuleProjectDirectory()).thenReturn(root.toFile());
        when(session.getRequest()).thenReturn(execRequest);

        // Should NOT throw — config parsing error is caught and logged
        participant.afterProjectsRead(session);
    }

    @Test
    void afterProjectsRead_invalidGlobPattern_failSafeReturnsNormally() throws Exception {
        // An invalid glob pattern (e.g. "[unclosed") in excludePaths causes
        // PatternSyntaxException. With failSafe=true (default), this should be caught
        // and the build should proceed normally.
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
        changedFiles.add("module-a/src/main/java/Foo.java");
        when(scalpelCore.detectChanges(any(), any(), any()))
                .thenReturn(new ChangeDetectionResult(changedFiles, new HashMap<>()));
        setupEmptyDependencyResolution();

        MavenSession session = createSimpleSession(root, allProjects, "trim");
        // Invalid glob pattern — "[unclosed" is not a valid glob
        session.getSystemProperties().setProperty("scalpel.excludePaths", "[unclosed");

        // Should NOT throw — PatternSyntaxException is caught by the failSafe handler
        participant.afterProjectsRead(session);
    }

    @Test
    void afterProjectsRead_invalidGlobPattern_failSafeDisabled_throws() throws Exception {
        // With failSafe=false, an invalid glob pattern should propagate as MavenExecutionException.
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
        changedFiles.add("module-a/src/main/java/Foo.java");
        when(scalpelCore.detectChanges(any(), any(), any()))
                .thenReturn(new ChangeDetectionResult(changedFiles, new HashMap<>()));
        setupEmptyDependencyResolution();

        MavenSession session = createSimpleSession(root, allProjects, "trim");
        // Invalid glob pattern + failSafe disabled
        session.getSystemProperties().setProperty("scalpel.excludePaths", "[unclosed");
        session.getSystemProperties().setProperty("scalpel.failSafe", "false");

        // Should throw MavenExecutionException when failSafe is disabled
        assertThrows(
                MavenExecutionException.class,
                () -> participant.afterProjectsRead(session),
                "Should throw MavenExecutionException when failSafe is disabled and glob is invalid");
    }

    @Test
    void normalizeGlobPattern_barePatternIsPrefixed() {
        assertEquals("{*.md,**/*.md}", ScalpelLifecycleParticipant.normalizeGlobPattern("*.md"));
        assertEquals("{LICENSE,**/LICENSE}", ScalpelLifecycleParticipant.normalizeGlobPattern("LICENSE"));
        assertEquals(
                "{.editorconfig,**/.editorconfig}", ScalpelLifecycleParticipant.normalizeGlobPattern(".editorconfig"));
    }

    @Test
    void normalizeGlobPattern_patternWithSlashIsUnchanged() {
        assertEquals("docs/*.md", ScalpelLifecycleParticipant.normalizeGlobPattern("docs/*.md"));
        assertEquals("**/*.md", ScalpelLifecycleParticipant.normalizeGlobPattern("**/*.md"));
        assertEquals(".github/**", ScalpelLifecycleParticipant.normalizeGlobPattern(".github/**"));
    }

    @Test
    void reportMode_bareExcludePathMatchesNestedFile() throws Exception {
        // Verifies that a bare glob like *.md (no /) excludes nested files (e.g. docs/guide.md)
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

        // module-a has a nested .md file changed, module-b has a source file changed
        Set<String> changedFiles = new LinkedHashSet<>();
        changedFiles.add("module-a/docs/guide.md");
        changedFiles.add("module-b/src/main/java/Foo.java");
        when(scalpelCore.detectChanges(any(), any(), any()))
                .thenReturn(new ChangeDetectionResult(changedFiles, new HashMap<>()));
        setupEmptyDependencyResolution();

        // Use bare *.md pattern (no slash) — should match nested files after normalization
        MavenSession session = createSimpleSession(root, allProjects, "report");
        session.getSystemProperties().setProperty("scalpel.excludePaths", "*.md");

        participant.afterProjectsRead(session);

        Path reportFile = root.resolve("target/scalpel-report.json");
        assertTrue(Files.exists(reportFile));
        String json = new String(Files.readAllBytes(reportFile), StandardCharsets.UTF_8);
        assertTrue(modulePresent(json, "module-b"), "module-b should be in report (source changed)");
        assertFalse(
                modulePresent(json, "module-a"),
                "module-a should NOT be in report (nested .md excluded by bare *.md pattern)");
    }

    // --- Multi-level dependency graph tests for DFS walker ---

    /**
     * Creates a multi-level dependency graph root node. The root has no dependency itself.
     * Children are provided as pre-built nodes (which may themselves have children).
     */
    private static DependencyNode createMultiLevelDependencyGraph(DependencyNode... children) {
        DefaultDependencyNode root = new DefaultDependencyNode((org.eclipse.aether.graph.Dependency) null);
        root.setChildren(List.of(children));
        return root;
    }

    /**
     * Creates a dependency node with the given dependency and child nodes.
     */
    private static DefaultDependencyNode createNodeWithChildren(
            org.eclipse.aether.graph.Dependency dep, DependencyNode... children) {
        DefaultDependencyNode node = new DefaultDependencyNode(dep);
        node.setChildren(List.of(children));
        return node;
    }

    /**
     * Mocks dependency resolution so that the module with the given artifactId gets the
     * provided graph for new resolutions and oldGraph for old resolutions.
     * Old vs new is distinguished by checking if the project instance is one of the
     * known allProjects (new) or a temp copy (old).
     */
    private void mockDependencyResolution(
            String targetArtifactId, DependencyNode newGraph, DependencyNode oldGraph, List<MavenProject> allProjects)
            throws DependencyResolutionException {
        when(dependenciesResolver.resolve(any(DefaultDependencyResolutionRequest.class)))
                .thenAnswer(invocation -> {
                    DefaultDependencyResolutionRequest req = invocation.getArgument(0);
                    MavenProject reqProject = req.getMavenProject();
                    boolean isOldResolution = allProjects.stream().noneMatch(p -> p == reqProject);
                    if (targetArtifactId.equals(reqProject.getArtifactId())) {
                        DependencyResolutionResult res = mock(DependencyResolutionResult.class);
                        when(res.getDependencyGraph()).thenReturn(isOldResolution ? oldGraph : newGraph);
                        return res;
                    }
                    DependencyResolutionResult empty = mock(DependencyResolutionResult.class);
                    when(empty.getDependencyGraph()).thenReturn(createDependencyGraph());
                    return empty;
                });
    }

    /**
     * Tests that a managed dep change is detected when the changed GA appears at the
     * second level of the dependency tree: root -> intermediate -> changed-dep.
     * The existing tests only exercise flat (single-level) graphs where the changed GA
     * is a direct child of the resolution root. This test verifies the DFS walker
     * descends into children.
     */
    @Test
    void transitiveDepChange_twoLevelTree_detected() throws Exception {
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
                  <modules><module>module-a</module></modules>
                  <properties>
                    <lib.version>1.0</lib.version>
                  </properties>
                  <dependencyManagement><dependencies>
                    <dependency>
                      <groupId>org.example</groupId>
                      <artifactId>deep-lib</artifactId>
                      <version>${lib.version}</version>
                    </dependency>
                  </dependencies></dependencyManagement>
                </project>
                """;

        String newParentPom = oldParentPom.replace("<lib.version>1.0</lib.version>", "<lib.version>2.0</lib.version>");
        writePom(root, "pom.xml", newParentPom);

        String moduleAPom = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>module-a</artifactId>
                </project>
                """;
        writePom(root, "module-a/pom.xml", moduleAPom);

        MavenProject parentProject = createProject("com.example", "parent", "1.0", root, "pom.xml", newParentPom);
        parentProject.getModel().setPackaging("pom");
        MavenProject moduleA = createProject("com.example", "module-a", "1.0", root, "module-a/pom.xml", moduleAPom);
        moduleA.setParent(parentProject);

        List<MavenProject> allProjects = List.of(parentProject, moduleA);

        Set<String> changedFiles = new LinkedHashSet<>();
        changedFiles.add("pom.xml");
        Map<String, byte[]> oldPoms = new HashMap<>();
        oldPoms.put("pom.xml", oldParentPom.getBytes(StandardCharsets.UTF_8));
        when(scalpelCore.detectChanges(any(), any(), any()))
                .thenReturn(new ChangeDetectionResult(changedFiles, oldPoms));

        // Build two-level dependency graphs (old=1.0, new=2.0 for deep-lib):
        // root -> intermediate-lib (compile) -> deep-lib (compile, the changed managed dep)
        org.eclipse.aether.graph.Dependency deepLibNew = new org.eclipse.aether.graph.Dependency(
                new DefaultArtifact("org.example", "deep-lib", "jar", "2.0"), "compile");
        DefaultDependencyNode deepLibNewNode = new DefaultDependencyNode(deepLibNew);
        deepLibNewNode.setChildren(List.of());
        org.eclipse.aether.graph.Dependency intermediateNew = new org.eclipse.aether.graph.Dependency(
                new DefaultArtifact("org.example", "intermediate-lib", "jar", "1.0"), "compile");
        DependencyNode newGraph =
                createMultiLevelDependencyGraph(createNodeWithChildren(intermediateNew, deepLibNewNode));

        org.eclipse.aether.graph.Dependency deepLibOld = new org.eclipse.aether.graph.Dependency(
                new DefaultArtifact("org.example", "deep-lib", "jar", "1.0"), "compile");
        DefaultDependencyNode deepLibOldNode = new DefaultDependencyNode(deepLibOld);
        deepLibOldNode.setChildren(List.of());
        org.eclipse.aether.graph.Dependency intermediateOld = new org.eclipse.aether.graph.Dependency(
                new DefaultArtifact("org.example", "intermediate-lib", "jar", "1.0"), "compile");
        DependencyNode oldGraph =
                createMultiLevelDependencyGraph(createNodeWithChildren(intermediateOld, deepLibOldNode));

        mockDependencyResolution("module-a", newGraph, oldGraph, allProjects);

        MavenSession session = createSimpleSession(root, allProjects, "report");

        participant.afterProjectsRead(session);

        Path reportFile = root.resolve("target/scalpel-report.json");
        assertTrue(Files.exists(reportFile), "Report file should be created");

        String json = new String(Files.readAllBytes(reportFile), StandardCharsets.UTF_8);

        // module-a should be detected as transitively affected even though the changed GA
        // is at depth 2 (not a direct child of the resolution root)
        assertTrue(
                moduleHasReason(json, "module-a", "TRANSITIVE_DEPENDENCY"),
                "module-a should have TRANSITIVE_DEPENDENCY reason (changed dep at depth 2 in tree)");
        assertTrue(
                moduleHasField(json, "module-a", "category", "TRANSITIVE"), "module-a should have TRANSITIVE category");
    }

    /**
     * Tests that a diamond dependency pattern (two paths leading to the same changed GA)
     * is handled correctly by the DFS walker's visited-set deduplication.
     * Graph: root -> path-b (compile) -> target-lib (test, changed)
     *        root -> path-c (compile) -> target-lib (test, changed)
     * Test scope is required here: with compile scope, the walker returns immediately
     * on the first match (line 863-864), so visited-set deduplication is never exercised.
     * With test scope, the walker records the match and continues, reaching the second path.
     */
    @Test
    void transitiveDepChange_diamondDependency_detectedOnce() throws Exception {
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
                  <modules><module>module-a</module></modules>
                  <properties>
                    <target.version>1.0</target.version>
                  </properties>
                  <dependencyManagement><dependencies>
                    <dependency>
                      <groupId>org.example</groupId>
                      <artifactId>target-lib</artifactId>
                      <version>${target.version}</version>
                    </dependency>
                  </dependencies></dependencyManagement>
                </project>
                """;

        String newParentPom =
                oldParentPom.replace("<target.version>1.0</target.version>", "<target.version>2.0</target.version>");
        writePom(root, "pom.xml", newParentPom);

        String moduleAPom = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>module-a</artifactId>
                </project>
                """;
        writePom(root, "module-a/pom.xml", moduleAPom);

        MavenProject parentProject = createProject("com.example", "parent", "1.0", root, "pom.xml", newParentPom);
        parentProject.getModel().setPackaging("pom");
        MavenProject moduleA = createProject("com.example", "module-a", "1.0", root, "module-a/pom.xml", moduleAPom);
        moduleA.setParent(parentProject);

        List<MavenProject> allProjects = List.of(parentProject, moduleA);

        Set<String> changedFiles = new LinkedHashSet<>();
        changedFiles.add("pom.xml");
        Map<String, byte[]> oldPoms = new HashMap<>();
        oldPoms.put("pom.xml", oldParentPom.getBytes(StandardCharsets.UTF_8));
        when(scalpelCore.detectChanges(any(), any(), any()))
                .thenReturn(new ChangeDetectionResult(changedFiles, oldPoms));

        // Build diamond dependency graphs (old=1.0, new=2.0 for target-lib):
        // root -> path-b -> target-lib (test, changed)
        //      -> path-c -> target-lib (test, changed)

        // New graph (target-lib:2.0)
        org.eclipse.aether.graph.Dependency targetLibNew = new org.eclipse.aether.graph.Dependency(
                new DefaultArtifact("org.example", "target-lib", "jar", "2.0"), "test");
        DefaultDependencyNode targetFromBNew = new DefaultDependencyNode(targetLibNew);
        targetFromBNew.setChildren(List.of());
        DefaultDependencyNode targetFromCNew = new DefaultDependencyNode(targetLibNew);
        targetFromCNew.setChildren(List.of());
        DefaultDependencyNode pathBNodeNew = createNodeWithChildren(
                new org.eclipse.aether.graph.Dependency(
                        new DefaultArtifact("org.example", "path-b", "jar", "1.0"), "compile"),
                targetFromBNew);
        DefaultDependencyNode pathCNodeNew = createNodeWithChildren(
                new org.eclipse.aether.graph.Dependency(
                        new DefaultArtifact("org.example", "path-c", "jar", "1.0"), "compile"),
                targetFromCNew);
        DependencyNode newGraph = createMultiLevelDependencyGraph(pathBNodeNew, pathCNodeNew);

        // Old graph (target-lib:1.0)
        org.eclipse.aether.graph.Dependency targetLibOld = new org.eclipse.aether.graph.Dependency(
                new DefaultArtifact("org.example", "target-lib", "jar", "1.0"), "test");
        DefaultDependencyNode targetFromBOld = new DefaultDependencyNode(targetLibOld);
        targetFromBOld.setChildren(List.of());
        DefaultDependencyNode targetFromCOld = new DefaultDependencyNode(targetLibOld);
        targetFromCOld.setChildren(List.of());
        DefaultDependencyNode pathBNodeOld = createNodeWithChildren(
                new org.eclipse.aether.graph.Dependency(
                        new DefaultArtifact("org.example", "path-b", "jar", "1.0"), "compile"),
                targetFromBOld);
        DefaultDependencyNode pathCNodeOld = createNodeWithChildren(
                new org.eclipse.aether.graph.Dependency(
                        new DefaultArtifact("org.example", "path-c", "jar", "1.0"), "compile"),
                targetFromCOld);
        DependencyNode oldGraph = createMultiLevelDependencyGraph(pathBNodeOld, pathCNodeOld);

        mockDependencyResolution("module-a", newGraph, oldGraph, allProjects);

        MavenSession session = createSimpleSession(root, allProjects, "report");

        participant.afterProjectsRead(session);

        Path reportFile = root.resolve("target/scalpel-report.json");
        assertTrue(Files.exists(reportFile), "Report file should be created");

        String json = new String(Files.readAllBytes(reportFile), StandardCharsets.UTF_8);

        // module-a should be detected exactly once despite the diamond
        assertTrue(
                moduleHasReason(json, "module-a", "TRANSITIVE_DEPENDENCY_TEST"),
                "module-a should have TRANSITIVE_DEPENDENCY_TEST reason (diamond dep converges on test-scoped changed GA)");
        assertTrue(
                moduleHasField(json, "module-a", "category", "TRANSITIVE"), "module-a should have TRANSITIVE category");

        // Verify the changed managed dep GA appears in the report
        assertTrue(json.contains("org.example:target-lib"), "Report should include the changed managed dep GA");
    }

    /**
     * Tests that when a changed GA appears deep in the tree but the dependency node
     * itself is test-scoped, the result scope is "test" and the module gets the
     * TRANSITIVE_DEPENDENCY_TEST reason.
     * Graph: root -> intermediate-lib (test) -> deep-test-lib (test, the changed managed dep)
     */
    @Test
    void transitiveDepChange_deepTestScope_returnsTest() throws Exception {
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
                  <modules><module>module-a</module></modules>
                  <properties>
                    <testlib.version>1.0</testlib.version>
                  </properties>
                  <dependencyManagement><dependencies>
                    <dependency>
                      <groupId>org.example</groupId>
                      <artifactId>deep-test-lib</artifactId>
                      <version>${testlib.version}</version>
                    </dependency>
                  </dependencies></dependencyManagement>
                </project>
                """;

        String newParentPom = oldParentPom.replace(
                "<testlib.version>1.0</testlib.version>", "<testlib.version>2.0</testlib.version>");
        writePom(root, "pom.xml", newParentPom);

        String moduleAPom = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>module-a</artifactId>
                </project>
                """;
        writePom(root, "module-a/pom.xml", moduleAPom);

        MavenProject parentProject = createProject("com.example", "parent", "1.0", root, "pom.xml", newParentPom);
        parentProject.getModel().setPackaging("pom");
        MavenProject moduleA = createProject("com.example", "module-a", "1.0", root, "module-a/pom.xml", moduleAPom);
        moduleA.setParent(parentProject);

        List<MavenProject> allProjects = List.of(parentProject, moduleA);

        Set<String> changedFiles = new LinkedHashSet<>();
        changedFiles.add("pom.xml");
        Map<String, byte[]> oldPoms = new HashMap<>();
        oldPoms.put("pom.xml", oldParentPom.getBytes(StandardCharsets.UTF_8));
        when(scalpelCore.detectChanges(any(), any(), any()))
                .thenReturn(new ChangeDetectionResult(changedFiles, oldPoms));

        // Build two-level trees (old=1.0, new=2.0 for deep-test-lib):
        // root -> intermediate-test-lib (test) -> deep-test-lib (test, the changed managed dep)

        // New graph (deep-test-lib:2.0)
        DefaultDependencyNode deepTestNewNode = new DefaultDependencyNode(new org.eclipse.aether.graph.Dependency(
                new DefaultArtifact("org.example", "deep-test-lib", "jar", "2.0"), "test"));
        deepTestNewNode.setChildren(List.of());
        DefaultDependencyNode intermediateNewNode = createNodeWithChildren(
                new org.eclipse.aether.graph.Dependency(
                        new DefaultArtifact("org.example", "intermediate-test-lib", "jar", "1.0"), "test"),
                deepTestNewNode);
        DependencyNode newGraph = createMultiLevelDependencyGraph(intermediateNewNode);

        // Old graph (deep-test-lib:1.0)
        DefaultDependencyNode deepTestOldNode = new DefaultDependencyNode(new org.eclipse.aether.graph.Dependency(
                new DefaultArtifact("org.example", "deep-test-lib", "jar", "1.0"), "test"));
        deepTestOldNode.setChildren(List.of());
        DefaultDependencyNode intermediateOldNode = createNodeWithChildren(
                new org.eclipse.aether.graph.Dependency(
                        new DefaultArtifact("org.example", "intermediate-test-lib", "jar", "1.0"), "test"),
                deepTestOldNode);
        DependencyNode oldGraph = createMultiLevelDependencyGraph(intermediateOldNode);

        mockDependencyResolution("module-a", newGraph, oldGraph, allProjects);

        MavenSession session = createSimpleSession(root, allProjects, "report");

        participant.afterProjectsRead(session);

        Path reportFile = root.resolve("target/scalpel-report.json");
        assertTrue(Files.exists(reportFile), "Report file should be created");

        String json = new String(Files.readAllBytes(reportFile), StandardCharsets.UTF_8);

        // module-a should have TRANSITIVE_DEPENDENCY_TEST reason because the changed dep
        // at depth 2 is test-scoped
        assertTrue(
                moduleHasReason(json, "module-a", "TRANSITIVE_DEPENDENCY_TEST"),
                "module-a should have TRANSITIVE_DEPENDENCY_TEST reason (test-scoped dep at depth 2)");
        assertTrue(
                moduleHasField(json, "module-a", "category", "TRANSITIVE"), "module-a should have TRANSITIVE category");
    }
}
