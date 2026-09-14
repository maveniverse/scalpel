/*
 * Copyright (c) Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.scalpel.extension3.internal;

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
