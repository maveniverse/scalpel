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
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.apache.maven.model.Model;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModuleMapperTest {

    private ModuleMapper mapper;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        mapper = new ModuleMapper();
    }

    @Test
    void isTestPath_srcTest() {
        assertTrue(ModuleMapper.isTestPath("module-a/src/test/java/Foo.java", "module-a"));
    }

    @Test
    void isTestPath_srcMain() {
        assertFalse(ModuleMapper.isTestPath("module-a/src/main/java/Foo.java", "module-a"));
    }

    @Test
    void isTestPath_rootProject() {
        assertTrue(ModuleMapper.isTestPath("src/test/java/Foo.java", ""));
        assertFalse(ModuleMapper.isTestPath("src/main/java/Foo.java", ""));
    }

    @Test
    void isTestPath_nonSrcFile() {
        assertFalse(ModuleMapper.isTestPath("module-a/pom.xml", "module-a"));
        assertFalse(ModuleMapper.isTestPath("module-a/README.md", "module-a"));
    }

    @Test
    void mapToProjectsClassified_testOnlyChanges() {
        Path root = tempDir;
        List<MavenProject> projects = createProjects(root);

        Set<String> changedFiles = new LinkedHashSet<>(
                List.of("module-a/src/test/java/FooTest.java", "module-a/src/test/resources/test-data.xml"));

        ModuleMapper.Result result = mapper.mapToProjectsClassified(changedFiles, projects, root);

        assertEquals(1, result.getTestOnlyAffected().size());
        assertTrue(result.getTestOnlyAffected().contains(projects.get(1))); // module-a
        assertTrue(result.getMainAffected().isEmpty());
        assertEquals(1, result.getAllAffected().size());
    }

    @Test
    void mapToProjectsClassified_mainChanges() {
        Path root = tempDir;
        List<MavenProject> projects = createProjects(root);

        Set<String> changedFiles = new LinkedHashSet<>(
                List.of("module-a/src/main/java/Foo.java", "module-a/src/main/resources/config.xml"));

        ModuleMapper.Result result = mapper.mapToProjectsClassified(changedFiles, projects, root);

        assertEquals(1, result.getMainAffected().size());
        assertTrue(result.getMainAffected().contains(projects.get(1))); // module-a
        assertTrue(result.getTestOnlyAffected().isEmpty());
    }

    @Test
    void mapToProjectsClassified_mixedChangesIsMain() {
        Path root = tempDir;
        List<MavenProject> projects = createProjects(root);

        Set<String> changedFiles =
                new LinkedHashSet<>(List.of("module-a/src/main/java/Foo.java", "module-a/src/test/java/FooTest.java"));

        ModuleMapper.Result result = mapper.mapToProjectsClassified(changedFiles, projects, root);

        assertEquals(1, result.getMainAffected().size());
        assertTrue(result.getMainAffected().contains(projects.get(1))); // module-a
        assertTrue(result.getTestOnlyAffected().isEmpty());
    }

    @Test
    void mapToProjectsClassified_multipleModules() {
        Path root = tempDir;
        List<MavenProject> projects = createProjects(root);

        Set<String> changedFiles = new LinkedHashSet<>(List.of(
                "module-a/src/test/java/FooTest.java", // test-only for module-a
                "module-b/src/main/java/Bar.java" // main for module-b
                ));

        ModuleMapper.Result result = mapper.mapToProjectsClassified(changedFiles, projects, root);

        assertEquals(1, result.getTestOnlyAffected().size());
        assertTrue(result.getTestOnlyAffected().contains(projects.get(1))); // module-a
        assertEquals(1, result.getMainAffected().size());
        assertTrue(result.getMainAffected().contains(projects.get(2))); // module-b
        assertEquals(2, result.getAllAffected().size());
    }

    @Test
    void mapToProjectsClassified_nonSrcFileIsMain() {
        Path root = tempDir;
        List<MavenProject> projects = createProjects(root);

        Set<String> changedFiles = new LinkedHashSet<>(List.of("module-a/README.md"));

        ModuleMapper.Result result = mapper.mapToProjectsClassified(changedFiles, projects, root);

        assertEquals(1, result.getMainAffected().size());
        assertTrue(result.getMainAffected().contains(projects.get(1)));
        assertTrue(result.getTestOnlyAffected().isEmpty());
    }

    private List<MavenProject> createProjects(Path root) {
        MavenProject parent =
                createProject("com.example", "parent", root.resolve("pom.xml").toFile());
        MavenProject moduleA = createProject(
                "com.example", "module-a", root.resolve("module-a/pom.xml").toFile());
        MavenProject moduleB = createProject(
                "com.example", "module-b", root.resolve("module-b/pom.xml").toFile());
        return List.of(parent, moduleA, moduleB);
    }

    private MavenProject createProject(String groupId, String artifactId, File pomFile) {
        Model model = new Model();
        model.setGroupId(groupId);
        model.setArtifactId(artifactId);
        model.setVersion("1.0");
        model.setPomFile(pomFile);
        MavenProject project = new MavenProject(model);
        project.setFile(pomFile);
        return project;
    }
}
