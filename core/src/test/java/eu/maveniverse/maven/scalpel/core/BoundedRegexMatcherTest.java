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

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class BoundedRegexMatcherTest {

    private static final Logger logger = LoggerFactory.getLogger(BoundedRegexMatcherTest.class);

    @Test
    void repeatedMatches_samePattern_compiledOnce() {
        BoundedRegexMatcher matcher = new BoundedRegexMatcher();
        for (int i = 0; i < 10; i++) {
            assertTrue(matcher.matches("module-it", ".*-it", "forceBuildModules", logger));
        }
        assertEquals(
                1,
                matcher.cachedPatternCount(),
                "the same pattern must be compiled once and reused, not recompiled per call");
    }

    @Test
    void repeatedMatches_distinctPatterns_eachCompiledOnce() {
        BoundedRegexMatcher matcher = new BoundedRegexMatcher();
        assertTrue(matcher.matches("module-it", ".*-it", "forceBuildModules", logger));
        assertTrue(matcher.matches("main", "ma.*", "disableOnBranch", logger));
        assertTrue(matcher.matches("main", "ma.*", "disableOnBranch", logger));
        assertEquals(2, matcher.cachedPatternCount(), "two distinct patterns mean two cached compiles");
    }

    @Test
    void matchSemantics_isFullMatch_notFind() {
        BoundedRegexMatcher matcher = new BoundedRegexMatcher();
        assertFalse(matcher.matches("module-x", "module", "disableOnBranch", logger));
        assertTrue(matcher.matches("module-x", "module.*", "disableOnBranch", logger));
    }

    @Test
    void input_atLimit_isMatched() {
        BoundedRegexMatcher matcher = new BoundedRegexMatcher();
        String value = "a".repeat(BoundedRegexMatcher.MAX_INPUT_LENGTH);
        assertTrue(matcher.matches(value, "a*", "disableOnBranch", logger), "input at the limit must still match");
    }

    @Test
    void input_overLimit_isSkippedWithSingleWarn() {
        BoundedRegexMatcher matcher = new BoundedRegexMatcher();
        String value = "a".repeat(BoundedRegexMatcher.MAX_INPUT_LENGTH + 1);
        String err = captureStderr(() -> {
            assertFalse(
                    matcher.matches(value, ".*", "disableOnBranch", logger),
                    "an over-limit input must not match, even against '.*'");
            // second pattern against the same input: the WARN must not repeat
            assertFalse(matcher.matches(value, ".*-x", "disableOnBranch", logger));
        });
        assertTrue(
                err.contains("exceeds"),
                "expected a WARN about the over-limit input, and it must name the config key; stderr was: " + err);
        assertTrue(err.contains("disableOnBranch"), "the cap WARN must name the config key; stderr was: " + err);
        assertEquals(
                1,
                countOccurrences(err, "character limit"),
                "one cap WARN per over-long input, not one per pattern; stderr was: " + err);
    }

    @Test
    void invalidPattern_isNotMatchedAndWarnsOnce() {
        BoundedRegexMatcher matcher = new BoundedRegexMatcher();
        String err = captureStderr(() -> {
            assertFalse(matcher.matches("anything", "[unclosed", "forceBuildModules", logger));
            assertFalse(matcher.matches("other", "[unclosed", "forceBuildModules", logger));
        });
        assertEquals(
                1,
                countOccurrences(err, "Invalid regex pattern"),
                "an invalid pattern must WARN once, not once per call; stderr was: " + err);
        assertEquals(0, matcher.cachedPatternCount(), "an invalid pattern is not cached as a compiled pattern");
    }

    @Test
    void nullInput_isNotMatched() {
        BoundedRegexMatcher matcher = new BoundedRegexMatcher();
        assertFalse(matcher.matches(null, ".*", "disableOnBranch", logger));
        assertFalse(matcher.matches("value", null, "disableOnBranch", logger));
    }

    @Test
    void cacheSize_isBounded() {
        BoundedRegexMatcher matcher = new BoundedRegexMatcher();
        for (int i = 0; i < BoundedRegexMatcher.MAX_CACHED_PATTERNS + 10; i++) {
            String value = "v" + i;
            assertTrue(matcher.matches(value, "v" + i, "forceBuildModules", logger));
        }
        assertEquals(
                BoundedRegexMatcher.MAX_CACHED_PATTERNS,
                matcher.cachedPatternCount(),
                "the compiled-pattern cache must stay bounded");
        // patterns compiled beyond the bound still match (uncached fallback)
        assertTrue(matcher.matches("late", "la.*", "forceBuildModules", logger));
    }

    /**
     * Runs the action with System.err captured (slf4j-simple writes to stderr)
     * and returns everything that was written during its execution.
     */
    private String captureStderr(Runnable action) {
        PrintStream originalErr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            action.run();
        } finally {
            System.setErr(originalErr);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    private int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
