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

    @Test
    void mapToProjectsClassified_repoRootFileDoesNotMapToRootAggregator() {
        Path root = tempDir;
        List<MavenProject> projects = createProjects(root);

        // Files at the repository root (README.md, .gitignore) are not part of
        // any module's source tree and should not trigger a rebuild of the root
        // aggregator.  The old catch-all (projectPath.isEmpty()) matched them.
        Set<String> changedFiles = new LinkedHashSet<>(List.of("README.md", ".gitignore"));

        ModuleMapper.Result result = mapper.mapToProjectsClassified(changedFiles, projects, root);

        assertTrue(result.getAllAffected().isEmpty(), "repo-root files should not map to any module");
    }

    @Test
    void mapToProjectsClassified_rootProjectSourceStillMaps() {
        Path root = tempDir;
        List<MavenProject> projects = createProjects(root);

        // Files within the root project's own source tree (src/main/java/...)
        // should still map to the root project.
        Set<String> changedFiles = new LinkedHashSet<>(List.of("src/main/java/com/example/App.java"));

        ModuleMapper.Result result = mapper.mapToProjectsClassified(changedFiles, projects, root);

        assertEquals(1, result.getMainAffected().size());
        assertTrue(
                result.getMainAffected().contains(projects.get(0)),
                "src/ files at root should map to the root project");
    }

    // --- Tests for the hash-lookup algorithm (#113) ---

    @Test
    void mapToProjectsClassified_nestedModulesMapToDeepest() {
        Path root = tempDir;
        // parent > parent/child > parent/child/grandchild
        MavenProject rootProject =
                createProject("com.example", "root", root.resolve("pom.xml").toFile());
        MavenProject parentModule = createProject(
                "com.example", "parent", root.resolve("parent/pom.xml").toFile());
        MavenProject childModule = createProject(
                "com.example", "child", root.resolve("parent/child/pom.xml").toFile());
        MavenProject grandchild = createProject(
                "com.example",
                "grandchild",
                root.resolve("parent/child/grandchild/pom.xml").toFile());
        List<MavenProject> projects = List.of(rootProject, parentModule, childModule, grandchild);

        // A file in grandchild should map to grandchild, not parent or child
        Set<String> changedFiles = new LinkedHashSet<>(List.of("parent/child/grandchild/src/main/java/App.java"));

        ModuleMapper.Result result = mapper.mapToProjectsClassified(changedFiles, projects, root);

        assertEquals(1, result.getMainAffected().size());
        assertTrue(
                result.getMainAffected().contains(grandchild),
                "file in deeply nested module should map to the deepest matching module");
    }

    @Test
    void mapToProjectsClassified_fileInIntermediateModuleMapsCorrectly() {
        Path root = tempDir;
        MavenProject rootProject =
                createProject("com.example", "root", root.resolve("pom.xml").toFile());
        MavenProject parentModule = createProject(
                "com.example", "parent", root.resolve("parent/pom.xml").toFile());
        MavenProject childModule = createProject(
                "com.example", "child", root.resolve("parent/child/pom.xml").toFile());
        List<MavenProject> projects = List.of(rootProject, parentModule, childModule);

        // A file in parent (not in child) should map to parent
        Set<String> changedFiles = new LinkedHashSet<>(List.of("parent/src/main/java/ParentApp.java"));

        ModuleMapper.Result result = mapper.mapToProjectsClassified(changedFiles, projects, root);

        assertEquals(1, result.getMainAffected().size());
        assertTrue(
                result.getMainAffected().contains(parentModule),
                "file in parent module should map to parent, not child");
    }

    @Test
    void mapToProjectsClassified_largeFileSetPerformance() {
        Path root = tempDir;
        // Simulate a large reactor with many modules
        List<MavenProject> projects = new java.util.ArrayList<>();
        MavenProject rootProject =
                createProject("com.example", "root", root.resolve("pom.xml").toFile());
        projects.add(rootProject);
        for (int i = 0; i < 200; i++) {
            String moduleName = "module-" + i;
            projects.add(createProject(
                    "com.example",
                    moduleName,
                    root.resolve(moduleName + "/pom.xml").toFile()));
        }

        // 2000 changed files across various modules
        Set<String> changedFiles = new LinkedHashSet<>();
        for (int i = 0; i < 2000; i++) {
            String module = "module-" + (i % 200);
            changedFiles.add(module + "/src/main/java/com/example/Class" + i + ".java");
        }

        // This should complete quickly with hash-based lookup
        long start = System.nanoTime();
        ModuleMapper.Result result = mapper.mapToProjectsClassified(changedFiles, projects, root);
        long elapsed = System.nanoTime() - start;

        // All 200 modules should be affected
        assertEquals(200, result.getAllAffected().size());
        // Should complete in well under 1 second (hash lookups are O(1))
        assertTrue(
                elapsed < 1_000_000_000L,
                "Large file set should complete in <1s but took " + (elapsed / 1_000_000) + "ms");
    }

    @Test
    void mapToProjectsClassified_fileUnderNonModuleDir_fallsToRoot() {
        Path root = tempDir;
        MavenProject rootProject =
                createProject("com.example", "root", root.resolve("pom.xml").toFile());
        MavenProject moduleA = createProject(
                "com.example", "module-a", root.resolve("module-a/pom.xml").toFile());
        List<MavenProject> projects = List.of(rootProject, moduleA);

        // A file under a directory that isn't a module should fall back to root
        Set<String> changedFiles = new LinkedHashSet<>(List.of("scripts/build.sh"));

        ModuleMapper.Result result = mapper.mapToProjectsClassified(changedFiles, projects, root);

        assertEquals(1, result.getMainAffected().size());
        assertTrue(
                result.getMainAffected().contains(rootProject),
                "file in non-module dir should fall back to root project");
    }

    @Test
    void mapToProjectsClassified_explainDisabled_noTriggeringFiles() {
        Path root = tempDir;
        List<MavenProject> projects = createProjects(root);

        Set<String> changedFiles = new LinkedHashSet<>(List.of("module-a/src/main/java/Foo.java"));

        ModuleMapper.Result result = mapper.mapToProjectsClassified(changedFiles, projects, root, false);

        assertEquals(1, result.getMainAffected().size());
        assertTrue(result.getTriggeringFiles().isEmpty(), "triggering files should be empty when explain=false");
    }

    @Test
    void getRelativePath_normalizedRootOptimization() {
        Path root = tempDir;
        Path normalizedRoot = root.toAbsolutePath().normalize();
        MavenProject project = createProject(
                "com.example", "module-a", root.resolve("module-a/pom.xml").toFile());

        // Static getRelativePath should work with pre-normalized root
        String relPath = ModuleMapper.getRelativePath(project, normalizedRoot);
        assertEquals("module-a", relPath);
    }

    @Test
    void getRelativePath_rootProject() {
        Path root = tempDir;
        Path normalizedRoot = root.toAbsolutePath().normalize();
        MavenProject project =
                createProject("com.example", "root", root.resolve("pom.xml").toFile());

        String relPath = ModuleMapper.getRelativePath(project, normalizedRoot);
        assertEquals("", relPath, "root project should have empty relative path");
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
