/*
 * Copyright (c) Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.scalpel.extension3.internal;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The would-be trim decision handed to the {@link ShadowBuildMonitor} (#92, #101): the build
 * set trim mode would have kept and the modules it would have skipped, each skippable module
 * with the reason it was judged safe to skip, plus the stable {@code decisionId} and whether
 * the run is a verification run (fail the build when a would-have-skipped module fails).
 *
 * <p>{@code upstreamOnly} (#222) carries the upstream build prerequisites the trim would have
 * kept solely to build their dependents' inputs. They sit inside {@code wouldHaveBuilt}, so
 * their failures are invisible to {@code wouldHaveSkippedButFailed}; the monitor joins them
 * with the failed modules into {@code upstreamOnlyTestFailures}, the measurement a consumer
 * needs before adopting {@code skipTestsForUpstream}.
 */
final class ShadowDecision {

    private final Collection<String> wouldHaveBuilt;
    private final Collection<String> wouldHaveSkipped;
    private final Map<String, String> skipReasons;
    private final Set<String> upstreamOnly;
    private final String decisionId;
    private final boolean verify;

    ShadowDecision(
            Collection<String> wouldHaveBuilt,
            Collection<String> wouldHaveSkipped,
            Map<String, String> skipReasons,
            Collection<String> upstreamOnly,
            String decisionId,
            boolean verify) {
        this.wouldHaveBuilt = wouldHaveBuilt;
        this.wouldHaveSkipped = wouldHaveSkipped;
        this.skipReasons = new LinkedHashMap<>(skipReasons);
        this.upstreamOnly = new LinkedHashSet<>(upstreamOnly);
        this.decisionId = decisionId;
        this.verify = verify;
    }

    Collection<String> getWouldHaveBuilt() {
        return wouldHaveBuilt;
    }

    Collection<String> getWouldHaveSkipped() {
        return wouldHaveSkipped;
    }

    Map<String, String> getSkipReasons() {
        return skipReasons;
    }

    Set<String> getUpstreamOnly() {
        return upstreamOnly;
    }

    String getDecisionId() {
        return decisionId;
    }

    boolean isVerify() {
        return verify;
    }

    static ShadowDecision measuring(
            Collection<String> wouldHaveBuilt,
            Collection<String> wouldHaveSkipped,
            Collection<String> upstreamOnly,
            String decisionId) {
        return new ShadowDecision(
                wouldHaveBuilt, wouldHaveSkipped, new LinkedHashMap<>(), upstreamOnly, decisionId, false);
    }

    static ShadowDecision verifying(
            Collection<String> wouldHaveBuilt,
            Collection<String> wouldHaveSkipped,
            Map<String, String> skipReasons,
            Collection<String> upstreamOnly,
            String decisionId) {
        return new ShadowDecision(wouldHaveBuilt, wouldHaveSkipped, skipReasons, upstreamOnly, decisionId, true);
    }
}
