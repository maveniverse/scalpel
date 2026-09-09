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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import eu.maveniverse.maven.scalpel.core.ScalpelConfiguration;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChangedFileClassifierTest {

    @TempDir
    Path tempDir;

    private ChangedFileClassifier classifier;

    @BeforeEach
    void setUp() {
        classifier = new ChangedFileClassifier();
    }

    @Test
    void classifyChanges_separatesPomAndSourceFiles() {
        Set<String> changedFiles = new LinkedHashSet<>();
        changedFiles.add("pom.xml");
        changedFiles.add("module-a/pom.xml");
        changedFiles.add("module-a/src/main/java/Foo.java");
        changedFiles.add("module-b/src/test/java/FooTest.java");
        changedFiles.add(".mvn/extensions.xml");

        ChangedFileClassifier.ClassificationResult result = classifier.classifyChanges(changedFiles);

        assertEquals(Set.of("pom.xml", "module-a/pom.xml"), result.pomChanges);
        assertEquals(
                Set.of("module-a/src/main/java/Foo.java", "module-b/src/test/java/FooTest.java"), result.sourceChanges);
    }

    @Test
    void classifyChanges_ignoresMvnFiles() {
        Set<String> changedFiles = new LinkedHashSet<>();
        changedFiles.add(".mvn/extensions.xml");
        changedFiles.add(".mvn/maven.config");
        changedFiles.add(".mvn/jvm.config");

        ChangedFileClassifier.ClassificationResult result = classifier.classifyChanges(changedFiles);

        assertTrue(result.pomChanges.isEmpty());
        assertTrue(result.sourceChanges.isEmpty());
    }

    @Test
    void classifyChanges_emptyInput() {
        ChangedFileClassifier.ClassificationResult result = classifier.classifyChanges(Set.of());

        assertTrue(result.pomChanges.isEmpty());
        assertTrue(result.sourceChanges.isEmpty());
    }

    @Test
    void normalizeGlobPattern_barePatternIsPrefixed() {
        assertEquals("{*.md,**/*.md}", ChangedFileClassifier.normalizeGlobPattern("*.md"));
        assertEquals("{LICENSE,**/LICENSE}", ChangedFileClassifier.normalizeGlobPattern("LICENSE"));
    }

    @Test
    void normalizeGlobPattern_patternWithSlashIsUnchanged() {
        assertEquals("docs/*.md", ChangedFileClassifier.normalizeGlobPattern("docs/*.md"));
        assertEquals("**/*.md", ChangedFileClassifier.normalizeGlobPattern("**/*.md"));
    }

    @Test
    void compileGlobMatchers_emptyPatterns() {
        List<PathMatcher> matchers = ChangedFileClassifier.compileGlobMatchers(List.of());
        assertTrue(matchers.isEmpty());
    }

    @Test
    void compileGlobMatchers_compilesPatterns() {
        List<PathMatcher> matchers = ChangedFileClassifier.compileGlobMatchers(List.of("*.md", "docs/**"));
        assertEquals(2, matchers.size());
    }

    @Test
    void matchesIncludePaths_matchesDirectModulePath() {
        Path root = tempDir;
        MavenProject project = createProject(root, "module-a", "com.example", "module-a");

        List<PathMatcher> matchers = ChangedFileClassifier.compileGlobMatchers(List.of("module-a"));
        assertTrue(ChangedFileClassifier.matchesIncludePaths(project, matchers, root));
    }

    @Test
    void matchesIncludePaths_noMatch() {
        Path root = tempDir;
        MavenProject project = createProject(root, "module-b", "com.example", "module-b");

        List<PathMatcher> matchers = ChangedFileClassifier.compileGlobMatchers(List.of("module-a"));
        assertFalse(ChangedFileClassifier.matchesIncludePaths(project, matchers, root));
    }

    @Test
    void matchesIncludePaths_matchesPomXmlPattern() {
        Path root = tempDir;
        MavenProject project = createProject(root, "module-a", "com.example", "module-a");

        List<PathMatcher> matchers = ChangedFileClassifier.compileGlobMatchers(List.of("module-a/**"));
        assertTrue(ChangedFileClassifier.matchesIncludePaths(project, matchers, root));
    }

    @Test
    void matchesDisableTrigger_matchesPattern() {
        ScalpelConfiguration config = configWith("scalpel.disableTriggers", "*.lock");

        // The lock file won't match *.lock, but let's test with a matching one
        Set<String> files2 = Set.of("deps.lock");
        assertTrue(classifier.matchesDisableTrigger(files2, config));
    }

    @Test
    void matchesDisableTrigger_noMatch() {
        ScalpelConfiguration config = configWith("scalpel.disableTriggers", "*.lock");
        Set<String> changedFiles = Set.of("src/main/java/Foo.java");

        assertFalse(classifier.matchesDisableTrigger(changedFiles, config));
    }

    @Test
    void filterExcludedPaths_excludesMatchingFiles() {
        ScalpelConfiguration config = configWith("scalpel.excludePaths", "docs/**");
        Set<String> changedFiles = new LinkedHashSet<>();
        changedFiles.add("docs/guide.md");
        changedFiles.add("src/main/java/Foo.java");

        Set<String> filtered = classifier.filterExcludedPaths(changedFiles, config);
        assertEquals(Set.of("src/main/java/Foo.java"), filtered);
    }

    @Test
    void filterExcludedPaths_noExclusions() {
        ScalpelConfiguration config = configWith("scalpel.mode", "trim");
        Set<String> changedFiles = Set.of("src/main/java/Foo.java");

        Set<String> filtered = classifier.filterExcludedPaths(changedFiles, config);
        assertEquals(changedFiles, filtered);
    }

    @Test
    void findFullBuildTrigger_findsMatch() {
        ScalpelConfiguration config = configWith("scalpel.fullBuildTriggers", ".github/workflows/**");
        Set<String> changedFiles = Set.of(".github/workflows/ci.yml", "src/main/java/Foo.java");

        String trigger = classifier.findFullBuildTrigger(changedFiles, config);
        assertEquals(".github/workflows/ci.yml", trigger);
    }

    @Test
    void findFullBuildTrigger_noMatch() {
        ScalpelConfiguration config = configWith("scalpel.fullBuildTriggers", ".github/workflows/**");
        Set<String> changedFiles = Set.of("src/main/java/Foo.java");

        String trigger = classifier.findFullBuildTrigger(changedFiles, config);
        assertNull(trigger);
    }

    @Test
    void relativePath_computesCorrectly() {
        Path root = tempDir;
        MavenProject project = createProject(root, "module-a", "com.example", "module-a");
        assertEquals("module-a", ChangedFileClassifier.relativePath(root, project));
    }

    private MavenProject createProject(Path root, String module, String groupId, String artifactId) {
        Path moduleDir = root.resolve(module);
        moduleDir.toFile().mkdirs();
        MavenProject project = mock(MavenProject.class);
        when(project.getGroupId()).thenReturn(groupId);
        when(project.getArtifactId()).thenReturn(artifactId);
        when(project.getBasedir()).thenReturn(moduleDir.toFile());
        when(project.getFile()).thenReturn(moduleDir.resolve("pom.xml").toFile());
        when(project.getProperties()).thenReturn(new Properties());
        return project;
    }

    private ScalpelConfiguration configWith(String key, String value) {
        Properties sysProps = new Properties();
        Properties userProps = new Properties();
        userProps.setProperty("scalpel.enabled", "true");
        userProps.setProperty("scalpel.mode", "trim");
        userProps.setProperty("scalpel.baseBranch", "main");
        userProps.setProperty(key, value);
        return ScalpelConfiguration.fromProperties(sysProps, userProps);
    }
}
