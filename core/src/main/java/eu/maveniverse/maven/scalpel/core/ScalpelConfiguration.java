/*
 * Copyright (c) Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.scalpel.core;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

public final class ScalpelConfiguration {

    private static final String PREFIX = "scalpel.";

    public static final String ENABLED = PREFIX + "enabled";
    public static final String BASE_BRANCH = PREFIX + "baseBranch";
    public static final String HEAD = PREFIX + "head";
    public static final String ALSO_MAKE = PREFIX + "alsoMake";
    public static final String ALSO_MAKE_DEPENDENTS = PREFIX + "alsoMakeDependents";
    public static final String FULL_BUILD_TRIGGERS = PREFIX + "fullBuildTriggers";
    public static final String FAIL_SAFE = PREFIX + "failSafe";
    public static final String MODE = PREFIX + "mode";

    public static final String DISABLE_ON_BRANCH = PREFIX + "disableOnBranch";
    public static final String DISABLE_ON_BASE_BRANCH = PREFIX + "disableOnBaseBranch";
    public static final String EXCLUDE_PATHS = PREFIX + "excludePaths";
    public static final String DISABLE_TRIGGERS = PREFIX + "disableTriggers";

    public static final String DISABLE_ON_SELECTED_PROJECTS = PREFIX + "disableOnSelectedProjects";

    public static final String SKIP_TESTS_FOR_UPSTREAM = PREFIX + "skipTestsForUpstream";
    public static final String UPSTREAM_ARGS = PREFIX + "upstreamArgs";
    public static final String DOWNSTREAM_ARGS = PREFIX + "downstreamArgs";

    public static final String FETCH_BASE_BRANCH = PREFIX + "fetchBaseBranch";

    public static final String UNCOMMITTED = PREFIX + "uncommitted";
    public static final String UNTRACKED = PREFIX + "untracked";

    public static final String FORCE_BUILD_MODULES = PREFIX + "forceBuildModules";
    public static final String BUILD_ALL_IF_NO_CHANGES = PREFIX + "buildAllIfNoChanges";
    public static final String IMPACTED_LOG = PREFIX + "impactedLog";
    public static final String REPORT_FILE = PREFIX + "reportFile";

    public static final String MODE_TRIM = "trim";
    public static final String MODE_SKIP_TESTS = "skip-tests";
    public static final String MODE_REPORT = "report";

    private static final String DEFAULT_FULL_BUILD_TRIGGERS = ".mvn/**";
    private static final String DEFAULT_REPORT_FILE = "target/scalpel-report.json";

    private final boolean enabled;
    private final String baseBranch;
    private final String head;
    private final boolean alsoMake;
    private final boolean alsoMakeDependents;
    private final List<String> fullBuildTriggers;
    private final List<String> disableOnBranch;
    private final List<String> disableOnBaseBranch;
    private final List<String> excludePaths;
    private final List<String> disableTriggers;
    private final boolean disableOnSelectedProjects;
    private final boolean fetchBaseBranch;
    private final boolean skipTestsForUpstream;
    private final List<String> upstreamArgs;
    private final List<String> downstreamArgs;
    private final boolean uncommitted;
    private final boolean untracked;
    private final List<String> forceBuildModules;
    private final boolean buildAllIfNoChanges;
    private final String impactedLog;
    private final boolean failSafe;
    private final String mode;
    private final String reportFile;

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
            List<String> disableTriggers,
            boolean disableOnSelectedProjects,
            boolean fetchBaseBranch,
            boolean skipTestsForUpstream,
            List<String> upstreamArgs,
            List<String> downstreamArgs,
            boolean uncommitted,
            boolean untracked,
            List<String> forceBuildModules,
            boolean buildAllIfNoChanges,
            String impactedLog,
            boolean failSafe,
            String mode,
            String reportFile) {
        this.enabled = enabled;
        this.baseBranch = baseBranch;
        this.head = head;
        this.alsoMake = alsoMake;
        this.alsoMakeDependents = alsoMakeDependents;
        this.fullBuildTriggers = fullBuildTriggers;
        this.disableOnBranch = disableOnBranch;
        this.disableOnBaseBranch = disableOnBaseBranch;
        this.excludePaths = excludePaths;
        this.disableTriggers = disableTriggers;
        this.disableOnSelectedProjects = disableOnSelectedProjects;
        this.fetchBaseBranch = fetchBaseBranch;
        this.skipTestsForUpstream = skipTestsForUpstream;
        this.upstreamArgs = upstreamArgs;
        this.downstreamArgs = downstreamArgs;
        this.uncommitted = uncommitted;
        this.untracked = untracked;
        this.forceBuildModules = forceBuildModules;
        this.buildAllIfNoChanges = buildAllIfNoChanges;
        this.impactedLog = impactedLog;
        this.failSafe = failSafe;
        this.mode = mode;
        this.reportFile = reportFile;
    }

    public static ScalpelConfiguration fromProperties(Properties system, Properties user) {
        boolean enabled = Boolean.parseBoolean(resolve(system, user, ENABLED, "true"));
        String baseBranch = resolve(system, user, BASE_BRANCH, null);
        if (baseBranch == null) {
            baseBranch = detectBaseBranch(system);
        }
        String head = resolve(system, user, HEAD, "HEAD");
        boolean alsoMake = Boolean.parseBoolean(resolve(system, user, ALSO_MAKE, "true"));
        boolean alsoMakeDependents = Boolean.parseBoolean(resolve(system, user, ALSO_MAKE_DEPENDENTS, "true"));
        String triggers = resolve(system, user, FULL_BUILD_TRIGGERS, DEFAULT_FULL_BUILD_TRIGGERS);
        List<String> fullBuildTriggers = parseList(triggers);
        List<String> disableOnBranch = parseList(resolve(system, user, DISABLE_ON_BRANCH, null));
        List<String> disableOnBaseBranch = parseList(resolve(system, user, DISABLE_ON_BASE_BRANCH, null));
        List<String> excludePaths = parseList(resolve(system, user, EXCLUDE_PATHS, null));
        List<String> disableTriggers = parseList(resolve(system, user, DISABLE_TRIGGERS, null));
        boolean disableOnSelectedProjects =
                Boolean.parseBoolean(resolve(system, user, DISABLE_ON_SELECTED_PROJECTS, "false"));
        boolean fetchBaseBranch = Boolean.parseBoolean(resolve(system, user, FETCH_BASE_BRANCH, "false"));
        boolean skipTestsForUpstream = Boolean.parseBoolean(resolve(system, user, SKIP_TESTS_FOR_UPSTREAM, "false"));
        List<String> upstreamArgs = parseList(resolve(system, user, UPSTREAM_ARGS, null));
        List<String> downstreamArgs = parseList(resolve(system, user, DOWNSTREAM_ARGS, null));
        boolean uncommitted = Boolean.parseBoolean(resolve(system, user, UNCOMMITTED, "false"));
        boolean untracked = Boolean.parseBoolean(resolve(system, user, UNTRACKED, "false"));
        List<String> forceBuildModules = parseList(resolve(system, user, FORCE_BUILD_MODULES, null));
        boolean buildAllIfNoChanges = Boolean.parseBoolean(resolve(system, user, BUILD_ALL_IF_NO_CHANGES, "false"));
        String impactedLog = resolve(system, user, IMPACTED_LOG, null);
        boolean failSafe = Boolean.parseBoolean(resolve(system, user, FAIL_SAFE, "true"));
        String mode = resolve(system, user, MODE, MODE_TRIM);
        String reportFile = resolve(system, user, REPORT_FILE, DEFAULT_REPORT_FILE);

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
                disableTriggers,
                disableOnSelectedProjects,
                fetchBaseBranch,
                skipTestsForUpstream,
                upstreamArgs,
                downstreamArgs,
                uncommitted,
                untracked,
                forceBuildModules,
                buildAllIfNoChanges,
                impactedLog,
                failSafe,
                mode,
                reportFile);
    }

    private static String resolve(Properties system, Properties user, String key, String defaultValue) {
        String value = system.getProperty(key);
        if (value != null) {
            return value;
        }
        value = user.getProperty(key);
        if (value != null) {
            return value;
        }
        return defaultValue;
    }

    private static List<String> parseList(String value) {
        if (value == null || value.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(Arrays.asList(value.split(",")));
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

    public boolean isEnabled() {
        return enabled;
    }

    public String getBaseBranch() {
        return baseBranch;
    }

    public String getHead() {
        return head;
    }

    public boolean isAlsoMake() {
        return alsoMake;
    }

    public boolean isAlsoMakeDependents() {
        return alsoMakeDependents;
    }

    public List<String> getFullBuildTriggers() {
        return fullBuildTriggers;
    }

    public List<String> getDisableOnBranch() {
        return disableOnBranch;
    }

    public List<String> getDisableOnBaseBranch() {
        return disableOnBaseBranch;
    }

    public List<String> getExcludePaths() {
        return excludePaths;
    }

    public List<String> getDisableTriggers() {
        return disableTriggers;
    }

    public boolean isDisableOnSelectedProjects() {
        return disableOnSelectedProjects;
    }

    public boolean isFetchBaseBranch() {
        return fetchBaseBranch;
    }

    public boolean isSkipTestsForUpstream() {
        return skipTestsForUpstream;
    }

    public List<String> getUpstreamArgs() {
        return upstreamArgs;
    }

    public List<String> getDownstreamArgs() {
        return downstreamArgs;
    }

    public boolean isUncommitted() {
        return uncommitted;
    }

    public boolean isUntracked() {
        return untracked;
    }

    public List<String> getForceBuildModules() {
        return forceBuildModules;
    }

    public boolean isBuildAllIfNoChanges() {
        return buildAllIfNoChanges;
    }

    public String getImpactedLog() {
        return impactedLog;
    }

    public boolean isFailSafe() {
        return failSafe;
    }

    public String getMode() {
        return mode;
    }

    public boolean isModeTrim() {
        return MODE_TRIM.equals(mode);
    }

    public boolean isModeSkipTests() {
        return MODE_SKIP_TESTS.equals(mode);
    }

    public boolean isModeReport() {
        return MODE_REPORT.equals(mode);
    }

    public String getReportFile() {
        return reportFile;
    }

    @Override
    public String toString() {
        return "ScalpelConfiguration{"
                + "enabled=" + enabled
                + ", baseBranch='" + baseBranch + '\''
                + ", head='" + head + '\''
                + ", mode='" + mode + '\''
                + ", alsoMake=" + alsoMake
                + ", alsoMakeDependents=" + alsoMakeDependents
                + ", fullBuildTriggers=" + fullBuildTriggers
                + ", disableOnBranch=" + disableOnBranch
                + ", disableOnBaseBranch=" + disableOnBaseBranch
                + ", excludePaths=" + excludePaths
                + ", disableTriggers=" + disableTriggers
                + ", disableOnSelectedProjects=" + disableOnSelectedProjects
                + ", fetchBaseBranch=" + fetchBaseBranch
                + ", skipTestsForUpstream=" + skipTestsForUpstream
                + ", upstreamArgs=" + upstreamArgs
                + ", downstreamArgs=" + downstreamArgs
                + ", uncommitted=" + uncommitted
                + ", untracked=" + untracked
                + ", forceBuildModules=" + forceBuildModules
                + ", buildAllIfNoChanges=" + buildAllIfNoChanges
                + ", impactedLog='" + impactedLog + '\''
                + ", failSafe=" + failSafe
                + ", reportFile='" + reportFile + '\''
                + '}';
    }
}
