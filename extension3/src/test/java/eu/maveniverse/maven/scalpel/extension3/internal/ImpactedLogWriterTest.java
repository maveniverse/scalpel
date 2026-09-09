/*
 * Copyright (c) Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.scalpel.extension3.internal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import eu.maveniverse.maven.scalpel.core.ScalpelConfiguration;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImpactedLogWriterTest {

    @TempDir
    Path tempDir;

    private ImpactedLogWriter writer;

    @BeforeEach
    void setUp() {
        writer = new ImpactedLogWriter(new ReportAssembler());
    }

    @Test
    void isSafeImpactedLogPath_acceptsSafePaths() {
        assertTrue(ImpactedLogWriter.isSafeImpactedLogPath("module-a"));
        assertTrue(ImpactedLogWriter.isSafeImpactedLogPath("module-a/sub-module"));
        assertTrue(ImpactedLogWriter.isSafeImpactedLogPath("Module_1.v2/dir"));
        assertTrue(ImpactedLogWriter.isSafeImpactedLogPath("m"));
        assertTrue(ImpactedLogWriter.isSafeImpactedLogPath("a-b_c.d/e"));
        assertTrue(ImpactedLogWriter.isSafeImpactedLogPath("."));
    }

    @Test
    void isSafeImpactedLogPath_rejectsUnsafePaths() {
        assertFalse(ImpactedLogWriter.isSafeImpactedLogPath(null));
        assertFalse(ImpactedLogWriter.isSafeImpactedLogPath(""));
        assertFalse(ImpactedLogWriter.isSafeImpactedLogPath("-module"));
        assertFalse(ImpactedLogWriter.isSafeImpactedLogPath("module;rm"));
        assertFalse(ImpactedLogWriter.isSafeImpactedLogPath("module$(id)"));
        assertFalse(ImpactedLogWriter.isSafeImpactedLogPath("module rm"));
        assertFalse(ImpactedLogWriter.isSafeImpactedLogPath("module`id`"));
        assertFalse(ImpactedLogWriter.isSafeImpactedLogPath("mo'dule"));
        assertFalse(ImpactedLogWriter.isSafeImpactedLogPath("mo\"dule"));
        assertFalse(ImpactedLogWriter.isSafeImpactedLogPath("mo\\dule"));
        assertFalse(ImpactedLogWriter.isSafeImpactedLogPath("module*"));
        assertFalse(ImpactedLogWriter.isSafeImpactedLogPath("mo?dule"));
        assertFalse(ImpactedLogWriter.isSafeImpactedLogPath("mo[du]le"));
        assertFalse(ImpactedLogWriter.isSafeImpactedLogPath("module|rm"));
        assertFalse(ImpactedLogWriter.isSafeImpactedLogPath("module&rm"));
        assertFalse(ImpactedLogWriter.isSafeImpactedLogPath("evil\nforge"));
        assertFalse(ImpactedLogWriter.isSafeImpactedLogPath("evil\rforge"));
        assertFalse(ImpactedLogWriter.isSafeImpactedLogPath("evil\tforge"));
        assertFalse(ImpactedLogWriter.isSafeImpactedLogPath("evil\0forge"));
        assertFalse(ImpactedLogWriter.isSafeImpactedLogPath("evil\u007Fforge"));
    }

    @Test
    void escapeControlChars_escapesControlCharacters() {
        assertEquals("evil\\u000aforge", ImpactedLogWriter.escapeControlChars("evil\nforge"));
        assertEquals("evil\\u000dforge", ImpactedLogWriter.escapeControlChars("evil\rforge"));
        assertEquals("evil\\u0009forge", ImpactedLogWriter.escapeControlChars("evil\tforge"));
        assertEquals("evil\\u0000forge", ImpactedLogWriter.escapeControlChars("evil\0forge"));
    }

    @Test
    void escapeControlChars_leavesNormalCharsAlone() {
        assertEquals("module-a", ImpactedLogWriter.escapeControlChars("module-a"));
        assertEquals("path/to/module", ImpactedLogWriter.escapeControlChars("path/to/module"));
    }

    @Test
    void writeImpactedLog_writesModulePaths() throws Exception {
        Path root = tempDir.resolve("project");
        Files.createDirectories(root);

        MavenProject p1 = createProject(root, "module-a");
        MavenProject p2 = createProject(root, "module-b");

        Set<MavenProject> affected = new LinkedHashSet<>();
        affected.add(p1);
        affected.add(p2);

        ScalpelConfiguration config = configWithImpactedLog("target/impacted.log");

        writer.writeImpactedLog(config, root, affected);

        Path logPath = root.resolve("target/impacted.log");
        assertTrue(Files.exists(logPath));
        List<String> lines = Files.readAllLines(logPath, StandardCharsets.UTF_8);
        assertEquals(2, lines.size());
        assertEquals("module-a", lines.get(0));
        assertEquals("module-b", lines.get(1));
    }

    @Test
    void writeImpactedLog_rootModuleRepresentedAsDot() throws Exception {
        Path root = tempDir.resolve("project");
        Files.createDirectories(root);

        // Root module: basedir == reactorRoot
        MavenProject rootProject = mock(MavenProject.class);
        when(rootProject.getGroupId()).thenReturn("com.example");
        when(rootProject.getArtifactId()).thenReturn("parent");
        when(rootProject.getBasedir()).thenReturn(root.toFile());
        when(rootProject.getFile()).thenReturn(root.resolve("pom.xml").toFile());
        when(rootProject.getProperties()).thenReturn(new Properties());

        Set<MavenProject> affected = new LinkedHashSet<>();
        affected.add(rootProject);

        ScalpelConfiguration config = configWithImpactedLog("target/impacted.log");

        writer.writeImpactedLog(config, root, affected);

        Path logPath = root.resolve("target/impacted.log");
        List<String> lines = Files.readAllLines(logPath, StandardCharsets.UTF_8);
        assertEquals(1, lines.size());
        assertEquals(".", lines.get(0));
    }

    @Test
    void writeImpactedLog_skipsWhenNull() throws Exception {
        ScalpelConfiguration config = configWith("scalpel.mode", "trim");

        // null impactedLog means no-op — verify no exception is thrown
        assertDoesNotThrow(() -> writer.writeImpactedLog(config, tempDir, Set.of()));
    }

    private MavenProject createProject(Path root, String module) {
        Path moduleDir = root.resolve(module);
        moduleDir.toFile().mkdirs();
        MavenProject project = mock(MavenProject.class);
        when(project.getGroupId()).thenReturn("com.example");
        when(project.getArtifactId()).thenReturn(module);
        when(project.getBasedir()).thenReturn(moduleDir.toFile());
        when(project.getFile()).thenReturn(moduleDir.resolve("pom.xml").toFile());
        when(project.getProperties()).thenReturn(new Properties());
        return project;
    }

    private ScalpelConfiguration configWithImpactedLog(String logPath) {
        Properties sysProps = new Properties();
        Properties userProps = new Properties();
        userProps.setProperty("scalpel.enabled", "true");
        userProps.setProperty("scalpel.mode", "trim");
        userProps.setProperty("scalpel.baseBranch", "main");
        userProps.setProperty("scalpel.impactedLog", logPath);
        return ScalpelConfiguration.fromProperties(sysProps, userProps);
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
