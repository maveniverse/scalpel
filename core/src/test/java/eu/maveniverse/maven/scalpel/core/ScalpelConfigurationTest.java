/*
 * Copyright (c) Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.scalpel.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class ScalpelConfigurationTest {

    @Test
    void skipTestsForDownstreamModules_defaultIsEmpty() {
        Properties system = new Properties();
        system.setProperty("scalpel.baseBranch", "main");
        ScalpelConfiguration config = ScalpelConfiguration.fromProperties(system, new Properties());
        assertTrue(config.getSkipTestsForDownstreamModules().isEmpty());
    }

    @Test
    void skipTestsForDownstreamModules_singleArtifactId() {
        Properties system = new Properties();
        system.setProperty("scalpel.baseBranch", "main");
        system.setProperty("scalpel.skipTestsForDownstreamModules", "module-b");
        ScalpelConfiguration config = ScalpelConfiguration.fromProperties(system, new Properties());
        assertEquals(Arrays.asList("module-b"), config.getSkipTestsForDownstreamModules());
    }

    @Test
    void skipTestsForDownstreamModules_multipleCommaSeparated() {
        Properties system = new Properties();
        system.setProperty("scalpel.baseBranch", "main");
        system.setProperty("scalpel.skipTestsForDownstreamModules", "module-b,module-c");
        ScalpelConfiguration config = ScalpelConfiguration.fromProperties(system, new Properties());
        assertEquals(Arrays.asList("module-b", "module-c"), config.getSkipTestsForDownstreamModules());
    }

    @Test
    void skipTestsForDownstreamModules_groupIdColonArtifactId() {
        Properties system = new Properties();
        system.setProperty("scalpel.baseBranch", "main");
        system.setProperty("scalpel.skipTestsForDownstreamModules", "com.example:module-b");
        ScalpelConfiguration config = ScalpelConfiguration.fromProperties(system, new Properties());
        assertEquals(Arrays.asList("com.example:module-b"), config.getSkipTestsForDownstreamModules());
    }

    @Test
    void skipTestsForDownstreamModules_fromUserProperties() {
        Properties system = new Properties();
        system.setProperty("scalpel.baseBranch", "main");
        Properties user = new Properties();
        user.setProperty("scalpel.skipTestsForDownstreamModules", "module-x");
        ScalpelConfiguration config = ScalpelConfiguration.fromProperties(system, user);
        assertEquals(Arrays.asList("module-x"), config.getSkipTestsForDownstreamModules());
    }

    @Test
    void skipTestsForDownstreamModules_appearsInToString() {
        Properties system = new Properties();
        system.setProperty("scalpel.baseBranch", "main");
        system.setProperty("scalpel.skipTestsForDownstreamModules", "module-b");
        ScalpelConfiguration config = ScalpelConfiguration.fromProperties(system, new Properties());
        assertTrue(config.toString().contains("skipTestsForDownstreamModules=[module-b]"));
    }
}
