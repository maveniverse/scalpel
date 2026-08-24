/*
 * Copyright (c) Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.scalpel.extension3.internal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.model.Resource;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.impl.RemoteRepositoryManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for issue #83: the filtered-resource walk must not follow symbolic links.
 */
class SymlinkResourceWalkTest {

    private static final String PARENT_POM_NEW = """
            <?xml version="1.0"?>
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.example</groupId>
              <artifactId>parent</artifactId>
              <version>1.0</version>
              <packaging>pom</packaging>
              <modules><module>module-b</module></modules>
              <properties>
                <spring.version>6.1.0</spring.version>
              </properties>
            </project>
            """;

    private static final String PARENT_POM_OLD = PARENT_POM_NEW.replace("6.1.0", "6.0.0");

    private static final String MODULE_POM = """
            <?xml version="1.0"?>
            <project>
              <modelVersion>4.0.0</modelVersion>
              <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
              <artifactId>module-b</artifactId>
            </project>
            """;

    private PomChangeAnalyzer analyzer;

    /** Default resolution context with a mock session; reactor-local parents resolve from the temp hierarchy. */
    private final PomChangeAnalyzer.ModelResolutionContext defaultResolutionCtx =
            new PomChangeAnalyzer.ModelResolutionContext(
                    new Properties(), new Properties(), mock(RepositorySystemSession.class), List.of());

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        analyzer = new PomChangeAnalyzer(
                mock(RepositorySystem.class),
                mock(RemoteRepositoryManager.class),
                new org.apache.maven.model.building.DefaultModelBuilderFactory().newInstance());
    }

    @Test
    @Timeout(10)
    void selfReferentialSymlinkInResourceDirCompletesPromptly() throws Exception {
        Path root = tempDir.resolve("project");
        Path resourceDir = root.resolve("module-b/src/main/resources");
        Files.createDirectories(resourceDir);
        // Plain file WITHOUT the property ref: the walk must terminate on its own,
        // not short-circuit on a match.
        Files.write(resourceDir.resolve("application.properties"), "some.key=value\n".getBytes(StandardCharsets.UTF_8));
        assumeTrue(createSymlink(resourceDir.resolve("loop"), resourceDir), "symlinks not supported");

        List<MavenProject> projects = buildProjects(root);
        Set<MavenProject> affected = analyze(root, projects);
        // The walk terminated; no property refs exist anywhere, so module-b is not affected.
        assertFalse(affected.contains(projects.get(1)), "analysis must terminate without false positive");
    }

    @Test
    @Timeout(10)
    void symlinkPointingOutsideModuleIsNotRead() throws Exception {
        Path root = tempDir.resolve("project");
        Path resourceDir = root.resolve("module-b/src/main/resources");
        Files.createDirectories(resourceDir);
        // Target outside the module DOES contain the placeholder; the walk must never read it.
        Path outside = tempDir.resolve("outside.properties");
        Files.write(outside, "spring.version=${spring.version}\n".getBytes(StandardCharsets.UTF_8));
        assumeTrue(createSymlink(resourceDir.resolve("probe"), outside), "symlinks not supported");

        List<MavenProject> projects = buildProjects(root);
        Set<MavenProject> affected = analyze(root, projects);
        assertFalse(
                affected.contains(projects.get(1)),
                "module-b must not be affected through a symlink outside the module");
    }

    private List<MavenProject> buildProjects(Path root) throws IOException {
        writePom(root.resolve("pom.xml"), PARENT_POM_NEW);
        writePom(root.resolve("module-b/pom.xml"), MODULE_POM);

        MavenProject parent = createProject("com.example", "parent", "1.0", root.resolve("pom.xml"));
        parent.setOriginalModel(parseModel(PARENT_POM_NEW));
        parent.getModel().setPackaging("pom");

        MavenProject moduleB = createProject("com.example", "module-b", "1.0", root.resolve("module-b/pom.xml"));
        moduleB.setOriginalModel(parseModel(MODULE_POM));
        moduleB.setParent(parent);

        Path resourceDir = root.resolve("module-b/src/main/resources");
        Resource resource = new Resource();
        resource.setDirectory(resourceDir.toString());
        resource.setFiltering(true);
        Build build = new Build();
        build.addResource(resource);
        moduleB.getModel().setBuild(build);

        return List.of(parent, moduleB);
    }

    private Set<MavenProject> analyze(Path root, List<MavenProject> projects) throws IOException {
        Map<String, byte[]> oldPoms = new HashMap<>();
        oldPoms.put("pom.xml", PARENT_POM_OLD.getBytes(StandardCharsets.UTF_8));

        return analyzer.analyzeChanges(Set.of("pom.xml"), oldPoms, projects, root, true, defaultResolutionCtx)
                .getAffectedProjects();
    }

    private boolean createSymlink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (UnsupportedOperationException | IOException e) {
            return false;
        }
    }

    private void writePom(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
    }

    private MavenProject createProject(String groupId, String artifactId, String version, Path pomFile) {
        Model model = new Model();
        model.setGroupId(groupId);
        model.setArtifactId(artifactId);
        model.setVersion(version);
        model.setPomFile(pomFile.toFile());
        MavenProject project = new MavenProject(model);
        project.setFile(pomFile.toFile());
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
}
