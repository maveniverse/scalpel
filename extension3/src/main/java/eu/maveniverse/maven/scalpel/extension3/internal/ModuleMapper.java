/*
 * Copyright (c) Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.scalpel.extension3.internal;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.inject.Named;
import javax.inject.Singleton;
import org.apache.maven.project.MavenProject;

@Singleton
@Named
class ModuleMapper {

    public Set<MavenProject> mapToProjects(Set<String> changedFiles, List<MavenProject> projects, Path reactorRoot) {
        Set<MavenProject> affected = new LinkedHashSet<>();

        // Sort projects by basedir depth (most specific first)
        List<MavenProject> sortedProjects = new ArrayList<>(projects);
        sortedProjects.sort(Comparator.comparingInt(
                        (MavenProject p) -> getRelativePath(p, reactorRoot).length())
                .reversed());

        for (String changedFile : changedFiles) {
            for (MavenProject project : sortedProjects) {
                String projectPath = getRelativePath(project, reactorRoot);
                if (projectPath.isEmpty() || changedFile.startsWith(projectPath + "/")) {
                    affected.add(project);
                    break;
                }
            }
        }

        return affected;
    }

    private String getRelativePath(MavenProject project, Path reactorRoot) {
        Path projectDir = project.getBasedir().toPath().toAbsolutePath().normalize();
        Path rootDir = reactorRoot.toAbsolutePath().normalize();
        if (projectDir.equals(rootDir)) {
            return "";
        }
        return rootDir.relativize(projectDir).toString();
    }
}
