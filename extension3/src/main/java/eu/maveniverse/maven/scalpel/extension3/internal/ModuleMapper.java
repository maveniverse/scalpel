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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Named;
import javax.inject.Singleton;
import org.apache.maven.project.MavenProject;

@Singleton
@Named
class ModuleMapper {

    static class Result {
        private final Set<MavenProject> mainAffected;
        private final Set<MavenProject> testOnlyAffected;

        Result(Set<MavenProject> mainAffected, Set<MavenProject> testOnlyAffected) {
            this.mainAffected = mainAffected;
            this.testOnlyAffected = testOnlyAffected;
        }

        Set<MavenProject> getAllAffected() {
            Set<MavenProject> all = new LinkedHashSet<>(mainAffected);
            all.addAll(testOnlyAffected);
            return all;
        }

        Set<MavenProject> getMainAffected() {
            return mainAffected;
        }

        Set<MavenProject> getTestOnlyAffected() {
            return testOnlyAffected;
        }
    }

    public Result mapToProjectsClassified(Set<String> changedFiles, List<MavenProject> projects, Path reactorRoot) {
        // Track for each project whether it has any main (non-test) source changes
        Map<MavenProject, Boolean> hasMainChange = new LinkedHashMap<>();

        // Sort projects by basedir depth (most specific first)
        List<MavenProject> sortedProjects = new ArrayList<>(projects);
        sortedProjects.sort(Comparator.comparingInt(
                        (MavenProject p) -> getRelativePath(p, reactorRoot).length())
                .reversed());

        for (String changedFile : changedFiles) {
            for (MavenProject project : sortedProjects) {
                String projectPath = getRelativePath(project, reactorRoot);
                if (projectPath.isEmpty() || changedFile.startsWith(projectPath + "/")) {
                    boolean isTest = isTestPath(changedFile, projectPath);
                    Boolean existing = hasMainChange.get(project);
                    if (existing == null) {
                        hasMainChange.put(project, !isTest);
                    } else if (!isTest) {
                        hasMainChange.put(project, Boolean.TRUE);
                    }
                    break;
                }
            }
        }

        Set<MavenProject> mainAffected = new LinkedHashSet<>();
        Set<MavenProject> testOnlyAffected = new LinkedHashSet<>();
        for (Map.Entry<MavenProject, Boolean> entry : hasMainChange.entrySet()) {
            if (entry.getValue()) {
                mainAffected.add(entry.getKey());
            } else {
                testOnlyAffected.add(entry.getKey());
            }
        }

        return new Result(mainAffected, testOnlyAffected);
    }

    public Set<MavenProject> mapToProjects(Set<String> changedFiles, List<MavenProject> projects, Path reactorRoot) {
        return mapToProjectsClassified(changedFiles, projects, reactorRoot).getAllAffected();
    }

    static boolean isTestPath(String changedFile, String projectPath) {
        String relativeToProject;
        if (projectPath.isEmpty()) {
            relativeToProject = changedFile;
        } else {
            relativeToProject = changedFile.substring(projectPath.length() + 1);
        }
        return relativeToProject.startsWith("src/test/");
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
