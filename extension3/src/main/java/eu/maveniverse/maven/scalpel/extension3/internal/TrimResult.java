/*
 * Copyright (c) Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.scalpel.extension3.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.maven.project.MavenProject;

/**
 * Result of computing the build set, categorizing modules as directly affected,
 * upstream-only (added via alsoMake), downstream-only (added via alsoMakeDependents),
 * or downstream-test-only (downstream via test-scoped dependency).
 */
final class TrimResult {

    private final List<MavenProject> buildSet;
    private final Set<MavenProject> directlyAffected;
    private final Set<MavenProject> upstreamOnly;
    private final Set<MavenProject> downstreamOnly;
    private final Set<MavenProject> downstreamTestOnly;
    private final Map<MavenProject, java.util.List<String>> buildReasons;

    TrimResult(
            List<MavenProject> buildSet,
            Set<MavenProject> directlyAffected,
            Set<MavenProject> upstreamOnly,
            Set<MavenProject> downstreamOnly) {
        this(buildSet, directlyAffected, upstreamOnly, downstreamOnly, Set.of());
    }

    TrimResult(
            List<MavenProject> buildSet,
            Set<MavenProject> directlyAffected,
            Set<MavenProject> upstreamOnly,
            Set<MavenProject> downstreamOnly,
            Set<MavenProject> downstreamTestOnly) {
        this(buildSet, directlyAffected, upstreamOnly, downstreamOnly, downstreamTestOnly, Map.of());
    }

    TrimResult(
            List<MavenProject> buildSet,
            Set<MavenProject> directlyAffected,
            Set<MavenProject> upstreamOnly,
            Set<MavenProject> downstreamOnly,
            Set<MavenProject> downstreamTestOnly,
            Map<MavenProject, java.util.List<String>> buildReasons) {
        this.buildSet = Collections.unmodifiableList(new ArrayList<>(buildSet));
        this.directlyAffected = Collections.unmodifiableSet(new LinkedHashSet<>(directlyAffected));
        this.upstreamOnly = Collections.unmodifiableSet(new LinkedHashSet<>(upstreamOnly));
        this.downstreamOnly = Collections.unmodifiableSet(new LinkedHashSet<>(downstreamOnly));
        this.downstreamTestOnly = Collections.unmodifiableSet(new LinkedHashSet<>(downstreamTestOnly));
        this.buildReasons = Collections.unmodifiableMap(new LinkedHashMap<>(buildReasons));
    }

    List<MavenProject> getBuildSet() {
        return buildSet;
    }

    Set<MavenProject> getDirectlyAffected() {
        return directlyAffected;
    }

    Set<MavenProject> getUpstreamOnly() {
        return upstreamOnly;
    }

    Set<MavenProject> getDownstreamOnly() {
        return downstreamOnly;
    }

    Set<MavenProject> getDownstreamTestOnly() {
        return downstreamTestOnly;
    }

    /**
     * Why each non-direct module is in the build set (explain-mode evidence),
     * e.g. "downstream of g:a" or "upstream of g:a".
     */
    Map<MavenProject, java.util.List<String>> getBuildReasons() {
        return buildReasons;
    }
}
