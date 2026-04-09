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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
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
                .changedFiles(Collections.singleton("module-a/src/Foo.java"))
                .addAffectedModule(new ScalpelReport.AffectedModule(
                        "com.example",
                        "module-a",
                        "module-a",
                        Collections.singletonList(ScalpelReport.REASON_SOURCE_CHANGE)))
                .build();

        String json = report.toJson();
        assertTrue(json.contains("\"SOURCE_CHANGE\""));
        assertTrue(json.contains("\"module-a\""));
        assertFalse(json.contains("\"TRANSITIVE_DEPENDENCY\""));
    }

    @Test
    void toJson_transitivelyAffectedModule() {
        ScalpelReport report = ScalpelReport.builder()
                .baseBranch("origin/main")
                .fullBuildTriggered(false)
                .changedFiles(Collections.singleton("pom.xml"))
                .changedManagedDependencies(Collections.singleton("org.apache.kafka:kafka-clients"))
                .addAffectedModule(new ScalpelReport.AffectedModule(
                        "com.example",
                        "module-a",
                        "module-a",
                        Collections.singletonList(ScalpelReport.REASON_POM_CHANGE)))
                .addAffectedModule(new ScalpelReport.AffectedModule(
                        "com.example",
                        "module-b",
                        "module-b",
                        Collections.singletonList(ScalpelReport.REASON_TRANSITIVE_DEPENDENCY)))
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
                .changedFiles(Collections.singleton("pom.xml"))
                .changedManagedPlugins(Collections.singleton("org.apache.maven.plugins:maven-compiler-plugin"))
                .addAffectedModule(new ScalpelReport.AffectedModule(
                        "com.example",
                        "module-a",
                        "module-a",
                        Collections.singletonList(ScalpelReport.REASON_MANAGED_PLUGIN)))
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
                .changedFiles(Collections.singleton("pom.xml"))
                .addAffectedModule(new ScalpelReport.AffectedModule(
                        "com.example",
                        "module-a",
                        "module-a",
                        Arrays.asList(ScalpelReport.REASON_MANAGED_PLUGIN, ScalpelReport.REASON_TRANSITIVE_DEPENDENCY)))
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
                .changedFiles(Collections.singleton(".mvn/extensions.xml"))
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
        assertTrue(json.contains("\"version\": \"1\""));
        assertTrue(json.contains("\"fullBuildTriggered\": false"));
        assertTrue(json.contains("\"affectedModules\": []"));
        assertTrue(json.contains("\"changedFiles\": []"));
    }

    @Test
    void writeToFile_createsFileWithCorrectContent() throws IOException {
        ScalpelReport report = ScalpelReport.builder()
                .baseBranch("origin/main")
                .fullBuildTriggered(false)
                .changedFiles(Collections.singleton("pom.xml"))
                .changedManagedDependencies(Collections.singleton("commons-lang:commons-lang"))
                .addAffectedModule(new ScalpelReport.AffectedModule(
                        "com.example",
                        "module-b",
                        "module-b",
                        Collections.singletonList(ScalpelReport.REASON_TRANSITIVE_DEPENDENCY)))
                .build();

        report.writeToFile(tempDir, "target/scalpel-report.json");

        Path reportFile = tempDir.resolve("target/scalpel-report.json");
        assertTrue(Files.exists(reportFile));
        String content = new String(Files.readAllBytes(reportFile), StandardCharsets.UTF_8);
        assertEquals(report.toJson(), content);
    }

    @Test
    void toJson_escapesSpecialCharacters() {
        ScalpelReport report = ScalpelReport.builder()
                .baseBranch("origin/main")
                .fullBuildTriggered(false)
                .changedFiles(Collections.singleton("path/with\"quotes.java"))
                .build();

        String json = report.toJson();
        assertTrue(json.contains("path/with\\\"quotes.java"));
    }
}
