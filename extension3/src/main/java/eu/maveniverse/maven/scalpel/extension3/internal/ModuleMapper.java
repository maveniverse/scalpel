/*
 * Copyright (c) Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.scalpel.extension3.internal;

import java.nio.file.Path;
import java.util.HashMap;
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
        private final Map<MavenProject, Set<String>> triggeringFiles;

        Result(
                Set<MavenProject> mainAffected,
                Set<MavenProject> testOnlyAffected,
                Map<MavenProject, Set<String>> triggeringFiles) {
            this.mainAffected = mainAffected;
            this.testOnlyAffected = testOnlyAffected;
            this.triggeringFiles = triggeringFiles;
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

        /**
         * Files that mapped each module into the affected set (explain-mode evidence).
         */
        Map<MavenProject, Set<String>> getTriggeringFiles() {
            return triggeringFiles;
        }
    }

    public Result mapToProjectsClassified(Set<String> changedFiles, List<MavenProject> projects, Path reactorRoot) {
        return mapToProjectsClassified(changedFiles, projects, reactorRoot, true);
    }

    public Result mapToProjectsClassified(
            Set<String> changedFiles, List<MavenProject> projects, Path reactorRoot, boolean explain) {
        // Track for each project whether it has any main (non-test) source changes
        Map<MavenProject, Boolean> hasMainChange = new LinkedHashMap<>();
        // Track which changed file triggered each project (explain-mode evidence)
        Map<MavenProject, Set<String>> triggeringFiles = new LinkedHashMap<>();

        // Precompute the relative path per project once; normalize the reactor root
        // once rather than on every call. Build a lookup map keyed by module directory
        // so each changed file can find its owning module via parent-directory walks
        // instead of a linear scan: O(M) setup + O(F × depth) lookups.
        Path rootDir = reactorRoot.toAbsolutePath().normalize();
        Map<String, MavenProject> moduleByDir = new HashMap<>();
        Map<MavenProject, String> pathByProject = new HashMap<>();
        MavenProject rootProject = null;
        for (MavenProject project : projects) {
            String projectPath = getRelativePath(project, rootDir);
            if (projectPath.isEmpty()) {
                rootProject = project;
            } else {
                moduleByDir.put(projectPath, project);
                pathByProject.put(project, projectPath);
            }
        }

        for (String changedFile : changedFiles) {
            MavenProject matched = findOwningModule(changedFile, moduleByDir, rootProject);
            if (matched != null) {
                String projectPath = matched == rootProject ? "" : pathByProject.get(matched);
                boolean isTest = isTestPath(changedFile, projectPath);
                Boolean existing = hasMainChange.get(matched);
                if (existing == null) {
                    hasMainChange.put(matched, !isTest);
                } else if (!isTest) {
                    hasMainChange.put(matched, Boolean.TRUE);
                }
                if (explain) {
                    triggeringFiles
                            .computeIfAbsent(matched, k -> new LinkedHashSet<>())
                            .add(changedFile);
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

        return new Result(mainAffected, testOnlyAffected, triggeringFiles);
    }

    public Set<MavenProject> mapToProjects(Set<String> changedFiles, List<MavenProject> projects, Path reactorRoot) {
        return mapToProjectsClassified(changedFiles, projects, reactorRoot).getAllAffected();
    }

    /**
     * Finds the owning module for a changed file by walking its parent directories from
     * deepest to root, doing hash lookups. The first match is the most specific (deepest-nested)
     * module, preserving the same semantics as the previous length-descending sort approach.
     *
     * @return the owning project, or {@code null} if no module owns this file
     */
    private static MavenProject findOwningModule(
            String changedFile, Map<String, MavenProject> moduleByDir, MavenProject rootProject) {
        // Walk parent directories from deepest to shallowest
        int slash = changedFile.lastIndexOf('/');
        if (slash <= 0) {
            // No directory separator (bare file like "README.md") or only at position 0:
            // root project only matches files in subdirectories, not bare root files
            return null;
        }
        while (slash > 0) {
            String dir = changedFile.substring(0, slash);
            MavenProject project = moduleByDir.get(dir);
            if (project != null) {
                return project;
            }
            slash = dir.lastIndexOf('/');
        }
        // No module directory matched; fall back to root project for files
        // that are in subdirectories (src/main/..., scripts/..., etc.)
        return rootProject;
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

    /**
     * Computes the relative path of a project directory against an already-normalized reactor root.
     * The caller must pass {@code reactorRoot.toAbsolutePath().normalize()} to avoid redundant
     * normalization on every call.
     */
    static String getRelativePath(MavenProject project, Path normalizedRoot) {
        Path projectDir = project.getBasedir().toPath().toAbsolutePath().normalize();
        if (projectDir.equals(normalizedRoot)) {
            return "";
        }
        return normalizedRoot.relativize(projectDir).toString().replace('\\', '/');
    }
}
