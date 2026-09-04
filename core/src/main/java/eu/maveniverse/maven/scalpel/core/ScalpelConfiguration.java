/*
 * Copyright (c) Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.scalpel.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * Immutable, resolved configuration for the Scalpel change-detection extension.
 *
 * <p>Every setting is a {@code -D} system property (or an entry in {@code .mvn/maven.config}) with
 * the {@code scalpel.} prefix. During resolution user properties (explicit {@code -D} on the
 * command line, including {@code .mvn/maven.config} entries) take precedence over system
 * properties (JVM properties, e.g. anything in {@code MAVEN_OPTS}), which take precedence over
 * the built-in default: the standard Maven convention. Instances are created only through
 * {@link #fromProperties(Properties, Properties)}; collection-valued getters return unmodifiable
 * lists.
 */
public final class ScalpelConfiguration {

    private static final String PREFIX = "scalpel.";

    /** System property {@code scalpel.enabled}: master switch for the extension. Default: {@code true}. */
    public static final String ENABLED = PREFIX + "enabled";

    /**
     * System property {@code scalpel.baseBranch}: the branch to diff the head against. Default:
     * auto-detected from CI environment variables, or {@code null} (Scalpel then runs as a no-op).
     */
    public static final String BASE_BRANCH = PREFIX + "baseBranch";

    /** System property {@code scalpel.head}: the commit to compare from. Default: {@code HEAD}. */
    public static final String HEAD = PREFIX + "head";

    /**
     * System property {@code scalpel.alsoMake}: include upstream dependencies of affected modules so
     * they compile. Default: {@code true}. Active in {@code trim} mode; also read in {@code report} mode.
     */
    public static final String ALSO_MAKE = PREFIX + "alsoMake";

    /**
     * System property {@code scalpel.alsoMakeDependents}: include downstream dependents of affected
     * modules. Default: {@code true}. Active in {@code trim} mode; also read in {@code report} mode.
     */
    public static final String ALSO_MAKE_DEPENDENTS = PREFIX + "alsoMakeDependents";

    /**
     * System property {@code scalpel.fullBuildTriggers}: comma-separated glob patterns; if any
     * changed file matches, a full build is triggered. Default: {@code .mvn/**}.
     */
    public static final String FULL_BUILD_TRIGGERS = PREFIX + "fullBuildTriggers";

    /**
     * System property {@code scalpel.failSafe}: on error, fall back to a full build instead of
     * failing the build. Default: {@code true}.
     */
    public static final String FAIL_SAFE = PREFIX + "failSafe";

    /**
     * System property {@code scalpel.mode}: operating mode, one of {@link #MODE_TRIM},
     * {@link #MODE_SKIP_TESTS}, or {@link #MODE_REPORT}. Default: {@code trim}.
     */
    public static final String MODE = PREFIX + "mode";

    /** System property {@code scalpel.explain}: emit per-module decision evidence. Default: {@code false}. */
    public static final String EXPLAIN = PREFIX + "explain";

    /**
     * System property {@code scalpel.disableOnBranch}: comma-separated regex patterns; Scalpel is
     * disabled when the current branch matches any of them. Default: none.
     */
    public static final String DISABLE_ON_BRANCH = PREFIX + "disableOnBranch";

    /**
     * System property {@code scalpel.disableOnBaseBranch}: comma-separated regex patterns; Scalpel is
     * disabled when the base branch name matches any of them; any remote prefix is stripped first,
     * so {@code origin/main} matches as {@code main}. Default: none.
     */
    public static final String DISABLE_ON_BASE_BRANCH = PREFIX + "disableOnBaseBranch";

    /**
     * System property {@code scalpel.excludePaths}: comma-separated glob patterns; changed files
     * matching any of them are ignored. Default: none.
     */
    public static final String EXCLUDE_PATHS = PREFIX + "excludePaths";

    /**
     * System property {@code scalpel.includePaths}: comma-separated glob patterns scoping the
     * affected set to modules whose path matches, applied after the file filters. Default: none.
     */
    public static final String INCLUDE_PATHS = PREFIX + "includePaths";

    /**
     * System property {@code scalpel.disableTriggers}: comma-separated glob patterns; if any changed
     * file matches, Scalpel is disabled entirely. Default: none.
     */
    public static final String DISABLE_TRIGGERS = PREFIX + "disableTriggers";

