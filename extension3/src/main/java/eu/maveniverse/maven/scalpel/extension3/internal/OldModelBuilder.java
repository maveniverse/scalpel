/*
 * Copyright (c) Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.scalpel.extension3.internal;

import static java.util.Objects.requireNonNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Model;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.project.ProjectBuildingResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
@Named
class OldModelBuilder {

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final ProjectBuilder projectBuilder;

    @Inject
    public OldModelBuilder(ProjectBuilder projectBuilder) {
        this.projectBuilder = requireNonNull(projectBuilder, "projectBuilder");
    }

    public Map<String, Model> buildOldModels(Map<String, byte[]> pomContents, MavenSession session, Path reactorRoot)
            throws IOException, ProjectBuildingException {
        Path tempDir = Files.createTempDirectory("scalpel-old-poms-");
        try {
            // Write all old POM bytes to temp dir at corresponding relative paths
            List<File> pomFiles = new ArrayList<>();
            for (Map.Entry<String, byte[]> entry : pomContents.entrySet()) {
                Path pomPath = tempDir.resolve(entry.getKey());
                Files.createDirectories(pomPath.getParent());
                Files.write(pomPath, entry.getValue());
                pomFiles.add(pomPath.toFile());
            }

            // Clone the building request with minimal validation
            ProjectBuildingRequest request = new DefaultProjectBuildingRequest(session.getProjectBuildingRequest());
            request.setProcessPlugins(false);
            request.setResolveDependencies(false);
            request.setValidationLevel(org.apache.maven.model.building.ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL);

            // Build all projects
            List<ProjectBuildingResult> results = projectBuilder.build(pomFiles, true, request);

            Map<String, Model> models = new HashMap<>();
            for (ProjectBuildingResult result : results) {
                MavenProject project = result.getProject();
                if (project != null) {
                    String key = project.getGroupId() + ":" + project.getArtifactId();
                    models.put(key, project.getModel());
                    logger.debug("Built old model for {}", key);
                }
            }
            return models;
        } finally {
            deleteRecursively(tempDir);
        }
    }

    private void deleteRecursively(Path path) throws IOException {
        if (Files.exists(path)) {
            Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }
}
