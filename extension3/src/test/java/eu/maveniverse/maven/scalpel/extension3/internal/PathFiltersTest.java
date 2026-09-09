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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PathFiltersTest {

    @TempDir
    Path tempDir;

    // ---- helper to build a config from property overrides ----

    private ScalpelConfiguration config(String... keyValuePairs) {
        Properties sysProps = new Properties();
        // baseBranch is required for a valid config in most tests
        sysProps.setProperty("scalpel.baseBranch", "origin/main");
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            sysProps.setProperty(keyValuePairs[i], keyValuePairs[i + 1]);
        }
        return ScalpelConfiguration.fromProperties(sysProps, new Properties());
    }

    private MavenProject mockProject(String artifactId, Path baseDir) {
        MavenProject project = mock(MavenProject.class);
        when(project.getGroupId()).thenReturn("com.example");
        when(project.getArtifactId()).thenReturn(artifactId);
        when(project.getBasedir()).thenReturn(baseDir.toFile());
        when(project.getFile()).thenReturn(baseDir.resolve("pom.xml").toFile());
        return project;
    }

    private Set<String> setOf(String... values) {
        Set<String> set = new LinkedHashSet<>();
        for (String v : values) {
            set.add(v);
        }
        return set;
    }

    // ---- filterExcludedPaths ----

    @Test
    void filterExcludedPaths_noExcludes_returnsOriginal() {
        PathFilters pf = new PathFilters(config());
        Set<String> files = setOf("src/Main.java", "README.md");
        Set<String> result = pf.filterExcludedPaths(files);
        assertEquals(files, result);
    }

    @Test
    void filterExcludedPaths_excludesMdFiles() {
        PathFilters pf = new PathFilters(config("scalpel.excludePaths", "*.md"));
        Set<String> files = setOf("src/Main.java", "README.md", "docs/guide.md");
        Set<String> result = pf.filterExcludedPaths(files);
        assertEquals(setOf("src/Main.java"), result);
    }

    @Test
    void filterExcludedPaths_excludesNestedXml() {
        // **/*.xml already contains a '/', so normalizeGlobPattern leaves it unchanged.
        // The glob **/*.xml matches config/settings.xml but NOT root-level pom.xml.
        PathFilters pf = new PathFilters(config("scalpel.excludePaths", "**/*.xml"));
        Set<String> files = setOf("src/Main.java", "config/settings.xml", "pom.xml");
        Set<String> result = pf.filterExcludedPaths(files);
        assertEquals(setOf("src/Main.java", "pom.xml"), result);
    }

    @Test
    void filterExcludedPaths_multiplePatterns() {
        PathFilters pf = new PathFilters(config("scalpel.excludePaths", "*.md,*.txt"));
        Set<String> files = setOf("src/Main.java", "README.md", "NOTES.txt");
        Set<String> result = pf.filterExcludedPaths(files);
        assertEquals(setOf("src/Main.java"), result);
    }

    @Test
    void filterExcludedPaths_directoryPattern() {
        PathFilters pf = new PathFilters(config("scalpel.excludePaths", ".github/**"));
        Set<String> files = setOf("src/Main.java", ".github/workflows/ci.yml");
        Set<String> result = pf.filterExcludedPaths(files);
        assertEquals(setOf("src/Main.java"), result);
    }

    // ---- matchesDisableTrigger ----

    @Test
    void matchesDisableTrigger_noTriggers_returnsFalse() {
        PathFilters pf = new PathFilters(config());
        assertFalse(pf.matchesDisableTrigger(setOf("src/Main.java")));
    }

    @Test
    void matchesDisableTrigger_matchingFile_returnsTrue() {
        PathFilters pf = new PathFilters(config("scalpel.disableTriggers", ".github/**"));
        assertTrue(pf.matchesDisableTrigger(setOf("src/Main.java", ".github/workflows/ci.yml")));
    }

    @Test
    void matchesDisableTrigger_noMatch_returnsFalse() {
        PathFilters pf = new PathFilters(config("scalpel.disableTriggers", ".github/**"));
        assertFalse(pf.matchesDisableTrigger(setOf("src/Main.java", "README.md")));
    }

    @Test
    void matchesDisableTrigger_bareGlobMatchesNested() {
        PathFilters pf = new PathFilters(config("scalpel.disableTriggers", "*.lock"));
        assertTrue(pf.matchesDisableTrigger(setOf("deps/package.lock")));
    }

    // ---- findFullBuildTrigger ----

    @Test
    void findFullBuildTrigger_defaultMvn_matchesMvnFiles() {
        PathFilters pf = new PathFilters(config());
        // Default fullBuildTriggers is .mvn/**
        assertEquals(".mvn/extensions.xml", pf.findFullBuildTrigger(setOf(".mvn/extensions.xml")));
    }

    @Test
    void findFullBuildTrigger_noMatch_returnsNull() {
        PathFilters pf = new PathFilters(config());
        assertNull(pf.findFullBuildTrigger(setOf("src/Main.java")));
    }

    @Test
    void findFullBuildTrigger_customTrigger() {
        PathFilters pf = new PathFilters(config("scalpel.fullBuildTriggers", "build-config/**"));
        assertEquals(
                "build-config/settings.xml",
                pf.findFullBuildTrigger(setOf("src/Main.java", "build-config/settings.xml")));
    }

    @Test
    void findFullBuildTrigger_emptyChangedFiles() {
        PathFilters pf = new PathFilters(config());
        assertNull(pf.findFullBuildTrigger(setOf()));
    }

    // ---- hasIncludeFilters ----

    @Test
    void hasIncludeFilters_noIncludes_returnsFalse() {
        PathFilters pf = new PathFilters(config());
        assertFalse(pf.hasIncludeFilters());
    }

    @Test
    void hasIncludeFilters_withIncludes_returnsTrue() {
        PathFilters pf = new PathFilters(config("scalpel.includePaths", "module-a/**"));
        assertTrue(pf.hasIncludeFilters());
    }

    // ---- matchesIncludePaths ----

    @Test
    void matchesIncludePaths_matchingModule() {
        PathFilters pf = new PathFilters(config("scalpel.includePaths", "module-a/**"));
        Path root = tempDir.resolve("project");
        root.toFile().mkdirs();
        Path moduleDir = root.resolve("module-a");
        moduleDir.toFile().mkdirs();
        MavenProject project = mockProject("module-a", moduleDir);
        assertTrue(pf.matchesIncludePaths(project, root));
    }

    @Test
    void matchesIncludePaths_nonMatchingModule() {
        PathFilters pf = new PathFilters(config("scalpel.includePaths", "module-a/**"));
        Path root = tempDir.resolve("project");
        root.toFile().mkdirs();
        Path moduleDir = root.resolve("module-b");
        moduleDir.toFile().mkdirs();
        MavenProject project = mockProject("module-b", moduleDir);
        assertFalse(pf.matchesIncludePaths(project, root));
    }

    @Test
    void matchesIncludePaths_directModuleName() {
        PathFilters pf = new PathFilters(config("scalpel.includePaths", "module-a"));
        Path root = tempDir.resolve("project");
        root.toFile().mkdirs();
        Path moduleDir = root.resolve("module-a");
        moduleDir.toFile().mkdirs();
        MavenProject project = mockProject("module-a", moduleDir);
        assertTrue(pf.matchesIncludePaths(project, root));
    }

    @Test
    void matchesIncludePaths_multiplePatterns() {
        PathFilters pf = new PathFilters(config("scalpel.includePaths", "module-a/**,module-b/**"));
        Path root = tempDir.resolve("project");
        root.toFile().mkdirs();
        Path moduleDirA = root.resolve("module-a");
        moduleDirA.toFile().mkdirs();
        Path moduleDirB = root.resolve("module-b");
        moduleDirB.toFile().mkdirs();
        Path moduleDirC = root.resolve("module-c");
        moduleDirC.toFile().mkdirs();

        assertTrue(pf.matchesIncludePaths(mockProject("module-a", moduleDirA), root));
        assertTrue(pf.matchesIncludePaths(mockProject("module-b", moduleDirB), root));
        assertFalse(pf.matchesIncludePaths(mockProject("module-c", moduleDirC), root));
    }

    // ---- filterBuildSet (trim mode: upstream survive) ----

    @Test
    void filterBuildSet_noIncludes_returnsSameList() {
        PathFilters pf = new PathFilters(config());
        Path root = tempDir.resolve("project");
        root.toFile().mkdirs();

        MavenProject p = mockProject("module-a", root.resolve("module-a"));
        root.resolve("module-a").toFile().mkdirs();
        List<MavenProject> buildSet = List.of(p);

        List<MavenProject> result = pf.filterBuildSet(buildSet, Set.of(), Set.of(), root);
        assertEquals(buildSet, result);
    }

    @Test
    void filterBuildSet_upstreamSurvivesOutsideScope() {
        PathFilters pf = new PathFilters(config("scalpel.includePaths", "module-a/**"));
        Path root = tempDir.resolve("project");
        root.toFile().mkdirs();

        Path moduleADir = root.resolve("module-a");
        moduleADir.toFile().mkdirs();
        Path moduleBDir = root.resolve("module-b");
        moduleBDir.toFile().mkdirs();
        Path moduleCDir = root.resolve("module-c");
        moduleCDir.toFile().mkdirs();

        MavenProject affected = mockProject("module-a", moduleADir);
        MavenProject upstream = mockProject("module-b", moduleBDir);
        MavenProject downstream = mockProject("module-c", moduleCDir);

        List<MavenProject> buildSet = new ArrayList<>(List.of(affected, upstream, downstream));
        Set<MavenProject> affectedSet = Set.of(affected);
        Set<MavenProject> upstreamOnly = Set.of(upstream);

        List<MavenProject> result = pf.filterBuildSet(buildSet, affectedSet, upstreamOnly, root);

        // affected: kept (in affectedSet)
        // upstream: kept (in upstreamOnly) — this is the trim-mode semantic
        // downstream: removed (not in affected or upstream, doesn't match includePaths)
        assertTrue(result.contains(affected), "affected module should be kept");
        assertTrue(result.contains(upstream), "upstream prerequisite should survive even outside scope");
        assertFalse(result.contains(downstream), "downstream outside scope should be removed");
    }

    @Test
    void filterBuildSet_downstreamInsideScopeKept() {
        PathFilters pf = new PathFilters(config("scalpel.includePaths", "module-a/**,module-c/**"));
        Path root = tempDir.resolve("project");
        root.toFile().mkdirs();

        Path moduleADir = root.resolve("module-a");
        moduleADir.toFile().mkdirs();
        Path moduleCDir = root.resolve("module-c");
        moduleCDir.toFile().mkdirs();

        MavenProject affected = mockProject("module-a", moduleADir);
        MavenProject downstream = mockProject("module-c", moduleCDir);

        List<MavenProject> buildSet = new ArrayList<>(List.of(affected, downstream));
        Set<MavenProject> affectedSet = Set.of(affected);

        List<MavenProject> result = pf.filterBuildSet(buildSet, affectedSet, Set.of(), root);

        assertTrue(result.contains(affected));
        assertTrue(result.contains(downstream), "downstream inside scope should be kept");
    }

    /**
     * Documents the intentional semantic difference between trim mode and skip-tests mode:
     * in trim mode ({@link PathFilters#filterBuildSet}), upstream prerequisites survive even
     * outside includePaths scope; in skip-tests mode ({@link PathFilters#matchesIncludePaths}),
     * the strict check has no upstream exception — the module's tests are skipped.
     */
    @Test
    void trimVsSkipTests_upstreamSemanticDifference() {
        PathFilters pf = new PathFilters(config("scalpel.includePaths", "module-a/**"));
        Path root = tempDir.resolve("project");
        root.toFile().mkdirs();

        Path moduleADir = root.resolve("module-a");
        moduleADir.toFile().mkdirs();
        Path moduleBDir = root.resolve("module-b");
        moduleBDir.toFile().mkdirs();

        MavenProject affected = mockProject("module-a", moduleADir);
        MavenProject upstream = mockProject("module-b", moduleBDir);

        // Trim mode: upstream survives the filter
        List<MavenProject> buildSet = new ArrayList<>(List.of(affected, upstream));
        List<MavenProject> trimResult = pf.filterBuildSet(buildSet, Set.of(affected), Set.of(upstream), root);
        assertTrue(trimResult.contains(upstream), "trim mode: upstream should survive the include filter");

        // Skip-tests mode: strict check — upstream does NOT match
        assertFalse(
                pf.matchesIncludePaths(upstream, root),
                "skip-tests mode: upstream outside scope should NOT match (tests will be skipped)");
    }

    // ---- normalizeGlobPattern (delegated to ScalpelLifecycleParticipant) ----

    @Test
    void normalizeGlobPattern_barePattern() {
        assertEquals("{*.md,**/*.md}", ScalpelLifecycleParticipant.normalizeGlobPattern("*.md"));
        assertEquals("{LICENSE,**/LICENSE}", ScalpelLifecycleParticipant.normalizeGlobPattern("LICENSE"));
    }

    @Test
    void normalizeGlobPattern_patternWithSlash() {
        assertEquals("docs/*.md", ScalpelLifecycleParticipant.normalizeGlobPattern("docs/*.md"));
        assertEquals("**/*.md", ScalpelLifecycleParticipant.normalizeGlobPattern("**/*.md"));
        assertEquals(".github/**", ScalpelLifecycleParticipant.normalizeGlobPattern(".github/**"));
    }

    // ---- empty pattern lists are no-ops ----

    @Test
    void emptyPatterns_areNoOps() {
        PathFilters pf = new PathFilters(config());
        Set<String> files = setOf("src/Main.java", "README.md");

        // filterExcludedPaths with no excludes returns original
        assertEquals(files, pf.filterExcludedPaths(files));

        // matchesDisableTrigger with no triggers returns false
        assertFalse(pf.matchesDisableTrigger(files));

        // findFullBuildTrigger with default .mvn/** doesn't match these
        assertNull(pf.findFullBuildTrigger(files));

        // hasIncludeFilters with no includes returns false
        assertFalse(pf.hasIncludeFilters());
    }
}
