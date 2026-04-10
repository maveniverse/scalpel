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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class ScalpelConfigurationTest {

    private static ScalpelConfiguration defaultConfig() {
        return ScalpelConfiguration.fromProperties(new Properties(), new Properties());
    }

    // ---------------------------------------------------------------
    // Default values for boolean fields
    // ---------------------------------------------------------------

    @Test
    void defaultDisableOnSelectedProjects_isFalse() {
        assertFalse(defaultConfig().isDisableOnSelectedProjects());
    }

    @Test
    void defaultFetchBaseBranch_isFalse() {
        assertFalse(defaultConfig().isFetchBaseBranch());
    }

    @Test
    void defaultSkipTestsForUpstream_isFalse() {
        assertFalse(defaultConfig().isSkipTestsForUpstream());
    }

    @Test
    void defaultUncommitted_isFalse() {
        assertFalse(defaultConfig().isUncommitted());
    }

    @Test
    void defaultUntracked_isFalse() {
        assertFalse(defaultConfig().isUntracked());
    }

    @Test
    void defaultBuildAllIfNoChanges_isFalse() {
        assertFalse(defaultConfig().isBuildAllIfNoChanges());
    }

    // ---------------------------------------------------------------
    // Default values for list fields (empty)
    // ---------------------------------------------------------------

    @Test
    void defaultDisableOnBranch_isEmpty() {
        assertTrue(defaultConfig().getDisableOnBranch().isEmpty());
    }

    @Test
    void defaultDisableOnBaseBranch_isEmpty() {
        assertTrue(defaultConfig().getDisableOnBaseBranch().isEmpty());
    }

    @Test
    void defaultExcludePaths_isEmpty() {
        assertTrue(defaultConfig().getExcludePaths().isEmpty());
    }

    @Test
    void defaultDisableTriggers_isEmpty() {
        assertTrue(defaultConfig().getDisableTriggers().isEmpty());
    }

    @Test
    void defaultUpstreamArgs_isEmpty() {
        assertTrue(defaultConfig().getUpstreamArgs().isEmpty());
    }

    @Test
    void defaultDownstreamArgs_isEmpty() {
        assertTrue(defaultConfig().getDownstreamArgs().isEmpty());
    }

    @Test
    void defaultForceBuildModules_isEmpty() {
        assertTrue(defaultConfig().getForceBuildModules().isEmpty());
    }

    @Test
    void defaultSkipTestsForDownstreamModules_isEmpty() {
        assertTrue(defaultConfig().getSkipTestsForDownstreamModules().isEmpty());
    }

    // ---------------------------------------------------------------
    // Default value for impactedLog (null)
    // ---------------------------------------------------------------

    @Test
    void defaultImpactedLog_isNull() {
        assertNull(defaultConfig().getImpactedLog());
    }

    // ---------------------------------------------------------------
    // Parsing boolean fields from system properties
    // ---------------------------------------------------------------

    @Test
    void disableOnSelectedProjects_parsedFromSystemProperty() {
        Properties sys = new Properties();
        sys.setProperty("scalpel.disableOnSelectedProjects", "true");
        ScalpelConfiguration config = ScalpelConfiguration.fromProperties(sys, new Properties());
        assertTrue(config.isDisableOnSelectedProjects());
    }

    @Test
    void fetchBaseBranch_parsedFromSystemProperty() {
        Properties sys = new Properties();
        sys.setProperty("scalpel.fetchBaseBranch", "true");
        ScalpelConfiguration config = ScalpelConfiguration.fromProperties(sys, new Properties());
        assertTrue(config.isFetchBaseBranch());
    }

    @Test
    void skipTestsForUpstream_parsedFromSystemProperty() {
        Properties sys = new Properties();
        sys.setProperty("scalpel.skipTestsForUpstream", "true");
        ScalpelConfiguration config = ScalpelConfiguration.fromProperties(sys, new Properties());
        assertTrue(config.isSkipTestsForUpstream());
    }

    @Test
    void uncommitted_parsedFromSystemProperty() {
        Properties sys = new Properties();
        sys.setProperty("scalpel.uncommitted", "true");
        ScalpelConfiguration config = ScalpelConfiguration.fromProperties(sys, new Properties());
        assertTrue(config.isUncommitted());
    }

    @Test
    void untracked_parsedFromSystemProperty() {
        Properties sys = new Properties();
        sys.setProperty("scalpel.untracked", "true");
        ScalpelConfiguration config = ScalpelConfiguration.fromProperties(sys, new Properties());
        assertTrue(config.isUntracked());
    }

    @Test
    void buildAllIfNoChanges_parsedFromSystemProperty() {
        Properties sys = new Properties();
        sys.setProperty("scalpel.buildAllIfNoChanges", "true");
        ScalpelConfiguration config = ScalpelConfiguration.fromProperties(sys, new Properties());
        assertTrue(config.isBuildAllIfNoChanges());
    }

    // ---------------------------------------------------------------
    // Parsing CSV list fields
    // ---------------------------------------------------------------

    @Test
    void disableOnBranch_parsedAsList() {
        Properties sys = new Properties();
        sys.setProperty("scalpel.disableOnBranch", "main,release/.*");
        ScalpelConfiguration config = ScalpelConfiguration.fromProperties(sys, new Properties());
        assertEquals(Arrays.asList("main", "release/.*"), config.getDisableOnBranch());
    }

    @Test
    void disableOnBranch_singleEntry() {
        Properties sys = new Properties();
        sys.setProperty("scalpel.disableOnBranch", "main");
        ScalpelConfiguration config = ScalpelConfiguration.fromProperties(sys, new Properties());
        assertEquals(Collections.singletonList("main"), config.getDisableOnBranch());
    }

    @Test
    void disableOnBaseBranch_parsedAsList() {
        Properties sys = new Properties();
        sys.setProperty("scalpel.disableOnBaseBranch", "main,develop");
        ScalpelConfiguration config = ScalpelConfiguration.fromProperties(sys, new Properties());
        assertEquals(Arrays.asList("main", "develop"), config.getDisableOnBaseBranch());
    }

    @Test
    void excludePaths_parsedAsList() {
        Properties sys = new Properties();
        sys.setProperty("scalpel.excludePaths", "*.md,LICENSE,.editorconfig");
        ScalpelConfiguration config = ScalpelConfiguration.fromProperties(sys, new Properties());
        assertEquals(Arrays.asList("*.md", "LICENSE", ".editorconfig"), config.getExcludePaths());
    }

    @Test
    void disableTriggers_parsedAsList() {
        Properties sys = new Properties();
        sys.setProperty("scalpel.disableTriggers", ".github/**,Jenkinsfile");
        ScalpelConfiguration config = ScalpelConfiguration.fromProperties(sys, new Properties());
        assertEquals(Arrays.asList(".github/**", "Jenkinsfile"), config.getDisableTriggers());
    }

    @Test
    void upstreamArgs_parsedAsList() {
        Properties sys = new Properties();
        sys.setProperty("scalpel.upstreamArgs", "skipITs=true,someKey=val");
        ScalpelConfiguration config = ScalpelConfiguration.fromProperties(sys, new Properties());
        assertEquals(Arrays.asList("skipITs=true", "someKey=val"), config.getUpstreamArgs());
    }

    @Test
    void downstreamArgs_parsedAsList() {
        Properties sys = new Properties();
        sys.setProperty("scalpel.downstreamArgs", "skipITs=true");
        ScalpelConfiguration config = ScalpelConfiguration.fromProperties(sys, new Properties());
        assertEquals(Collections.singletonList("skipITs=true"), config.getDownstreamArgs());
    }

    @Test
    void forceBuildModules_parsedAsList() {
        Properties sys = new Properties();
        sys.setProperty("scalpel.forceBuildModules", ".*-it,.*-tests");
        ScalpelConfiguration config = ScalpelConfiguration.fromProperties(sys, new Properties());
        assertEquals(Arrays.asList(".*-it", ".*-tests"), config.getForceBuildModules());
    }

    @Test
    void emptyStringList_returnsEmptyList() {
        Properties sys = new Properties();
        sys.setProperty("scalpel.disableOnBranch", "");
        ScalpelConfiguration config = ScalpelConfiguration.fromProperties(sys, new Properties());
        assertTrue(config.getDisableOnBranch().isEmpty());
    }

    // ---------------------------------------------------------------
    // skipTestsForDownstreamModules
    // ---------------------------------------------------------------

    @Test
    void skipTestsForDownstreamModules_singleArtifactId() {
        Properties sys = new Properties();
        sys.setProperty("scalpel.skipTestsForDownstreamModules", "module-b");
        ScalpelConfiguration config = ScalpelConfiguration.fromProperties(sys, new Properties());
        assertEquals(Arrays.asList("module-b"), config.getSkipTestsForDownstreamModules());
    }

    @Test
    void skipTestsForDownstreamModules_multipleCommaSeparated() {
        Properties sys = new Properties();
        sys.setProperty("scalpel.skipTestsForDownstreamModules", "module-b,module-c");
        ScalpelConfiguration config = ScalpelConfiguration.fromProperties(sys, new Properties());
        assertEquals(Arrays.asList("module-b", "module-c"), config.getSkipTestsForDownstreamModules());
    }

    @Test
    void skipTestsForDownstreamModules_groupIdColonArtifactId() {
        Properties sys = new Properties();
        sys.setProperty("scalpel.skipTestsForDownstreamModules", "com.example:module-b");
        ScalpelConfiguration config = ScalpelConfiguration.fromProperties(sys, new Properties());
        assertEquals(Arrays.asList("com.example:module-b"), config.getSkipTestsForDownstreamModules());
    }

    @Test
    void skipTestsForDownstreamModules_fromUserProperties() {
        Properties sys = new Properties();
        Properties user = new Properties();
        user.setProperty("scalpel.skipTestsForDownstreamModules", "module-x");
        ScalpelConfiguration config = ScalpelConfiguration.fromProperties(sys, user);
        assertEquals(Arrays.asList("module-x"), config.getSkipTestsForDownstreamModules());
    }

    @Test
    void skipTestsForDownstreamModules_appearsInToString() {
        Properties sys = new Properties();
        sys.setProperty("scalpel.skipTestsForDownstreamModules", "module-b");
        ScalpelConfiguration config = ScalpelConfiguration.fromProperties(sys, new Properties());
        assertTrue(config.toString().contains("skipTestsForDownstreamModules=[module-b]"));
    }

    // ---------------------------------------------------------------
    // Parsing impactedLog
    // ---------------------------------------------------------------

    @Test
    void impactedLog_parsedFromSystemProperty() {
        Properties sys = new Properties();
        sys.setProperty("scalpel.impactedLog", "target/scalpel-impacted.log");
        ScalpelConfiguration config = ScalpelConfiguration.fromProperties(sys, new Properties());
        assertEquals("target/scalpel-impacted.log", config.getImpactedLog());
    }

    // ---------------------------------------------------------------
    // System property takes precedence over user property
    // ---------------------------------------------------------------

    @Test
    void systemProperty_takesPrecedenceOverUserProperty() {
        Properties sys = new Properties();
        sys.setProperty("scalpel.disableOnBranch", "main");
        Properties user = new Properties();
        user.setProperty("scalpel.disableOnBranch", "develop");
        ScalpelConfiguration config = ScalpelConfiguration.fromProperties(sys, user);
        assertEquals(Collections.singletonList("main"), config.getDisableOnBranch());
    }

    @Test
    void userProperty_usedWhenSystemPropertyAbsent() {
        Properties user = new Properties();
        user.setProperty("scalpel.fetchBaseBranch", "true");
        ScalpelConfiguration config = ScalpelConfiguration.fromProperties(new Properties(), user);
        assertTrue(config.isFetchBaseBranch());
    }

    // ---------------------------------------------------------------
    // toString includes fields
    // ---------------------------------------------------------------

    @Test
    void toString_includesFields() {
        Properties sys = new Properties();
        sys.setProperty("scalpel.disableOnBranch", "main");
        sys.setProperty("scalpel.impactedLog", "target/impacted.log");
        sys.setProperty("scalpel.fetchBaseBranch", "true");
        ScalpelConfiguration config = ScalpelConfiguration.fromProperties(sys, new Properties());
        String str = config.toString();
        assertTrue(str.contains("disableOnBranch="));
        assertTrue(str.contains("fetchBaseBranch=true"));
        assertTrue(str.contains("impactedLog="));
    }

    // ---------------------------------------------------------------
    // Constant values
    // ---------------------------------------------------------------

    @Test
    void constantKeys_haveCorrectValues() {
        assertEquals("scalpel.disableOnBranch", ScalpelConfiguration.DISABLE_ON_BRANCH);
        assertEquals("scalpel.disableOnBaseBranch", ScalpelConfiguration.DISABLE_ON_BASE_BRANCH);
        assertEquals("scalpel.excludePaths", ScalpelConfiguration.EXCLUDE_PATHS);
        assertEquals("scalpel.disableTriggers", ScalpelConfiguration.DISABLE_TRIGGERS);
        assertEquals("scalpel.disableOnSelectedProjects", ScalpelConfiguration.DISABLE_ON_SELECTED_PROJECTS);
        assertEquals("scalpel.fetchBaseBranch", ScalpelConfiguration.FETCH_BASE_BRANCH);
        assertEquals("scalpel.skipTestsForUpstream", ScalpelConfiguration.SKIP_TESTS_FOR_UPSTREAM);
        assertEquals("scalpel.skipTestsForDownstreamModules", ScalpelConfiguration.SKIP_TESTS_FOR_DOWNSTREAM_MODULES);
        assertEquals("scalpel.upstreamArgs", ScalpelConfiguration.UPSTREAM_ARGS);
        assertEquals("scalpel.downstreamArgs", ScalpelConfiguration.DOWNSTREAM_ARGS);
        assertEquals("scalpel.uncommitted", ScalpelConfiguration.UNCOMMITTED);
        assertEquals("scalpel.untracked", ScalpelConfiguration.UNTRACKED);
        assertEquals("scalpel.forceBuildModules", ScalpelConfiguration.FORCE_BUILD_MODULES);
        assertEquals("scalpel.buildAllIfNoChanges", ScalpelConfiguration.BUILD_ALL_IF_NO_CHANGES);
        assertEquals("scalpel.impactedLog", ScalpelConfiguration.IMPACTED_LOG);
    }

    // ---------------------------------------------------------------
    // Regression: existing fields still parse correctly
    // ---------------------------------------------------------------

    @Test
    void existingFields_parseCorrectly() {
        Properties sys = new Properties();
        sys.setProperty("scalpel.baseBranch", "origin/main");
        sys.setProperty("scalpel.alsoMake", "false");
        sys.setProperty("scalpel.alsoMakeDependents", "false");
        sys.setProperty("scalpel.mode", "skip-tests");
        sys.setProperty("scalpel.failSafe", "false");
        ScalpelConfiguration config = ScalpelConfiguration.fromProperties(sys, new Properties());
        assertEquals("origin/main", config.getBaseBranch());
        assertFalse(config.isAlsoMake());
        assertFalse(config.isAlsoMakeDependents());
        assertEquals("skip-tests", config.getMode());
        assertFalse(config.isFailSafe());
    }

    // ---------------------------------------------------------------
    // Mode validation
    // ---------------------------------------------------------------

    @Test
    void invalidMode_throwsException() {
        Properties sys = new Properties();
        sys.setProperty("scalpel.mode", "invalid");
        assertThrows(IllegalArgumentException.class, () -> ScalpelConfiguration.fromProperties(sys, new Properties()));
    }

    @Test
    void validModes_accepted() {
        for (String mode : new String[] {"trim", "skip-tests", "report"}) {
            Properties sys = new Properties();
            sys.setProperty("scalpel.mode", mode);
            ScalpelConfiguration config = ScalpelConfiguration.fromProperties(sys, new Properties());
            assertEquals(mode, config.getMode());
        }
    }

    // ---------------------------------------------------------------
    // Whitespace trimming
    // ---------------------------------------------------------------

    @Test
    void listField_trimsWhitespaceInValues() {
        Properties sys = new Properties();
        sys.setProperty("scalpel.disableOnBranch", "main, release/.*,hotfix");
        ScalpelConfiguration config = ScalpelConfiguration.fromProperties(sys, new Properties());
        List<String> list = config.getDisableOnBranch();
        assertEquals(3, list.size());
        assertEquals("main", list.get(0));
        assertEquals("release/.*", list.get(1));
        assertEquals("hotfix", list.get(2));
    }
}
