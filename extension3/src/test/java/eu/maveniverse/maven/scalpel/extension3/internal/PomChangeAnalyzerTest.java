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
import static org.mockito.Mockito.mock;

import eu.maveniverse.maven.scalpel.core.ScalpelConfiguration;
import java.io.File;
import java.io.IOException;
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
import java.util.stream.Stream;
import org.apache.maven.model.Build;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.Profile;
import org.apache.maven.model.Resource;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.impl.RemoteRepositoryManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class PomChangeAnalyzerTest {

    private PomChangeAnalyzer analyzer;

    /** Default resolution context with a mock session — used by all tests that don't need real artifact resolution. */
    private final PomChangeAnalyzer.ModelResolutionContext defaultResolutionCtx =
            new PomChangeAnalyzer.ModelResolutionContext(
                    new Properties(), new Properties(), mock(RepositorySystemSession.class), List.of());

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        analyzer = new PomChangeAnalyzer(mock(RepositorySystem.class), mock(RemoteRepositoryManager.class));
    }

    /** Convenience wrapper: calls analyzeChanges with default explain=true and the default mock resolution context. */
    private PomChangeAnalyzer.Result analyzeChanges(
            Set<String> changedPomPaths,
            Map<String, byte[]> oldPomContents,
            List<MavenProject> allProjects,
            Path reactorRoot) {
        return analyzer.analyzeChanges(
                changedPomPaths,
                oldPomContents,
                allProjects,
                reactorRoot,
                ScalpelConfiguration.DEFAULT_MAX_RESOURCE_FILE_SIZE,
                true,
                defaultResolutionCtx);
    }

    /** Convenience wrapper with custom maxResourceFileSize. */
    private PomChangeAnalyzer.Result analyzeChanges(
            Set<String> changedPomPaths,
            Map<String, byte[]> oldPomContents,
            List<MavenProject> allProjects,
            Path reactorRoot,
            long maxResourceFileSize) {
        return analyzer.analyzeChanges(
                changedPomPaths,
                oldPomContents,
                allProjects,
                reactorRoot,
                maxResourceFileSize,
                true,
                defaultResolutionCtx);
    }

    // --- diffProperties tests ---

    @Test
    void diffProperties_noChanges() {
        Properties a = new Properties();
        a.setProperty("foo", "1.0");
        a.setProperty("bar", "2.0");
        Properties b = new Properties();
        b.setProperty("foo", "1.0");
        b.setProperty("bar", "2.0");
        assertTrue(analyzer.diffProperties(a, b).isEmpty());
    }

    @Test
    void diffProperties_changedValue() {
        Properties a = new Properties();
        a.setProperty("dep.version", "1.0");
        Properties b = new Properties();
        b.setProperty("dep.version", "2.0");
        Set<String> changed = analyzer.diffProperties(a, b);
        assertEquals(Set.of("dep.version"), changed);
    }

    @Test
    void diffProperties_addedProperty() {
        Properties a = new Properties();
        Properties b = new Properties();
        b.setProperty("new.prop", "value");
        Set<String> changed = analyzer.diffProperties(a, b);
        assertEquals(Set.of("new.prop"), changed);
    }

    @Test
    void diffProperties_removedProperty() {
        Properties a = new Properties();
        a.setProperty("old.prop", "value");
        Properties b = new Properties();
        Set<String> changed = analyzer.diffProperties(a, b);
        assertEquals(Set.of("old.prop"), changed);
    }

    @Test
    void diffProperties_nullHandling() {
        assertTrue(analyzer.diffProperties(null, null).isEmpty());
        Set<String> changed = analyzer.diffProperties(null, new Properties());
        assertTrue(changed.isEmpty());
    }

    // --- analyzeChanges integration tests ---

    @Test
    void analyzeChanges_leafModulePomChanged() throws Exception {
        // Setup: a leaf module (module-a) has its POM changed
        Path root = setupReactorRoot();
        List<MavenProject> projects = createSimpleReactor(root);
        MavenProject moduleA = projects.get(1); // module-a

        Set<String> changedPoms = Set.of("module-a/pom.xml");
        Map<String, byte[]> oldPoms = new HashMap<>();
        oldPoms.put("module-a/pom.xml", readFile(moduleA.getFile()));

        Set<MavenProject> affected =
                analyzeChanges(changedPoms, oldPoms, projects, root).getAffectedProjects();

        assertTrue(affected.contains(moduleA), "Leaf module with changed POM should be affected");
        assertEquals(1, affected.size(), "Only the leaf module should be affected");
    }

    @Test
    void analyzeChanges_parentPropertyChangeAffectsOnlyReferencingChild() throws Exception {
        // Setup: parent POM has dep.version property, only module-b references it
        Path root = setupReactorRoot();
        List<MavenProject> projects = createReactorWithPropertyUsage(root);

        // Old parent POM had dep.version=1.0
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
                    <dep.version>1.0</dep.version>
                  </properties>
                </project>
                """;

        Set<String> changedPoms = Set.of("pom.xml");
        Map<String, byte[]> oldPoms = new HashMap<>();
        oldPoms.put("pom.xml", oldParentPom.getBytes(StandardCharsets.UTF_8));

        Set<MavenProject> affected =
                analyzeChanges(changedPoms, oldPoms, projects, root).getAffectedProjects();

        MavenProject moduleA = projects.get(1);
        MavenProject moduleB = projects.get(2);
        assertTrue(affected.contains(moduleB), "module-b references ${dep.version} and should be affected");

        assertFalse(
                affected.contains(moduleA), "module-a does NOT reference ${dep.version} and should NOT be affected");
    }

    @Test
    void analyzeChanges_parentDepMgmtChangeAffectsOnlyUsingChild() throws Exception {
        // Setup: parent POM has depMgmt for com.example:lib-x, only module-b uses it
        Path root = setupReactorRoot();
        List<MavenProject> projects = createReactorWithDepMgmtUsage(root);

        // Old parent POM had lib-x:1.0 in depMgmt
        String oldParentPom = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <modules><module>module-a</module><module>module-b</module></modules>
                  <dependencyManagement><dependencies>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>lib-x</artifactId>
                      <version>1.0</version>
                    </dependency>
                  </dependencies></dependencyManagement>
                </project>
                """;

        Set<String> changedPoms = Set.of("pom.xml");
        Map<String, byte[]> oldPoms = new HashMap<>();
        oldPoms.put("pom.xml", oldParentPom.getBytes(StandardCharsets.UTF_8));

        Set<MavenProject> affected =
                analyzeChanges(changedPoms, oldPoms, projects, root).getAffectedProjects();

        MavenProject moduleB = projects.get(2);
        assertTrue(affected.contains(moduleB), "module-b uses managed dep com.example:lib-x and should be affected");

        MavenProject moduleA = projects.get(1);
        assertFalse(affected.contains(moduleA), "module-a does NOT use managed dep and should NOT be affected");
    }

    @Test
    void analyzeChanges_noEffectiveChangeInParent() throws Exception {
        // Setup: parent POM changed cosmetically (same properties, deps)
        Path root = setupReactorRoot();
        List<MavenProject> projects = createSimpleReactor(root);

        // Old POM is identical to current
        String oldParentPom = """
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

        Set<String> changedPoms = Set.of("pom.xml");
        Map<String, byte[]> oldPoms = new HashMap<>();
        oldPoms.put("pom.xml", oldParentPom.getBytes(StandardCharsets.UTF_8));

        Set<MavenProject> affected =
                analyzeChanges(changedPoms, oldPoms, projects, root).getAffectedProjects();

        assertTrue(affected.isEmpty(), "Cosmetic parent POM change should not affect any module");
    }

    @Test
    void analyzeChanges_newPomMarksAllChildren() throws Exception {
        Path root = setupReactorRoot();
        List<MavenProject> projects = createSimpleReactor(root);

        // POM didn't exist in old commit (no entry in oldPoms)
        Set<String> changedPoms = Set.of("pom.xml");
        Map<String, byte[]> oldPoms = new HashMap<>();

        Set<MavenProject> affected =
                analyzeChanges(changedPoms, oldPoms, projects, root).getAffectedProjects();

        assertEquals(3, affected.size(), "New parent POM should mark parent + all children");
    }

    @Test
    void analyzeChanges_returnsChangedProperties() throws Exception {
        Path root = setupReactorRoot();
        List<MavenProject> projects = createReactorWithPropertyUsage(root);

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
                    <dep.version>1.0</dep.version>
                  </properties>
                </project>
                """;

        Set<String> changedPoms = Set.of("pom.xml");
        Map<String, byte[]> oldPoms = new HashMap<>();
        oldPoms.put("pom.xml", oldParentPom.getBytes(StandardCharsets.UTF_8));

        PomChangeAnalyzer.Result result = analyzeChanges(changedPoms, oldPoms, projects, root);

        assertTrue(
                result.getChangedProperties().contains("dep.version"), "Changed properties should include dep.version");
    }

    @Test
    void analyzeChanges_propertyIndirectionReturnsChangedGAs() throws Exception {
        // Verify that the result includes the changed managed dep GAs for transitive checking
        Path root = setupReactorRoot();
        List<MavenProject> projects = createReactorWithManagedDepPropertyIndirection(root);

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
                    <spring.version>5.3.0</spring.version>
                  </properties>
                  <dependencyManagement><dependencies>
                    <dependency>
                      <groupId>org.springframework</groupId>
                      <artifactId>spring-core</artifactId>
                      <version>${spring.version}</version>
                    </dependency>
                  </dependencies></dependencyManagement>
                </project>
                """;

        Set<String> changedPoms = Set.of("pom.xml");
        Map<String, byte[]> oldPoms = new HashMap<>();
        oldPoms.put("pom.xml", oldParentPom.getBytes(StandardCharsets.UTF_8));

        PomChangeAnalyzer.Result result = analyzeChanges(changedPoms, oldPoms, projects, root);

        assertTrue(
                result.getChangedManagedDependencyGAs().contains("org.springframework:spring-core"),
                "Changed managed dep GAs should include spring-core (via property indirection)");
    }

    @Test
    void analyzeChanges_propertyIndirectionThroughManagedDep() throws Exception {
        // Parent defines <spring.version> used in depMgmt: <version>${spring.version}</version>
        // module-b uses managed dep spring-core (no version in child POM)
        // When spring.version changes, module-b should be affected
        Path root = setupReactorRoot();
        List<MavenProject> projects = createReactorWithManagedDepPropertyIndirection(root);

        // Old parent POM had spring.version=5.3.0
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
                    <spring.version>5.3.0</spring.version>
                  </properties>
                  <dependencyManagement><dependencies>
                    <dependency>
                      <groupId>org.springframework</groupId>
                      <artifactId>spring-core</artifactId>
                      <version>${spring.version}</version>
                    </dependency>
                  </dependencies></dependencyManagement>
                </project>
                """;

        Set<String> changedPoms = Set.of("pom.xml");
        Map<String, byte[]> oldPoms = new HashMap<>();
        oldPoms.put("pom.xml", oldParentPom.getBytes(StandardCharsets.UTF_8));

        Set<MavenProject> affected =
                analyzeChanges(changedPoms, oldPoms, projects, root).getAffectedProjects();

        MavenProject moduleB = projects.get(2);
        assertTrue(
                affected.contains(moduleB),
                "module-b uses managed dep spring-core whose version comes from changed property spring.version");

        MavenProject moduleA = projects.get(1);
        assertFalse(affected.contains(moduleA), "module-a does not use spring-core and should NOT be affected");
    }

    @Test
    void analyzeChanges_propertyIndirectionThroughManagedPlugin() throws Exception {
        // Parent defines <compiler.version> used in pluginMgmt
        // module-b uses maven-compiler-plugin (managed)
        // When compiler.version changes, module-b should be affected
        Path root = setupReactorRoot();
        List<MavenProject> projects = createReactorWithManagedPluginPropertyIndirection(root);

        // Old parent POM had compiler.version=3.11.0
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

        Set<String> changedPoms = Set.of("pom.xml");
        Map<String, byte[]> oldPoms = new HashMap<>();
        oldPoms.put("pom.xml", oldParentPom.getBytes(StandardCharsets.UTF_8));

        Set<MavenProject> affected =
                analyzeChanges(changedPoms, oldPoms, projects, root).getAffectedProjects();

        MavenProject moduleB = projects.get(2);
        assertTrue(
                affected.contains(moduleB),
                "module-b uses managed plugin maven-compiler-plugin whose version comes from changed property");

        MavenProject moduleA = projects.get(1);
        assertFalse(
                affected.contains(moduleA), "module-a does not use maven-compiler-plugin and should NOT be affected");
    }

    // --- Profile-aware POM comparison tests ---

    @Test
    void analyzeChanges_activeProfilePropertyChangeAffectsChild() throws Exception {
        Path root = setupReactorRoot();

        // Parent POM with profile "my-profile" having dep.version=2.0 (new value)
        String parentPomXml = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <modules><module>module-a</module><module>module-b</module></modules>
                  <profiles><profile>
                    <id>my-profile</id>
                    <properties><dep.version>2.0</dep.version></properties>
                  </profile></profiles>
                </project>
                """;
        writePom(root.resolve("pom.xml"), parentPomXml);

        String moduleAPomXml = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>module-a</artifactId>
                </project>
                """;
        writePom(root.resolve("module-a/pom.xml"), moduleAPomXml);

        // module-b references ${dep.version}
        String moduleBPomXml = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>module-b</artifactId>
                  <dependencies>
                    <dependency><groupId>com.example</groupId><artifactId>lib-x</artifactId><version>${dep.version}</version></dependency>
                  </dependencies>
                </project>
                """;
        writePom(root.resolve("module-b/pom.xml"), moduleBPomXml);

        List<MavenProject> projects = buildProjectList(root, parentPomXml, moduleAPomXml, moduleBPomXml);

        // Set profile as active on parent
        Profile activeProfile = new Profile();
        activeProfile.setId("my-profile");
        projects.get(0).setActiveProfiles(List.of(activeProfile));

        // Old parent POM had dep.version=1.0 in the profile
        String oldParentPom = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <modules><module>module-a</module><module>module-b</module></modules>
                  <profiles><profile>
                    <id>my-profile</id>
                    <properties><dep.version>1.0</dep.version></properties>
                  </profile></profiles>
                </project>
                """;

        Set<String> changedPoms = Set.of("pom.xml");
        Map<String, byte[]> oldPoms = new HashMap<>();
        oldPoms.put("pom.xml", oldParentPom.getBytes(StandardCharsets.UTF_8));

        PomChangeAnalyzer.Result result = analyzeChanges(changedPoms, oldPoms, projects, root);

        assertTrue(
                result.getAffectedProjects().contains(projects.get(2)),
                "module-b references ${dep.version} and should be affected by active profile change");
        assertFalse(
                result.getAffectedProjects().contains(projects.get(1)), "module-a does NOT reference ${dep.version}");
        assertTrue(result.getChangedProperties().contains("dep.version"));
    }

    @Test
    void analyzeChanges_inactiveProfileChangeIgnored() throws Exception {
        Path root = setupReactorRoot();

        // Parent POM with profile "my-profile" having dep.version=2.0
        String parentPomXml = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <modules><module>module-a</module><module>module-b</module></modules>
                  <profiles><profile>
                    <id>my-profile</id>
                    <properties><dep.version>2.0</dep.version></properties>
                  </profile></profiles>
                </project>
                """;
        writePom(root.resolve("pom.xml"), parentPomXml);

        String moduleAPomXml = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>module-a</artifactId>
                </project>
                """;
        writePom(root.resolve("module-a/pom.xml"), moduleAPomXml);

        String moduleBPomXml = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>module-b</artifactId>
                  <dependencies>
                    <dependency><groupId>com.example</groupId><artifactId>lib-x</artifactId><version>${dep.version}</version></dependency>
                  </dependencies>
                </project>
                """;
        writePom(root.resolve("module-b/pom.xml"), moduleBPomXml);

        List<MavenProject> projects = buildProjectList(root, parentPomXml, moduleAPomXml, moduleBPomXml);
        // Do NOT set active profiles - the profile is inactive

        String oldParentPom = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <modules><module>module-a</module><module>module-b</module></modules>
                  <profiles><profile>
                    <id>my-profile</id>
                    <properties><dep.version>1.0</dep.version></properties>
                  </profile></profiles>
                </project>
                """;

        Set<String> changedPoms = Set.of("pom.xml");
        Map<String, byte[]> oldPoms = new HashMap<>();
        oldPoms.put("pom.xml", oldParentPom.getBytes(StandardCharsets.UTF_8));

        Set<MavenProject> affected =
                analyzeChanges(changedPoms, oldPoms, projects, root).getAffectedProjects();

        assertTrue(affected.isEmpty(), "Inactive profile change should not affect any module");
    }

    @Test
    void analyzeChanges_activeProfileManagedDepChangeAffectsChild() throws Exception {
        Path root = setupReactorRoot();

        // Parent POM with profile that has managed dep lib-x:2.0
        String parentPomXml = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <modules><module>module-a</module><module>module-b</module></modules>
                  <profiles><profile>
                    <id>my-profile</id>
                    <dependencyManagement><dependencies>
                      <dependency><groupId>com.example</groupId><artifactId>lib-x</artifactId><version>2.0</version></dependency>
                    </dependencies></dependencyManagement>
                  </profile></profiles>
                </project>
                """;
        writePom(root.resolve("pom.xml"), parentPomXml);

        String moduleAPomXml = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>module-a</artifactId>
                </project>
                """;
        writePom(root.resolve("module-a/pom.xml"), moduleAPomXml);

        // module-b uses lib-x
        String moduleBPomXml = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>module-b</artifactId>
                  <dependencies>
                    <dependency><groupId>com.example</groupId><artifactId>lib-x</artifactId></dependency>
                  </dependencies>
                </project>
                """;
        writePom(root.resolve("module-b/pom.xml"), moduleBPomXml);

        List<MavenProject> projects = buildProjectList(root, parentPomXml, moduleAPomXml, moduleBPomXml);
        Profile activeProfile = new Profile();
        activeProfile.setId("my-profile");
        projects.get(0).setActiveProfiles(List.of(activeProfile));

        // Old parent POM had lib-x:1.0 in the profile
        String oldParentPom = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <modules><module>module-a</module><module>module-b</module></modules>
                  <profiles><profile>
                    <id>my-profile</id>
                    <dependencyManagement><dependencies>
                      <dependency><groupId>com.example</groupId><artifactId>lib-x</artifactId><version>1.0</version></dependency>
                    </dependencies></dependencyManagement>
                  </profile></profiles>
                </project>
                """;

        Set<String> changedPoms = Set.of("pom.xml");
        Map<String, byte[]> oldPoms = new HashMap<>();
        oldPoms.put("pom.xml", oldParentPom.getBytes(StandardCharsets.UTF_8));

        PomChangeAnalyzer.Result result = analyzeChanges(changedPoms, oldPoms, projects, root);

        assertTrue(
                result.getAffectedProjects().contains(projects.get(2)),
                "module-b uses managed dep lib-x from active profile and should be affected");
        assertFalse(result.getAffectedProjects().contains(projects.get(1)), "module-a does NOT use lib-x");
        assertTrue(result.getChangedManagedDependencyGAs().contains("com.example:lib-x"));
    }

    // --- Source directory, resource, and repository comparison tests (parameterized) ---

    @ParameterizedTest(name = "{0}")
    @MethodSource("parentElementChangeAffectsParentCases")
    void analyzeChanges_parentElementChangeAffectsParent(
            String description, String newParentPomXml, String oldParentPomXml) throws Exception {
        Path root = setupReactorRoot();

        writePom(root.resolve("pom.xml"), newParentPomXml);

        String moduleAPomXml = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>module-a</artifactId>
                </project>
                """;
        writePom(root.resolve("module-a/pom.xml"), moduleAPomXml);

        String moduleBPomXml = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>module-b</artifactId>
                </project>
                """;
        writePom(root.resolve("module-b/pom.xml"), moduleBPomXml);

        List<MavenProject> projects = buildProjectList(root, newParentPomXml, moduleAPomXml, moduleBPomXml);

        Set<String> changedPoms = Set.of("pom.xml");
        Map<String, byte[]> oldPoms = new HashMap<>();
        oldPoms.put("pom.xml", oldParentPomXml.getBytes(StandardCharsets.UTF_8));

        PomChangeAnalyzer.Result result = analyzeChanges(changedPoms, oldPoms, projects, root);

        assertTrue(
                result.getAffectedProjects().contains(projects.get(0)),
                "Parent should be self-affected (" + description + ")");
    }

    static Stream<Arguments> parentElementChangeAffectsParentCases() {
        return Stream.of(
                // Source directory changes
                Arguments.of(
                        "source directory changed",
                        parentPomWith("<build><sourceDirectory>src/main/java2</sourceDirectory></build>"),
                        parentPomWith("<build><sourceDirectory>src/main/java</sourceDirectory></build>")),
                Arguments.of(
                        "test source directory changed",
                        parentPomWith("<build><testSourceDirectory>src/test/java2</testSourceDirectory></build>"),
                        parentPomWith("<build><testSourceDirectory>src/test/java</testSourceDirectory></build>")),
                Arguments.of(
                        "script source directory changed",
                        parentPomWith(
                                "<build><scriptSourceDirectory>src/main/scripts2</scriptSourceDirectory></build>"),
                        parentPomWith(
                                "<build><scriptSourceDirectory>src/main/scripts</scriptSourceDirectory></build>")),
                // Resource changes
                Arguments.of(
                        "resource directory changed",
                        parentPomWith("<build><resources><resource><directory>src/main/resources2</directory>"
                                + "</resource></resources></build>"),
                        parentPomWith("<build><resources><resource><directory>src/main/resources</directory>"
                                + "</resource></resources></build>")),
                Arguments.of(
                        "resource filtering changed",
                        parentPomWith("<build><resources><resource><directory>src/main/resources</directory>"
                                + "<filtering>true</filtering></resource></resources></build>"),
                        parentPomWith("<build><resources><resource><directory>src/main/resources</directory>"
                                + "<filtering>false</filtering></resource></resources></build>")),
                Arguments.of(
                        "resource targetPath changed",
                        parentPomWith("<build><resources><resource><directory>src/main/resources</directory>"
                                + "<targetPath>META-INF/new</targetPath></resource></resources></build>"),
                        parentPomWith("<build><resources><resource><directory>src/main/resources</directory>"
                                + "<targetPath>META-INF/old</targetPath></resource></resources></build>")),
                Arguments.of(
                        "resource includes changed",
                        parentPomWith("<build><resources><resource><directory>src/main/resources</directory>"
                                + "<includes><include>**/*.xml</include><include>**/*.properties</include>"
                                + "</includes></resource></resources></build>"),
                        parentPomWith("<build><resources><resource><directory>src/main/resources</directory>"
                                + "<includes><include>**/*.xml</include></includes>"
                                + "</resource></resources></build>")),
                Arguments.of(
                        "resource includes changed only by duplicated entry",
                        parentPomWith("<build><resources><resource><directory>src/main/resources</directory>"
                                + "<includes><include>**/*.xml</include><include>**/*.xml</include>"
                                + "<include>**/*.properties</include></includes>"
                                + "</resource></resources></build>"),
                        parentPomWith("<build><resources><resource><directory>src/main/resources</directory>"
                                + "<includes><include>**/*.xml</include>"
                                + "<include>**/*.properties</include><include>**/*.properties</include>"
                                + "</includes></resource></resources></build>")),
                Arguments.of(
                        "resource excludes changed only by duplicated entry",
                        parentPomWith("<build><resources><resource><directory>src/main/resources</directory>"
                                + "<excludes><exclude>**/*.keystore</exclude><exclude>**/*.keystore</exclude>"
                                + "<exclude>**/*.jks</exclude></excludes>"
                                + "</resource></resources></build>"),
                        parentPomWith("<build><resources><resource><directory>src/main/resources</directory>"
                                + "<excludes><exclude>**/*.keystore</exclude>"
                                + "<exclude>**/*.jks</exclude><exclude>**/*.jks</exclude></excludes>"
                                + "</resource></resources></build>")),
                Arguments.of(
                        "test resource directory changed",
                        parentPomWith("<build><testResources><testResource>"
                                + "<directory>src/test/resources2</directory>"
                                + "</testResource></testResources></build>"),
                        parentPomWith("<build><testResources><testResource>"
                                + "<directory>src/test/resources</directory>"
                                + "</testResource></testResources></build>")),
                // Repository changes
                Arguments.of(
                        "repository added",
                        parentPomWith("<repositories><repository><id>central</id>"
                                + "<url>https://repo.maven.apache.org/maven2</url>"
                                + "</repository></repositories>"),
                        parentPomWith("")),
                Arguments.of(
                        "repository URL changed",
                        parentPomWith("<repositories><repository><id>custom</id>"
                                + "<url>https://new-repo.example.com/maven2</url>"
                                + "</repository></repositories>"),
                        parentPomWith("<repositories><repository><id>custom</id>"
                                + "<url>https://old-repo.example.com/maven2</url>"
                                + "</repository></repositories>")),
                Arguments.of(
                        "repository snapshot policy changed",
                        parentPomWith("<repositories><repository><id>custom</id>"
                                + "<url>https://repo.example.com/maven2</url>"
                                + "<snapshots><enabled>true</enabled></snapshots>"
                                + "</repository></repositories>"),
                        parentPomWith("<repositories><repository><id>custom</id>"
                                + "<url>https://repo.example.com/maven2</url>"
                                + "<snapshots><enabled>false</enabled></snapshots>"
                                + "</repository></repositories>")),
                Arguments.of(
                        "plugin repository added",
                        parentPomWith("<pluginRepositories><pluginRepository><id>plugin-repo</id>"
                                + "<url>https://plugins.example.com/maven2</url>"
                                + "</pluginRepository></pluginRepositories>"),
                        parentPomWith("")),
                Arguments.of(
                        "repository layout changed",
                        parentPomWith("<repositories><repository><id>custom</id>"
                                + "<url>https://repo.example.com/maven2</url>"
                                + "<layout>default</layout></repository></repositories>"),
                        parentPomWith("<repositories><repository><id>custom</id>"
                                + "<url>https://repo.example.com/maven2</url>"
                                + "<layout>legacy</layout></repository></repositories>")),
                Arguments.of(
                        "repository updatePolicy changed",
                        parentPomWith("<repositories><repository><id>custom</id>"
                                + "<url>https://repo.example.com/maven2</url>"
                                + "<snapshots><enabled>true</enabled>"
                                + "<updatePolicy>always</updatePolicy></snapshots>"
                                + "</repository></repositories>"),
                        parentPomWith("<repositories><repository><id>custom</id>"
                                + "<url>https://repo.example.com/maven2</url>"
                                + "<snapshots><enabled>true</enabled>"
                                + "<updatePolicy>daily</updatePolicy></snapshots>"
                                + "</repository></repositories>")),
                Arguments.of(
                        "repository checksumPolicy changed",
                        parentPomWith("<repositories><repository><id>custom</id>"
                                + "<url>https://repo.example.com/maven2</url>"
                                + "<releases><checksumPolicy>fail</checksumPolicy></releases>"
                                + "</repository></repositories>"),
                        parentPomWith("<repositories><repository><id>custom</id>"
                                + "<url>https://repo.example.com/maven2</url>"
                                + "<releases><checksumPolicy>warn</checksumPolicy></releases>"
                                + "</repository></repositories>")),
                Arguments.of(
                        "repository removed",
                        parentPomWith(""),
                        parentPomWith("<repositories><repository><id>custom</id>"
                                + "<url>https://repo.example.com/maven2</url>"
                                + "</repository></repositories>")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("parentElementNoChangeCases")
    void analyzeChanges_parentElementNoChange(String description, String newParentPomXml, String oldParentPomXml)
            throws Exception {
        Path root = setupReactorRoot();

        writePom(root.resolve("pom.xml"), newParentPomXml);

        String moduleAPomXml = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>module-a</artifactId>
                </project>
                """;
        writePom(root.resolve("module-a/pom.xml"), moduleAPomXml);

        String moduleBPomXml = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>module-b</artifactId>
                </project>
                """;
        writePom(root.resolve("module-b/pom.xml"), moduleBPomXml);

        List<MavenProject> projects = buildProjectList(root, newParentPomXml, moduleAPomXml, moduleBPomXml);

        Set<String> changedPoms = Set.of("pom.xml");
        Map<String, byte[]> oldPoms = new HashMap<>();
        oldPoms.put("pom.xml", oldParentPomXml.getBytes(StandardCharsets.UTF_8));

        PomChangeAnalyzer.Result result = analyzeChanges(changedPoms, oldPoms, projects, root);

        assertTrue(result.getAffectedProjects().isEmpty(), description);
    }

    static Stream<Arguments> parentElementNoChangeCases() {
        String resourceIncludesA = "<build><resources><resource><directory>src/main/resources</directory>"
                + "<includes><include>**/*.xml</include><include>**/*.properties</include>"
                + "</includes></resource></resources></build>";
        String resourceIncludesB = "<build><resources><resource><directory>src/main/resources</directory>"
                + "<includes><include>**/*.properties</include><include>**/*.xml</include>"
                + "</includes></resource></resources></build>";

        return Stream.of(
                Arguments.of(
                        "Reordered includes with same patterns should not affect any module",
                        parentPomWith(resourceIncludesB),
                        parentPomWith(resourceIncludesA)),
                Arguments.of(
                        "Same source directories should not affect any module",
                        parentPomWith("<build><sourceDirectory>src/main/java</sourceDirectory></build>"),
                        parentPomWith("<build><sourceDirectory>src/main/java</sourceDirectory></build>")),
                Arguments.of(
                        "Same repositories should not affect any module",
                        parentPomWith("<repositories><repository><id>central</id>"
                                + "<url>https://repo.maven.apache.org/maven2</url>"
                                + "</repository></repositories>"),
                        parentPomWith("<repositories><repository><id>central</id>"
                                + "<url>https://repo.maven.apache.org/maven2</url>"
                                + "</repository></repositories>")));
    }

    private static String parentPomWith(String extraSection) {
        return """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <modules><module>module-a</module><module>module-b</module></modules>
                  %s
                </project>
                """.formatted(extraSection);
    }

    // --- Resource filtering property tracking tests ---

    @Test
    void analyzeChanges_filteredResourcePropertyChangeAffectsChild() throws Exception {
        Path root = setupReactorRoot();

        // Parent POM with app.version=2.0 (new)
        String parentPomXml = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <modules><module>module-a</module><module>module-b</module></modules>
                  <properties>
                    <app.version>2.0</app.version>
                  </properties>
                </project>
                """;
        writePom(root.resolve("pom.xml"), parentPomXml);

        // module-a: no filtered resources
        String moduleAPomXml = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>module-a</artifactId>
                </project>
                """;
        writePom(root.resolve("module-a/pom.xml"), moduleAPomXml);

        // module-b: has filtered resources
        String moduleBPomXml = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>module-b</artifactId>
                </project>
                """;
        writePom(root.resolve("module-b/pom.xml"), moduleBPomXml);

        // Create filtered resource file referencing ${app.version}
        Path resourceDir = root.resolve("module-b/src/main/resources");
        Files.createDirectories(resourceDir);
        Files.write(
                resourceDir.resolve("application.properties"),
                "app.version=${app.version}\n".getBytes(StandardCharsets.UTF_8));

        List<MavenProject> projects = buildProjectList(root, parentPomXml, moduleAPomXml, moduleBPomXml);

        // Set up filtered resource on module-b
        MavenProject moduleB = projects.get(2);
        Resource resource = new Resource();
        resource.setDirectory(root.resolve("module-b/src/main/resources").toString());
        resource.setFiltering(true);
        Build build = new Build();
        build.addResource(resource);
        moduleB.getModel().setBuild(build);

        // Old parent POM had app.version=1.0
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
                    <app.version>1.0</app.version>
                  </properties>
                </project>
                """;

        Set<String> changedPoms = Set.of("pom.xml");
        Map<String, byte[]> oldPoms = new HashMap<>();
        oldPoms.put("pom.xml", oldParentPom.getBytes(StandardCharsets.UTF_8));

        Set<MavenProject> affected =
                analyzeChanges(changedPoms, oldPoms, projects, root).getAffectedProjects();

        assertTrue(
                affected.contains(moduleB),
                "module-b has filtered resource with ${app.version} and should be affected");
        assertFalse(
                affected.contains(projects.get(1)), "module-a has no filtered resources and should NOT be affected");
    }

    @Test
    void analyzeChanges_filteredResourceWithoutPropertyRefNotAffected() throws Exception {
        Path root = setupReactorRoot();

        // Parent POM with app.version=2.0
        String parentPomXml = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <modules><module>module-a</module><module>module-b</module></modules>
                  <properties>
                    <app.version>2.0</app.version>
                  </properties>
                </project>
                """;
        writePom(root.resolve("pom.xml"), parentPomXml);

        String moduleAPomXml = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>module-a</artifactId>
                </project>
                """;
        writePom(root.resolve("module-a/pom.xml"), moduleAPomXml);

        String moduleBPomXml = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>module-b</artifactId>
                </project>
                """;
        writePom(root.resolve("module-b/pom.xml"), moduleBPomXml);

        // Create filtered resource that does NOT reference ${app.version}
        Path resourceDir = root.resolve("module-b/src/main/resources");
        Files.createDirectories(resourceDir);
        Files.write(resourceDir.resolve("config.properties"), "key=value\n".getBytes(StandardCharsets.UTF_8));

        List<MavenProject> projects = buildProjectList(root, parentPomXml, moduleAPomXml, moduleBPomXml);

        // Set up filtered resource on module-b
        MavenProject moduleB = projects.get(2);
        Resource resource = new Resource();
        resource.setDirectory(root.resolve("module-b/src/main/resources").toString());
        resource.setFiltering(true);
        Build build = new Build();
        build.addResource(resource);
        moduleB.getModel().setBuild(build);

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
                    <app.version>1.0</app.version>
                  </properties>
                </project>
                """;

        Set<String> changedPoms = Set.of("pom.xml");
        Map<String, byte[]> oldPoms = new HashMap<>();
        oldPoms.put("pom.xml", oldParentPom.getBytes(StandardCharsets.UTF_8));

        Set<MavenProject> affected =
                analyzeChanges(changedPoms, oldPoms, projects, root).getAffectedProjects();

        assertFalse(
                affected.contains(moduleB),
                "module-b's filtered resources don't reference ${app.version} - should NOT be affected");
    }

    @Test
    void analyzeChanges_filteredResourceBinaryFileSkipped() throws Exception {
        Path root = setupReactorRoot();

        String parentPomXml = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <modules><module>module-a</module></modules>
                  <properties>
                    <app.version>2.0</app.version>
                  </properties>
                </project>
                """;
        writePom(root.resolve("pom.xml"), parentPomXml);

        String moduleAPomXml = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>module-a</artifactId>
                </project>
                """;
        writePom(root.resolve("module-a/pom.xml"), moduleAPomXml);

        // Create a binary file (contains NUL bytes) in filtered resources
        Path resourceDir = root.resolve("module-a/src/main/resources");
        Files.createDirectories(resourceDir);
        byte[] binaryContent = new byte[100];
        binaryContent[50] = 0; // NUL byte makes it binary
        Files.write(resourceDir.resolve("image.png"), binaryContent);

        MavenProject parent = createProject(
                "com.example", "parent", "1.0", root.resolve("pom.xml").toFile());
        parent.setOriginalModel(parseModel(parentPomXml));
        parent.getModel().setPackaging("pom");

        MavenProject moduleA = createProject(
                "com.example",
                "module-a",
                "1.0",
                root.resolve("module-a/pom.xml").toFile());
        moduleA.setOriginalModel(parseModel(moduleAPomXml));
        moduleA.setParent(parent);
        Resource resource = new Resource();
        resource.setDirectory(resourceDir.toString());
        resource.setFiltering(true);
        Build build = new Build();
        build.addResource(resource);
        moduleA.getModel().setBuild(build);

        List<MavenProject> projects = List.of(parent, moduleA);

        String oldParentPom = parentPomXml.replace("<app.version>2.0</app.version>", "<app.version>1.0</app.version>");

        Set<MavenProject> affected = analyzeChanges(
                        Set.of("pom.xml"),
                        Map.of("pom.xml", oldParentPom.getBytes(StandardCharsets.UTF_8)),
                        projects,
                        root)
                .getAffectedProjects();

        assertFalse(
                affected.contains(moduleA),
                "module-a has only binary files in filtered resources and should NOT be affected");
    }

    @Test
    void analyzeChanges_filteredResourceOversizedTextFileMarkedAsAffected() throws Exception {
        Path root = setupReactorRoot();

        String parentPomXml = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <modules><module>module-a</module></modules>
                  <properties>
                    <app.version>2.0</app.version>
                  </properties>
                </project>
                """;
        writePom(root.resolve("pom.xml"), parentPomXml);

        String moduleAPomXml = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>module-a</artifactId>
                </project>
                """;
        writePom(root.resolve("module-a/pom.xml"), moduleAPomXml);

        // Create a large text file (no NUL bytes) exceeding a small custom limit
        Path resourceDir = root.resolve("module-a/src/main/resources");
        Files.createDirectories(resourceDir);
        byte[] largeText = new byte[2000];
        java.util.Arrays.fill(largeText, (byte) 'x');
        Files.write(resourceDir.resolve("large.txt"), largeText);

        MavenProject parent = createProject(
                "com.example", "parent", "1.0", root.resolve("pom.xml").toFile());
        parent.setOriginalModel(parseModel(parentPomXml));
        parent.getModel().setPackaging("pom");

        MavenProject moduleA = createProject(
                "com.example",
                "module-a",
                "1.0",
                root.resolve("module-a/pom.xml").toFile());
        moduleA.setOriginalModel(parseModel(moduleAPomXml));
        moduleA.setParent(parent);
        Resource resource = new Resource();
        resource.setDirectory(resourceDir.toString());
        resource.setFiltering(true);
        Build build = new Build();
        build.addResource(resource);
        moduleA.getModel().setBuild(build);

        List<MavenProject> projects = List.of(parent, moduleA);

        String oldParentPom = parentPomXml.replace("<app.version>2.0</app.version>", "<app.version>1.0</app.version>");

        // Use a custom small limit (1000 bytes) so the 2000-byte file exceeds it
        Set<MavenProject> affected = analyzeChanges(
                        Set.of("pom.xml"),
                        Map.of("pom.xml", oldParentPom.getBytes(StandardCharsets.UTF_8)),
                        projects,
                        root,
                        1000L)
                .getAffectedProjects();

        assertTrue(
                affected.contains(moduleA),
                "module-a has oversized text file in filtered resources and should be conservatively affected");
    }

    @Test
    void analyzeChanges_filteredResourceOversizedBinaryFileSkipped() throws Exception {
        Path root = setupReactorRoot();

        String parentPomXml = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <modules><module>module-a</module></modules>
                  <properties>
                    <app.version>2.0</app.version>
                  </properties>
                </project>
                """;
        writePom(root.resolve("pom.xml"), parentPomXml);

        String moduleAPomXml = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>module-a</artifactId>
                </project>
                """;
        writePom(root.resolve("module-a/pom.xml"), moduleAPomXml);

        // Create a large binary file (contains NUL bytes AND exceeds the size limit)
        // Binary detection should run BEFORE size check, so it should be skipped
        Path resourceDir = root.resolve("module-a/src/main/resources");
        Files.createDirectories(resourceDir);
        byte[] largeBinary = new byte[2000];
        largeBinary[10] = 0; // NUL byte makes it binary
        Files.write(resourceDir.resolve("font.woff2"), largeBinary);

        MavenProject parent = createProject(
                "com.example", "parent", "1.0", root.resolve("pom.xml").toFile());
        parent.setOriginalModel(parseModel(parentPomXml));
        parent.getModel().setPackaging("pom");

        MavenProject moduleA = createProject(
                "com.example",
                "module-a",
                "1.0",
                root.resolve("module-a/pom.xml").toFile());
        moduleA.setOriginalModel(parseModel(moduleAPomXml));
        moduleA.setParent(parent);
        Resource resource = new Resource();
        resource.setDirectory(resourceDir.toString());
        resource.setFiltering(true);
        Build build = new Build();
        build.addResource(resource);
        moduleA.getModel().setBuild(build);

        List<MavenProject> projects = List.of(parent, moduleA);

        String oldParentPom = parentPomXml.replace("<app.version>2.0</app.version>", "<app.version>1.0</app.version>");

        // Use a small limit so the file would exceed it IF it weren't binary
        Set<MavenProject> affected = analyzeChanges(
                        Set.of("pom.xml"),
                        Map.of("pom.xml", oldParentPom.getBytes(StandardCharsets.UTF_8)),
                        projects,
                        root,
                        1000L)
                .getAffectedProjects();

        assertFalse(
                affected.contains(moduleA),
                "module-a has only a large binary file - binary check should bail before size limit kicks in");
    }

    // --- New managed dep filtering tests (issue #131) ---

    @Test
    void analyzeChanges_newManagedDepNotUsedByAnyModule_noChildAffected() throws Exception {
        // Parent adds a NEW managed dep (lib-new) that no child module uses.
        // No child should be marked as directly affected. Brand-new managed deps are
        // excluded from changedManagedDependencyGAs (issue #131) — only modifications
        // and removals of existing managed deps are tracked.
        Path root = setupReactorRoot();

        // Current parent POM: has lib-x (existing, bumped) and lib-new (brand new)
        String parentPomXml = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <modules><module>module-a</module><module>module-b</module></modules>
                  <dependencyManagement><dependencies>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>lib-x</artifactId>
                      <version>2.0</version>
                    </dependency>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>lib-new</artifactId>
                      <version>1.0</version>
                    </dependency>
                  </dependencies></dependencyManagement>
                </project>
                """;
        writePom(root.resolve("pom.xml"), parentPomXml);

        // module-a: uses lib-x (affected by version bump), does NOT use lib-new
        String moduleAPomXml = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>module-a</artifactId>
                  <dependencies>
                    <dependency><groupId>com.example</groupId><artifactId>lib-x</artifactId></dependency>
                  </dependencies>
                </project>
                """;
        writePom(root.resolve("module-a/pom.xml"), moduleAPomXml);

        // module-b: does NOT use lib-x or lib-new
        String moduleBPomXml = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>module-b</artifactId>
                </project>
                """;
        writePom(root.resolve("module-b/pom.xml"), moduleBPomXml);

        List<MavenProject> projects = buildProjectList(root, parentPomXml, moduleAPomXml, moduleBPomXml);

        // Old parent POM: only had lib-x:1.0 (no lib-new)
        String oldParentPom = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <modules><module>module-a</module><module>module-b</module></modules>
                  <dependencyManagement><dependencies>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>lib-x</artifactId>
                      <version>1.0</version>
                    </dependency>
                  </dependencies></dependencyManagement>
                </project>
                """;

        Set<String> changedPoms = Set.of("pom.xml");
        Map<String, byte[]> oldPoms = new HashMap<>();
        oldPoms.put("pom.xml", oldParentPom.getBytes(StandardCharsets.UTF_8));

        PomChangeAnalyzer.Result result = analyzeChanges(changedPoms, oldPoms, projects, root);

        MavenProject moduleA = projects.get(1);
        MavenProject moduleB = projects.get(2);

        assertTrue(
                result.getAffectedProjects().contains(moduleA),
                "module-a uses lib-x whose version was bumped, should be affected");
        assertFalse(
                result.getAffectedProjects().contains(moduleB),
                "module-b does not use lib-x or lib-new, should NOT be affected");
        assertTrue(
                result.getChangedManagedDependencyGAs().contains("com.example:lib-x"),
                "lib-x version was bumped (modified), should be in changedManagedDependencyGAs");
        assertFalse(
                result.getChangedManagedDependencyGAs().contains("com.example:lib-new"),
                "lib-new is brand new (not a modification), should NOT be in changedManagedDependencyGAs");
    }

    @Test
    void analyzeChanges_newManagedDepUsedByOneModule_notIncludedInDownstream() throws Exception {
        // Parent adds a NEW managed dep (lib-new) and one child uses it.
        // Brand-new managed deps are excluded from change analysis (issue #131) — even
        // if a child references the GA, the dep is new (not modified) so it should not
        // trigger a rebuild. The dependency tree handles actual resolution changes.
        Path root = setupReactorRoot();

        String parentPomXml = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <modules><module>module-a</module><module>module-b</module></modules>
                  <dependencyManagement><dependencies>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>lib-new</artifactId>
                      <version>1.0</version>
                    </dependency>
                  </dependencies></dependencyManagement>
                </project>
                """;
        writePom(root.resolve("pom.xml"), parentPomXml);

        // module-a: uses lib-new
        String moduleAPomXml = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>module-a</artifactId>
                  <dependencies>
                    <dependency><groupId>com.example</groupId><artifactId>lib-new</artifactId></dependency>
                  </dependencies>
                </project>
                """;
        writePom(root.resolve("module-a/pom.xml"), moduleAPomXml);

        // module-b: does NOT use lib-new
        String moduleBPomXml = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>module-b</artifactId>
                </project>
                """;
        writePom(root.resolve("module-b/pom.xml"), moduleBPomXml);

        List<MavenProject> projects = buildProjectList(root, parentPomXml, moduleAPomXml, moduleBPomXml);

        // Old parent POM: no managed deps at all
        String oldParentPom = """
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

        Set<String> changedPoms = Set.of("pom.xml");
        Map<String, byte[]> oldPoms = new HashMap<>();
        oldPoms.put("pom.xml", oldParentPom.getBytes(StandardCharsets.UTF_8));

        PomChangeAnalyzer.Result result = analyzeChanges(changedPoms, oldPoms, projects, root);

        MavenProject moduleA = projects.get(1);
        MavenProject moduleB = projects.get(2);

        assertFalse(
                result.getAffectedProjects().contains(moduleA),
                "module-a uses lib-new but it's brand new (not modified), should NOT be affected");
        assertFalse(
                result.getAffectedProjects().contains(moduleB),
                "module-b does not use lib-new, should NOT be affected");
        assertFalse(
                result.getChangedManagedDependencyGAs().contains("com.example:lib-new"),
                "lib-new is brand new (not a modification), should NOT be in changedManagedDependencyGAs");
    }

    @Test
    void analyzeChanges_modifiedManagedDepAlwaysIncluded() throws Exception {
        // Parent bumps version of existing managed dep lib-x. Even if no child uses it
        // directly, it must remain in changedManagedDependencyGAs because it could affect
        // transitive resolution.
        Path root = setupReactorRoot();

        String parentPomXml = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <modules><module>module-a</module><module>module-b</module></modules>
                  <dependencyManagement><dependencies>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>lib-x</artifactId>
                      <version>2.0</version>
                    </dependency>
                  </dependencies></dependencyManagement>
                </project>
                """;
        writePom(root.resolve("pom.xml"), parentPomXml);

        // Neither module uses lib-x directly
        String moduleAPomXml = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>module-a</artifactId>
                </project>
                """;
        writePom(root.resolve("module-a/pom.xml"), moduleAPomXml);

        String moduleBPomXml = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>module-b</artifactId>
                </project>
                """;
        writePom(root.resolve("module-b/pom.xml"), moduleBPomXml);

        List<MavenProject> projects = buildProjectList(root, parentPomXml, moduleAPomXml, moduleBPomXml);

        // Old parent POM: had lib-x:1.0 (version bump, NOT new)
        String oldParentPom = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <modules><module>module-a</module><module>module-b</module></modules>
                  <dependencyManagement><dependencies>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>lib-x</artifactId>
                      <version>1.0</version>
                    </dependency>
                  </dependencies></dependencyManagement>
                </project>
                """;

        Set<String> changedPoms = Set.of("pom.xml");
        Map<String, byte[]> oldPoms = new HashMap<>();
        oldPoms.put("pom.xml", oldParentPom.getBytes(StandardCharsets.UTF_8));

        PomChangeAnalyzer.Result result = analyzeChanges(changedPoms, oldPoms, projects, root);

        assertTrue(
                result.getChangedManagedDependencyGAs().contains("com.example:lib-x"),
                "Modified (version-bumped) managed dep must always be in changedManagedDependencyGAs, even if unused");
    }

    @Test
    void analyzeChanges_newManagedDepOnlyInParent_allNewUnused_noChildAffected() throws Exception {
        // Parent adds ONLY new managed deps (no existing deps modified). No child uses any.
        // No children should be directly affected. Brand-new managed deps are excluded
        // from changedManagedDependencyGAs (issue #131).
        Path root = setupReactorRoot();

        String parentPomXml = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <modules><module>module-a</module><module>module-b</module></modules>
                  <dependencyManagement><dependencies>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>lib-new1</artifactId>
                      <version>1.0</version>
                    </dependency>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>lib-new2</artifactId>
                      <version>1.0</version>
                    </dependency>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>lib-new3</artifactId>
                      <version>1.0</version>
                    </dependency>
                  </dependencies></dependencyManagement>
                </project>
                """;
        writePom(root.resolve("pom.xml"), parentPomXml);

        String moduleAPomXml = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>module-a</artifactId>
                </project>
                """;
        writePom(root.resolve("module-a/pom.xml"), moduleAPomXml);

        String moduleBPomXml = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>module-b</artifactId>
                </project>
                """;
        writePom(root.resolve("module-b/pom.xml"), moduleBPomXml);

        List<MavenProject> projects = buildProjectList(root, parentPomXml, moduleAPomXml, moduleBPomXml);

        // Old parent POM: no managed deps
        String oldParentPom = """
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

        Set<String> changedPoms = Set.of("pom.xml");
        Map<String, byte[]> oldPoms = new HashMap<>();
        oldPoms.put("pom.xml", oldParentPom.getBytes(StandardCharsets.UTF_8));

        PomChangeAnalyzer.Result result = analyzeChanges(changedPoms, oldPoms, projects, root);

        MavenProject moduleA = projects.get(1);
        MavenProject moduleB = projects.get(2);

        assertFalse(
                result.getAffectedProjects().contains(moduleA),
                "module-a does not use any new managed dep, should NOT be affected");
        assertFalse(
                result.getAffectedProjects().contains(moduleB),
                "module-b does not use any new managed dep, should NOT be affected");
        assertTrue(
                result.getChangedManagedDependencyGAs().isEmpty(),
                "All managed deps are brand new (not modifications), changedManagedDependencyGAs should be empty");
    }

    // --- New POM and edge case tests ---

    @Test
    void analyzeChanges_newPomMarksAllChildrenAffected() throws Exception {
        Path root = setupReactorRoot();
        List<MavenProject> projects = createSimpleReactor(root);

        // No old POM bytes = new POM file
        Set<String> changedPoms = Set.of("pom.xml");
        Map<String, byte[]> oldPoms = new HashMap<>();
        // Intentionally no entry for "pom.xml" (null = new file)

        Set<MavenProject> affected =
                analyzeChanges(changedPoms, oldPoms, projects, root).getAffectedProjects();

        assertTrue(affected.contains(projects.get(0)), "Parent should be affected");
        assertTrue(affected.contains(projects.get(1)), "module-a should be affected (new parent POM)");
        assertTrue(affected.contains(projects.get(2)), "module-b should be affected (new parent POM)");
    }

    @Test
    void analyzeChanges_profileRemovedWhileActiveAffectsParent() throws Exception {
        Path root = setupReactorRoot();

        // New parent POM: profile 'prod' removed
        String parentPomXml = """
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
        writePom(root.resolve("pom.xml"), parentPomXml);

        String moduleAPomXml = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>module-a</artifactId>
                </project>
                """;
        writePom(root.resolve("module-a/pom.xml"), moduleAPomXml);

        String moduleBPomXml = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>module-b</artifactId>
                </project>
                """;
        writePom(root.resolve("module-b/pom.xml"), moduleBPomXml);

        List<MavenProject> projects = buildProjectList(root, parentPomXml, moduleAPomXml, moduleBPomXml);

        // Profile 'prod' is active but has been removed from the POM
        Profile activeProfile = new Profile();
        activeProfile.setId("prod");
        projects.get(0).setActiveProfiles(List.of(activeProfile));

        // Old parent POM had profile 'prod' with direct dependencies
        String oldParentPom = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <modules><module>module-a</module><module>module-b</module></modules>
                  <profiles><profile>
                    <id>prod</id>
                    <properties><prod.version>1.0</prod.version></properties>
                    <dependencies>
                      <dependency><groupId>com.prod</groupId><artifactId>lib</artifactId><version>1.0</version></dependency>
                    </dependencies>
                  </profile></profiles>
                </project>
                """;

        Set<String> changedPoms = Set.of("pom.xml");
        Map<String, byte[]> oldPoms = new HashMap<>();
        oldPoms.put("pom.xml", oldParentPom.getBytes(StandardCharsets.UTF_8));

        PomChangeAnalyzer.Result result = analyzeChanges(changedPoms, oldPoms, projects, root);

        // Parent should be self-affected because the removed profile had direct dependencies
        assertTrue(
                result.getAffectedProjects().contains(projects.get(0)),
                "Parent should be affected (active profile with deps removed)");
        assertTrue(
                result.getChangedProperties().contains("prod.version"),
                "Removed profile properties should be detected");
    }

    @Test
    void analyzeChanges_profileDirectDepsChangeAffectsParent() throws Exception {
        Path root = setupReactorRoot();

        String parentPomXml = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <modules><module>module-a</module><module>module-b</module></modules>
                  <profiles><profile>
                    <id>prod</id>
                    <dependencies>
                      <dependency><groupId>com.prod</groupId><artifactId>lib</artifactId><version>2.0</version></dependency>
                    </dependencies>
                  </profile></profiles>
                </project>
                """;
        writePom(root.resolve("pom.xml"), parentPomXml);

        String moduleAPomXml = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>module-a</artifactId>
                </project>
                """;
        writePom(root.resolve("module-a/pom.xml"), moduleAPomXml);

        String moduleBPomXml = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>module-b</artifactId>
                </project>
                """;
        writePom(root.resolve("module-b/pom.xml"), moduleBPomXml);

        List<MavenProject> projects = buildProjectList(root, parentPomXml, moduleAPomXml, moduleBPomXml);
        Profile activeProfile = new Profile();
        activeProfile.setId("prod");
        projects.get(0).setActiveProfiles(List.of(activeProfile));

        // Old POM: profile had lib version 1.0
        String oldParentPom = parentPomXml.replace("<version>2.0</version>", "<version>1.0</version>");

        Set<String> changedPoms = Set.of("pom.xml");
        Map<String, byte[]> oldPoms = new HashMap<>();
        oldPoms.put("pom.xml", oldParentPom.getBytes(StandardCharsets.UTF_8));

        PomChangeAnalyzer.Result result = analyzeChanges(changedPoms, oldPoms, projects, root);

        assertTrue(
                result.getAffectedProjects().contains(projects.get(0)),
                "Parent should be self-affected (direct deps changed in active profile)");
    }

    @Test
    void analyzeChanges_leafModulePomChangeAffectsOnlySelf() throws Exception {
        Path root = setupReactorRoot();
        List<MavenProject> projects = createSimpleReactor(root);

        // Change only module-a's POM (leaf module)
        Set<String> changedPoms = Set.of("module-a/pom.xml");
        Map<String, byte[]> oldPoms = new HashMap<>();

        Set<MavenProject> affected =
                analyzeChanges(changedPoms, oldPoms, projects, root).getAffectedProjects();

        assertTrue(affected.contains(projects.get(1)), "module-a should be affected");
        assertFalse(affected.contains(projects.get(2)), "module-b should NOT be affected");
        assertEquals(1, affected.size());
    }

    @Test
    void analyzeChanges_directPluginChangeAffectsParent() throws Exception {
        Path root = setupReactorRoot();

        String parentPomXml = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <modules><module>module-a</module><module>module-b</module></modules>
                  <build><plugins>
                    <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.12.0</version></plugin>
                  </plugins></build>
                </project>
                """;
        writePom(root.resolve("pom.xml"), parentPomXml);

        String moduleAPomXml = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>module-a</artifactId>
                </project>
                """;
        writePom(root.resolve("module-a/pom.xml"), moduleAPomXml);

        String moduleBPomXml = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>module-b</artifactId>
                </project>
                """;
        writePom(root.resolve("module-b/pom.xml"), moduleBPomXml);

        List<MavenProject> projects = buildProjectList(root, parentPomXml, moduleAPomXml, moduleBPomXml);

        String oldParentPom = parentPomXml.replace("3.12.0", "3.11.0");

        Set<String> changedPoms = Set.of("pom.xml");
        Map<String, byte[]> oldPoms = new HashMap<>();
        oldPoms.put("pom.xml", oldParentPom.getBytes(StandardCharsets.UTF_8));

        PomChangeAnalyzer.Result result = analyzeChanges(changedPoms, oldPoms, projects, root);

        assertTrue(
                result.getAffectedProjects().contains(projects.get(0)),
                "Parent should be self-affected (direct plugin changed)");
    }

    @Test
    void analyzeChanges_directDependencyChangeAffectsParent() throws Exception {
        Path root = setupReactorRoot();

        String parentPomXml = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <modules><module>module-a</module><module>module-b</module></modules>
                  <dependencies>
                    <dependency><groupId>junit</groupId><artifactId>junit</artifactId><version>4.13.2</version></dependency>
                  </dependencies>
                </project>
                """;
        writePom(root.resolve("pom.xml"), parentPomXml);

        String moduleAPomXml = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>module-a</artifactId>
                </project>
                """;
        writePom(root.resolve("module-a/pom.xml"), moduleAPomXml);

        String moduleBPomXml = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>module-b</artifactId>
                </project>
                """;
        writePom(root.resolve("module-b/pom.xml"), moduleBPomXml);

        List<MavenProject> projects = buildProjectList(root, parentPomXml, moduleAPomXml, moduleBPomXml);

        String oldParentPom = parentPomXml.replace("4.13.2", "4.13.1");

        Set<String> changedPoms = Set.of("pom.xml");
        Map<String, byte[]> oldPoms = new HashMap<>();
        oldPoms.put("pom.xml", oldParentPom.getBytes(StandardCharsets.UTF_8));

        PomChangeAnalyzer.Result result = analyzeChanges(changedPoms, oldPoms, projects, root);

        assertTrue(
                result.getAffectedProjects().contains(projects.get(0)),
                "Parent should be self-affected (direct dependency changed)");
    }

    @Test
    void analyzeChanges_packagingChangeAffectsParent() throws Exception {
        Path root = setupReactorRoot();

        String parentPomXml = """
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
        writePom(root.resolve("pom.xml"), parentPomXml);

        String moduleAPomXml = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>module-a</artifactId>
                </project>
                """;
        writePom(root.resolve("module-a/pom.xml"), moduleAPomXml);

        String moduleBPomXml = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>module-b</artifactId>
                </project>
                """;
        writePom(root.resolve("module-b/pom.xml"), moduleBPomXml);

        List<MavenProject> projects = buildProjectList(root, parentPomXml, moduleAPomXml, moduleBPomXml);

        // Old POM had jar packaging
        String oldParentPom = parentPomXml.replace("<packaging>pom</packaging>", "<packaging>jar</packaging>");

        Set<String> changedPoms = Set.of("pom.xml");
        Map<String, byte[]> oldPoms = new HashMap<>();
        oldPoms.put("pom.xml", oldParentPom.getBytes(StandardCharsets.UTF_8));

        PomChangeAnalyzer.Result result = analyzeChanges(changedPoms, oldPoms, projects, root);

        assertTrue(
                result.getAffectedProjects().contains(projects.get(0)),
                "Parent should be self-affected (packaging changed)");
    }

    @Test
    void analyzeChanges_unmatchedPomSkipped() throws Exception {
        Path root = setupReactorRoot();
        List<MavenProject> projects = createSimpleReactor(root);

        // POM path doesn't match any reactor project
        Set<String> changedPoms = Set.of("nonexistent/pom.xml");
        Map<String, byte[]> oldPoms = new HashMap<>();

        Set<MavenProject> affected =
                analyzeChanges(changedPoms, oldPoms, projects, root).getAffectedProjects();

        assertTrue(affected.isEmpty(), "Unmatched POM path should not affect any module");
    }

    @Test
    void analyzeChanges_unmatchedPomCollectedInResult() throws Exception {
        Path root = setupReactorRoot();
        List<MavenProject> projects = createSimpleReactor(root);

        Set<String> changedPoms = new LinkedHashSet<>();
        changedPoms.add("nonexistent/pom.xml");
        changedPoms.add("also-missing/pom.xml");
        Map<String, byte[]> oldPoms = new HashMap<>();

        PomChangeAnalyzer.Result result = analyzeChanges(changedPoms, oldPoms, projects, root);

        assertEquals(
                List.of("nonexistent/pom.xml", "also-missing/pom.xml"),
                result.getUnmatchedPomPaths(),
                "Unmatched changed POM paths should be exposed on the Result");
        assertTrue(result.getAffectedProjects().isEmpty(), "Unmatched POM paths should not affect any module");
    }

    @Test
    void analyzeChanges_matchedPomsLeaveUnmatchedListEmpty() throws Exception {
        Path root = setupReactorRoot();
        List<MavenProject> projects = createSimpleReactor(root);

        Set<String> changedPoms = Set.of("pom.xml");
        Map<String, byte[]> oldPoms = new HashMap<>();

        PomChangeAnalyzer.Result result = analyzeChanges(changedPoms, oldPoms, projects, root);

        assertTrue(result.getUnmatchedPomPaths().isEmpty(), "All changed POMs matched a reactor project");
    }

    // --- Import-scope BOM detection tests ---

    @Test
    void analyzeChanges_bomImportScopeManagedDepChangeAffectsImporter() throws Exception {
        // BOM module defines managed dep lib-x. module-a imports the BOM and uses lib-x.
        // When BOM's managed dep version changes, module-a should be affected.
        Path root = setupReactorRootWithBom();
        List<MavenProject> projects = createReactorWithBomImport(root);

        // Old BOM POM had lib-x:1.0
        String oldBomPom = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>bom</artifactId>
                  <packaging>pom</packaging>
                  <dependencyManagement><dependencies>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>lib-x</artifactId>
                      <version>1.0</version>
                    </dependency>
                  </dependencies></dependencyManagement>
                </project>
                """;

        Set<String> changedPoms = Set.of("bom/pom.xml");
        Map<String, byte[]> oldPoms = new HashMap<>();
        oldPoms.put("bom/pom.xml", oldBomPom.getBytes(StandardCharsets.UTF_8));

        PomChangeAnalyzer.Result result = analyzeChanges(changedPoms, oldPoms, projects, root);

        MavenProject moduleA = projects.get(2);
        MavenProject moduleB = projects.get(3);

        assertTrue(
                result.getAffectedProjects().contains(moduleA),
                "module-a imports BOM and uses managed dep lib-x, should be affected");
        assertFalse(
                result.getAffectedProjects().contains(moduleB), "module-b does not import BOM, should NOT be affected");
        assertTrue(
                result.getChangedManagedDependencyGAs().contains("com.example:lib-x"),
                "Changed managed dep GAs should include lib-x");
    }

    @Test
    void analyzeChanges_bomImportScopeNoChangeNotAffected() throws Exception {
        // BOM POM changed cosmetically (same managed deps), importers should not be affected
        Path root = setupReactorRootWithBom();
        List<MavenProject> projects = createReactorWithBomImport(root);

        // Old BOM POM is identical to current (lib-x:2.0)
        String oldBomPom = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>bom</artifactId>
                  <packaging>pom</packaging>
                  <dependencyManagement><dependencies>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>lib-x</artifactId>
                      <version>2.0</version>
                    </dependency>
                  </dependencies></dependencyManagement>
                </project>
                """;

        Set<String> changedPoms = Set.of("bom/pom.xml");
        Map<String, byte[]> oldPoms = new HashMap<>();
        oldPoms.put("bom/pom.xml", oldBomPom.getBytes(StandardCharsets.UTF_8));

        PomChangeAnalyzer.Result result = analyzeChanges(changedPoms, oldPoms, projects, root);

        assertTrue(result.getAffectedProjects().isEmpty(), "Cosmetic BOM change should not affect any module");
    }

    @Test
    void analyzeChanges_bomImportScopePropertyIndirection() throws Exception {
        // BOM uses property for managed dep version. When property changes,
        // importing module using that managed dep should be affected.
        Path root = setupReactorRootWithBom();

        // BOM with property-based version
        String bomPomXml = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>bom</artifactId>
                  <packaging>pom</packaging>
                  <properties>
                    <lib.version>2.0</lib.version>
                  </properties>
                  <dependencyManagement><dependencies>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>lib-x</artifactId>
                      <version>${lib.version}</version>
                    </dependency>
                  </dependencies></dependencyManagement>
                </project>
                """;
        writePom(root.resolve("bom/pom.xml"), bomPomXml);

        List<MavenProject> projects = createReactorWithBomImportCustomBom(root, bomPomXml);

        // Old BOM POM had lib.version=1.0
        String oldBomPom = bomPomXml.replace("<lib.version>2.0</lib.version>", "<lib.version>1.0</lib.version>");

        Set<String> changedPoms = Set.of("bom/pom.xml");
        Map<String, byte[]> oldPoms = new HashMap<>();
        oldPoms.put("bom/pom.xml", oldBomPom.getBytes(StandardCharsets.UTF_8));

        PomChangeAnalyzer.Result result = analyzeChanges(changedPoms, oldPoms, projects, root);

        MavenProject moduleA = projects.get(2);
        assertTrue(
                result.getAffectedProjects().contains(moduleA),
                "module-a uses managed dep lib-x whose version comes from changed property in BOM");
        assertTrue(
                result.getChangedManagedDependencyGAs().contains("com.example:lib-x"),
                "Changed managed dep GAs should include lib-x (via property indirection in BOM)");
    }

    @Test
    void analyzeChanges_parentPropertyChangePropagatesToBomManagedDepConsumers() throws Exception {
        // Scenario: property defined in root parent pom, BOM uses it in managed dep version,
        // module-a imports BOM and uses the managed dep.
        // When the ROOT property changes, module-a should be detected as affected
        // (via root -> BOM managed dep -> module-a consumer chain).
        // This is the pattern used by projects like Apache Camel Quarkus where version
        // properties live in the root pom.xml but are consumed in a separate BOM module.
        Path root = setupReactorRootWithBom();

        String parentPomXml = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <modules><module>bom</module><module>module-a</module><module>module-b</module></modules>
                  <properties>
                    <lib.version>2.0</lib.version>
                  </properties>
                </project>
                """;

        // BOM has no property of its own — it inherits lib.version from parent
        String bomPomXml = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>bom</artifactId>
                  <packaging>pom</packaging>
                  <dependencyManagement><dependencies>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>lib-x</artifactId>
                      <version>${lib.version}</version>
                    </dependency>
                  </dependencies></dependencyManagement>
                </project>
                """;

        // module-a: imports BOM, uses lib-x
        String moduleAPomXml = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>module-a</artifactId>
                  <dependencyManagement><dependencies>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>bom</artifactId>
                      <version>${project.version}</version>
                      <type>pom</type>
                      <scope>import</scope>
                    </dependency>
                  </dependencies></dependencyManagement>
                  <dependencies>
                    <dependency><groupId>com.example</groupId><artifactId>lib-x</artifactId></dependency>
                  </dependencies>
                </project>
                """;
        // module-b: no BOM import, no lib-x
        String moduleBPomXml = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>module-b</artifactId>
                </project>
                """;
        writePom(root.resolve("pom.xml"), parentPomXml);
        writePom(root.resolve("bom/pom.xml"), bomPomXml);
        writePom(root.resolve("module-a/pom.xml"), moduleAPomXml);
        writePom(root.resolve("module-b/pom.xml"), moduleBPomXml);

        MavenProject parent = createProject(
                "com.example", "parent", "1.0", root.resolve("pom.xml").toFile());
        parent.setOriginalModel(parseModel(parentPomXml));
        setEffectiveModel(parent, parentPomXml);
        parent.getModel().setPackaging("pom");

        MavenProject bom = createProject(
                "com.example", "bom", "1.0", root.resolve("bom/pom.xml").toFile());
        bom.setOriginalModel(parseModel(bomPomXml));
        setEffectiveModel(bom, bomPomXml);
        bom.getModel().setPackaging("pom");
        bom.setParent(parent);

        MavenProject moduleA = createProject(
                "com.example",
                "module-a",
                "1.0",
                root.resolve("module-a/pom.xml").toFile());
        moduleA.setOriginalModel(parseModel(moduleAPomXml));
        setEffectiveModel(moduleA, moduleAPomXml);
        moduleA.setParent(parent);

        MavenProject moduleB = createProject(
                "com.example",
                "module-b",
                "1.0",
                root.resolve("module-b/pom.xml").toFile());
        moduleB.setOriginalModel(parseModel(moduleBPomXml));
        setEffectiveModel(moduleB, moduleBPomXml);
        moduleB.setParent(parent);

        List<MavenProject> projects = new ArrayList<>();
        projects.add(parent);
        projects.add(bom);
        projects.add(moduleA);
        projects.add(moduleB);

        // Old parent pom had lib.version=1.0
        String oldParentPom = parentPomXml.replace("<lib.version>2.0</lib.version>", "<lib.version>1.0</lib.version>");

        Set<String> changedPoms = Set.of("pom.xml");
        Map<String, byte[]> oldPoms = new HashMap<>();
        oldPoms.put("pom.xml", oldParentPom.getBytes(StandardCharsets.UTF_8));

        PomChangeAnalyzer.Result result = analyzeChanges(changedPoms, oldPoms, projects, root);

        // BOM is directly affected because it references ${lib.version} in its managed dep version
        assertTrue(
                result.getAffectedProjects().contains(bom),
                "BOM references ${lib.version} in managed dep version and should be directly affected");

        // module-b does not use lib-x at all
        assertFalse(
                result.getAffectedProjects().contains(moduleB),
                "module-b does not use lib-x and should NOT be affected");

        // lib-x is added to changedManagedDependencyGAs by propagating
        // through the BOM's managed deps that reference the changed property. This enables
        // computeTransitivelyAffected (in ScalpelLifecycleParticipant) to subsequently find
        // consumers of lib-x (like module-a) via Maven dependency resolution.
        assertTrue(
                result.getChangedManagedDependencyGAs().contains("com.example:lib-x"),
                "Changed managed dep GAs should include lib-x (property defined in root, consumed in BOM managed dep)");

        assertTrue(
                result.getChangedProperties().contains("lib.version"), "Changed properties should include lib.version");
    }

    @Test
    void analyzeChanges_parentPropertyChangePropagatesToBomManagedPluginConsumers() throws Exception {
        Path root = setupReactorRootWithBom();

        String parentPomXml = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <modules><module>bom</module><module>module-a</module></modules>
                  <properties>
                    <compiler.version>3.12.0</compiler.version>
                  </properties>
                </project>
                """;

        String bomPomXml = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>bom</artifactId>
                  <packaging>pom</packaging>
                  <build><pluginManagement><plugins>
                    <plugin>
                      <groupId>org.apache.maven.plugins</groupId>
                      <artifactId>maven-compiler-plugin</artifactId>
                      <version>${compiler.version}</version>
                    </plugin>
                  </plugins></pluginManagement></build>
                </project>
                """;

        String moduleAPomXml = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>module-a</artifactId>
                </project>
                """;

        writePom(root.resolve("pom.xml"), parentPomXml);
        writePom(root.resolve("bom/pom.xml"), bomPomXml);
        writePom(root.resolve("module-a/pom.xml"), moduleAPomXml);

        MavenProject parent = createProject(
                "com.example", "parent", "1.0", root.resolve("pom.xml").toFile());
        parent.setOriginalModel(parseModel(parentPomXml));
        setEffectiveModel(parent, parentPomXml);
        parent.getModel().setPackaging("pom");

        MavenProject bom = createProject(
                "com.example", "bom", "1.0", root.resolve("bom/pom.xml").toFile());
        bom.setOriginalModel(parseModel(bomPomXml));
        setEffectiveModel(bom, bomPomXml);
        bom.getModel().setPackaging("pom");
        bom.setParent(parent);

        MavenProject moduleA = createProject(
                "com.example",
                "module-a",
                "1.0",
                root.resolve("module-a/pom.xml").toFile());
        moduleA.setOriginalModel(parseModel(moduleAPomXml));
        setEffectiveModel(moduleA, moduleAPomXml);
        moduleA.setParent(parent);

        List<MavenProject> projects = List.of(parent, bom, moduleA);

        String oldParentPom = parentPomXml.replace(
                "<compiler.version>3.12.0</compiler.version>", "<compiler.version>3.11.0</compiler.version>");

        PomChangeAnalyzer.Result result = analyzeChanges(
                Set.of("pom.xml"), Map.of("pom.xml", oldParentPom.getBytes(StandardCharsets.UTF_8)), projects, root);

        assertTrue(
                result.getAffectedProjects().contains(bom),
                "BOM references ${compiler.version} in managed plugin and should be affected");
        assertTrue(
                result.getChangedManagedPluginGAs().contains("org.apache.maven.plugins:maven-compiler-plugin"),
                "Changed managed plugin GAs should include maven-compiler-plugin"
                        + " (property in root, consumed in BOM managed plugin)");
    }

    @Test
    void analyzeChanges_bomImportScopeNewBomMarksAllImporters() throws Exception {
        // New BOM POM (no old bytes) should mark all importers as affected
        Path root = setupReactorRootWithBom();
        List<MavenProject> projects = createReactorWithBomImport(root);

        Set<String> changedPoms = Set.of("bom/pom.xml");
        Map<String, byte[]> oldPoms = new HashMap<>();
        // No entry for bom/pom.xml = new file

        PomChangeAnalyzer.Result result = analyzeChanges(changedPoms, oldPoms, projects, root);

        MavenProject bom = projects.get(1);
        MavenProject moduleA = projects.get(2);

        assertTrue(result.getAffectedProjects().contains(bom), "BOM should be affected");
        assertTrue(result.getAffectedProjects().contains(moduleA), "module-a (BOM importer) should be affected");
    }

    @Test
    void findBomImporters_detectsImportScopeEntries() {
        // Verify findBomImporters correctly detects import-scope BOM entries
        MavenProject parent = createProject(
                "com.example", "parent", "1.0", tempDir.resolve("pom.xml").toFile());
        parent.setOriginalModel(parseModel("""
                <?xml version="1.0"?>
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version>
                  <packaging>pom</packaging>
                </project>"""));

        MavenProject bom = createProject(
                "com.example", "bom", "1.0", tempDir.resolve("bom/pom.xml").toFile());
        bom.setOriginalModel(parseModel("""
                <?xml version="1.0"?>
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId><artifactId>bom</artifactId><version>1.0</version>
                  <packaging>pom</packaging>
                </project>"""));

        MavenProject moduleA = createProject(
                "com.example",
                "module-a",
                "1.0",
                tempDir.resolve("module-a/pom.xml").toFile());
        moduleA.setOriginalModel(parseModel("""
                <?xml version="1.0"?>
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId><artifactId>module-a</artifactId><version>1.0</version>
                  <dependencyManagement><dependencies>
                    <dependency><groupId>com.example</groupId><artifactId>bom</artifactId><version>1.0</version><type>pom</type><scope>import</scope></dependency>
                  </dependencies></dependencyManagement>
                </project>"""));

        MavenProject moduleB = createProject(
                "com.example",
                "module-b",
                "1.0",
                tempDir.resolve("module-b/pom.xml").toFile());
        moduleB.setOriginalModel(parseModel("""
                <?xml version="1.0"?>
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId><artifactId>module-b</artifactId><version>1.0</version>
                </project>"""));

        List<MavenProject> projects = new ArrayList<>();
        projects.add(parent);
        projects.add(bom);
        projects.add(moduleA);
        projects.add(moduleB);

        Map<MavenProject, List<MavenProject>> result = analyzer.findBomImporters(projects);

        assertTrue(result.containsKey(bom), "BOM should be detected as imported");
        assertEquals(1, result.get(bom).size(), "BOM should have exactly one importer");
        assertTrue(result.get(bom).contains(moduleA), "module-a should be an importer of BOM");
        assertFalse(result.containsKey(parent), "parent should not be detected as a BOM");
    }

    // --- Parent identity mismatch tests (issue #28) ---

    @Test
    void analyzeChanges_parentPropertyChangeWithDifferentParentObjects() throws Exception {
        // Simulates the case where project.getParent() returns a DIFFERENT MavenProject
        // instance than the one in allProjects (same GA, different object).
        // This happens in some Maven configurations and caused all children to be
        // flagged as affected because the parent was treated as a leaf module.
        Path root = setupReactorRoot();

        String parentPomXml = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <modules><module>module-a</module><module>module-b</module></modules>
                  <properties>
                    <dep.version>2.0</dep.version>
                  </properties>
                </project>
                """;
        writePom(root.resolve("pom.xml"), parentPomXml);

        String moduleAPomXml = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>module-a</artifactId>
                </project>
                """;
        writePom(root.resolve("module-a/pom.xml"), moduleAPomXml);

        String moduleBPomXml = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>module-b</artifactId>
                  <dependencies>
                    <dependency><groupId>com.example</groupId><artifactId>lib-x</artifactId><version>${dep.version}</version></dependency>
                  </dependencies>
                </project>
                """;
        writePom(root.resolve("module-b/pom.xml"), moduleBPomXml);

        // Create the reactor parent instance
        MavenProject parent = createProject(
                "com.example", "parent", "1.0", root.resolve("pom.xml").toFile());
        parent.setOriginalModel(parseModel(parentPomXml));
        parent.getModel().setPackaging("pom");

        // Create DIFFERENT parent objects for children (same GA, different instances)
        MavenProject parentRefA = createProject(
                "com.example", "parent", "1.0", root.resolve("pom.xml").toFile());
        MavenProject parentRefB = createProject(
                "com.example", "parent", "1.0", root.resolve("pom.xml").toFile());

        MavenProject moduleA = createProject(
                "com.example",
                "module-a",
                "1.0",
                root.resolve("module-a/pom.xml").toFile());
        moduleA.setOriginalModel(parseModel(moduleAPomXml));
        moduleA.setParent(parentRefA);

        MavenProject moduleB = createProject(
                "com.example",
                "module-b",
                "1.0",
                root.resolve("module-b/pom.xml").toFile());
        moduleB.setOriginalModel(parseModel(moduleBPomXml));
        moduleB.setParent(parentRefB);

        List<MavenProject> projects = new ArrayList<>();
        projects.add(parent);
        projects.add(moduleA);
        projects.add(moduleB);

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
                    <dep.version>1.0</dep.version>
                  </properties>
                </project>
                """;

        Set<String> changedPoms = Set.of("pom.xml");
        Map<String, byte[]> oldPoms = new HashMap<>();
        oldPoms.put("pom.xml", oldParentPom.getBytes(StandardCharsets.UTF_8));

        PomChangeAnalyzer.Result result = analyzeChanges(changedPoms, oldPoms, projects, root);

        assertTrue(
                result.getAffectedProjects().contains(moduleB),
                "module-b references ${dep.version} and should be affected");
        assertFalse(
                result.getAffectedProjects().contains(moduleA),
                "module-a does NOT reference ${dep.version} and should NOT be affected");
        assertFalse(
                result.getAffectedProjects().contains(parent),
                "parent should NOT be self-affected for a property-only change");
    }

    // --- Helper methods ---

    private Path setupReactorRootWithBom() throws IOException {
        Path root = tempDir.resolve("project");
        Files.createDirectories(root);
        Files.createDirectories(root.resolve("bom"));
        Files.createDirectories(root.resolve("module-a"));
        Files.createDirectories(root.resolve("module-b"));
        return root;
    }

    private List<MavenProject> createReactorWithBomImport(Path root) throws IOException {
        String bomPomXml = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>bom</artifactId>
                  <packaging>pom</packaging>
                  <dependencyManagement><dependencies>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>lib-x</artifactId>
                      <version>2.0</version>
                    </dependency>
                  </dependencies></dependencyManagement>
                </project>
                """;
        return createReactorWithBomImportCustomBom(root, bomPomXml);
    }

    private List<MavenProject> createReactorWithBomImportCustomBom(Path root, String bomPomXml) throws IOException {
        String parentPomXml = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <modules><module>bom</module><module>module-a</module><module>module-b</module></modules>
                </project>
                """;
        writePom(root.resolve("pom.xml"), parentPomXml);

        writePom(root.resolve("bom/pom.xml"), bomPomXml);

        // module-a: imports BOM, uses lib-x
        String moduleAPomXml = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>module-a</artifactId>
                  <dependencyManagement><dependencies>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>bom</artifactId>
                      <version>${project.version}</version>
                      <type>pom</type>
                      <scope>import</scope>
                    </dependency>
                  </dependencies></dependencyManagement>
                  <dependencies>
                    <dependency><groupId>com.example</groupId><artifactId>lib-x</artifactId></dependency>
                  </dependencies>
                </project>
                """;
        writePom(root.resolve("module-a/pom.xml"), moduleAPomXml);

        // module-b: no BOM import, no lib-x
        String moduleBPomXml = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>module-b</artifactId>
                </project>
                """;
        writePom(root.resolve("module-b/pom.xml"), moduleBPomXml);

        MavenProject parent = createProject(
                "com.example", "parent", "1.0", root.resolve("pom.xml").toFile());
        parent.setOriginalModel(parseModel(parentPomXml));
        setEffectiveModel(parent, parentPomXml);
        parent.getModel().setPackaging("pom");

        MavenProject bom = createProject(
                "com.example", "bom", "1.0", root.resolve("bom/pom.xml").toFile());
        bom.setOriginalModel(parseModel(bomPomXml));
        setEffectiveModel(bom, bomPomXml);
        bom.getModel().setPackaging("pom");
        bom.setParent(parent);

        MavenProject moduleA = createProject(
                "com.example",
                "module-a",
                "1.0",
                root.resolve("module-a/pom.xml").toFile());
        moduleA.setOriginalModel(parseModel(moduleAPomXml));
        setEffectiveModel(moduleA, moduleAPomXml);
        moduleA.setParent(parent);

        MavenProject moduleB = createProject(
                "com.example",
                "module-b",
                "1.0",
                root.resolve("module-b/pom.xml").toFile());
        moduleB.setOriginalModel(parseModel(moduleBPomXml));
        setEffectiveModel(moduleB, moduleBPomXml);
        moduleB.setParent(parent);

        List<MavenProject> projects = new ArrayList<>();
        projects.add(parent);
        projects.add(bom);
        projects.add(moduleA);
        projects.add(moduleB);
        return projects;
    }

    private Path setupReactorRoot() throws IOException {
        Path root = tempDir.resolve("project");
        Files.createDirectories(root);
        Files.createDirectories(root.resolve("module-a"));
        Files.createDirectories(root.resolve("module-b"));
        return root;
    }

    private List<MavenProject> createSimpleReactor(Path root) throws IOException {
        // Parent POM
        String parentPomXml = """
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
        writePom(root.resolve("pom.xml"), parentPomXml);

        String moduleAPomXml = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>module-a</artifactId>
                </project>
                """;
        writePom(root.resolve("module-a/pom.xml"), moduleAPomXml);

        String moduleBPomXml = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>module-b</artifactId>
                </project>
                """;
        writePom(root.resolve("module-b/pom.xml"), moduleBPomXml);

        return buildProjectList(root, parentPomXml, moduleAPomXml, moduleBPomXml);
    }

    private List<MavenProject> createReactorWithPropertyUsage(Path root) throws IOException {
        // Parent POM with dep.version=2.0 (new value)
        String parentPomXml = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <modules><module>module-a</module><module>module-b</module></modules>
                  <properties>
                    <dep.version>2.0</dep.version>
                  </properties>
                </project>
                """;
        writePom(root.resolve("pom.xml"), parentPomXml);

        // module-a: does NOT reference ${dep.version}
        String moduleAPomXml = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>module-a</artifactId>
                  <dependencies>
                    <dependency><groupId>org.other</groupId><artifactId>other-lib</artifactId><version>3.0</version></dependency>
                  </dependencies>
                </project>
                """;
        writePom(root.resolve("module-a/pom.xml"), moduleAPomXml);

        // module-b: references ${dep.version} in a dependency
        String moduleBPomXml = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>module-b</artifactId>
                  <dependencies>
                    <dependency><groupId>com.example</groupId><artifactId>lib-x</artifactId><version>${dep.version}</version></dependency>
                  </dependencies>
                </project>
                """;
        writePom(root.resolve("module-b/pom.xml"), moduleBPomXml);

        return buildProjectList(root, parentPomXml, moduleAPomXml, moduleBPomXml);
    }

    private List<MavenProject> createReactorWithDepMgmtUsage(Path root) throws IOException {
        // Parent POM with depMgmt for lib-x:2.0 (new value)
        String parentPomXml = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <modules><module>module-a</module><module>module-b</module></modules>
                  <dependencyManagement><dependencies>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>lib-x</artifactId>
                      <version>2.0</version>
                    </dependency>
                  </dependencies></dependencyManagement>
                </project>
                """;
        writePom(root.resolve("pom.xml"), parentPomXml);

        // module-a: does NOT use lib-x
        String moduleAPomXml = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>module-a</artifactId>
                </project>
                """;
        writePom(root.resolve("module-a/pom.xml"), moduleAPomXml);

        // module-b: uses lib-x (managed, no version)
        String moduleBPomXml = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>module-b</artifactId>
                  <dependencies>
                    <dependency><groupId>com.example</groupId><artifactId>lib-x</artifactId></dependency>
                  </dependencies>
                </project>
                """;
        writePom(root.resolve("module-b/pom.xml"), moduleBPomXml);

        return buildProjectList(root, parentPomXml, moduleAPomXml, moduleBPomXml);
    }

    private List<MavenProject> createReactorWithManagedDepPropertyIndirection(Path root) throws IOException {
        // Parent POM: spring.version=6.0.0 (new), managed dep spring-core uses ${spring.version}
        String parentPomXml = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <modules><module>module-a</module><module>module-b</module></modules>
                  <properties>
                    <spring.version>6.0.0</spring.version>
                  </properties>
                  <dependencyManagement><dependencies>
                    <dependency>
                      <groupId>org.springframework</groupId>
                      <artifactId>spring-core</artifactId>
                      <version>${spring.version}</version>
                    </dependency>
                  </dependencies></dependencyManagement>
                </project>
                """;
        writePom(root.resolve("pom.xml"), parentPomXml);

        // module-a: does NOT use spring-core
        String moduleAPomXml = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>module-a</artifactId>
                </project>
                """;
        writePom(root.resolve("module-a/pom.xml"), moduleAPomXml);

        // module-b: uses spring-core (managed, no version in child POM)
        String moduleBPomXml = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>module-b</artifactId>
                  <dependencies>
                    <dependency><groupId>org.springframework</groupId><artifactId>spring-core</artifactId></dependency>
                  </dependencies>
                </project>
                """;
        writePom(root.resolve("module-b/pom.xml"), moduleBPomXml);

        return buildProjectList(root, parentPomXml, moduleAPomXml, moduleBPomXml);
    }

    private List<MavenProject> createReactorWithManagedPluginPropertyIndirection(Path root) throws IOException {
        // Parent POM: compiler.version=3.12.0 (new), managed plugin uses ${compiler.version}
        String parentPomXml = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <modules><module>module-a</module><module>module-b</module></modules>
                  <properties>
                    <compiler.version>3.12.0</compiler.version>
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
        writePom(root.resolve("pom.xml"), parentPomXml);

        // module-a: does NOT use maven-compiler-plugin
        String moduleAPomXml = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>module-a</artifactId>
                </project>
                """;
        writePom(root.resolve("module-a/pom.xml"), moduleAPomXml);

        // module-b: uses maven-compiler-plugin (managed, no version in child POM)
        String moduleBPomXml = """
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
        writePom(root.resolve("module-b/pom.xml"), moduleBPomXml);

        return buildProjectList(root, parentPomXml, moduleAPomXml, moduleBPomXml);
    }

    private List<MavenProject> buildProjectList(
            Path root, String parentPomXml, String moduleAPomXml, String moduleBPomXml) {
        MavenProject parent = createProject(
                "com.example", "parent", "1.0", root.resolve("pom.xml").toFile());
        parent.setOriginalModel(parseModel(parentPomXml));
        parent.getModel().setPackaging("pom");
        // Set effective model from the POM XML (for effective model comparison)
        setEffectiveModel(parent, parentPomXml);

        MavenProject moduleA = createProject(
                "com.example",
                "module-a",
                "1.0",
                root.resolve("module-a/pom.xml").toFile());
        moduleA.setOriginalModel(parseModel(moduleAPomXml));
        moduleA.setParent(parent);
        setEffectiveModel(moduleA, moduleAPomXml);

        MavenProject moduleB = createProject(
                "com.example",
                "module-b",
                "1.0",
                root.resolve("module-b/pom.xml").toFile());
        moduleB.setOriginalModel(parseModel(moduleBPomXml));
        moduleB.setParent(parent);
        setEffectiveModel(moduleB, moduleBPomXml);

        List<MavenProject> projects = new ArrayList<>();
        projects.add(parent);
        projects.add(moduleA);
        projects.add(moduleB);
        return projects;
    }

    /**
     * Set the effective model on a MavenProject by parsing the POM XML and resolving
     * managed dependency/plugin versions from the parent.
     * In production, Maven builds the effective model with inheritance and interpolation.
     * In tests, we simulate this by merging parent managed dep/plugin versions into
     * child dependencies, and inheriting parent properties.
     */
    private void setEffectiveModel(MavenProject project, String pomXml) {
        Model effective = parseModel(pomXml);
        // Preserve the GAV and packaging from the project's existing model
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

        // Simulate Maven's effective model: resolve managed dep/plugin versions from parent
        if (project.getParent() != null) {
            Model parentModel = project.getParent().getModel();
            if (parentModel != null) {
                // Inherit parent properties
                Properties parentProps = parentModel.getProperties();
                if (parentProps != null) {
                    Properties effectiveProps = effective.getProperties();
                    if (effectiveProps == null) {
                        effectiveProps = new Properties();
                        effective.setProperties(effectiveProps);
                    }
                    for (String name : parentProps.stringPropertyNames()) {
                        if (!effectiveProps.containsKey(name)) {
                            effectiveProps.setProperty(name, parentProps.getProperty(name));
                        }
                    }
                }

                // Resolve managed dependency versions
                if (parentModel.getDependencyManagement() != null) {
                    Map<String, String> managedVersions = new HashMap<>();
                    for (Dependency d : parentModel.getDependencyManagement().getDependencies()) {
                        managedVersions.put(d.getGroupId() + ":" + d.getArtifactId(), d.getVersion());
                    }
                    for (Dependency dep : effective.getDependencies()) {
                        if (dep.getVersion() == null || dep.getVersion().startsWith("${")) {
                            String version = managedVersions.get(dep.getGroupId() + ":" + dep.getArtifactId());
                            if (version != null) {
                                dep.setVersion(version);
                            }
                        }
                    }
                }

                // Resolve managed plugin versions
                if (parentModel.getBuild() != null && parentModel.getBuild().getPluginManagement() != null) {
                    Map<String, String> managedPluginVersions = new HashMap<>();
                    for (Plugin p : parentModel.getBuild().getPluginManagement().getPlugins()) {
                        managedPluginVersions.put(p.getGroupId() + ":" + p.getArtifactId(), p.getVersion());
                    }
                    if (effective.getBuild() != null && effective.getBuild().getPlugins() != null) {
                        for (Plugin plugin : effective.getBuild().getPlugins()) {
                            if (plugin.getVersion() == null
                                    || plugin.getVersion().startsWith("${")) {
                                String version =
                                        managedPluginVersions.get(plugin.getGroupId() + ":" + plugin.getArtifactId());
                                if (version != null) {
                                    plugin.setVersion(version);
                                }
                            }
                        }
                    }
                }
            }
        }

        // Interpolate property references in dependency, plugin, and managed dep/plugin versions
        Properties props = effective.getProperties();
        if (props != null) {
            interpolateDepVersions(effective.getDependencies(), props);
            if (effective.getDependencyManagement() != null) {
                interpolateDepVersions(effective.getDependencyManagement().getDependencies(), props);
            }
            if (effective.getBuild() != null) {
                interpolatePluginVersions(effective.getBuild().getPlugins(), props);
                if (effective.getBuild().getPluginManagement() != null) {
                    interpolatePluginVersions(
                            effective.getBuild().getPluginManagement().getPlugins(), props);
                }
            }
        }

        project.setModel(effective);
    }

    private void interpolateDepVersions(List<Dependency> deps, Properties props) {
        if (deps == null) {
            return;
        }
        for (Dependency dep : deps) {
            if (dep.getVersion() != null
                    && dep.getVersion().startsWith("${")
                    && dep.getVersion().endsWith("}")) {
                String propName = dep.getVersion().substring(2, dep.getVersion().length() - 1);
                String propValue = props.getProperty(propName);
                if (propValue != null) {
                    dep.setVersion(propValue);
                }
            }
        }
    }

    private void interpolatePluginVersions(List<Plugin> plugins, Properties props) {
        if (plugins == null) {
            return;
        }
        for (Plugin plugin : plugins) {
            if (plugin.getVersion() != null
                    && plugin.getVersion().startsWith("${")
                    && plugin.getVersion().endsWith("}")) {
                String propName =
                        plugin.getVersion().substring(2, plugin.getVersion().length() - 1);
                String propValue = props.getProperty(propName);
                if (propValue != null) {
                    plugin.setVersion(propValue);
                }
            }
        }
    }

    private MavenProject createProject(String groupId, String artifactId, String version, File pomFile) {
        Model model = new Model();
        model.setGroupId(groupId);
        model.setArtifactId(artifactId);
        model.setVersion(version);
        model.setPomFile(pomFile);
        MavenProject project = new MavenProject(model);
        project.setFile(pomFile);
        return project;
    }

    private Model parseModel(String xml) {
        try {
            return new org.apache.maven.model.io.xpp3.MavenXpp3Reader()
                    .read(new java.io.ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void writePom(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
    }

    private byte[] readFile(File file) throws IOException {
        return Files.readAllBytes(file.toPath());
    }
}
