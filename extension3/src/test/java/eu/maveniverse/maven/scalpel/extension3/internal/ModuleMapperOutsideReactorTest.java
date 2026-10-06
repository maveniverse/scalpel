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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A file living under a directory that holds its own pom.xml but whose module is ABSENT from
 * the current reactor (profile-gated module, disabled module) must contribute nothing: the
 * old fallback mapped such files to the root project, marking the entire reactor affected
 * (#185). Bare root files keep mapping to the root project, unchanged.
 *
 * <p>Files belonging to a deleted module (pom.xml removed at HEAD, no pom.xml on disk)
 * must also contribute nothing — they must not be attributed to the nearest surviving
 * ancestor when a {@code deletedModuleDirs} set is supplied (#216).
 */
class ModuleMapperOutsideReactorTest {

    @TempDir
    Path tempDir;

    @Test
    void fileUnderNonReactorModuleDirectory_isIgnored() throws IOException {
        ModuleMapper mapper = new ModuleMapper();
        Path root = tempDir;
        List<MavenProject> projects = createProjects(root);

        // operator/ holds its own pom.xml on disk but is not part of the reactor list:
        // exactly the profile-gated-module situation from the issue's apicurio case.
        Path operatorPom = root.resolve("operator/pom.xml");
        Files.createDirectories(operatorPom.getParent());
        Files.write(operatorPom, "<project/>".getBytes(StandardCharsets.UTF_8));

        Set<String> changedFiles = new LinkedHashSet<>(List.of("operator/Makefile"));

        ModuleMapper.Result result = mapper.mapToProjectsClassified(changedFiles, projects, root);

        assertTrue(result.getMainAffected().isEmpty(), "outside-reactor files must not affect any module");
        assertTrue(result.getTestOnlyAffected().isEmpty());
        assertTrue(result.getAllAffected().isEmpty(), "the build set must not widen to the root project");
    }

    @Test
    void nestedFileUnderNonReactorModuleDirectory_isIgnored() throws IOException {
        ModuleMapper mapper = new ModuleMapper();
        Path root = tempDir;
        List<MavenProject> projects = createProjects(root);

        Path operatorPom = root.resolve("operator/pom.xml");
        Files.createDirectories(operatorPom.getParent());
        Files.write(operatorPom, "<project/>".getBytes(StandardCharsets.UTF_8));

        Set<String> changedFiles = new LinkedHashSet<>(List.of("operator/install/deploy/catalog.yaml"));
        ModuleMapper.Result result = mapper.mapToProjectsClassified(changedFiles, projects, root);

        assertTrue(result.getAllAffected().isEmpty(), "nested files under operator/ contribute nothing");
    }

    @Test
    void bareRootFile_contributesNothing() throws IOException {
        ModuleMapper mapper = new ModuleMapper();
        Path root = tempDir;
        List<MavenProject> projects = createProjects(root);

        Set<String> changedFiles = new LinkedHashSet<>(List.of("renovate.json"));
        ModuleMapper.Result result = mapper.mapToProjectsClassified(changedFiles, projects, root);

        // Bare root files (no directory separator) return null today and keep doing so:
        // per the issue's own verification they contribute nothing, and 57 of 57 modules
        // were skipped on such a commit.
        assertTrue(result.getAllAffected().isEmpty(), "bare root files contribute nothing");
    }

    // --- Tests for deleted module source file attribution (#216) ---

    /**
     * Reproducer for #216: deleting a leaf module whose pom.xml no longer exists on disk
     * must not attribute its source files to the nearest surviving ancestor.
     *
     * <p>Reactor structure:
     * <pre>
     *   root
     *   └── libs          (aggregator, in reactor)
     *       ├── lib-a     (in reactor)
     *       ├── lib-b     (in reactor)
     *       └── lib-gone  (DELETED — not in reactor, no pom.xml on disk)
     * </pre>
     * lib-gone's source files must not make {@code libs} a DIRECT SOURCE_CHANGE.
     */
    @Test
    void deletedModuleSourceFiles_notAttributedToParent() {
        ModuleMapper mapper = new ModuleMapper();
        Path root = tempDir;

        // Reactor at HEAD: root, libs, lib-a, lib-b — lib-gone is deleted
        MavenProject rootProject =
                createProject("com.example", "root", root.resolve("pom.xml").toFile());
        MavenProject libs = createProject(
                "com.example", "libs", root.resolve("libs/pom.xml").toFile());
        MavenProject libA = createProject(
                "com.example", "lib-a", root.resolve("libs/lib-a/pom.xml").toFile());
        MavenProject libB = createProject(
                "com.example", "lib-b", root.resolve("libs/lib-b/pom.xml").toFile());
        List<MavenProject> projects = List.of(rootProject, libs, libA, libB);

        // Source files from the deleted lib-gone module (pom.xml is deleted, not on disk)
        Set<String> changedFiles = new LinkedHashSet<>(
                List.of("libs/lib-gone/src/main/java/LibGone.java", "libs/lib-gone/src/test/java/LibGoneTest.java"));

        // deletedModuleDirs: derived from the changed pom.xml that has no match in reactor
        Set<String> deletedModuleDirs = Set.of("libs/lib-gone");

        ModuleMapper.Result result =
                mapper.mapToProjectsClassified(changedFiles, projects, root, true, deletedModuleDirs);

        assertTrue(
                result.getAllAffected().isEmpty(),
                "deleted-module source files must not be attributed to any surviving module");
        assertTrue(result.getMainAffected().isEmpty(), "libs must not be marked DIRECT SOURCE_CHANGE");
    }

    /**
     * Without the deletedModuleDirs guard, the same files would fall through and attribute
     * to the nearest surviving ancestor. Confirm the old (broken) behaviour — so that the
     * regression test below can demonstrate the fix.
     */
    @Test
    void deletedModuleSourceFiles_withoutGuard_wouldAttributeToParent() {
        ModuleMapper mapper = new ModuleMapper();
        Path root = tempDir;

        MavenProject rootProject =
                createProject("com.example", "root", root.resolve("pom.xml").toFile());
        MavenProject libs = createProject(
                "com.example", "libs", root.resolve("libs/pom.xml").toFile());
        MavenProject libA = createProject(
                "com.example", "lib-a", root.resolve("libs/lib-a/pom.xml").toFile());
        MavenProject libB = createProject(
                "com.example", "lib-b", root.resolve("libs/lib-b/pom.xml").toFile());
        List<MavenProject> projects = List.of(rootProject, libs, libA, libB);

        // No pom.xml on disk for lib-gone (deleted), no deletedModuleDirs guard
        Set<String> changedFiles = new LinkedHashSet<>(List.of("libs/lib-gone/src/main/java/LibGone.java"));

        // Without the guard (empty deletedModuleDirs), the file walks up to libs
        ModuleMapper.Result result = mapper.mapToProjectsClassified(changedFiles, projects, root, true, Set.of());

        assertEquals(1, result.getMainAffected().size(), "without guard, file falls through to libs");
        assertTrue(result.getMainAffected().contains(libs), "without guard, libs is incorrectly attributed");
    }

    /**
     * A deeply nested source file under the deleted module must also be stopped at the
     * deleted module's directory prefix, not walk further up to the surviving ancestor.
     */
    @Test
    void deletedModuleDeepSourceFile_notAttributedToParent() {
        ModuleMapper mapper = new ModuleMapper();
        Path root = tempDir;

        MavenProject rootProject =
                createProject("com.example", "root", root.resolve("pom.xml").toFile());
        MavenProject libs = createProject(
                "com.example", "libs", root.resolve("libs/pom.xml").toFile());
        List<MavenProject> projects = List.of(rootProject, libs);

        Set<String> changedFiles =
                new LinkedHashSet<>(List.of("libs/lib-gone/src/main/java/com/example/deep/package/Service.java"));
        Set<String> deletedModuleDirs = Set.of("libs/lib-gone");

        ModuleMapper.Result result =
                mapper.mapToProjectsClassified(changedFiles, projects, root, true, deletedModuleDirs);

        assertTrue(
                result.getAllAffected().isEmpty(),
                "deeply nested files under deleted module must not be attributed to any surviving module");
    }

    /**
     * Sibling modules of the deleted module must still be attributable normally.
     * Only files UNDER the deleted module's prefix are stopped.
     */
    @Test
    void siblingModuleChanges_stillAttributedCorrectly() {
        ModuleMapper mapper = new ModuleMapper();
        Path root = tempDir;

        MavenProject rootProject =
                createProject("com.example", "root", root.resolve("pom.xml").toFile());
        MavenProject libs = createProject(
                "com.example", "libs", root.resolve("libs/pom.xml").toFile());
        MavenProject libA = createProject(
                "com.example", "lib-a", root.resolve("libs/lib-a/pom.xml").toFile());
        List<MavenProject> projects = List.of(rootProject, libs, libA);

        // lib-gone deleted, but lib-a has its own source changes too
        Set<String> changedFiles = new LinkedHashSet<>(List.of(
                "libs/lib-gone/src/main/java/LibGone.java", // deleted module
                "libs/lib-a/src/main/java/LibA.java" // surviving sibling
                ));
        Set<String> deletedModuleDirs = Set.of("libs/lib-gone");

        ModuleMapper.Result result =
                mapper.mapToProjectsClassified(changedFiles, projects, root, true, deletedModuleDirs);

        assertEquals(1, result.getMainAffected().size(), "only lib-a should be affected");
        assertTrue(result.getMainAffected().contains(libA), "lib-a should be in main affected");
        assertFalse(result.getMainAffected().contains(libs), "libs must not be attributed");
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

    private MavenProject createProject(String groupId, String artifactId, java.io.File pomFile) {
        org.apache.maven.model.Model model = new org.apache.maven.model.Model();
        model.setGroupId(groupId);
        model.setArtifactId(artifactId);
        model.setVersion("1.0");
        model.setPomFile(pomFile);
        MavenProject project = new MavenProject(model);
        project.setFile(pomFile);
        return project;
    }
}
