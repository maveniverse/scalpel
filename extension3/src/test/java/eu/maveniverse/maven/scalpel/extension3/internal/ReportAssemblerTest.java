/*
 * Copyright (c) Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.scalpel.extension3.internal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.maveniverse.maven.scalpel.core.ScalpelConfiguration;
import java.util.Properties;
import org.apache.maven.MavenExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the status-path write semantics of {@link ReportAssembler} (#188): a failed
 * status report write honours failSafe instead of always warn-and-continue, so
 * failSafe=false means "fail the build when Scalpel cannot do its job" on this path too.
 */
class ReportAssemblerTest {

    @TempDir
    java.nio.file.Path tempDir;

    private static ScalpelConfiguration configWith(boolean failSafe, String reportFile) {
        java.util.Properties sys = new Properties();
        sys.setProperty("scalpel.failSafe", String.valueOf(failSafe));
        sys.setProperty("scalpel.reportFile", reportFile);
        return ScalpelConfiguration.fromProperties(sys, new Properties());
    }

    @Test
    void writeStatusReport_failSafeFalse_failsTheBuildOnWriteFailure() {
        ReportAssembler assembler = new ReportAssembler();
        // An absolute path outside the reactor trips resolveContained (#97): same
        // IOException any unwritable destination produces, deterministic on every OS.
        String outside = tempDir.resolve("outside").resolve("report.json").toString();

        MavenExecutionException e = assertThrows(
                MavenExecutionException.class,
                () -> assembler.writeStatusReport(
                        configWith(false, outside), tempDir, "skipped", "no changes detected"),
                "with failSafe=false a failed status write must fail the build");
        assertTrue(
                e.getMessage() != null && e.getMessage().contains("scalpel.reportFile"),
                "the failure should carry the write-failure detail, got: " + e.getMessage());
    }

    @Test
    void writeStatusReport_failSafeTrue_warnsAndContinues() {
        ReportAssembler assembler = new ReportAssembler();
        String outside = tempDir.resolve("outside2").resolve("report.json").toString();

        assertDoesNotThrow(() ->
                assembler.writeStatusReport(configWith(true, outside), tempDir, "skipped", "no changes detected"));
        assertTrue(!java.nio.file.Files.exists(java.nio.file.Path.of(outside)), "nothing written outside the reactor");
    }

    @Test
    void writeStatusReport_statusWriteFails_failSafeFalse_failsTheBuild() throws java.io.IOException {
        // The reportFile itself points at an existing DIRECTORY: writeToFile cannot
        // overwrite it, exercising the plain write failure (not containment).
        ReportAssembler assembler = new ReportAssembler();
        java.nio.file.Path dirAsFile = tempDir.resolve("dir-report");
        java.nio.file.Files.createDirectories(dirAsFile);

        MavenExecutionException e = assertThrows(
                MavenExecutionException.class,
                () -> assembler.writeStatusReport(
                        configWith(false, dirAsFile.toString()), tempDir, "skipped", "no changes detected"));
        assertTrue(
                e.getMessage() != null && e.getMessage().contains("scalpel.reportFile"),
                "the failure should name the property, got: " + e.getMessage());
    }
}