    /**
     * System property {@code scalpel.disableOnSelectedProjects}: disable Scalpel when a
     * {@code -pl} project selection is active. Default: {@code false}.
     */
    public static final String DISABLE_ON_SELECTED_PROJECTS = PREFIX + "disableOnSelectedProjects";

    /**
     * System property {@code scalpel.skipTestsForUpstream}: skip tests on modules included only as
     * upstream dependencies. Default: {@code false}. Applies in {@code skip-tests} mode.
     */
    public static final String SKIP_TESTS_FOR_UPSTREAM = PREFIX + "skipTestsForUpstream";

    /**
     * System property {@code scalpel.skipTestsForDownstreamModules}: comma-separated patterns
     * (artifactId or {@code groupId:artifactId}) naming downstream modules whose tests are skipped.
     * Default: none. Applies in {@code skip-tests} mode.
     */
    public static final String SKIP_TESTS_FOR_DOWNSTREAM_MODULES = PREFIX + "skipTestsForDownstreamModules";

    /**
     * System property {@code scalpel.upstreamArgs}: comma-separated {@code key=value} properties set
     * on modules included only as upstream dependencies. Default: none.
     */
    public static final String UPSTREAM_ARGS = PREFIX + "upstreamArgs";

    /**
     * System property {@code scalpel.downstreamArgs}: comma-separated {@code key=value} properties
     * set on modules included only as downstream dependents. Default: none.
     */
    public static final String DOWNSTREAM_ARGS = PREFIX + "downstreamArgs";

    /**
     * System property {@code scalpel.fetchBaseBranch}: fetch the base branch from the remote before
     * change detection (for shallow clones and forks). Default: {@code false}.
     */
    public static final String FETCH_BASE_BRANCH = PREFIX + "fetchBaseBranch";

    /** System property {@code scalpel.uncommitted}: include staged and unstaged changes. Default: {@code false}. */
    public static final String UNCOMMITTED = PREFIX + "uncommitted";

    /** System property {@code scalpel.untracked}: include untracked files in change detection. Default: {@code false}. */
    public static final String UNTRACKED = PREFIX + "untracked";

    /**
     * System property {@code scalpel.forceBuildModules}: comma-separated regex patterns; modules
     * whose artifactId matches are always included. Default: none.
     */
    public static final String FORCE_BUILD_MODULES = PREFIX + "forceBuildModules";

    /**
     * System property {@code scalpel.buildAllIfNoChanges}: build every module when no changes are
     * detected. Default: {@code false}.
     */
    public static final String BUILD_ALL_IF_NO_CHANGES = PREFIX + "buildAllIfNoChanges";

    /**
     * System property {@code scalpel.impactedLog}: path to write the directly impacted module paths
     * to, one per line, alongside the JSON report. Default: none.
     */
    public static final String IMPACTED_LOG = PREFIX + "impactedLog";

    /**
     * System property {@code scalpel.reportFile}: path of the JSON report, relative to the reactor
     * root. Default: {@code target/scalpel-report.json}.
     */
    public static final String REPORT_FILE = PREFIX + "reportFile";

    /**
     * System property {@code scalpel.maxResourceFileSize}: maximum size in bytes of a git blob
     * read for change analysis (old-POM content at the merge base). Larger blobs are skipped
     * with a WARN and the affected analysis treats the module conservatively as affected.
     * Default: {@link #DEFAULT_MAX_RESOURCE_FILE_SIZE}.
     */
    public static final String MAX_RESOURCE_FILE_SIZE = PREFIX + "maxResourceFileSize";

    /** Mode value selecting {@code trim}: drop unaffected modules from the reactor. */
    public static final String MODE_TRIM = "trim";

    /** Mode value selecting {@code skip-tests}: build everything, skip tests on unaffected modules. */
    public static final String MODE_SKIP_TESTS = "skip-tests";

    /** Mode value selecting {@code report}: write a JSON report without modifying the reactor. */
    public static final String MODE_REPORT = "report";

    /** Default value for {@link #FULL_BUILD_TRIGGERS}: {@code .mvn/**}. */
    private static final String DEFAULT_FULL_BUILD_TRIGGERS = ".mvn/**";

