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

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;
import org.apache.maven.model.Build;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.model.PluginManagement;
import org.apache.maven.model.Profile;
import org.apache.maven.model.Resource;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class PomChangeAnalyzerTest {

    private PomChangeAnalyzer analyzer;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        analyzer = new PomChangeAnalyzer();
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

    // --- diffDependencyManagement tests ---

    @Test
    void diffDependencyManagement_noChanges() {
        Model old = modelWithManagedDep("com.example", "lib-a", "1.0");
        Model now = modelWithManagedDep("com.example", "lib-a", "1.0");
        assertTrue(analyzer.diffDependencyManagement(old, now).isEmpty());
    }

    @Test
    void diffDependencyManagement_versionChanged() {
        Model old = modelWithManagedDep("com.example", "lib-a", "1.0");
        Model now = modelWithManagedDep("com.example", "lib-a", "2.0");
        Set<String> changed = analyzer.diffDependencyManagement(old, now);
        assertEquals(Set.of("com.example:lib-a"), changed);
    }

    @Test
    void diffDependencyManagement_addedDep() {
        Model old = new Model();
        Model now = modelWithManagedDep("com.example", "lib-new", "1.0");
        Set<String> changed = analyzer.diffDependencyManagement(old, now);
        assertEquals(Set.of("com.example:lib-new"), changed);
    }

    // --- diffPluginManagement tests ---

    @Test
    void diffPluginManagement_noChanges() {
        Model old = modelWithManagedPlugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0");
        Model now = modelWithManagedPlugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0");
        assertTrue(analyzer.diffPluginManagement(old, now).isEmpty());
    }

    @Test
    void diffPluginManagement_versionChanged() {
        Model old = modelWithManagedPlugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0");
        Model now = modelWithManagedPlugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.12.0");
        Set<String> changed = analyzer.diffPluginManagement(old, now);
        assertEquals(Set.of("org.apache.maven.plugins:maven-compiler-plugin"), changed);
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
                analyzer.analyzeChanges(changedPoms, oldPoms, projects, root).getAffectedProjects();

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
                analyzer.analyzeChanges(changedPoms, oldPoms, projects, root).getAffectedProjects();

        MavenProject moduleB = projects.get(2);
        assertTrue(affected.contains(moduleB), "module-b references ${dep.version} and should be affected");

        MavenProject moduleA = projects.get(1);
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
                analyzer.analyzeChanges(changedPoms, oldPoms, projects, root).getAffectedProjects();

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
                analyzer.analyzeChanges(changedPoms, oldPoms, projects, root).getAffectedProjects();

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
                analyzer.analyzeChanges(changedPoms, oldPoms, projects, root).getAffectedProjects();

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

        PomChangeAnalyzer.Result result = analyzer.analyzeChanges(changedPoms, oldPoms, projects, root);

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

        PomChangeAnalyzer.Result result = analyzer.analyzeChanges(changedPoms, oldPoms, projects, root);

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
                analyzer.analyzeChanges(changedPoms, oldPoms, projects, root).getAffectedProjects();

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
                analyzer.analyzeChanges(changedPoms, oldPoms, projects, root).getAffectedProjects();

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

        PomChangeAnalyzer.Result result = analyzer.analyzeChanges(changedPoms, oldPoms, projects, root);

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
                analyzer.analyzeChanges(changedPoms, oldPoms, projects, root).getAffectedProjects();

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

        PomChangeAnalyzer.Result result = analyzer.analyzeChanges(changedPoms, oldPoms, projects, root);

        assertTrue(
                result.getAffectedProjects().contains(projects.get(2)),
                "module-b uses managed dep lib-x from active profile and should be affected");
        assertFalse(result.getAffectedProjects().contains(projects.get(1)), "module-a does NOT use lib-x");
        assertTrue(result.getChangedManagedDependencyGAs().contains("com.example:lib-x"));
    }

    // --- Plugin configuration semantic diff tests ---

    @Test
    void diffPluginManagement_whitespaceConfigChangeIgnored() {
        Model old =
                modelWithManagedPluginConfig("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0", "  11  ");
        Model now = modelWithManagedPluginConfig("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0", "11");
        assertTrue(
                analyzer.diffPluginManagement(old, now).isEmpty(),
                "Whitespace-only config change should not trigger diff");
    }

    @Test
    void diffPluginManagement_configValueChangeDetected() {
        Model old = modelWithManagedPluginConfig("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0", "11");
        Model now = modelWithManagedPluginConfig("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0", "17");
        Set<String> changed = analyzer.diffPluginManagement(old, now);
        assertEquals(
                Set.of("org.apache.maven.plugins:maven-compiler-plugin"),
                changed,
                "Config value change should be detected");
    }

    @Test
    void diffPluginManagement_executionChangeDetected() {
        Model old = modelWithManagedPluginExecution(
                "org.apache.maven.plugins", "maven-surefire-plugin", "3.0.0", "default-test", "test");
        Model now = modelWithManagedPluginExecution(
                "org.apache.maven.plugins", "maven-surefire-plugin", "3.0.0", "default-test", "integration-test");
        Set<String> changed = analyzer.diffPluginManagement(old, now);
        assertEquals(
                Set.of("org.apache.maven.plugins:maven-surefire-plugin"),
                changed,
                "Execution phase change should be detected");
    }

    @Test
    void diffPluginManagement_sameExecutionNoChange() {
        Model old = modelWithManagedPluginExecution(
                "org.apache.maven.plugins", "maven-surefire-plugin", "3.0.0", "default-test", "test");
        Model now = modelWithManagedPluginExecution(
                "org.apache.maven.plugins", "maven-surefire-plugin", "3.0.0", "default-test", "test");
        assertTrue(analyzer.diffPluginManagement(old, now).isEmpty(), "Identical executions should not trigger diff");
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

        PomChangeAnalyzer.Result result = analyzer.analyzeChanges(changedPoms, oldPoms, projects, root);

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

        PomChangeAnalyzer.Result result = analyzer.analyzeChanges(changedPoms, oldPoms, projects, root);

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
                analyzer.analyzeChanges(changedPoms, oldPoms, projects, root).getAffectedProjects();

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
                analyzer.analyzeChanges(changedPoms, oldPoms, projects, root).getAffectedProjects();

        assertFalse(
                affected.contains(moduleB),
                "module-b's filtered resources don't reference ${app.version} - should NOT be affected");
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
                analyzer.analyzeChanges(changedPoms, oldPoms, projects, root).getAffectedProjects();

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

        PomChangeAnalyzer.Result result = analyzer.analyzeChanges(changedPoms, oldPoms, projects, root);

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

        PomChangeAnalyzer.Result result = analyzer.analyzeChanges(changedPoms, oldPoms, projects, root);

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
                analyzer.analyzeChanges(changedPoms, oldPoms, projects, root).getAffectedProjects();

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

        PomChangeAnalyzer.Result result = analyzer.analyzeChanges(changedPoms, oldPoms, projects, root);

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

        PomChangeAnalyzer.Result result = analyzer.analyzeChanges(changedPoms, oldPoms, projects, root);

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

        PomChangeAnalyzer.Result result = analyzer.analyzeChanges(changedPoms, oldPoms, projects, root);

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
                analyzer.analyzeChanges(changedPoms, oldPoms, projects, root).getAffectedProjects();

        assertTrue(affected.isEmpty(), "Unmatched POM path should not affect any module");
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

        PomChangeAnalyzer.Result result = analyzer.analyzeChanges(changedPoms, oldPoms, projects, root);

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

        PomChangeAnalyzer.Result result = analyzer.analyzeChanges(changedPoms, oldPoms, projects, root);

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

        PomChangeAnalyzer.Result result = analyzer.analyzeChanges(changedPoms, oldPoms, projects, root);

        MavenProject moduleA = projects.get(2);
        assertTrue(
                result.getAffectedProjects().contains(moduleA),
                "module-a uses managed dep lib-x whose version comes from changed property in BOM");
        assertTrue(
                result.getChangedManagedDependencyGAs().contains("com.example:lib-x"),
                "Changed managed dep GAs should include lib-x (via property indirection in BOM)");
    }

    @Test
    void analyzeChanges_bomImportScopeNewBomMarksAllImporters() throws Exception {
        // New BOM POM (no old bytes) should mark all importers as affected
        Path root = setupReactorRootWithBom();
        List<MavenProject> projects = createReactorWithBomImport(root);

        Set<String> changedPoms = Set.of("bom/pom.xml");
        Map<String, byte[]> oldPoms = new HashMap<>();
        // No entry for bom/pom.xml = new file

        PomChangeAnalyzer.Result result = analyzer.analyzeChanges(changedPoms, oldPoms, projects, root);

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
        parent.getModel().setPackaging("pom");

        MavenProject bom = createProject(
                "com.example", "bom", "1.0", root.resolve("bom/pom.xml").toFile());
        bom.setOriginalModel(parseModel(bomPomXml));
        bom.getModel().setPackaging("pom");
        bom.setParent(parent);

        MavenProject moduleA = createProject(
                "com.example",
                "module-a",
                "1.0",
                root.resolve("module-a/pom.xml").toFile());
        moduleA.setOriginalModel(parseModel(moduleAPomXml));
        moduleA.setParent(parent);

        MavenProject moduleB = createProject(
                "com.example",
                "module-b",
                "1.0",
                root.resolve("module-b/pom.xml").toFile());
        moduleB.setOriginalModel(parseModel(moduleBPomXml));
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

        MavenProject moduleA = createProject(
                "com.example",
                "module-a",
                "1.0",
                root.resolve("module-a/pom.xml").toFile());
        moduleA.setOriginalModel(parseModel(moduleAPomXml));
        moduleA.setParent(parent);

        MavenProject moduleB = createProject(
                "com.example",
                "module-b",
                "1.0",
                root.resolve("module-b/pom.xml").toFile());
        moduleB.setOriginalModel(parseModel(moduleBPomXml));
        moduleB.setParent(parent);

        List<MavenProject> projects = new ArrayList<>();
        projects.add(parent);
        projects.add(moduleA);
        projects.add(moduleB);
        return projects;
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

    private Model modelWithManagedDep(String groupId, String artifactId, String version) {
        Model model = new Model();
        DependencyManagement dm = new DependencyManagement();
        Dependency dep = new Dependency();
        dep.setGroupId(groupId);
        dep.setArtifactId(artifactId);
        dep.setVersion(version);
        dm.addDependency(dep);
        model.setDependencyManagement(dm);
        return model;
    }

    private Model modelWithManagedPlugin(String groupId, String artifactId, String version) {
        Model model = new Model();
        Build build = new Build();
        PluginManagement pm = new PluginManagement();
        Plugin plugin = new Plugin();
        plugin.setGroupId(groupId);
        plugin.setArtifactId(artifactId);
        plugin.setVersion(version);
        pm.addPlugin(plugin);
        build.setPluginManagement(pm);
        model.setBuild(build);
        return model;
    }

    private Model modelWithManagedPluginConfig(String groupId, String artifactId, String version, String sourceValue) {
        Model model = new Model();
        Build build = new Build();
        PluginManagement pm = new PluginManagement();
        Plugin plugin = new Plugin();
        plugin.setGroupId(groupId);
        plugin.setArtifactId(artifactId);
        plugin.setVersion(version);
        Xpp3Dom config = new Xpp3Dom("configuration");
        Xpp3Dom source = new Xpp3Dom("source");
        source.setValue(sourceValue);
        config.addChild(source);
        plugin.setConfiguration(config);
        pm.addPlugin(plugin);
        build.setPluginManagement(pm);
        model.setBuild(build);
        return model;
    }

    private Model modelWithManagedPluginExecution(
            String groupId, String artifactId, String version, String execId, String phase) {
        Model model = new Model();
        Build build = new Build();
        PluginManagement pm = new PluginManagement();
        Plugin plugin = new Plugin();
        plugin.setGroupId(groupId);
        plugin.setArtifactId(artifactId);
        plugin.setVersion(version);
        PluginExecution execution = new PluginExecution();
        execution.setId(execId);
        execution.setPhase(phase);
        plugin.addExecution(execution);
        pm.addPlugin(plugin);
        build.setPluginManagement(pm);
        model.setBuild(build);
        return model;
    }
}
