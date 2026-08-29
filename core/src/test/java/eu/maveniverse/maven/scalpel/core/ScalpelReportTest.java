/*
 * Copyright (c) Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.scalpel.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScalpelReportTest {

    @TempDir
    Path tempDir;

    @Test
    void toJson_directlyAffectedModule() {
        ScalpelReport report = ScalpelReport.builder()
                .baseBranch("origin/main")
                .fullBuildTriggered(false)
                .changedFiles(Set.of("module-a/src/Foo.java"))
                .addAffectedModule(new ScalpelReport.AffectedModule(
                        "com.example", "module-a", "module-a", List.of(ScalpelReport.REASON_SOURCE_CHANGE)))
                .build();

        String json = report.toJson();
        assertTrue(json.contains("\"SOURCE_CHANGE\""));
        assertTrue(json.contains("\"module-a\""));
        assertFalse(json.contains("\"TRANSITIVE_DEPENDENCY\""));
    }

    @Test
    void toJson_skippedModuleListedWithReason() {
        ScalpelReport report = ScalpelReport.builder()
                .baseBranch("origin/main")
                .fullBuildTriggered(false)
                .changedFiles(Set.of("module-a/src/Foo.java"))
                .addAffectedModule(new ScalpelReport.AffectedModule(
                        "com.example", "module-a", "module-a", List.of(ScalpelReport.REASON_SOURCE_CHANGE)))
                .addSkippedModule(new ScalpelReport.SkippedModule(
                        "com.example", "module-b", "module-b", ScalpelReport.SKIP_REASON_NOT_AFFECTED))
                .build();

        String json = report.toJson();
        assertTrue(json.contains("\"skippedModules\""), "skippedModules array must be present");
        assertTrue(json.contains("\"NOT_AFFECTED\""), "skip reason must be serialized");
        assertTrue(json.contains("\"module-b\""), "skipped module artifactId must be serialized");
    }

    @Test
    void toJson_skippedModulesOmittedWhenNothingSkipped() {
        ScalpelReport report = ScalpelReport.builder()
                .baseBranch("origin/main")
                .fullBuildTriggered(false)
                .changedFiles(Set.of("module-a/src/Foo.java"))
                .addAffectedModule(new ScalpelReport.AffectedModule(
                        "com.example", "module-a", "module-a", List.of(ScalpelReport.REASON_SOURCE_CHANGE)))
                .build();

        String json = report.toJson();
        assertFalse(json.contains("\"skippedModules\""), "skippedModules must be omitted when nothing is skipped");
    }

    @Test
    void toJson_transitivelyAffectedModule() {
        ScalpelReport report = ScalpelReport.builder()
                .baseBranch("origin/main")
                .fullBuildTriggered(false)
                .changedFiles(Set.of("pom.xml"))
                .changedManagedDependencies(Set.of("org.apache.kafka:kafka-clients"))
                .addAffectedModule(new ScalpelReport.AffectedModule(
                        "com.example", "module-a", "module-a", List.of(ScalpelReport.REASON_POM_CHANGE)))
                .addAffectedModule(new ScalpelReport.AffectedModule(
                        "com.example", "module-b", "module-b", List.of(ScalpelReport.REASON_TRANSITIVE_DEPENDENCY)))
                .build();

        String json = report.toJson();
        assertTrue(json.contains("\"POM_CHANGE\""));
        assertTrue(json.contains("\"TRANSITIVE_DEPENDENCY\""));
        assertTrue(json.contains("\"module-a\""));
        assertTrue(json.contains("\"module-b\""));
        assertTrue(json.contains("\"org.apache.kafka:kafka-clients\""));
    }

    @Test
    void toJson_managedPluginAffectedModule() {
        ScalpelReport report = ScalpelReport.builder()
                .baseBranch("origin/main")
                .fullBuildTriggered(false)
                .changedFiles(Set.of("pom.xml"))
                .changedManagedPlugins(Set.of("org.apache.maven.plugins:maven-compiler-plugin"))
                .addAffectedModule(new ScalpelReport.AffectedModule(
                        "com.example", "module-a", "module-a", List.of(ScalpelReport.REASON_MANAGED_PLUGIN)))
                .build();

        String json = report.toJson();
        assertTrue(json.contains("\"MANAGED_PLUGIN\""));
        assertTrue(json.contains("\"org.apache.maven.plugins:maven-compiler-plugin\""));
    }

    @Test
    void toJson_multipleReasons() {
        ScalpelReport report = ScalpelReport.builder()
                .baseBranch("origin/main")
                .fullBuildTriggered(false)
                .changedFiles(Set.of("pom.xml"))
                .addAffectedModule(new ScalpelReport.AffectedModule(
                        "com.example",
                        "module-a",
                        "module-a",
                        List.of(ScalpelReport.REASON_MANAGED_PLUGIN, ScalpelReport.REASON_TRANSITIVE_DEPENDENCY)))
                .build();

        String json = report.toJson();
        assertTrue(json.contains("\"MANAGED_PLUGIN\""));
        assertTrue(json.contains("\"TRANSITIVE_DEPENDENCY\""));
    }

    @Test
    void toJson_fullBuildTriggered() {
        ScalpelReport report = ScalpelReport.builder()
                .baseBranch("origin/main")
                .fullBuildTriggered(true)
                .triggerFile(".mvn/extensions.xml")
                .changedFiles(Set.of(".mvn/extensions.xml"))
                .build();

        String json = report.toJson();
        assertTrue(json.contains("\"fullBuildTriggered\": true"));
        assertTrue(json.contains("\".mvn/extensions.xml\""));
        assertTrue(json.contains("\"affectedModules\": []"));
    }

    @Test
    void toJson_emptyReport() {
        ScalpelReport report = ScalpelReport.builder()
                .baseBranch("origin/main")
                .fullBuildTriggered(false)
                .build();

        String json = report.toJson();
        assertTrue(json.contains("\"version\": \"2\""));
        assertTrue(json.contains("\"fullBuildTriggered\": false"));
        assertTrue(json.contains("\"affectedModules\": []"));
        assertTrue(json.contains("\"changedFiles\": []"));
    }

    @Test
    void writeToFile_createsFileWithCorrectContent() throws IOException {
        ScalpelReport report = ScalpelReport.builder()
                .baseBranch("origin/main")
                .fullBuildTriggered(false)
                .changedFiles(Set.of("pom.xml"))
                .changedManagedDependencies(Set.of("commons-lang:commons-lang"))
                .addAffectedModule(new ScalpelReport.AffectedModule(
                        "com.example", "module-b", "module-b", List.of(ScalpelReport.REASON_TRANSITIVE_DEPENDENCY)))
                .build();

        report.writeToFile(tempDir, "target/scalpel-report.json");

        Path reportFile = tempDir.resolve("target/scalpel-report.json");
        assertTrue(Files.exists(reportFile));
        String content = new String(Files.readAllBytes(reportFile), StandardCharsets.UTF_8);
        assertEquals(report.toJson(), content);
    }

    @Test
    void toJson_sourceSetIncludedWhenPresent() {
        ScalpelReport report = ScalpelReport.builder()
                .baseBranch("origin/main")
                .fullBuildTriggered(false)
                .changedFiles(Set.of("module-a/src/test/java/FooTest.java"))
                .addAffectedModule(new ScalpelReport.AffectedModule(
                        "com.example",
                        "module-a",
                        "module-a",
                        List.of(ScalpelReport.REASON_TEST_CHANGE),
                        ScalpelReport.CATEGORY_DIRECT,
                        "test"))
                .build();

        String json = report.toJson();
        assertTrue(json.contains("\"sourceSet\": \"test\""));
        assertTrue(json.contains("\"TEST_CHANGE\""));
        assertTrue(json.contains("\"category\": \"DIRECT\""));
    }

    @Test
    void toJson_sourceSetOmittedWhenNull() {
        ScalpelReport report = ScalpelReport.builder()
                .baseBranch("origin/main")
                .fullBuildTriggered(false)
                .changedFiles(Set.of("module-a/src/main/java/Foo.java"))
                .addAffectedModule(new ScalpelReport.AffectedModule(
                        "com.example",
                        "module-a",
                        "module-a",
                        List.of(ScalpelReport.REASON_DOWNSTREAM_DEPENDENT),
                        ScalpelReport.CATEGORY_DOWNSTREAM))
                .build();

        String json = report.toJson();
        assertFalse(json.contains("\"sourceSet\""));
    }

    @Test
    void toJson_sourceSetMainForMainChanges() {
        ScalpelReport report = ScalpelReport.builder()
                .baseBranch("origin/main")
                .fullBuildTriggered(false)
                .changedFiles(Set.of("module-a/src/main/java/Foo.java"))
                .addAffectedModule(new ScalpelReport.AffectedModule(
                        "com.example",
                        "module-a",
                        "module-a",
                        List.of(ScalpelReport.REASON_SOURCE_CHANGE),
                        ScalpelReport.CATEGORY_DIRECT,
                        "main"))
                .build();

        String json = report.toJson();
        assertTrue(json.contains("\"sourceSet\": \"main\""));
        assertTrue(json.contains("\"SOURCE_CHANGE\""));
    }

    @Test
    void toJson_testsSkippedReasonIncludedWhenSet() {
        ScalpelReport report = ScalpelReport.builder()
                .baseBranch("origin/main")
                .fullBuildTriggered(false)
                .changedFiles(Set.of("module-a/src/Foo.java"))
                .addAffectedModule(new ScalpelReport.AffectedModule(
                        "com.example",
                        "module-b",
                        "module-b",
                        List.of(ScalpelReport.REASON_DOWNSTREAM_DEPENDENT),
                        ScalpelReport.CATEGORY_DOWNSTREAM,
                        null,
                        ScalpelReport.REASON_EXCLUDED_DOWNSTREAM))
                .build();

        String json = report.toJson();
        assertTrue(json.contains("\"testsSkippedReason\": \"EXCLUDED_DOWNSTREAM\""));
        assertTrue(json.contains("\"category\": \"DOWNSTREAM\""));
    }

    @Test
    void toJson_testsSkippedReasonOmittedWhenNull() {
        ScalpelReport report = ScalpelReport.builder()
                .baseBranch("origin/main")
                .fullBuildTriggered(false)
                .changedFiles(Set.of("module-a/src/Foo.java"))
                .addAffectedModule(new ScalpelReport.AffectedModule(
                        "com.example",
                        "module-b",
                        "module-b",
                        List.of(ScalpelReport.REASON_DOWNSTREAM_DEPENDENT),
                        ScalpelReport.CATEGORY_DOWNSTREAM))
                .build();

        String json = report.toJson();
        assertFalse(json.contains("testsSkippedReason"));
        assertTrue(json.contains("\"category\": \"DOWNSTREAM\""));
    }

    @Test
    void toJson_testsSkippedReasonWithoutCategory() {
        ScalpelReport report = ScalpelReport.builder()
                .baseBranch("origin/main")
                .fullBuildTriggered(false)
                .changedFiles(Set.of("module-a/src/Foo.java"))
                .addAffectedModule(new ScalpelReport.AffectedModule(
                        "com.example",
                        "module-b",
                        "module-b",
                        List.of(ScalpelReport.REASON_DOWNSTREAM_DEPENDENT),
                        null,
                        null,
                        ScalpelReport.REASON_EXCLUDED_DOWNSTREAM))
                .build();

        String json = report.toJson();
        assertTrue(json.contains("\"testsSkippedReason\": \"EXCLUDED_DOWNSTREAM\""));
        assertFalse(json.contains("\"category\""));
    }

    @Test
    void affectedModule_gettersReturnCorrectValues() {
        ScalpelReport.AffectedModule module = new ScalpelReport.AffectedModule(
                "com.example",
                "module-a",
                "module-a",
                List.of(ScalpelReport.REASON_DOWNSTREAM_DEPENDENT),
                ScalpelReport.CATEGORY_DOWNSTREAM,
                null,
                ScalpelReport.REASON_EXCLUDED_DOWNSTREAM);

        assertEquals("com.example", module.getGroupId());
        assertEquals("module-a", module.getArtifactId());
        assertEquals("module-a", module.getPath());
        assertEquals(List.of(ScalpelReport.REASON_DOWNSTREAM_DEPENDENT), module.getReasons());
        assertEquals(ScalpelReport.CATEGORY_DOWNSTREAM, module.getCategory());
        assertEquals(ScalpelReport.REASON_EXCLUDED_DOWNSTREAM, module.getTestsSkippedReason());
    }

    @Test
    void toJson_bothSourceSetAndTestsSkippedReason() {
        ScalpelReport report = ScalpelReport.builder()
                .baseBranch("origin/main")
                .fullBuildTriggered(false)
                .changedFiles(Set.of("module-a/src/Foo.java"))
                .addAffectedModule(new ScalpelReport.AffectedModule(
                        "com.example",
                        "module-b",
                        "module-b",
                        List.of(ScalpelReport.REASON_DOWNSTREAM_DEPENDENT),
                        ScalpelReport.CATEGORY_DOWNSTREAM,
                        "main",
                        ScalpelReport.REASON_EXCLUDED_DOWNSTREAM))
                .build();

        String json = report.toJson();
        assertTrue(json.contains("\"sourceSet\": \"main\""));
        assertTrue(json.contains("\"testsSkippedReason\": \"EXCLUDED_DOWNSTREAM\""));
        assertTrue(json.contains("\"category\": \"DOWNSTREAM\""));
    }

    // ---------------------------------------------------------------
    // ModuleBuilder tests
    // ---------------------------------------------------------------

    @Test
    void moduleBuilder_minimalBuild() {
        ScalpelReport.AffectedModule module = ScalpelReport.AffectedModule.moduleBuilder(
                        "com.example", "module-a", "module-a", List.of("SOURCE_CHANGE"))
                .build();

        assertEquals("com.example", module.getGroupId());
        assertEquals("module-a", module.getArtifactId());
        assertEquals("module-a", module.getPath());
        assertEquals(List.of("SOURCE_CHANGE"), module.getReasons());
        assertNull(module.getCategory());
        assertNull(module.getSourceSet());
        assertNull(module.getTestsSkippedReason());
    }

    @Test
    void moduleBuilder_allFieldsSet() {
        ScalpelReport.AffectedModule module = ScalpelReport.AffectedModule.moduleBuilder(
                        "com.example", "module-b", "path/module-b", List.of(ScalpelReport.REASON_DOWNSTREAM_DEPENDENT))
                .category(ScalpelReport.CATEGORY_DOWNSTREAM)
                .sourceSet("main")
                .testsSkippedReason(ScalpelReport.REASON_EXCLUDED_DOWNSTREAM)
                .build();

        assertEquals("com.example", module.getGroupId());
        assertEquals("module-b", module.getArtifactId());
        assertEquals("path/module-b", module.getPath());
        assertEquals(ScalpelReport.CATEGORY_DOWNSTREAM, module.getCategory());
        assertEquals("main", module.getSourceSet());
        assertEquals(ScalpelReport.REASON_EXCLUDED_DOWNSTREAM, module.getTestsSkippedReason());
    }

    @Test
    void moduleBuilder_producesCorrectJson() {
        ScalpelReport report = ScalpelReport.builder()
                .baseBranch("origin/main")
                .fullBuildTriggered(false)
                .changedFiles(Set.of("module-a/src/Foo.java"))
                .addAffectedModule(ScalpelReport.AffectedModule.moduleBuilder(
                                "com.example", "module-a", "module-a", List.of(ScalpelReport.REASON_SOURCE_CHANGE))
                        .category(ScalpelReport.CATEGORY_DIRECT)
                        .sourceSet("main")
                        .build())
                .build();

        String json = report.toJson();
        assertTrue(json.contains("\"SOURCE_CHANGE\""));
        assertTrue(json.contains("\"category\": \"DIRECT\""));
        assertTrue(json.contains("\"sourceSet\": \"main\""));
    }

    @Test
    void moduleBuilder_categoryOnly() {
        ScalpelReport.AffectedModule module = ScalpelReport.AffectedModule.moduleBuilder(
                        "com.example", "module-a", "module-a", List.of(ScalpelReport.REASON_TRANSITIVE_DEPENDENCY))
                .category(ScalpelReport.CATEGORY_UPSTREAM)
                .build();

        assertEquals(ScalpelReport.CATEGORY_UPSTREAM, module.getCategory());
        assertNull(module.getSourceSet());
        assertNull(module.getTestsSkippedReason());
    }

    @Test
    void toJson_escapesSpecialCharacters() {
        ScalpelReport report = ScalpelReport.builder()
                .baseBranch("origin/main")
                .fullBuildTriggered(false)
                .changedFiles(Set.of("path/with\"quotes.java"))
                .build();

        String json = report.toJson();
        assertTrue(json.contains("path/with\\\"quotes.java"));
    }

    // ---------------------------------------------------------------
    // Evidence (explain mode, #93)
    // ---------------------------------------------------------------

    @Test
    void toJson_evidenceIncludedWhenPresent() {
        ScalpelReport report = ScalpelReport.builder()
                .baseBranch("origin/main")
                .fullBuildTriggered(false)
                .changedFiles(Set.of("module-a/src/main/java/Foo.java"))
                .addAffectedModule(ScalpelReport.AffectedModule.moduleBuilder(
                                "com.example", "module-a", "module-a", List.of(ScalpelReport.REASON_SOURCE_CHANGE))
                        .category(ScalpelReport.CATEGORY_DIRECT)
                        .evidence(List.of("module-a/src/main/java/Foo.java"))
                        .build())
                .build();

        String json = report.toJson();
        assertTrue(
                json.contains("\"evidence\": [\"module-a/src/main/java/Foo.java\"]"),
                "evidence array must be emitted when present");
    }

    @Test
    void toJson_evidenceOmittedWhenNull() {
        ScalpelReport report = ScalpelReport.builder()
                .baseBranch("origin/main")
                .fullBuildTriggered(false)
                .changedFiles(Set.of("module-a/src/main/java/Foo.java"))
                .addAffectedModule(ScalpelReport.AffectedModule.moduleBuilder(
                                "com.example", "module-a", "module-a", List.of(ScalpelReport.REASON_SOURCE_CHANGE))
                        .category(ScalpelReport.CATEGORY_DIRECT)
                        .build())
                .build();

        String json = report.toJson();
        assertFalse(json.contains("evidence"), "evidence must not appear when not set");
    }

    // ---------------------------------------------------------------
    // Golden-file and schema tests
    // ---------------------------------------------------------------

    @Test
    void toJson_matchesGoldenFile() throws IOException {
        // Build a report that exercises every v2 field:
        // category, sourceSet, excludedUpstreamCount, testsSkipped, testsSkippedReason
        ScalpelReport report = ScalpelReport.builder()
                .baseBranch("origin/main")
                .fullBuildTriggered(false)
                .changedFiles(
                        List.of("module-a/src/main/java/Foo.java", "module-b/src/test/java/BarTest.java", "pom.xml"))
                .changedProperties(List.of("kafka.version"))
                .changedManagedDependencies(List.of("org.apache.kafka:kafka-clients"))
                .changedManagedPlugins(List.of("org.apache.maven.plugins:maven-compiler-plugin"))
                .excludedUpstreamCount(2)
                .addAffectedModule(ScalpelReport.AffectedModule.moduleBuilder(
                                "com.example", "module-a", "module-a", List.of(ScalpelReport.REASON_SOURCE_CHANGE))
                        .category(ScalpelReport.CATEGORY_DIRECT)
                        .sourceSet("main")
                        .build())
                .addAffectedModule(ScalpelReport.AffectedModule.moduleBuilder(
                                "com.example", "module-b", "module-b", List.of(ScalpelReport.REASON_TEST_CHANGE))
                        .category(ScalpelReport.CATEGORY_DIRECT)
                        .sourceSet("test")
                        .build())
                .addAffectedModule(ScalpelReport.AffectedModule.moduleBuilder(
                                "com.example",
                                "module-c",
                                "module-c",
                                List.of(ScalpelReport.REASON_DOWNSTREAM_DEPENDENT))
                        .category(ScalpelReport.CATEGORY_DOWNSTREAM)
                        .sourceSet("main")
                        .testsSkippedReason(ScalpelReport.REASON_EXCLUDED_DOWNSTREAM)
                        .build())
                .addAffectedModule(ScalpelReport.AffectedModule.moduleBuilder(
                                "com.example",
                                "module-d",
                                "module-d",
                                List.of(ScalpelReport.REASON_TRANSITIVE_DEPENDENCY))
                        .category(ScalpelReport.CATEGORY_TRANSITIVE)
                        .build())
                .build();

        String actual = report.toJson();

        // The golden file uses a placeholder for scalpelVersion since it changes with every release.
        // Replace the actual version with the placeholder before comparing.
        String normalised =
                actual.replaceFirst("\"scalpelVersion\": \"[^\"]*\"", "\"scalpelVersion\": \"<scalpelVersion>\"");

        String expected;
        try (InputStream is =
                getClass().getResourceAsStream("/eu/maveniverse/maven/scalpel/core/golden-report-v2.json")) {
            assertNotNull(is, "golden-report-v2.json must be on the test classpath");
            expected = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertEquals(
                expected,
                normalised,
                "Report JSON structure has changed: update the golden file, and bump "
                        + "REPORT_VERSION if the change is not an optional additive field.");
    }

    @Test
    void reportVersion_isTwo() {
        assertEquals("2", ScalpelReport.REPORT_VERSION);
    }

    @Test
    void toJson_unmatchedPomPathsIncludedWhenSet() {
        ScalpelReport report = ScalpelReport.builder()
                .baseBranch("origin/main")
                .fullBuildTriggered(false)
                .changedFiles(Set.of("gated-module/pom.xml"))
                .unmatchedPomPaths(List.of("gated-module/pom.xml"))
                .build();

        String json = report.toJson();
        assertTrue(json.contains("\"unmatchedPomPaths\": [\"gated-module/pom.xml\"]"));
    }

    @Test
    void toJson_unmatchedPomPathsOmittedWhenEmpty() {
        ScalpelReport report = ScalpelReport.builder()
                .baseBranch("origin/main")
                .fullBuildTriggered(false)
                .changedFiles(Set.of("module-a/pom.xml"))
                .build();

        String json = report.toJson();
        assertFalse(json.contains("unmatchedPomPaths"));
    }

    @Test
    void toJson_timingFieldsOmittedWhenNotInstrumented() {
        // #99 null-input rule: a report built without instrumentation data must not
        // carry timings/operations keys at all (no null, no empty objects).
        ScalpelReport report = ScalpelReport.builder()
                .baseBranch("origin/main")
                .fullBuildTriggered(false)
                .changedFiles(Set.of("module-a/src/Foo.java"))
                .addAffectedModule(new ScalpelReport.AffectedModule(
                        "com.example", "module-a", "module-a", List.of(ScalpelReport.REASON_SOURCE_CHANGE)))
                .build();

        String json = report.toJson();
        assertFalse(json.contains("\"timings\""), "timings must be omitted when nothing was recorded");
        assertFalse(json.contains("\"operations\""), "operations must be omitted when nothing was counted");
    }

    @Test
    void jsonSchema_isOnClasspath() throws IOException {
        try (InputStream is =
                getClass().getResourceAsStream("/eu/maveniverse/maven/scalpel/core/scalpel-report-v2.schema.json")) {
            assertNotNull(is, "scalpel-report-v2.schema.json must be on the classpath");
            String schema = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(schema.contains("\"const\": \"2\""), "schema must declare version 2");
        }
    }
}
