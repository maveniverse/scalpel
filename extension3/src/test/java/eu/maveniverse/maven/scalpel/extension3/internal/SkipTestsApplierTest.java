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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import eu.maveniverse.maven.scalpel.core.ScalpelConfiguration;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkipTestsApplierTest {

    @TempDir
    Path tempDir;

    private ReactorTrimmer reactorTrimmer;
    private TransitiveImpactAnalyzer transitiveImpactAnalyzer;
    private SkipTestsApplier applier;

    @BeforeEach
    void setUp() {
        reactorTrimmer = mock(ReactorTrimmer.class);
        transitiveImpactAnalyzer = mock(TransitiveImpactAnalyzer.class);
        applier = new SkipTestsApplier(reactorTrimmer, transitiveImpactAnalyzer);
    }

    @Test
    void skipTestsOnAll_setsPropertyOnAllProjects() {
        MavenProject p1 = createProject("module-a");
        MavenProject p2 = createProject("module-b");

        applier.skipTestsOnAll(List.of(p1, p2));

        assertEquals("true", p1.getProperties().getProperty("maven.test.skip"));
        assertEquals("true", p2.getProperties().getProperty("maven.test.skip"));
    }

    @Test
    void matchesDownstreamExclusion_matchesByArtifactId() {
        MavenProject project = createProject("module-a");
        assertTrue(applier.matchesDownstreamExclusion(project, List.of("module-a")));
    }

    @Test
    void matchesDownstreamExclusion_matchesByGA() {
        MavenProject project = createProject("module-a");
        assertTrue(applier.matchesDownstreamExclusion(project, List.of("com.example:module-a")));
    }

    @Test
    void matchesDownstreamExclusion_noMatch() {
        MavenProject project = createProject("module-a");
        assertFalse(applier.matchesDownstreamExclusion(project, List.of("module-b")));
    }

    @Test
    void matchesDownstreamExclusion_emptyPatterns() {
        MavenProject project = createProject("module-a");
        assertFalse(applier.matchesDownstreamExclusion(project, List.of()));
    }

    @Test
    void applyPerCategoryArgs_setsUpstreamArgs() {
        MavenProject upstream = createProject("upstream");
        MavenProject downstream = createProject("downstream");
        MavenProject downstreamTest = createProject("downstream-test");

        TrimResult trimResult = new TrimResult(
                List.of(upstream, downstream, downstreamTest),
                Set.of(),
                Set.of(upstream),
                Set.of(downstream),
                Set.of(downstreamTest));

        ScalpelConfiguration config = configWith("scalpel.upstreamArgs", "maven.javadoc.skip=true");
        applier.applyPerCategoryArgs(trimResult, config);

        assertEquals("true", upstream.getProperties().getProperty("maven.javadoc.skip"));
        assertFalse(downstream.getProperties().containsKey("maven.javadoc.skip"));
    }

    @Test
    void applyPerCategoryArgs_setsDownstreamArgs() {
        MavenProject upstream = createProject("upstream");
        MavenProject downstream = createProject("downstream");
        MavenProject downstreamTest = createProject("downstream-test");

        TrimResult trimResult = new TrimResult(
                List.of(upstream, downstream, downstreamTest),
                Set.of(),
                Set.of(upstream),
                Set.of(downstream),
                Set.of(downstreamTest));

        ScalpelConfiguration config = configWith("scalpel.downstreamArgs", "skipITs=true");
        applier.applyPerCategoryArgs(trimResult, config);

        assertFalse(upstream.getProperties().containsKey("skipITs"));
        assertEquals("true", downstream.getProperties().getProperty("skipITs"));
        assertEquals("true", downstreamTest.getProperties().getProperty("skipITs"));
    }

    @Test
    void softenTestJarProducers_softensWhenConsumerExists() {
        MavenProject consumer = createProject("consumer");
        MavenProject producer = createProject("producer");
        producer.getProperties().setProperty("maven.test.skip", "true");

        List<MavenProject> testProjects = new ArrayList<>(List.of(consumer));
        List<MavenProject> skippedProjects = new ArrayList<>(List.of(producer));

        when(reactorTrimmer.hasTestJarDependency(consumer, producer)).thenReturn(true);

        Set<MavenProject> softened = applier.softenTestJarProducers(testProjects, skippedProjects);

        assertEquals(1, softened.size());
        assertTrue(softened.contains(producer));
        assertFalse(producer.getProperties().containsKey("maven.test.skip"));
        assertEquals("true", producer.getProperties().getProperty("skipTests"));
        assertTrue(skippedProjects.isEmpty()); // Producer removed from skipped list
    }

    @Test
    void softenTestJarProducers_noSofteningWhenNoConsumer() {
        MavenProject consumer = createProject("consumer");
        MavenProject producer = createProject("producer");
        producer.getProperties().setProperty("maven.test.skip", "true");

        List<MavenProject> testProjects = new ArrayList<>(List.of(consumer));
        List<MavenProject> skippedProjects = new ArrayList<>(List.of(producer));

        when(reactorTrimmer.hasTestJarDependency(consumer, producer)).thenReturn(false);

        Set<MavenProject> softened = applier.softenTestJarProducers(testProjects, skippedProjects);

        assertTrue(softened.isEmpty());
        assertEquals("true", producer.getProperties().getProperty("maven.test.skip"));
    }

    @Test
    void shouldSkipTestsForExcludedDownstream_returnsFalse_whenNoPatternsConfigured() {
        MavenProject project = createProject("module-a");
        ScalpelConfiguration config = configWith("scalpel.mode", "skip-tests");
        TrimResult trimResult = new TrimResult(List.of(project), Set.of(), Set.of(), Set.of(project));

        assertFalse(applier.shouldSkipTestsForExcludedDownstream(
                project,
                trimResult,
                config,
                new TransitiveImpactAnalyzer.EffectiveModels(java.util.Map.of(), java.util.Map.of()),
                new TransitiveImpactAnalyzer.ResolutionContext(
                        tempDir,
                        mock(org.apache.maven.execution.MavenSession.class),
                        new java.util.LinkedHashMap<>(),
                        new java.util.LinkedHashMap<>(),
                        new eu.maveniverse.maven.scalpel.core.Timings())));
    }

    @Test
    void shouldSkipTestsForExcludedDownstream_returnsFalse_whenNotDownstream() {
        MavenProject project = createProject("module-a");
        ScalpelConfiguration config = configWith("scalpel.skipTestsForDownstreamModules", "module-a");
        // project is NOT in downstream sets
        TrimResult trimResult = new TrimResult(List.of(project), Set.of(project), Set.of(), Set.of());

        assertFalse(applier.shouldSkipTestsForExcludedDownstream(
                project,
                trimResult,
                config,
                new TransitiveImpactAnalyzer.EffectiveModels(java.util.Map.of(), java.util.Map.of()),
                new TransitiveImpactAnalyzer.ResolutionContext(
                        tempDir,
                        mock(org.apache.maven.execution.MavenSession.class),
                        new java.util.LinkedHashMap<>(),
                        new java.util.LinkedHashMap<>(),
                        new eu.maveniverse.maven.scalpel.core.Timings())));
    }

    private MavenProject createProject(String artifactId) {
        MavenProject project = mock(MavenProject.class);
        when(project.getGroupId()).thenReturn("com.example");
        when(project.getArtifactId()).thenReturn(artifactId);
        when(project.getBasedir()).thenReturn(tempDir.resolve(artifactId).toFile());
        when(project.getFile())
                .thenReturn(tempDir.resolve(artifactId + "/pom.xml").toFile());
        Properties props = new Properties();
        when(project.getProperties()).thenReturn(props);
        return project;
    }

    private ScalpelConfiguration configWith(String key, String value) {
        Properties sysProps = new Properties();
        Properties userProps = new Properties();
        userProps.setProperty("scalpel.enabled", "true");
        userProps.setProperty("scalpel.mode", "skip-tests");
        userProps.setProperty("scalpel.baseBranch", "main");
        userProps.setProperty(key, value);
        return ScalpelConfiguration.fromProperties(sysProps, userProps);
    }
}
