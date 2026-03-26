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
import org.apache.maven.model.PluginManagement;
import org.apache.maven.project.MavenProject;
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

        Set<MavenProject> affected = analyzer.analyzeChanges(changedPoms, oldPoms, projects, root);

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

        Set<MavenProject> affected = analyzer.analyzeChanges(changedPoms, oldPoms, projects, root);

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

        Set<MavenProject> affected = analyzer.analyzeChanges(changedPoms, oldPoms, projects, root);

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

        Set<MavenProject> affected = analyzer.analyzeChanges(changedPoms, oldPoms, projects, root);

        assertTrue(affected.isEmpty(), "Cosmetic parent POM change should not affect any module");
    }

    @Test
    void analyzeChanges_newPomMarksAllChildren() throws Exception {
        Path root = setupReactorRoot();
        List<MavenProject> projects = createSimpleReactor(root);

        // POM didn't exist in old commit (no entry in oldPoms)
        Set<String> changedPoms = Collections.singleton("pom.xml");
        Map<String, byte[]> oldPoms = new HashMap<>();

        Set<MavenProject> affected = analyzer.analyzeChanges(changedPoms, oldPoms, projects, root);

        assertEquals(3, affected.size(), "New parent POM should mark parent + all children");
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
}
