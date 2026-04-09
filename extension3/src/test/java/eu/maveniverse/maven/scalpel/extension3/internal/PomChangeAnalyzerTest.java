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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
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
        assertEquals(Collections.singleton("dep.version"), changed);
    }

    @Test
    void diffProperties_addedProperty() {
        Properties a = new Properties();
        Properties b = new Properties();
        b.setProperty("new.prop", "value");
        Set<String> changed = analyzer.diffProperties(a, b);
        assertEquals(Collections.singleton("new.prop"), changed);
    }

    @Test
    void diffProperties_removedProperty() {
        Properties a = new Properties();
        a.setProperty("old.prop", "value");
        Properties b = new Properties();
        Set<String> changed = analyzer.diffProperties(a, b);
        assertEquals(Collections.singleton("old.prop"), changed);
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
        assertEquals(Collections.singleton("com.example:lib-a"), changed);
    }

    @Test
    void diffDependencyManagement_addedDep() {
        Model old = new Model();
        Model now = modelWithManagedDep("com.example", "lib-new", "1.0");
        Set<String> changed = analyzer.diffDependencyManagement(old, now);
        assertEquals(Collections.singleton("com.example:lib-new"), changed);
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
        assertEquals(Collections.singleton("org.apache.maven.plugins:maven-compiler-plugin"), changed);
    }

    // --- analyzeChanges integration tests ---

    @Test
    void analyzeChanges_leafModulePomChanged() throws Exception {
        // Setup: a leaf module (module-a) has its POM changed
        Path root = setupReactorRoot();
        List<MavenProject> projects = createSimpleReactor(root);
        MavenProject moduleA = projects.get(1); // module-a

        Set<String> changedPoms = Collections.singleton("module-a/pom.xml");
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
        String oldParentPom = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <groupId>com.example</groupId>\n"
                + "  <artifactId>parent</artifactId>\n"
                + "  <version>1.0</version>\n"
                + "  <packaging>pom</packaging>\n"
                + "  <modules><module>module-a</module><module>module-b</module></modules>\n"
                + "  <properties>\n"
                + "    <dep.version>1.0</dep.version>\n"
                + "  </properties>\n"
                + "</project>\n";

        Set<String> changedPoms = Collections.singleton("pom.xml");
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
        String oldParentPom = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <groupId>com.example</groupId>\n"
                + "  <artifactId>parent</artifactId>\n"
                + "  <version>1.0</version>\n"
                + "  <packaging>pom</packaging>\n"
                + "  <modules><module>module-a</module><module>module-b</module></modules>\n"
                + "  <dependencyManagement><dependencies>\n"
                + "    <dependency>\n"
                + "      <groupId>com.example</groupId>\n"
                + "      <artifactId>lib-x</artifactId>\n"
                + "      <version>1.0</version>\n"
                + "    </dependency>\n"
                + "  </dependencies></dependencyManagement>\n"
                + "</project>\n";

        Set<String> changedPoms = Collections.singleton("pom.xml");
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
        String oldParentPom = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <groupId>com.example</groupId>\n"
                + "  <artifactId>parent</artifactId>\n"
                + "  <version>1.0</version>\n"
                + "  <packaging>pom</packaging>\n"
                + "  <modules><module>module-a</module><module>module-b</module></modules>\n"
                + "</project>\n";

        Set<String> changedPoms = Collections.singleton("pom.xml");
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
        Set<String> changedPoms = Collections.singleton("pom.xml");
        Map<String, byte[]> oldPoms = new HashMap<>();

        Set<MavenProject> affected =
                analyzer.analyzeChanges(changedPoms, oldPoms, projects, root).getAffectedProjects();

        assertEquals(3, affected.size(), "New parent POM should mark parent + all children");
    }

    @Test
    void analyzeChanges_returnsChangedProperties() throws Exception {
        Path root = setupReactorRoot();
        List<MavenProject> projects = createReactorWithPropertyUsage(root);

        String oldParentPom = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <groupId>com.example</groupId>\n"
                + "  <artifactId>parent</artifactId>\n"
                + "  <version>1.0</version>\n"
                + "  <packaging>pom</packaging>\n"
                + "  <modules><module>module-a</module><module>module-b</module></modules>\n"
                + "  <properties>\n"
                + "    <dep.version>1.0</dep.version>\n"
                + "  </properties>\n"
                + "</project>\n";

        Set<String> changedPoms = Collections.singleton("pom.xml");
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

        String oldParentPom = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <groupId>com.example</groupId>\n"
                + "  <artifactId>parent</artifactId>\n"
                + "  <version>1.0</version>\n"
                + "  <packaging>pom</packaging>\n"
                + "  <modules><module>module-a</module><module>module-b</module></modules>\n"
                + "  <properties>\n"
                + "    <spring.version>5.3.0</spring.version>\n"
                + "  </properties>\n"
                + "  <dependencyManagement><dependencies>\n"
                + "    <dependency>\n"
                + "      <groupId>org.springframework</groupId>\n"
                + "      <artifactId>spring-core</artifactId>\n"
                + "      <version>${spring.version}</version>\n"
                + "    </dependency>\n"
                + "  </dependencies></dependencyManagement>\n"
                + "</project>\n";

        Set<String> changedPoms = Collections.singleton("pom.xml");
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
        String oldParentPom = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <groupId>com.example</groupId>\n"
                + "  <artifactId>parent</artifactId>\n"
                + "  <version>1.0</version>\n"
                + "  <packaging>pom</packaging>\n"
                + "  <modules><module>module-a</module><module>module-b</module></modules>\n"
                + "  <properties>\n"
                + "    <spring.version>5.3.0</spring.version>\n"
                + "  </properties>\n"
                + "  <dependencyManagement><dependencies>\n"
                + "    <dependency>\n"
                + "      <groupId>org.springframework</groupId>\n"
                + "      <artifactId>spring-core</artifactId>\n"
                + "      <version>${spring.version}</version>\n"
                + "    </dependency>\n"
                + "  </dependencies></dependencyManagement>\n"
                + "</project>\n";

        Set<String> changedPoms = Collections.singleton("pom.xml");
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

        Set<String> changedPoms = Collections.singleton("pom.xml");
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
        String parentPomXml = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <groupId>com.example</groupId>\n"
                + "  <artifactId>parent</artifactId>\n"
                + "  <version>1.0</version>\n"
                + "  <packaging>pom</packaging>\n"
                + "  <modules><module>module-a</module><module>module-b</module></modules>\n"
                + "  <profiles><profile>\n"
                + "    <id>my-profile</id>\n"
                + "    <properties><dep.version>2.0</dep.version></properties>\n"
                + "  </profile></profiles>\n"
                + "</project>\n";
        writePom(root.resolve("pom.xml"), parentPomXml);

        String moduleAPomXml = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>\n"
                + "  <artifactId>module-a</artifactId>\n"
                + "</project>\n";
        writePom(root.resolve("module-a/pom.xml"), moduleAPomXml);

        // module-b references ${dep.version}
        String moduleBPomXml = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>\n"
                + "  <artifactId>module-b</artifactId>\n"
                + "  <dependencies>\n"
                + "    <dependency><groupId>com.example</groupId><artifactId>lib-x</artifactId><version>${dep.version}</version></dependency>\n"
                + "  </dependencies>\n"
                + "</project>\n";
        writePom(root.resolve("module-b/pom.xml"), moduleBPomXml);

        List<MavenProject> projects = buildProjectList(root, parentPomXml, moduleAPomXml, moduleBPomXml);

        // Set profile as active on parent
        Profile activeProfile = new Profile();
        activeProfile.setId("my-profile");
        projects.get(0).setActiveProfiles(Collections.singletonList(activeProfile));

        // Old parent POM had dep.version=1.0 in the profile
        String oldParentPom = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <groupId>com.example</groupId>\n"
                + "  <artifactId>parent</artifactId>\n"
                + "  <version>1.0</version>\n"
                + "  <packaging>pom</packaging>\n"
                + "  <modules><module>module-a</module><module>module-b</module></modules>\n"
                + "  <profiles><profile>\n"
                + "    <id>my-profile</id>\n"
                + "    <properties><dep.version>1.0</dep.version></properties>\n"
                + "  </profile></profiles>\n"
                + "</project>\n";

        Set<String> changedPoms = Collections.singleton("pom.xml");
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
        String parentPomXml = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <groupId>com.example</groupId>\n"
                + "  <artifactId>parent</artifactId>\n"
                + "  <version>1.0</version>\n"
                + "  <packaging>pom</packaging>\n"
                + "  <modules><module>module-a</module><module>module-b</module></modules>\n"
                + "  <profiles><profile>\n"
                + "    <id>my-profile</id>\n"
                + "    <properties><dep.version>2.0</dep.version></properties>\n"
                + "  </profile></profiles>\n"
                + "</project>\n";
        writePom(root.resolve("pom.xml"), parentPomXml);

        String moduleAPomXml = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>\n"
                + "  <artifactId>module-a</artifactId>\n"
                + "</project>\n";
        writePom(root.resolve("module-a/pom.xml"), moduleAPomXml);

        String moduleBPomXml = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>\n"
                + "  <artifactId>module-b</artifactId>\n"
                + "  <dependencies>\n"
                + "    <dependency><groupId>com.example</groupId><artifactId>lib-x</artifactId><version>${dep.version}</version></dependency>\n"
                + "  </dependencies>\n"
                + "</project>\n";
        writePom(root.resolve("module-b/pom.xml"), moduleBPomXml);

        List<MavenProject> projects = buildProjectList(root, parentPomXml, moduleAPomXml, moduleBPomXml);
        // Do NOT set active profiles - the profile is inactive

        String oldParentPom = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <groupId>com.example</groupId>\n"
                + "  <artifactId>parent</artifactId>\n"
                + "  <version>1.0</version>\n"
                + "  <packaging>pom</packaging>\n"
                + "  <modules><module>module-a</module><module>module-b</module></modules>\n"
                + "  <profiles><profile>\n"
                + "    <id>my-profile</id>\n"
                + "    <properties><dep.version>1.0</dep.version></properties>\n"
                + "  </profile></profiles>\n"
                + "</project>\n";

        Set<String> changedPoms = Collections.singleton("pom.xml");
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
        String parentPomXml = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <groupId>com.example</groupId>\n"
                + "  <artifactId>parent</artifactId>\n"
                + "  <version>1.0</version>\n"
                + "  <packaging>pom</packaging>\n"
                + "  <modules><module>module-a</module><module>module-b</module></modules>\n"
                + "  <profiles><profile>\n"
                + "    <id>my-profile</id>\n"
                + "    <dependencyManagement><dependencies>\n"
                + "      <dependency><groupId>com.example</groupId><artifactId>lib-x</artifactId><version>2.0</version></dependency>\n"
                + "    </dependencies></dependencyManagement>\n"
                + "  </profile></profiles>\n"
                + "</project>\n";
        writePom(root.resolve("pom.xml"), parentPomXml);

        String moduleAPomXml = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>\n"
                + "  <artifactId>module-a</artifactId>\n"
                + "</project>\n";
        writePom(root.resolve("module-a/pom.xml"), moduleAPomXml);

        // module-b uses lib-x
        String moduleBPomXml = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>\n"
                + "  <artifactId>module-b</artifactId>\n"
                + "  <dependencies>\n"
                + "    <dependency><groupId>com.example</groupId><artifactId>lib-x</artifactId></dependency>\n"
                + "  </dependencies>\n"
                + "</project>\n";
        writePom(root.resolve("module-b/pom.xml"), moduleBPomXml);

        List<MavenProject> projects = buildProjectList(root, parentPomXml, moduleAPomXml, moduleBPomXml);
        Profile activeProfile = new Profile();
        activeProfile.setId("my-profile");
        projects.get(0).setActiveProfiles(Collections.singletonList(activeProfile));

        // Old parent POM had lib-x:1.0 in the profile
        String oldParentPom = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <groupId>com.example</groupId>\n"
                + "  <artifactId>parent</artifactId>\n"
                + "  <version>1.0</version>\n"
                + "  <packaging>pom</packaging>\n"
                + "  <modules><module>module-a</module><module>module-b</module></modules>\n"
                + "  <profiles><profile>\n"
                + "    <id>my-profile</id>\n"
                + "    <dependencyManagement><dependencies>\n"
                + "      <dependency><groupId>com.example</groupId><artifactId>lib-x</artifactId><version>1.0</version></dependency>\n"
                + "    </dependencies></dependencyManagement>\n"
                + "  </profile></profiles>\n"
                + "</project>\n";

        Set<String> changedPoms = Collections.singleton("pom.xml");
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
                Collections.singleton("org.apache.maven.plugins:maven-compiler-plugin"),
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
                Collections.singleton("org.apache.maven.plugins:maven-surefire-plugin"),
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

    // --- Source directory comparison tests ---

    @Test
    void analyzeChanges_sourceDirectoryChangeAffectsParent() throws Exception {
        Path root = setupReactorRoot();

        String parentPomXml = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <groupId>com.example</groupId>\n"
                + "  <artifactId>parent</artifactId>\n"
                + "  <version>1.0</version>\n"
                + "  <packaging>pom</packaging>\n"
                + "  <modules><module>module-a</module><module>module-b</module></modules>\n"
                + "  <build>\n"
                + "    <sourceDirectory>src/main/java2</sourceDirectory>\n"
                + "  </build>\n"
                + "</project>\n";
        writePom(root.resolve("pom.xml"), parentPomXml);

        String moduleAPomXml = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>\n"
                + "  <artifactId>module-a</artifactId>\n"
                + "</project>\n";
        writePom(root.resolve("module-a/pom.xml"), moduleAPomXml);

        String moduleBPomXml = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>\n"
                + "  <artifactId>module-b</artifactId>\n"
                + "</project>\n";
        writePom(root.resolve("module-b/pom.xml"), moduleBPomXml);

        List<MavenProject> projects = buildProjectList(root, parentPomXml, moduleAPomXml, moduleBPomXml);

        // Old POM had src/main/java as source directory
        String oldParentPom = parentPomXml.replace("src/main/java2", "src/main/java");

        Set<String> changedPoms = Collections.singleton("pom.xml");
        Map<String, byte[]> oldPoms = new HashMap<>();
        oldPoms.put("pom.xml", oldParentPom.getBytes(StandardCharsets.UTF_8));

        PomChangeAnalyzer.Result result = analyzer.analyzeChanges(changedPoms, oldPoms, projects, root);

        assertTrue(
                result.getAffectedProjects().contains(projects.get(0)),
                "Parent should be self-affected (source directory changed)");
    }

    @Test
    void analyzeChanges_testSourceDirectoryChangeAffectsParent() throws Exception {
        Path root = setupReactorRoot();

        String parentPomXml = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <groupId>com.example</groupId>\n"
                + "  <artifactId>parent</artifactId>\n"
                + "  <version>1.0</version>\n"
                + "  <packaging>pom</packaging>\n"
                + "  <modules><module>module-a</module><module>module-b</module></modules>\n"
                + "  <build>\n"
                + "    <testSourceDirectory>src/test/java2</testSourceDirectory>\n"
                + "  </build>\n"
                + "</project>\n";
        writePom(root.resolve("pom.xml"), parentPomXml);

        String moduleAPomXml = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>\n"
                + "  <artifactId>module-a</artifactId>\n"
                + "</project>\n";
        writePom(root.resolve("module-a/pom.xml"), moduleAPomXml);

        String moduleBPomXml = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>\n"
                + "  <artifactId>module-b</artifactId>\n"
                + "</project>\n";
        writePom(root.resolve("module-b/pom.xml"), moduleBPomXml);

        List<MavenProject> projects = buildProjectList(root, parentPomXml, moduleAPomXml, moduleBPomXml);

        String oldParentPom = parentPomXml.replace("src/test/java2", "src/test/java");

        Set<String> changedPoms = Collections.singleton("pom.xml");
        Map<String, byte[]> oldPoms = new HashMap<>();
        oldPoms.put("pom.xml", oldParentPom.getBytes(StandardCharsets.UTF_8));

        PomChangeAnalyzer.Result result = analyzer.analyzeChanges(changedPoms, oldPoms, projects, root);

        assertTrue(
                result.getAffectedProjects().contains(projects.get(0)),
                "Parent should be self-affected (test source directory changed)");
    }

    @Test
    void analyzeChanges_scriptSourceDirectoryChangeAffectsParent() throws Exception {
        Path root = setupReactorRoot();

        String parentPomXml = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <groupId>com.example</groupId>\n"
                + "  <artifactId>parent</artifactId>\n"
                + "  <version>1.0</version>\n"
                + "  <packaging>pom</packaging>\n"
                + "  <modules><module>module-a</module><module>module-b</module></modules>\n"
                + "  <build>\n"
                + "    <scriptSourceDirectory>src/main/scripts2</scriptSourceDirectory>\n"
                + "  </build>\n"
                + "</project>\n";
        writePom(root.resolve("pom.xml"), parentPomXml);

        String moduleAPomXml = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>\n"
                + "  <artifactId>module-a</artifactId>\n"
                + "</project>\n";
        writePom(root.resolve("module-a/pom.xml"), moduleAPomXml);

        String moduleBPomXml = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>\n"
                + "  <artifactId>module-b</artifactId>\n"
                + "</project>\n";
        writePom(root.resolve("module-b/pom.xml"), moduleBPomXml);

        List<MavenProject> projects = buildProjectList(root, parentPomXml, moduleAPomXml, moduleBPomXml);

        String oldParentPom = parentPomXml.replace("src/main/scripts2", "src/main/scripts");

        Set<String> changedPoms = Collections.singleton("pom.xml");
        Map<String, byte[]> oldPoms = new HashMap<>();
        oldPoms.put("pom.xml", oldParentPom.getBytes(StandardCharsets.UTF_8));

        PomChangeAnalyzer.Result result = analyzer.analyzeChanges(changedPoms, oldPoms, projects, root);

        assertTrue(
                result.getAffectedProjects().contains(projects.get(0)),
                "Parent should be self-affected (script source directory changed)");
    }

    @Test
    void analyzeChanges_resourceDirectoryChangeAffectsParent() throws Exception {
        Path root = setupReactorRoot();

        String parentPomXml = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <groupId>com.example</groupId>\n"
                + "  <artifactId>parent</artifactId>\n"
                + "  <version>1.0</version>\n"
                + "  <packaging>pom</packaging>\n"
                + "  <modules><module>module-a</module><module>module-b</module></modules>\n"
                + "  <build>\n"
                + "    <resources><resource><directory>src/main/resources2</directory></resource></resources>\n"
                + "  </build>\n"
                + "</project>\n";
        writePom(root.resolve("pom.xml"), parentPomXml);

        String moduleAPomXml = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>\n"
                + "  <artifactId>module-a</artifactId>\n"
                + "</project>\n";
        writePom(root.resolve("module-a/pom.xml"), moduleAPomXml);

        String moduleBPomXml = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>\n"
                + "  <artifactId>module-b</artifactId>\n"
                + "</project>\n";
        writePom(root.resolve("module-b/pom.xml"), moduleBPomXml);

        List<MavenProject> projects = buildProjectList(root, parentPomXml, moduleAPomXml, moduleBPomXml);

        String oldParentPom = parentPomXml.replace("src/main/resources2", "src/main/resources");

        Set<String> changedPoms = Collections.singleton("pom.xml");
        Map<String, byte[]> oldPoms = new HashMap<>();
        oldPoms.put("pom.xml", oldParentPom.getBytes(StandardCharsets.UTF_8));

        PomChangeAnalyzer.Result result = analyzer.analyzeChanges(changedPoms, oldPoms, projects, root);

        assertTrue(
                result.getAffectedProjects().contains(projects.get(0)),
                "Parent should be self-affected (resource directory changed)");
    }

    @Test
    void analyzeChanges_resourceFilteringChangeAffectsParent() throws Exception {
        Path root = setupReactorRoot();

        String parentPomXml = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <groupId>com.example</groupId>\n"
                + "  <artifactId>parent</artifactId>\n"
                + "  <version>1.0</version>\n"
                + "  <packaging>pom</packaging>\n"
                + "  <modules><module>module-a</module><module>module-b</module></modules>\n"
                + "  <build>\n"
                + "    <resources><resource><directory>src/main/resources</directory><filtering>true</filtering></resource></resources>\n"
                + "  </build>\n"
                + "</project>\n";
        writePom(root.resolve("pom.xml"), parentPomXml);

        String moduleAPomXml = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>\n"
                + "  <artifactId>module-a</artifactId>\n"
                + "</project>\n";
        writePom(root.resolve("module-a/pom.xml"), moduleAPomXml);

        String moduleBPomXml = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>\n"
                + "  <artifactId>module-b</artifactId>\n"
                + "</project>\n";
        writePom(root.resolve("module-b/pom.xml"), moduleBPomXml);

        List<MavenProject> projects = buildProjectList(root, parentPomXml, moduleAPomXml, moduleBPomXml);

        // Old POM had filtering=false
        String oldParentPom = parentPomXml.replace("<filtering>true</filtering>", "<filtering>false</filtering>");

        Set<String> changedPoms = Collections.singleton("pom.xml");
        Map<String, byte[]> oldPoms = new HashMap<>();
        oldPoms.put("pom.xml", oldParentPom.getBytes(StandardCharsets.UTF_8));

        PomChangeAnalyzer.Result result = analyzer.analyzeChanges(changedPoms, oldPoms, projects, root);

        assertTrue(
                result.getAffectedProjects().contains(projects.get(0)),
                "Parent should be self-affected (resource filtering changed)");
    }

    @Test
    void analyzeChanges_sameSourceDirectoriesNoChange() throws Exception {
        Path root = setupReactorRoot();

        String parentPomXml = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <groupId>com.example</groupId>\n"
                + "  <artifactId>parent</artifactId>\n"
                + "  <version>1.0</version>\n"
                + "  <packaging>pom</packaging>\n"
                + "  <modules><module>module-a</module><module>module-b</module></modules>\n"
                + "  <build>\n"
                + "    <sourceDirectory>src/main/java</sourceDirectory>\n"
                + "  </build>\n"
                + "</project>\n";
        writePom(root.resolve("pom.xml"), parentPomXml);

        String moduleAPomXml = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>\n"
                + "  <artifactId>module-a</artifactId>\n"
                + "</project>\n";
        writePom(root.resolve("module-a/pom.xml"), moduleAPomXml);

        String moduleBPomXml = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>\n"
                + "  <artifactId>module-b</artifactId>\n"
                + "</project>\n";
        writePom(root.resolve("module-b/pom.xml"), moduleBPomXml);

        List<MavenProject> projects = buildProjectList(root, parentPomXml, moduleAPomXml, moduleBPomXml);

        Set<String> changedPoms = Collections.singleton("pom.xml");
        Map<String, byte[]> oldPoms = new HashMap<>();
        oldPoms.put("pom.xml", parentPomXml.getBytes(StandardCharsets.UTF_8));

        PomChangeAnalyzer.Result result = analyzer.analyzeChanges(changedPoms, oldPoms, projects, root);

        assertTrue(result.getAffectedProjects().isEmpty(), "Same source directories should not affect any module");
    }

    // --- Repository comparison tests ---

    @Test
    void analyzeChanges_repositoryAddedAffectsParent() throws Exception {
        Path root = setupReactorRoot();

        String parentPomXml = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <groupId>com.example</groupId>\n"
                + "  <artifactId>parent</artifactId>\n"
                + "  <version>1.0</version>\n"
                + "  <packaging>pom</packaging>\n"
                + "  <modules><module>module-a</module><module>module-b</module></modules>\n"
                + "  <repositories>\n"
                + "    <repository>\n"
                + "      <id>central</id>\n"
                + "      <url>https://repo.maven.apache.org/maven2</url>\n"
                + "    </repository>\n"
                + "  </repositories>\n"
                + "</project>\n";
        writePom(root.resolve("pom.xml"), parentPomXml);

        String moduleAPomXml = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>\n"
                + "  <artifactId>module-a</artifactId>\n"
                + "</project>\n";
        writePom(root.resolve("module-a/pom.xml"), moduleAPomXml);

        String moduleBPomXml = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>\n"
                + "  <artifactId>module-b</artifactId>\n"
                + "</project>\n";
        writePom(root.resolve("module-b/pom.xml"), moduleBPomXml);

        List<MavenProject> projects = buildProjectList(root, parentPomXml, moduleAPomXml, moduleBPomXml);

        // Old POM had no repositories
        String oldParentPom = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <groupId>com.example</groupId>\n"
                + "  <artifactId>parent</artifactId>\n"
                + "  <version>1.0</version>\n"
                + "  <packaging>pom</packaging>\n"
                + "  <modules><module>module-a</module><module>module-b</module></modules>\n"
                + "</project>\n";

        Set<String> changedPoms = Collections.singleton("pom.xml");
        Map<String, byte[]> oldPoms = new HashMap<>();
        oldPoms.put("pom.xml", oldParentPom.getBytes(StandardCharsets.UTF_8));

        PomChangeAnalyzer.Result result = analyzer.analyzeChanges(changedPoms, oldPoms, projects, root);

        assertTrue(
                result.getAffectedProjects().contains(projects.get(0)),
                "Parent should be self-affected (repository added)");
    }

    @Test
    void analyzeChanges_repositoryUrlChangeAffectsParent() throws Exception {
        Path root = setupReactorRoot();

        String parentPomXml = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <groupId>com.example</groupId>\n"
                + "  <artifactId>parent</artifactId>\n"
                + "  <version>1.0</version>\n"
                + "  <packaging>pom</packaging>\n"
                + "  <modules><module>module-a</module><module>module-b</module></modules>\n"
                + "  <repositories>\n"
                + "    <repository>\n"
                + "      <id>custom</id>\n"
                + "      <url>https://new-repo.example.com/maven2</url>\n"
                + "    </repository>\n"
                + "  </repositories>\n"
                + "</project>\n";
        writePom(root.resolve("pom.xml"), parentPomXml);

        String moduleAPomXml = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>\n"
                + "  <artifactId>module-a</artifactId>\n"
                + "</project>\n";
        writePom(root.resolve("module-a/pom.xml"), moduleAPomXml);

        String moduleBPomXml = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>\n"
                + "  <artifactId>module-b</artifactId>\n"
                + "</project>\n";
        writePom(root.resolve("module-b/pom.xml"), moduleBPomXml);

        List<MavenProject> projects = buildProjectList(root, parentPomXml, moduleAPomXml, moduleBPomXml);

        String oldParentPom =
                parentPomXml.replace("https://new-repo.example.com/maven2", "https://old-repo.example.com/maven2");

        Set<String> changedPoms = Collections.singleton("pom.xml");
        Map<String, byte[]> oldPoms = new HashMap<>();
        oldPoms.put("pom.xml", oldParentPom.getBytes(StandardCharsets.UTF_8));

        PomChangeAnalyzer.Result result = analyzer.analyzeChanges(changedPoms, oldPoms, projects, root);

        assertTrue(
                result.getAffectedProjects().contains(projects.get(0)),
                "Parent should be self-affected (repository URL changed)");
    }

    @Test
    void analyzeChanges_repositorySnapshotPolicyChangeAffectsParent() throws Exception {
        Path root = setupReactorRoot();

        String parentPomXml = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <groupId>com.example</groupId>\n"
                + "  <artifactId>parent</artifactId>\n"
                + "  <version>1.0</version>\n"
                + "  <packaging>pom</packaging>\n"
                + "  <modules><module>module-a</module><module>module-b</module></modules>\n"
                + "  <repositories>\n"
                + "    <repository>\n"
                + "      <id>custom</id>\n"
                + "      <url>https://repo.example.com/maven2</url>\n"
                + "      <snapshots><enabled>true</enabled></snapshots>\n"
                + "    </repository>\n"
                + "  </repositories>\n"
                + "</project>\n";
        writePom(root.resolve("pom.xml"), parentPomXml);

        String moduleAPomXml = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>\n"
                + "  <artifactId>module-a</artifactId>\n"
                + "</project>\n";
        writePom(root.resolve("module-a/pom.xml"), moduleAPomXml);

        String moduleBPomXml = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>\n"
                + "  <artifactId>module-b</artifactId>\n"
                + "</project>\n";
        writePom(root.resolve("module-b/pom.xml"), moduleBPomXml);

        List<MavenProject> projects = buildProjectList(root, parentPomXml, moduleAPomXml, moduleBPomXml);

        // Old POM had snapshots disabled
        String oldParentPom = parentPomXml.replace(
                "<snapshots><enabled>true</enabled></snapshots>", "<snapshots><enabled>false</enabled></snapshots>");

        Set<String> changedPoms = Collections.singleton("pom.xml");
        Map<String, byte[]> oldPoms = new HashMap<>();
        oldPoms.put("pom.xml", oldParentPom.getBytes(StandardCharsets.UTF_8));

        PomChangeAnalyzer.Result result = analyzer.analyzeChanges(changedPoms, oldPoms, projects, root);

        assertTrue(
                result.getAffectedProjects().contains(projects.get(0)),
                "Parent should be self-affected (repository snapshot policy changed)");
    }

    @Test
    void analyzeChanges_pluginRepositoryChangeAffectsParent() throws Exception {
        Path root = setupReactorRoot();

        String parentPomXml = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <groupId>com.example</groupId>\n"
                + "  <artifactId>parent</artifactId>\n"
                + "  <version>1.0</version>\n"
                + "  <packaging>pom</packaging>\n"
                + "  <modules><module>module-a</module><module>module-b</module></modules>\n"
                + "  <pluginRepositories>\n"
                + "    <pluginRepository>\n"
                + "      <id>plugin-repo</id>\n"
                + "      <url>https://plugins.example.com/maven2</url>\n"
                + "    </pluginRepository>\n"
                + "  </pluginRepositories>\n"
                + "</project>\n";
        writePom(root.resolve("pom.xml"), parentPomXml);

        String moduleAPomXml = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>\n"
                + "  <artifactId>module-a</artifactId>\n"
                + "</project>\n";
        writePom(root.resolve("module-a/pom.xml"), moduleAPomXml);

        String moduleBPomXml = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>\n"
                + "  <artifactId>module-b</artifactId>\n"
                + "</project>\n";
        writePom(root.resolve("module-b/pom.xml"), moduleBPomXml);

        List<MavenProject> projects = buildProjectList(root, parentPomXml, moduleAPomXml, moduleBPomXml);

        // Old POM had no plugin repositories
        String oldParentPom = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <groupId>com.example</groupId>\n"
                + "  <artifactId>parent</artifactId>\n"
                + "  <version>1.0</version>\n"
                + "  <packaging>pom</packaging>\n"
                + "  <modules><module>module-a</module><module>module-b</module></modules>\n"
                + "</project>\n";

        Set<String> changedPoms = Collections.singleton("pom.xml");
        Map<String, byte[]> oldPoms = new HashMap<>();
        oldPoms.put("pom.xml", oldParentPom.getBytes(StandardCharsets.UTF_8));

        PomChangeAnalyzer.Result result = analyzer.analyzeChanges(changedPoms, oldPoms, projects, root);

        assertTrue(
                result.getAffectedProjects().contains(projects.get(0)),
                "Parent should be self-affected (plugin repository added)");
    }

    @Test
    void analyzeChanges_sameRepositoriesNoChange() throws Exception {
        Path root = setupReactorRoot();

        String parentPomXml = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <groupId>com.example</groupId>\n"
                + "  <artifactId>parent</artifactId>\n"
                + "  <version>1.0</version>\n"
                + "  <packaging>pom</packaging>\n"
                + "  <modules><module>module-a</module><module>module-b</module></modules>\n"
                + "  <repositories>\n"
                + "    <repository>\n"
                + "      <id>central</id>\n"
                + "      <url>https://repo.maven.apache.org/maven2</url>\n"
                + "    </repository>\n"
                + "  </repositories>\n"
                + "</project>\n";
        writePom(root.resolve("pom.xml"), parentPomXml);

        String moduleAPomXml = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>\n"
                + "  <artifactId>module-a</artifactId>\n"
                + "</project>\n";
        writePom(root.resolve("module-a/pom.xml"), moduleAPomXml);

        String moduleBPomXml = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>\n"
                + "  <artifactId>module-b</artifactId>\n"
                + "</project>\n";
        writePom(root.resolve("module-b/pom.xml"), moduleBPomXml);

        List<MavenProject> projects = buildProjectList(root, parentPomXml, moduleAPomXml, moduleBPomXml);

        Set<String> changedPoms = Collections.singleton("pom.xml");
        Map<String, byte[]> oldPoms = new HashMap<>();
        oldPoms.put("pom.xml", parentPomXml.getBytes(StandardCharsets.UTF_8));

        PomChangeAnalyzer.Result result = analyzer.analyzeChanges(changedPoms, oldPoms, projects, root);

        assertTrue(result.getAffectedProjects().isEmpty(), "Same repositories should not affect any module");
    }

    @Test
    void analyzeChanges_repositoryRemovedAffectsParent() throws Exception {
        Path root = setupReactorRoot();

        // New POM has no repositories
        String parentPomXml = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <groupId>com.example</groupId>\n"
                + "  <artifactId>parent</artifactId>\n"
                + "  <version>1.0</version>\n"
                + "  <packaging>pom</packaging>\n"
                + "  <modules><module>module-a</module><module>module-b</module></modules>\n"
                + "</project>\n";
        writePom(root.resolve("pom.xml"), parentPomXml);

        String moduleAPomXml = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>\n"
                + "  <artifactId>module-a</artifactId>\n"
                + "</project>\n";
        writePom(root.resolve("module-a/pom.xml"), moduleAPomXml);

        String moduleBPomXml = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>\n"
                + "  <artifactId>module-b</artifactId>\n"
                + "</project>\n";
        writePom(root.resolve("module-b/pom.xml"), moduleBPomXml);

        List<MavenProject> projects = buildProjectList(root, parentPomXml, moduleAPomXml, moduleBPomXml);

        // Old POM had a repository
        String oldParentPom = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <groupId>com.example</groupId>\n"
                + "  <artifactId>parent</artifactId>\n"
                + "  <version>1.0</version>\n"
                + "  <packaging>pom</packaging>\n"
                + "  <modules><module>module-a</module><module>module-b</module></modules>\n"
                + "  <repositories>\n"
                + "    <repository>\n"
                + "      <id>custom</id>\n"
                + "      <url>https://repo.example.com/maven2</url>\n"
                + "    </repository>\n"
                + "  </repositories>\n"
                + "</project>\n";

        Set<String> changedPoms = Collections.singleton("pom.xml");
        Map<String, byte[]> oldPoms = new HashMap<>();
        oldPoms.put("pom.xml", oldParentPom.getBytes(StandardCharsets.UTF_8));

        PomChangeAnalyzer.Result result = analyzer.analyzeChanges(changedPoms, oldPoms, projects, root);

        assertTrue(
                result.getAffectedProjects().contains(projects.get(0)),
                "Parent should be self-affected (repository removed)");
    }

    // --- Resource filtering property tracking tests ---

    @Test
    void analyzeChanges_filteredResourcePropertyChangeAffectsChild() throws Exception {
        Path root = setupReactorRoot();

        // Parent POM with app.version=2.0 (new)
        String parentPomXml = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <groupId>com.example</groupId>\n"
                + "  <artifactId>parent</artifactId>\n"
                + "  <version>1.0</version>\n"
                + "  <packaging>pom</packaging>\n"
                + "  <modules><module>module-a</module><module>module-b</module></modules>\n"
                + "  <properties>\n"
                + "    <app.version>2.0</app.version>\n"
                + "  </properties>\n"
                + "</project>\n";
        writePom(root.resolve("pom.xml"), parentPomXml);

        // module-a: no filtered resources
        String moduleAPomXml = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>\n"
                + "  <artifactId>module-a</artifactId>\n"
                + "</project>\n";
        writePom(root.resolve("module-a/pom.xml"), moduleAPomXml);

        // module-b: has filtered resources
        String moduleBPomXml = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>\n"
                + "  <artifactId>module-b</artifactId>\n"
                + "</project>\n";
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
        String oldParentPom = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <groupId>com.example</groupId>\n"
                + "  <artifactId>parent</artifactId>\n"
                + "  <version>1.0</version>\n"
                + "  <packaging>pom</packaging>\n"
                + "  <modules><module>module-a</module><module>module-b</module></modules>\n"
                + "  <properties>\n"
                + "    <app.version>1.0</app.version>\n"
                + "  </properties>\n"
                + "</project>\n";

        Set<String> changedPoms = Collections.singleton("pom.xml");
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
        String parentPomXml = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <groupId>com.example</groupId>\n"
                + "  <artifactId>parent</artifactId>\n"
                + "  <version>1.0</version>\n"
                + "  <packaging>pom</packaging>\n"
                + "  <modules><module>module-a</module><module>module-b</module></modules>\n"
                + "  <properties>\n"
                + "    <app.version>2.0</app.version>\n"
                + "  </properties>\n"
                + "</project>\n";
        writePom(root.resolve("pom.xml"), parentPomXml);

        String moduleAPomXml = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>\n"
                + "  <artifactId>module-a</artifactId>\n"
                + "</project>\n";
        writePom(root.resolve("module-a/pom.xml"), moduleAPomXml);

        String moduleBPomXml = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>\n"
                + "  <artifactId>module-b</artifactId>\n"
                + "</project>\n";
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

        String oldParentPom = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <groupId>com.example</groupId>\n"
                + "  <artifactId>parent</artifactId>\n"
                + "  <version>1.0</version>\n"
                + "  <packaging>pom</packaging>\n"
                + "  <modules><module>module-a</module><module>module-b</module></modules>\n"
                + "  <properties>\n"
                + "    <app.version>1.0</app.version>\n"
                + "  </properties>\n"
                + "</project>\n";

        Set<String> changedPoms = Collections.singleton("pom.xml");
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
        Set<String> changedPoms = Collections.singleton("pom.xml");
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
        String parentPomXml = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <groupId>com.example</groupId>\n"
                + "  <artifactId>parent</artifactId>\n"
                + "  <version>1.0</version>\n"
                + "  <packaging>pom</packaging>\n"
                + "  <modules><module>module-a</module><module>module-b</module></modules>\n"
                + "</project>\n";
        writePom(root.resolve("pom.xml"), parentPomXml);

        String moduleAPomXml = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>\n"
                + "  <artifactId>module-a</artifactId>\n"
                + "</project>\n";
        writePom(root.resolve("module-a/pom.xml"), moduleAPomXml);

        String moduleBPomXml = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>\n"
                + "  <artifactId>module-b</artifactId>\n"
                + "</project>\n";
        writePom(root.resolve("module-b/pom.xml"), moduleBPomXml);

        List<MavenProject> projects = buildProjectList(root, parentPomXml, moduleAPomXml, moduleBPomXml);

        // Profile 'prod' is active but has been removed from the POM
        Profile activeProfile = new Profile();
        activeProfile.setId("prod");
        projects.get(0).setActiveProfiles(Collections.singletonList(activeProfile));

        // Old parent POM had profile 'prod' with direct dependencies
        String oldParentPom = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <groupId>com.example</groupId>\n"
                + "  <artifactId>parent</artifactId>\n"
                + "  <version>1.0</version>\n"
                + "  <packaging>pom</packaging>\n"
                + "  <modules><module>module-a</module><module>module-b</module></modules>\n"
                + "  <profiles><profile>\n"
                + "    <id>prod</id>\n"
                + "    <properties><prod.version>1.0</prod.version></properties>\n"
                + "    <dependencies>\n"
                + "      <dependency><groupId>com.prod</groupId><artifactId>lib</artifactId><version>1.0</version></dependency>\n"
                + "    </dependencies>\n"
                + "  </profile></profiles>\n"
                + "</project>\n";

        Set<String> changedPoms = Collections.singleton("pom.xml");
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

        String parentPomXml = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <groupId>com.example</groupId>\n"
                + "  <artifactId>parent</artifactId>\n"
                + "  <version>1.0</version>\n"
                + "  <packaging>pom</packaging>\n"
                + "  <modules><module>module-a</module><module>module-b</module></modules>\n"
                + "  <profiles><profile>\n"
                + "    <id>prod</id>\n"
                + "    <dependencies>\n"
                + "      <dependency><groupId>com.prod</groupId><artifactId>lib</artifactId><version>2.0</version></dependency>\n"
                + "    </dependencies>\n"
                + "  </profile></profiles>\n"
                + "</project>\n";
        writePom(root.resolve("pom.xml"), parentPomXml);

        String moduleAPomXml = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>\n"
                + "  <artifactId>module-a</artifactId>\n"
                + "</project>\n";
        writePom(root.resolve("module-a/pom.xml"), moduleAPomXml);

        String moduleBPomXml = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>\n"
                + "  <artifactId>module-b</artifactId>\n"
                + "</project>\n";
        writePom(root.resolve("module-b/pom.xml"), moduleBPomXml);

        List<MavenProject> projects = buildProjectList(root, parentPomXml, moduleAPomXml, moduleBPomXml);
        Profile activeProfile = new Profile();
        activeProfile.setId("prod");
        projects.get(0).setActiveProfiles(Collections.singletonList(activeProfile));

        // Old POM: profile had lib version 1.0
        String oldParentPom = parentPomXml.replace("<version>2.0</version>", "<version>1.0</version>");

        Set<String> changedPoms = Collections.singleton("pom.xml");
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
        Set<String> changedPoms = Collections.singleton("module-a/pom.xml");
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

        String parentPomXml = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <groupId>com.example</groupId>\n"
                + "  <artifactId>parent</artifactId>\n"
                + "  <version>1.0</version>\n"
                + "  <packaging>pom</packaging>\n"
                + "  <modules><module>module-a</module><module>module-b</module></modules>\n"
                + "  <build><plugins>\n"
                + "    <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.12.0</version></plugin>\n"
                + "  </plugins></build>\n"
                + "</project>\n";
        writePom(root.resolve("pom.xml"), parentPomXml);

        String moduleAPomXml = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>\n"
                + "  <artifactId>module-a</artifactId>\n"
                + "</project>\n";
        writePom(root.resolve("module-a/pom.xml"), moduleAPomXml);

        String moduleBPomXml = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>\n"
                + "  <artifactId>module-b</artifactId>\n"
                + "</project>\n";
        writePom(root.resolve("module-b/pom.xml"), moduleBPomXml);

        List<MavenProject> projects = buildProjectList(root, parentPomXml, moduleAPomXml, moduleBPomXml);

        String oldParentPom = parentPomXml.replace("3.12.0", "3.11.0");

        Set<String> changedPoms = Collections.singleton("pom.xml");
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

        String parentPomXml = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <groupId>com.example</groupId>\n"
                + "  <artifactId>parent</artifactId>\n"
                + "  <version>1.0</version>\n"
                + "  <packaging>pom</packaging>\n"
                + "  <modules><module>module-a</module><module>module-b</module></modules>\n"
                + "  <dependencies>\n"
                + "    <dependency><groupId>junit</groupId><artifactId>junit</artifactId><version>4.13.2</version></dependency>\n"
                + "  </dependencies>\n"
                + "</project>\n";
        writePom(root.resolve("pom.xml"), parentPomXml);

        String moduleAPomXml = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>\n"
                + "  <artifactId>module-a</artifactId>\n"
                + "</project>\n";
        writePom(root.resolve("module-a/pom.xml"), moduleAPomXml);

        String moduleBPomXml = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>\n"
                + "  <artifactId>module-b</artifactId>\n"
                + "</project>\n";
        writePom(root.resolve("module-b/pom.xml"), moduleBPomXml);

        List<MavenProject> projects = buildProjectList(root, parentPomXml, moduleAPomXml, moduleBPomXml);

        String oldParentPom = parentPomXml.replace("4.13.2", "4.13.1");

        Set<String> changedPoms = Collections.singleton("pom.xml");
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

        String parentPomXml = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <groupId>com.example</groupId>\n"
                + "  <artifactId>parent</artifactId>\n"
                + "  <version>1.0</version>\n"
                + "  <packaging>pom</packaging>\n"
                + "  <modules><module>module-a</module><module>module-b</module></modules>\n"
                + "</project>\n";
        writePom(root.resolve("pom.xml"), parentPomXml);

        String moduleAPomXml = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>\n"
                + "  <artifactId>module-a</artifactId>\n"
                + "</project>\n";
        writePom(root.resolve("module-a/pom.xml"), moduleAPomXml);

        String moduleBPomXml = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>\n"
                + "  <artifactId>module-b</artifactId>\n"
                + "</project>\n";
        writePom(root.resolve("module-b/pom.xml"), moduleBPomXml);

        List<MavenProject> projects = buildProjectList(root, parentPomXml, moduleAPomXml, moduleBPomXml);

        // Old POM had jar packaging
        String oldParentPom = parentPomXml.replace("<packaging>pom</packaging>", "<packaging>jar</packaging>");

        Set<String> changedPoms = Collections.singleton("pom.xml");
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
        Set<String> changedPoms = Collections.singleton("nonexistent/pom.xml");
        Map<String, byte[]> oldPoms = new HashMap<>();

        Set<MavenProject> affected =
                analyzer.analyzeChanges(changedPoms, oldPoms, projects, root).getAffectedProjects();

        assertTrue(affected.isEmpty(), "Unmatched POM path should not affect any module");
    }

    // --- Helper methods ---

    private Path setupReactorRoot() throws IOException {
        Path root = tempDir.resolve("project");
        Files.createDirectories(root);
        Files.createDirectories(root.resolve("module-a"));
        Files.createDirectories(root.resolve("module-b"));
        return root;
    }

    private List<MavenProject> createSimpleReactor(Path root) throws IOException {
        // Parent POM
        String parentPomXml = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <groupId>com.example</groupId>\n"
                + "  <artifactId>parent</artifactId>\n"
                + "  <version>1.0</version>\n"
                + "  <packaging>pom</packaging>\n"
                + "  <modules><module>module-a</module><module>module-b</module></modules>\n"
                + "</project>\n";
        writePom(root.resolve("pom.xml"), parentPomXml);

        String moduleAPomXml = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>\n"
                + "  <artifactId>module-a</artifactId>\n"
                + "</project>\n";
        writePom(root.resolve("module-a/pom.xml"), moduleAPomXml);

        String moduleBPomXml = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>\n"
                + "  <artifactId>module-b</artifactId>\n"
                + "</project>\n";
        writePom(root.resolve("module-b/pom.xml"), moduleBPomXml);

        return buildProjectList(root, parentPomXml, moduleAPomXml, moduleBPomXml);
    }

    private List<MavenProject> createReactorWithPropertyUsage(Path root) throws IOException {
        // Parent POM with dep.version=2.0 (new value)
        String parentPomXml = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <groupId>com.example</groupId>\n"
                + "  <artifactId>parent</artifactId>\n"
                + "  <version>1.0</version>\n"
                + "  <packaging>pom</packaging>\n"
                + "  <modules><module>module-a</module><module>module-b</module></modules>\n"
                + "  <properties>\n"
                + "    <dep.version>2.0</dep.version>\n"
                + "  </properties>\n"
                + "</project>\n";
        writePom(root.resolve("pom.xml"), parentPomXml);

        // module-a: does NOT reference ${dep.version}
        String moduleAPomXml = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>\n"
                + "  <artifactId>module-a</artifactId>\n"
                + "  <dependencies>\n"
                + "    <dependency><groupId>org.other</groupId><artifactId>other-lib</artifactId><version>3.0</version></dependency>\n"
                + "  </dependencies>\n"
                + "</project>\n";
        writePom(root.resolve("module-a/pom.xml"), moduleAPomXml);

        // module-b: references ${dep.version} in a dependency
        String moduleBPomXml = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>\n"
                + "  <artifactId>module-b</artifactId>\n"
                + "  <dependencies>\n"
                + "    <dependency><groupId>com.example</groupId><artifactId>lib-x</artifactId><version>${dep.version}</version></dependency>\n"
                + "  </dependencies>\n"
                + "</project>\n";
        writePom(root.resolve("module-b/pom.xml"), moduleBPomXml);

        return buildProjectList(root, parentPomXml, moduleAPomXml, moduleBPomXml);
    }

    private List<MavenProject> createReactorWithDepMgmtUsage(Path root) throws IOException {
        // Parent POM with depMgmt for lib-x:2.0 (new value)
        String parentPomXml = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <groupId>com.example</groupId>\n"
                + "  <artifactId>parent</artifactId>\n"
                + "  <version>1.0</version>\n"
                + "  <packaging>pom</packaging>\n"
                + "  <modules><module>module-a</module><module>module-b</module></modules>\n"
                + "  <dependencyManagement><dependencies>\n"
                + "    <dependency>\n"
                + "      <groupId>com.example</groupId>\n"
                + "      <artifactId>lib-x</artifactId>\n"
                + "      <version>2.0</version>\n"
                + "    </dependency>\n"
                + "  </dependencies></dependencyManagement>\n"
                + "</project>\n";
        writePom(root.resolve("pom.xml"), parentPomXml);

        // module-a: does NOT use lib-x
        String moduleAPomXml = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>\n"
                + "  <artifactId>module-a</artifactId>\n"
                + "</project>\n";
        writePom(root.resolve("module-a/pom.xml"), moduleAPomXml);

        // module-b: uses lib-x (managed, no version)
        String moduleBPomXml = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>\n"
                + "  <artifactId>module-b</artifactId>\n"
                + "  <dependencies>\n"
                + "    <dependency><groupId>com.example</groupId><artifactId>lib-x</artifactId></dependency>\n"
                + "  </dependencies>\n"
                + "</project>\n";
        writePom(root.resolve("module-b/pom.xml"), moduleBPomXml);

        return buildProjectList(root, parentPomXml, moduleAPomXml, moduleBPomXml);
    }

    private List<MavenProject> createReactorWithManagedDepPropertyIndirection(Path root) throws IOException {
        // Parent POM: spring.version=6.0.0 (new), managed dep spring-core uses ${spring.version}
        String parentPomXml = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <groupId>com.example</groupId>\n"
                + "  <artifactId>parent</artifactId>\n"
                + "  <version>1.0</version>\n"
                + "  <packaging>pom</packaging>\n"
                + "  <modules><module>module-a</module><module>module-b</module></modules>\n"
                + "  <properties>\n"
                + "    <spring.version>6.0.0</spring.version>\n"
                + "  </properties>\n"
                + "  <dependencyManagement><dependencies>\n"
                + "    <dependency>\n"
                + "      <groupId>org.springframework</groupId>\n"
                + "      <artifactId>spring-core</artifactId>\n"
                + "      <version>${spring.version}</version>\n"
                + "    </dependency>\n"
                + "  </dependencies></dependencyManagement>\n"
                + "</project>\n";
        writePom(root.resolve("pom.xml"), parentPomXml);

        // module-a: does NOT use spring-core
        String moduleAPomXml = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>\n"
                + "  <artifactId>module-a</artifactId>\n"
                + "</project>\n";
        writePom(root.resolve("module-a/pom.xml"), moduleAPomXml);

        // module-b: uses spring-core (managed, no version in child POM)
        String moduleBPomXml = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>\n"
                + "  <artifactId>module-b</artifactId>\n"
                + "  <dependencies>\n"
                + "    <dependency><groupId>org.springframework</groupId><artifactId>spring-core</artifactId></dependency>\n"
                + "  </dependencies>\n"
                + "</project>\n";
        writePom(root.resolve("module-b/pom.xml"), moduleBPomXml);

        return buildProjectList(root, parentPomXml, moduleAPomXml, moduleBPomXml);
    }

    private List<MavenProject> createReactorWithManagedPluginPropertyIndirection(Path root) throws IOException {
        // Parent POM: compiler.version=3.12.0 (new), managed plugin uses ${compiler.version}
        String parentPomXml = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <groupId>com.example</groupId>\n"
                + "  <artifactId>parent</artifactId>\n"
                + "  <version>1.0</version>\n"
                + "  <packaging>pom</packaging>\n"
                + "  <modules><module>module-a</module><module>module-b</module></modules>\n"
                + "  <properties>\n"
                + "    <compiler.version>3.12.0</compiler.version>\n"
                + "  </properties>\n"
                + "  <build><pluginManagement><plugins>\n"
                + "    <plugin>\n"
                + "      <groupId>org.apache.maven.plugins</groupId>\n"
                + "      <artifactId>maven-compiler-plugin</artifactId>\n"
                + "      <version>${compiler.version}</version>\n"
                + "    </plugin>\n"
                + "  </plugins></pluginManagement></build>\n"
                + "</project>\n";
        writePom(root.resolve("pom.xml"), parentPomXml);

        // module-a: does NOT use maven-compiler-plugin
        String moduleAPomXml = "<?xml version=\"1.0\"?>\n"
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>\n"
                + "  <artifactId>module-a</artifactId>\n"
                + "</project>\n";
        writePom(root.resolve("module-a/pom.xml"), moduleAPomXml);

        // module-b: uses maven-compiler-plugin (managed, no version in child POM)
        String moduleBPomXml = "<?xml version=\"1.0\"?>\n"
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
