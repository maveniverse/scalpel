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
import java.util.Map;

/**
 * The would-be trim decision handed to the {@link ShadowBuildMonitor} (#92, #101): the build
 * set trim mode would have kept and the modules it would have skipped, each skippable module
 * with the reason it was judged safe to skip, plus the stable {@code decisionId} and whether
 * the run is a verification run (fail the build when a would-have-skipped module fails).
 */
final class ShadowDecision {

    private final Collection<String> wouldHaveBuilt;
    private final Collection<String> wouldHaveSkipped;
    private final Map<String, String> skipReasons;
    private final String decisionId;
    private final boolean verify;

    ShadowDecision(
            Collection<String> wouldHaveBuilt,
            Collection<String> wouldHaveSkipped,
            Map<String, String> skipReasons,
            String decisionId,
            boolean verify) {
        this.wouldHaveBuilt = wouldHaveBuilt;
        this.wouldHaveSkipped = wouldHaveSkipped;
        this.skipReasons = new LinkedHashMap<>(skipReasons);
        this.decisionId = decisionId;
        this.verify = verify;
    }

    Collection<String> getWouldHaveBuilt() {
        return wouldHaveBuilt;
    }

    Collection<String> getWouldHaveSkipped() {
        return wouldHaveSkipped;
    }

    /** The skip reason for a would-have-skipped module path, for the verify failure report. */
    String skipReasonFor(String modulePath) {
        return skipReasons.getOrDefault(modulePath, "NOT_AFFECTED");
    }

    Map<String, String> getSkipReasons() {
        return skipReasons;
    }

    String getDecisionId() {
        return decisionId;
    }

    boolean isVerify() {
        return verify;
    }

    static ShadowDecision measuring(
            Collection<String> wouldHaveBuilt, Collection<String> wouldHaveSkipped, String decisionId) {
        return new ShadowDecision(wouldHaveBuilt, wouldHaveSkipped, new LinkedHashMap<>(), decisionId, false);
    }

    static ShadowDecision verifying(
            Collection<String> wouldHaveBuilt,
            Collection<String> wouldHaveSkipped,
            Map<String, String> skipReasons,
            String decisionId) {
        return new ShadowDecision(wouldHaveBuilt, wouldHaveSkipped, skipReasons, decisionId, true);
    }
}
