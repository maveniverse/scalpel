/*
 * Copyright (c) Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.scalpel.core;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.slf4j.Logger;

/**
 * Matches the user-supplied regex options ({@code disableOnBranch}, {@code disableOnBaseBranch},
 * {@code forceBuildModules}) with bounded cost:
 *
 * <ul>
 *   <li>each pattern is compiled once and cached (an instance is meant to be reused for a build;
 *       {@link #MAX_CACHED_PATTERNS} bounds the cache so a long-lived instance cannot grow without
 *       limit; beyond the bound patterns are compiled per call again, which is the unfixed behavior)</li>
 *   <li>the matched input is capped at {@link #MAX_INPUT_LENGTH} characters; longer inputs are
 *       never matched (treated as non-match, with a WARN) because branch names and especially
 *       artifactIds are influenced by pull request authors, and match cost grows with input length</li>
 *   <li>invalid patterns are reported once per pattern and config key, and treated as non-matching</li>
 *   <li>{@code null} input or pattern never matches and never throws</li>
 * </ul>
 *
 * <p>The cap bounds the input, not the pattern: a maintainer-supplied pattern with nested
 * quantifiers can still backtrack heavily against a short input, so patterns should stay linear
 * (no nested quantifiers like {@code (a+)+}); the README documents this guidance. Match semantics
 * are those of {@link String#matches(String)}: the whole input must match.
 */
public final class BoundedRegexMatcher {

    /**
     * Branch names and artifactIds longer than this are never matched. Legitimate values are far
     * shorter (git refs are conventionally limited to a few hundred bytes and real artifactIds
     * are well under 100 characters), while match cost grows with input length.
     */
    public static final int MAX_INPUT_LENGTH = 256;

    /** Upper bound for compiled patterns held by one instance; package-private for tests. */
    static final int MAX_CACHED_PATTERNS = 256;

    /** How much of an over-long input to echo in the WARN (the input itself is PR-influenced). */
    private static final int WARN_ECHO_LENGTH = 32;

    /**
     * Upper bound for one-time-WARN deduplication keys held by one instance: keys are derived
     * from PR-influenced input, so both their count and their size must stay bounded.
     */
    private static final int MAX_WARNED_KEYS = 256;

    private record Compiled(Pattern pattern, String syntaxError) {}

    private final ConcurrentHashMap<String, Compiled> cache = new ConcurrentHashMap<>();

    /** Deduplication keys for one-time WARNs ("input:" / "pattern:" prefix + value). */
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    /**
     * Returns whether {@code value} fully matches {@code pattern}, with the bounded-cost
     * guarantees described on the class. Never throws for a null input, null pattern, invalid
     * pattern or over-long input; each of those is a non-match.
     *
     * @param value the input to match (branch name, artifactId); null is a non-match
     * @param pattern the user-supplied regex; null is a non-match
     * @param configKey the configuration key the pattern came from (used in log messages)
     * @param logger where WARNs about skipped matches and invalid patterns go
     */
    public boolean matches(String value, String pattern, String configKey, Logger logger) {
        if (pattern == null || value == null) {
            return false;
        }
        if (value.length() > MAX_INPUT_LENGTH) {
            // the dedup key uses only the echoed prefix, never the full over-long value
            if (warned.size() < MAX_WARNED_KEYS && warned.add("input:" + configKey + ":" + echo(value))) {
                logger.warn(
                        "Scalpel: Skipping {} match: input of length {} exceeds the {} character limit"
                                + " (starts with '{}'); treating as non-match",
                        configKey,
                        value.length(),
                        MAX_INPUT_LENGTH,
                        echo(value));
            }
            return false;
        }
        Compiled compiled = cache.get(pattern);
        if (compiled == null) {
            compiled = compile(pattern);
            if (cache.size() < MAX_CACHED_PATTERNS) {
                cache.putIfAbsent(pattern, compiled);
            }
        }
        if (compiled.syntaxError() != null) {
            if (warned.size() < MAX_WARNED_KEYS && warned.add("pattern:" + configKey + ":" + pattern)) {
                logger.warn(
                        "Scalpel: Invalid regex pattern '{}' in {}: {}", pattern, configKey, compiled.syntaxError());
            }
            return false;
        }
        return compiled.pattern().matcher(value).matches();
    }

    private static Compiled compile(String pattern) {
        try {
            return new Compiled(Pattern.compile(pattern), null);
        } catch (PatternSyntaxException e) {
            return new Compiled(null, e.getMessage());
        }
    }

    private static String echo(String value) {
        return value.length() <= WARN_ECHO_LENGTH ? value : value.substring(0, WARN_ECHO_LENGTH) + "...";
    }

    /**
     * Number of distinct valid patterns currently cached; a test seam proving patterns are
     * compiled once rather than per call.
     */
    int cachedPatternCount() {
        return (int)
                cache.values().stream().filter(c -> c.syntaxError() == null).count();
    }
}
