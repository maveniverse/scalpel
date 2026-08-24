/*
 * Copyright (c) Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.scalpel.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * Configuration precedence must follow the Maven convention: user properties
 * (CLI {@code -D}, {@code .mvn/maven.config}) win over system properties
 * (JVM properties, e.g. anything in {@code MAVEN_OPTS}).
 *
 * @see <a href="https://github.com/maveniverse/scalpel/issues/81">issue #81</a>
 */
class ConfigPrecedenceTest {

    @Test
    void userPropertyWinsOverSystemProperty() {
        Properties system = new Properties();
        system.setProperty("scalpel.mode", "report");
        Properties user = new Properties();
        user.setProperty("scalpel.mode", "trim");

        ScalpelConfiguration config = ScalpelConfiguration.fromProperties(system, user);

        assertEquals("trim", config.getMode(), "user (-D) must override system (MAVEN_OPTS), per Maven convention");
    }

    @Test
    void systemPropertyUsedWhenUserPropertyAbsent() {
        Properties system = new Properties();
        system.setProperty("scalpel.baseBranch", "origin/main");

        ScalpelConfiguration config = ScalpelConfiguration.fromProperties(system, new Properties());

        assertEquals("origin/main", config.getBaseBranch());
    }
}
