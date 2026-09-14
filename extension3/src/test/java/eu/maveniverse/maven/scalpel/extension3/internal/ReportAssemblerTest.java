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
                e.getCause() != null && e.getCause().getMessage().contains("scalpel.reportFile"),
                "the cause should carry the property name, got: " + e.getCause());
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
        // The reportFile is a RELATIVE path pointing at an existing directory inside the
        // reactor, so resolveContained accepts it and Files.write fails on a directory:
        // the plain write-failure branch, distinct from the containment branch of the
        // first test (which used an absolute path, rejected before any write).
        ReportAssembler assembler = new ReportAssembler();
        java.nio.file.Files.createDirectories(tempDir.resolve("dir-report"));

        MavenExecutionException e = assertThrows(
                MavenExecutionException.class,
                () -> assembler.writeStatusReport(
                        configWith(false, "dir-report"), tempDir, "skipped", "no changes detected"));
        assertTrue(
                e.getCause() instanceof java.io.IOException,
                "the cause must be the underlying write IOException, got: " + e.getCause());
    }
}
