/*
 * Copyright (c) Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.scalpel.extension3.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.building.*;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModelBuilderSpikeTest {

    @TempDir
    Path tempDir;

    @Test
    void modelBuilder_interpolatesProperties() throws Exception {
        String pomXml = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>bom</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <properties>
                    <lib.version>2.5</lib.version>
                  </properties>
                  <dependencyManagement><dependencies>
                    <dependency>
                      <groupId>commons-lang</groupId>
                      <artifactId>commons-lang</artifactId>
                      <version>${lib.version}</version>
                    </dependency>
                  </dependencies></dependencyManagement>
                </project>
                """;

        Model effective = buildEffectiveModel(pomXml, null);

        assertNotNull(effective.getDependencyManagement());
        assertEquals(1, effective.getDependencyManagement().getDependencies().size());
        Dependency dep = effective.getDependencyManagement().getDependencies().get(0);
        assertEquals("2.5", dep.getVersion(), "Property should be interpolated");
    }

    @Test
    void modelBuilder_withParentPom_inheritsProperties() throws Exception {
        // Parent POM defines the property
        String parentPomXml = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <properties>
                    <lib.version>2.5</lib.version>
                  </properties>
                </project>
                """;

        // Child BOM uses inherited property
        String bomPomXml = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>com.example</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0</version>
                  </parent>
                  <artifactId>bom</artifactId>
                  <packaging>pom</packaging>
                  <dependencyManagement><dependencies>
                    <dependency>
                      <groupId>commons-lang</groupId>
                      <artifactId>commons-lang</artifactId>
                      <version>${lib.version}</version>
                    </dependency>
                  </dependencies></dependencyManagement>
                </project>
                """;

        // Write parent POM to disk so ModelBuilder can resolve via relativePath
        Path parentDir = tempDir.resolve("project");
        Files.createDirectories(parentDir.resolve("bom"));
        Files.write(parentDir.resolve("pom.xml"), parentPomXml.getBytes(StandardCharsets.UTF_8));
        Path childPomFile = parentDir.resolve("bom/pom.xml");
        Files.write(childPomFile, bomPomXml.getBytes(StandardCharsets.UTF_8));

        Model effective = buildEffectiveModel(bomPomXml, childPomFile);

        assertNotNull(effective.getDependencyManagement());
        Dependency dep = effective.getDependencyManagement().getDependencies().get(0);
        assertEquals("2.5", dep.getVersion(), "Inherited property should be interpolated");
    }

    @Test
    void effectiveDiff_versionBumpViaProperty() throws Exception {
        String oldPom = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>bom</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <properties><lib.version>2.5</lib.version></properties>
                  <dependencyManagement><dependencies>
                    <dependency><groupId>commons-lang</groupId><artifactId>commons-lang</artifactId>
                      <version>${lib.version}</version></dependency>
                  </dependencies></dependencyManagement>
                </project>
                """;

        String newPom = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>bom</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <properties><lib.version>2.6</lib.version></properties>
                  <dependencyManagement><dependencies>
                    <dependency><groupId>commons-lang</groupId><artifactId>commons-lang</artifactId>
                      <version>${lib.version}</version></dependency>
                  </dependencies></dependencyManagement>
                </project>
                """;

        Model oldEffective = buildEffectiveModel(oldPom, null);
        Model newEffective = buildEffectiveModel(newPom, null);

        assertEquals(
                "2.5",
                oldEffective.getDependencyManagement().getDependencies().get(0).getVersion());
        assertEquals(
                "2.6",
                newEffective.getDependencyManagement().getDependencies().get(0).getVersion());
    }

    @Test
    void effectiveDiff_propertyChangeNoVersionEffect() throws Exception {
        // Changing an unrelated property does NOT change the managed dep version
        String oldPom = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>bom</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <properties><unrelated>old</unrelated></properties>
                  <dependencyManagement><dependencies>
                    <dependency><groupId>commons-lang</groupId><artifactId>commons-lang</artifactId>
                      <version>2.5</version></dependency>
                  </dependencies></dependencyManagement>
                </project>
                """;

        String newPom = """
                <?xml version="1.0"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>bom</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <properties><unrelated>new</unrelated></properties>
                  <dependencyManagement><dependencies>
                    <dependency><groupId>commons-lang</groupId><artifactId>commons-lang</artifactId>
                      <version>2.5</version></dependency>
                  </dependencies></dependencyManagement>
                </project>
                """;

        Model oldEffective = buildEffectiveModel(oldPom, null);
        Model newEffective = buildEffectiveModel(newPom, null);

        assertEquals(
                oldEffective.getDependencyManagement().getDependencies().get(0).getVersion(),
                newEffective.getDependencyManagement().getDependencies().get(0).getVersion(),
                "Unrelated property change should not affect managed dep version");
    }

    private Model buildEffectiveModel(String pomXml, Path pomFile) throws Exception {
        ModelBuilder builder = new DefaultModelBuilderFactory().newInstance();
        DefaultModelBuildingRequest request = new DefaultModelBuildingRequest();
        request.setProcessPlugins(false);
        request.setValidationLevel(ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL);

        if (pomFile != null) {
            request.setPomFile(pomFile.toFile());
        } else {
            Model rawModel =
                    new MavenXpp3Reader().read(new ByteArrayInputStream(pomXml.getBytes(StandardCharsets.UTF_8)));
            request.setRawModel(rawModel);
        }

        ModelBuildingResult result = builder.build(request);
        return result.getEffectiveModel();
    }
}