    /** Default value for {@link #REPORT_FILE}: {@code target/scalpel-report.json}. */
    private static final String DEFAULT_REPORT_FILE = "target/scalpel-report.json";

    /** Default value for {@link #MAX_RESOURCE_FILE_SIZE}: ten mebibytes ({@code 10 * 1024 * 1024}). */
    public static final long DEFAULT_MAX_RESOURCE_FILE_SIZE = 10L * 1024 * 1024; // 10 MB

    private static final Set<String> KNOWN_KEYS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            ENABLED,
            BASE_BRANCH,
            HEAD,
            ALSO_MAKE,
            ALSO_MAKE_DEPENDENTS,
            FULL_BUILD_TRIGGERS,
            FAIL_SAFE,
            MODE,
            EXPLAIN,
            DISABLE_ON_BRANCH,
            DISABLE_ON_BASE_BRANCH,
            EXCLUDE_PATHS,
            INCLUDE_PATHS,
            DISABLE_TRIGGERS,
            DISABLE_ON_SELECTED_PROJECTS,
            SKIP_TESTS_FOR_UPSTREAM,
            SKIP_TESTS_FOR_DOWNSTREAM_MODULES,
            UPSTREAM_ARGS,
            DOWNSTREAM_ARGS,
            FETCH_BASE_BRANCH,
            UNCOMMITTED,
            UNTRACKED,
            FORCE_BUILD_MODULES,
            BUILD_ALL_IF_NO_CHANGES,
            IMPACTED_LOG,
            REPORT_FILE,
            MAX_RESOURCE_FILE_SIZE)));

    private final boolean enabled;
    private final String baseBranch;
    private final String head;
    private final boolean alsoMake;
    private final boolean alsoMakeDependents;
    private final List<String> fullBuildTriggers;
    private final List<String> disableOnBranch;
    private final List<String> disableOnBaseBranch;
    private final List<String> excludePaths;
    private final List<String> includePaths;
    private final List<String> disableTriggers;
    private final boolean disableOnSelectedProjects;
    private final boolean fetchBaseBranch;
    private final boolean skipTestsForUpstream;
    private final List<String> skipTestsForDownstreamModules;
    private final List<String> upstreamArgs;
    private final List<String> downstreamArgs;
    private final boolean uncommitted;
    private final boolean untracked;
    private final List<String> forceBuildModules;
    private final boolean buildAllIfNoChanges;
    private final String impactedLog;
    private final boolean failSafe;
    private final String mode;
    private final boolean explain;
    private final String reportFile;
    private final long maxResourceFileSize;
    private final List<String> warnings;

    private ScalpelConfiguration(
            boolean enabled,
            String baseBranch,
            String head,
            boolean alsoMake,
            boolean alsoMakeDependents,
            List<String> fullBuildTriggers,
            List<String> disableOnBranch,
            List<String> disableOnBaseBranch,
            List<String> excludePaths,
            List<String> includePaths,
            List<String> disableTriggers,
            boolean disableOnSelectedProjects,
            boolean fetchBaseBranch,
            boolean skipTestsForUpstream,
            List<String> skipTestsForDownstreamModules,
            List<String> upstreamArgs,
            List<String> downstreamArgs,
            boolean uncommitted,
            boolean untracked,
            List<String> forceBuildModules,
            boolean buildAllIfNoChanges,
            String impactedLog,
            boolean failSafe,
            String mode,
            boolean explain,
            String reportFile,
            long maxResourceFileSize,
            List<String> warnings) {
        this.enabled = enabled;
        this.baseBranch = baseBranch;
        this.head = head;
        this.alsoMake = alsoMake;
        this.alsoMakeDependents = alsoMakeDependents;
        this.fullBuildTriggers = fullBuildTriggers;
        this.disableOnBranch = disableOnBranch;
        this.disableOnBaseBranch = disableOnBaseBranch;
        this.excludePaths = excludePaths;
        this.includePaths = includePaths;
        this.disableTriggers = disableTriggers;
        this.disableOnSelectedProjects = disableOnSelectedProjects;
        this.fetchBaseBranch = fetchBaseBranch;
        this.skipTestsForUpstream = skipTestsForUpstream;
        this.skipTestsForDownstreamModules = skipTestsForDownstreamModules;
        this.upstreamArgs = upstreamArgs;
        this.downstreamArgs = downstreamArgs;
        this.uncommitted = uncommitted;
        this.untracked = untracked;
        this.forceBuildModules = forceBuildModules;
        this.buildAllIfNoChanges = buildAllIfNoChanges;
        this.impactedLog = impactedLog;
        this.failSafe = failSafe;
        this.mode = mode;
        this.explain = explain;
        this.reportFile = reportFile;
        this.maxResourceFileSize = maxResourceFileSize;
        this.warnings = warnings;
    }

    /**
     * Resolves a configuration from system and user properties.
     *
     * @param system system properties (JVM properties, e.g. from {@code MAVEN_OPTS})
     * @param user user properties (explicit {@code -D} on the command line, including
     *     {@code .mvn/maven.config} entries); take precedence over system properties per the
     *     Maven convention
     * @return the resolved configuration
     * @throws IllegalArgumentException if {@link #MODE} or {@link #MAX_RESOURCE_FILE_SIZE} has an
     *     invalid value, or if any boolean property is set to a value other than
     *     {@code true}/{@code false} (case-insensitive; anything else is rejected, not
     *     silently coerced)
     */
    public static ScalpelConfiguration fromProperties(Properties system, Properties user) {
        boolean enabled = parseStrictBoolean(ENABLED, resolve(system, user, ENABLED, "true"));
        String baseBranch = resolve(system, user, BASE_BRANCH, null);
        if (baseBranch == null) {
            baseBranch = detectBaseBranch(system);
        }
        String head = resolve(system, user, HEAD, "HEAD");
        boolean alsoMake = parseStrictBoolean(ALSO_MAKE, resolve(system, user, ALSO_MAKE, "true"));
        boolean alsoMakeDependents =
                parseStrictBoolean(ALSO_MAKE_DEPENDENTS, resolve(system, user, ALSO_MAKE_DEPENDENTS, "true"));
        String triggers = resolve(system, user, FULL_BUILD_TRIGGERS, DEFAULT_FULL_BUILD_TRIGGERS);
        List<String> fullBuildTriggers = parseList(triggers);
        List<String> disableOnBranch = parseList(resolve(system, user, DISABLE_ON_BRANCH, null));
        List<String> disableOnBaseBranch = parseList(resolve(system, user, DISABLE_ON_BASE_BRANCH, null));
        List<String> excludePaths = parseList(resolve(system, user, EXCLUDE_PATHS, null));
        List<String> includePaths = parseList(resolve(system, user, INCLUDE_PATHS, null));
        List<String> disableTriggers = parseList(resolve(system, user, DISABLE_TRIGGERS, null));
        boolean disableOnSelectedProjects = parseStrictBoolean(
                DISABLE_ON_SELECTED_PROJECTS, resolve(system, user, DISABLE_ON_SELECTED_PROJECTS, "false"));
        boolean fetchBaseBranch =
                parseStrictBoolean(FETCH_BASE_BRANCH, resolve(system, user, FETCH_BASE_BRANCH, "false"));
        boolean skipTestsForUpstream =
                parseStrictBoolean(SKIP_TESTS_FOR_UPSTREAM, resolve(system, user, SKIP_TESTS_FOR_UPSTREAM, "false"));
        List<String> skipTestsForDownstreamModules =
                parseList(resolve(system, user, SKIP_TESTS_FOR_DOWNSTREAM_MODULES, null));
        List<String> upstreamArgs = parseList(resolve(system, user, UPSTREAM_ARGS, null));
        List<String> downstreamArgs = parseList(resolve(system, user, DOWNSTREAM_ARGS, null));
        boolean uncommitted = parseStrictBoolean(UNCOMMITTED, resolve(system, user, UNCOMMITTED, "false"));
        boolean untracked = parseStrictBoolean(UNTRACKED, resolve(system, user, UNTRACKED, "false"));
        List<String> forceBuildModules = parseList(resolve(system, user, FORCE_BUILD_MODULES, null));
        boolean buildAllIfNoChanges =
                parseStrictBoolean(BUILD_ALL_IF_NO_CHANGES, resolve(system, user, BUILD_ALL_IF_NO_CHANGES, "false"));
        String impactedLog = resolve(system, user, IMPACTED_LOG, null);
        boolean failSafe = parseStrictBoolean(FAIL_SAFE, resolve(system, user, FAIL_SAFE, "true"));
        String mode = resolve(system, user, MODE, MODE_TRIM);
        if (!MODE_TRIM.equals(mode) && !MODE_SKIP_TESTS.equals(mode) && !MODE_REPORT.equals(mode)) {
            throw new IllegalArgumentException("Invalid scalpel.mode '" + mode + "', expected one of: " + MODE_TRIM
                    + ", " + MODE_SKIP_TESTS + ", " + MODE_REPORT);
        }
        boolean explain = parseStrictBoolean(EXPLAIN, resolve(system, user, EXPLAIN, "false"));
        String reportFile = resolve(system, user, REPORT_FILE, DEFAULT_REPORT_FILE);
        String maxResourceFileSizeStr = resolve(system, user, MAX_RESOURCE_FILE_SIZE, null);
        long maxResourceFileSize = DEFAULT_MAX_RESOURCE_FILE_SIZE;
        if (maxResourceFileSizeStr != null) {
            try {
                maxResourceFileSize = Long.parseLong(maxResourceFileSizeStr);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid " + MAX_RESOURCE_FILE_SIZE + " '" + maxResourceFileSizeStr
                        + "', expected a positive integer (bytes)");
            }
            if (maxResourceFileSize <= 0) {
                throw new IllegalArgumentException("Invalid " + MAX_RESOURCE_FILE_SIZE + " '" + maxResourceFileSizeStr
                        + "', must be a positive integer (bytes)");
            }
        }

        List<String> warnings = detectUnknownKeys(system, user);

        return new ScalpelConfiguration(
                enabled,
                baseBranch,
                head,
                alsoMake,
                alsoMakeDependents,
                fullBuildTriggers,
                disableOnBranch,
                disableOnBaseBranch,
                excludePaths,
                includePaths,
                disableTriggers,
                disableOnSelectedProjects,
                fetchBaseBranch,
                skipTestsForUpstream,
                skipTestsForDownstreamModules,
                upstreamArgs,
                downstreamArgs,
                uncommitted,
                untracked,
                forceBuildModules,
                buildAllIfNoChanges,
                impactedLog,
                failSafe,
                mode,
                explain,
                reportFile,
                maxResourceFileSize,
                warnings);
    }

    private static String resolve(Properties system, Properties user, String key, String defaultValue) {
        // Maven convention: user properties (CLI -D, .mvn/maven.config) take precedence
        // over system properties (JVM properties, e.g. MAVEN_OPTS). See issue #81.
        String value = user.getProperty(key);
        if (value == null) {
            value = system.getProperty(key);
        }
        return value != null ? value.trim() : defaultValue;
    }

    private static List<String> parseList(String value) {
        if (value == null || value.isEmpty()) {
            return List.of();
        }
        String[] parts = value.split(",");
        List<String> result = new ArrayList<>(parts.length);
        for (String part : parts) {
            result.add(part.trim());
        }
        return Collections.unmodifiableList(result);
    }

    private static boolean parseStrictBoolean(String key, String value) {
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        throw new IllegalArgumentException(
                "Invalid boolean value '" + value + "' for " + key + ". Expected 'true' or 'false'.");
    }

    private static List<String> detectUnknownKeys(Properties system, Properties user) {
        List<String> warnings = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        collectUnknownScalpelKeys(system, seen, warnings);
        collectUnknownScalpelKeys(user, seen, warnings);
        return warnings.isEmpty() ? List.of() : Collections.unmodifiableList(warnings);
    }

    private static void collectUnknownScalpelKeys(Properties props, Set<String> seen, List<String> warnings) {
        for (String key : props.stringPropertyNames()) {
            if (key.startsWith(PREFIX) && !KNOWN_KEYS.contains(key) && seen.add(key)) {
                String closest = findClosestKey(key);
                if (closest != null) {
                    warnings.add("Unknown configuration key '" + key + "'. Did you mean '" + closest + "'?");
                } else {
                    warnings.add("Unknown configuration key '" + key + "'.");
                }
            }
        }
    }

    private static String findClosestKey(String unknown) {
        String best = null;
        int bestDist = Integer.MAX_VALUE;
        for (String known : KNOWN_KEYS) {
            int dist = editDistance(unknown, known);
            if (dist < bestDist) {
                bestDist = dist;
                best = known;
            }
        }
        // Only suggest if the edit distance is at most half the suffix length
        // (after stripping the shared "scalpel." prefix). Without this adjustment,
        // the 8-char shared prefix inflates the threshold and causes unrelated keys
        // like "scalpel.foobar" to incorrectly suggest "scalpel.head".
        if (best == null) {
            return null;
        }
        int suffixLen = Math.max(unknown.length(), best.length()) - PREFIX.length();
        if (bestDist <= suffixLen / 2) {
            return best;
        }
        return null;
    }

    static int editDistance(String a, String b) {
        int lenA = a.length();
        int lenB = b.length();
        int[] prev = new int[lenB + 1];
        int[] curr = new int[lenB + 1];
        for (int j = 0; j <= lenB; j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= lenA; i++) {
            curr[0] = i;
            for (int j = 1; j <= lenB; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[lenB];
    }

    private static String detectBaseBranch(Properties system) {
        // GitHub Actions
        String branch = system.getProperty("env.GITHUB_BASE_REF");
        if (branch != null && !branch.isEmpty()) {
            return "origin/" + branch;
        }
        // GitLab CI
        branch = system.getProperty("env.CI_MERGE_REQUEST_TARGET_BRANCH_NAME");
        if (branch != null && !branch.isEmpty()) {
            return "origin/" + branch;
        }
        // Jenkins
        branch = system.getProperty("env.CHANGE_TARGET");
        if (branch != null && !branch.isEmpty()) {
            return "origin/" + branch;
        }
        return null;
    }

    /** Returns whether the extension is active. Default: {@code true}. */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Returns the branch to diff the head against, or {@code null} if unset (then auto-detected from
     * CI, and if still unset Scalpel runs as a no-op).
     */
    public String getBaseBranch() {
        return baseBranch;
    }

    /** Returns the commit to compare from. Default: {@code HEAD}. */
    public String getHead() {
        return head;
    }

    /** Returns whether upstream dependencies of affected modules are included. Default: {@code true}. */
    public boolean isAlsoMake() {
        return alsoMake;
    }

    /** Returns whether downstream dependents of affected modules are included. Default: {@code true}. */
    public boolean isAlsoMakeDependents() {
        return alsoMakeDependents;
    }

    /** Returns the glob patterns whose match by a changed file triggers a full build. Default: {@code .mvn/**}. */
    public List<String> getFullBuildTriggers() {
        return fullBuildTriggers;
    }

    /** Returns the regex patterns that disable Scalpel when the current branch matches. Default: none. */
    public List<String> getDisableOnBranch() {
        return disableOnBranch;
    }

    /** Returns the regex patterns that disable Scalpel when the base branch name (remote prefix stripped) matches. Default: none. */
    public List<String> getDisableOnBaseBranch() {
        return disableOnBaseBranch;
    }

    /** Returns the glob patterns; changed files matching them are ignored. Default: none. */
    public List<String> getExcludePaths() {
        return excludePaths;
    }

    /** Returns the glob patterns scoping the affected set to matching modules. Default: none. */
    public List<String> getIncludePaths() {
        return includePaths;
    }

    /** Returns the glob patterns; a changed file matching any disables Scalpel entirely. Default: none. */
    public List<String> getDisableTriggers() {
        return disableTriggers;
    }

    /** Returns whether Scalpel is disabled when a {@code -pl} selection is active. Default: {@code false}. */
    public boolean isDisableOnSelectedProjects() {
        return disableOnSelectedProjects;
    }

    /** Returns whether the base branch is fetched from the remote before change detection. Default: {@code false}. */
    public boolean isFetchBaseBranch() {
        return fetchBaseBranch;
    }

    /** Returns whether tests are skipped on modules included only as upstream dependencies. Default: {@code false}. */
    public boolean isSkipTestsForUpstream() {
        return skipTestsForUpstream;
    }

    /** Returns the patterns naming downstream modules whose tests are skipped. Default: none. */
    public List<String> getSkipTestsForDownstreamModules() {
        return skipTestsForDownstreamModules;
    }

    /** Returns the {@code key=value} properties set on upstream-only modules. Default: none. */
    public List<String> getUpstreamArgs() {
        return upstreamArgs;
    }

    /** Returns the {@code key=value} properties set on downstream-only modules. Default: none. */
    public List<String> getDownstreamArgs() {
        return downstreamArgs;
    }

    /** Returns whether staged and unstaged changes are included. Default: {@code false}. */
    public boolean isUncommitted() {
        return uncommitted;
    }

    /** Returns whether untracked files are included in change detection. Default: {@code false}. */
    public boolean isUntracked() {
        return untracked;
    }

    /** Returns the regex patterns; modules whose artifactId matches are always included. Default: none. */
    public List<String> getForceBuildModules() {
        return forceBuildModules;
    }

    /** Returns whether every module is built when no changes are detected. Default: {@code false}. */
    public boolean isBuildAllIfNoChanges() {
        return buildAllIfNoChanges;
    }

    /** Returns the path to write impacted module paths to, or {@code null} if disabled. Default: none. */
    public String getImpactedLog() {
        return impactedLog;
    }

    /** Returns whether errors fall back to a full build instead of failing. Default: {@code true}. */
    public boolean isFailSafe() {
        return failSafe;
    }

    /** Returns whether per-module decision evidence is emitted (explain mode). Default: {@code false}. */
    public boolean isExplain() {
        return explain;
    }

    /** Returns the operating mode: {@code trim}, {@code skip-tests}, or {@code report}. Default: {@code trim}. */
    public String getMode() {
        return mode;
    }

    /** Returns whether the operating mode is {@code trim}; {@code true} by default. */
    public boolean isModeTrim() {
        return MODE_TRIM.equals(mode);
    }

    /** Returns whether the operating mode is {@code skip-tests}; {@code false} by default. */
    public boolean isModeSkipTests() {
        return MODE_SKIP_TESTS.equals(mode);
    }

    /** Returns whether the operating mode is {@code report}; {@code false} by default. */
    public boolean isModeReport() {
        return MODE_REPORT.equals(mode);
    }

    /** Returns the JSON report path, relative to the reactor root. Default: {@code target/scalpel-report.json}. */
    public String getReportFile() {
        return reportFile;
    }

    /**
     * Returns the maximum size in bytes of a git blob read for change analysis (old-POM
     * content at the merge base); larger blobs are skipped with a WARN and treated
     * conservatively as affected. Default: {@link #DEFAULT_MAX_RESOURCE_FILE_SIZE}.
     */
    public long getMaxResourceFileSize() {
        return maxResourceFileSize;
    }

    /**
     * Returns configuration warnings collected during parsing (e.g. unknown {@code scalpel.*} keys
     * with a "did you mean" suggestion). Empty when the configuration is clean.
     */
    public List<String> getWarnings() {
        return warnings;
    }

    @Override
    public String toString() {
        return "ScalpelConfiguration{"
                + "enabled=" + enabled
                + ", baseBranch='" + baseBranch + '\''
                + ", head='" + head + '\''
                + ", mode='" + mode + '\''
                + ", explain=" + explain
                + ", alsoMake=" + alsoMake
                + ", alsoMakeDependents=" + alsoMakeDependents
                + ", fullBuildTriggers=" + fullBuildTriggers
                + ", disableOnBranch=" + disableOnBranch
                + ", disableOnBaseBranch=" + disableOnBaseBranch
                + ", excludePaths=" + excludePaths
                + ", includePaths=" + includePaths
                + ", disableTriggers=" + disableTriggers
                + ", disableOnSelectedProjects=" + disableOnSelectedProjects
                + ", fetchBaseBranch=" + fetchBaseBranch
                + ", skipTestsForUpstream=" + skipTestsForUpstream
                + ", skipTestsForDownstreamModules=" + skipTestsForDownstreamModules
                + ", upstreamArgs=" + upstreamArgs
                + ", downstreamArgs=" + downstreamArgs
                + ", uncommitted=" + uncommitted
                + ", untracked=" + untracked
                + ", forceBuildModules=" + forceBuildModules
                + ", buildAllIfNoChanges=" + buildAllIfNoChanges
                + ", impactedLog='" + impactedLog + '\''
                + ", failSafe=" + failSafe
                + ", reportFile='" + reportFile + '\''
                + ", maxResourceFileSize=" + maxResourceFileSize
                + ", warnings=" + warnings
                + '}';
    }
}
