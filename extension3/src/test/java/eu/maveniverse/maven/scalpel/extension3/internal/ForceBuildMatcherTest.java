/*
 * Copyright (c) Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.scalpel.extension3.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Properties;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ForceBuildMatcherTest {

    private ForceBuildMatcher matcher;

    @BeforeEach
    void setUp() {
        matcher = new ForceBuildMatcher();
    }

    @Test
    void matchesForceBuild_matchesExactArtifactId() {
        MavenProject project = createProject("module-a");
        String result = matcher.matchesForceBuild(project, List.of("module-a"));
        assertEquals("module-a", result);
    }

    @Test
    void matchesForceBuild_matchesRegexPattern() {
        MavenProject project = createProject("module-integration-tests");
        String result = matcher.matchesForceBuild(project, List.of(".*-integration-tests"));
        assertEquals(".*-integration-tests", result);
    }

    @Test
    void matchesForceBuild_noMatch() {
        MavenProject project = createProject("module-a");
        String result = matcher.matchesForceBuild(project, List.of("module-b"));
        assertNull(result);
    }

    @Test
    void matchesForceBuild_emptyPatterns() {
        MavenProject project = createProject("module-a");
        String result = matcher.matchesForceBuild(project, List.of());
        assertNull(result);
    }

    @Test
    void matchesForceBuild_multiplePatterns_returnsFirstMatch() {
        MavenProject project = createProject("module-a");
        String result = matcher.matchesForceBuild(project, List.of("module-b", "module-a", "module-.*"));
        assertEquals("module-a", result);
    }

    @Test
    void matchesForceBuild_partialMatch_doesNotMatch() {
        // Regex matching is full-string match by default in BoundedRegexMatcher
        MavenProject project = createProject("module-a-extra");
        String result = matcher.matchesForceBuild(project, List.of("module-a"));
        assertNull(result);
    }

    @Test
    void matchesForceBuild_wildcardPattern() {
        MavenProject project = createProject("any-module");
        String result = matcher.matchesForceBuild(project, List.of(".*"));
        assertEquals(".*", result);
    }

    private MavenProject createProject(String artifactId) {
        MavenProject project = mock(MavenProject.class);
        when(project.getGroupId()).thenReturn("com.example");
        when(project.getArtifactId()).thenReturn(artifactId);
        when(project.getProperties()).thenReturn(new Properties());
        return project;
    }
}
