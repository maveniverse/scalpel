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
            boolean failSafe,
            String mode,
            String reportFile) {
        this.enabled = enabled;
        this.baseBranch = baseBranch;
        this.head = head;
        this.alsoMake = alsoMake;
        this.alsoMakeDependents = alsoMakeDependents;
        this.fullBuildTriggers = fullBuildTriggers;
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
        List<String> fullBuildTriggers = triggers != null && !triggers.isEmpty()
                ? Arrays.asList(triggers.split(","))
                : Collections.<String>emptyList();
        boolean failSafe = Boolean.parseBoolean(resolve(system, user, FAIL_SAFE, "true"));
        String mode = resolve(system, user, MODE, MODE_TRIM);
        String reportFile = resolve(system, user, REPORT_FILE, DEFAULT_REPORT_FILE);

        return new ScalpelConfiguration(
                enabled, baseBranch, head, alsoMake, alsoMakeDependents, fullBuildTriggers, failSafe, mode, reportFile);
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
                + ", failSafe=" + failSafe
                + ", reportFile='" + reportFile + '\''
                + '}';
    }
}
