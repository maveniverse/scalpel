/*
 * Copyright (c) Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.scalpel.core;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Named-phase timing and operation-count instrumentation for the Scalpel analysis path, so
 * "how long did Scalpel take and where did the time go" is answerable from a build log (#99).
 * Phases accumulate across repeated {@link #start}/{@link #stop} cycles; operation counters
 * accumulate across {@link #increment} calls. One instance is meant to travel through a single
 * analysis run and is not thread-safe (the analysis runs on the Maven session thread).
 */
public final class Timings {

    // Phases recorded by ScalpelCore.detectChanges
    public static final String PHASE_REPO_OPEN = "repoOpen";
    public static final String PHASE_FETCH = "fetch";
    public static final String PHASE_MERGE_BASE = "mergeBase";
    public static final String PHASE_DIFF = "diff";
    public static final String PHASE_STATUS = "status";
    public static final String PHASE_READ_OLD_POMS = "readOldPoms";

    // Phases recorded by ScalpelLifecycleParticipant.afterProjectsRead
    public static final String PHASE_MODULE_MAPPING = "moduleMapping";
    public static final String PHASE_POM_ANALYSIS = "pomAnalysis";
    public static final String PHASE_TRANSITIVE_RESOLVE = "transitiveResolve";
    public static final String PHASE_TRIM = "trim";
    public static final String PHASE_APPLY_SKIP_TESTS = "applySkipTests";

    // Operation counters
    public static final String OP_GIT_BLOBS_READ = "blobs";
    public static final String OP_EFFECTIVE_MODELS = "models";
    public static final String OP_DEPENDENCY_RESOLVES = "resolves";
    public static final String OP_RESOLVE_CACHE_HITS = "resolveCacheHits";
    public static final String OP_RESOURCES_VISITED = "resources";

    private final Map<String, Long> phaseNanos = new LinkedHashMap<>();
    private final Map<String, Long> runningSince = new LinkedHashMap<>();
    private final Map<String, Long> operationCounts = new LinkedHashMap<>();
    private final Set<String> phaseOrder = new LinkedHashSet<>();

    /**
     * Starts (or restarts) the named phase; a phase started twice without an intervening
     * {@link #stop} discards the earlier measurement start.
     */
    public void start(String phase) {
        phaseOrder.add(phase);
        runningSince.put(phase, System.nanoTime());
    }

    /**
     * Stops the named phase and accumulates its elapsed time. Stopping a phase that is not
     * running is a no-op, so stop can safely sit in a {@code finally} on every exit path.
     */
    public void stop(String phase) {
        Long start = runningSince.remove(phase);
        if (start != null) {
            phaseNanos.merge(phase, System.nanoTime() - start, Long::sum);
        }
    }

    public void increment(String operation) {
        operationCounts.merge(operation, 1L, Long::sum);
    }

    /** Adds {@code delta} to the counter; non-positive deltas are ignored. */
    public void increment(String operation, long delta) {
        if (delta > 0) {
            operationCounts.merge(operation, delta, Long::sum);
        }
    }

    /**
     * Millis accumulated for the phase so far, including a measurement that is still running.
     */
    public long getPhaseMillis(String phase) {
        long nanos = phaseNanos.getOrDefault(phase, 0L);
        Long start = runningSince.get(phase);
        if (start != null) {
            nanos += System.nanoTime() - start;
        }
        return nanos / 1_000_000;
    }

    /** Recorded phase names in start order, whether stopped or still running. */
    public Set<String> getPhaseNames() {
        return new LinkedHashSet<>(phaseOrder);
    }

    public long getOperationCount(String operation) {
        return operationCounts.getOrDefault(operation, 0L);
    }

    /** Counted operation names in first-seen order. */
    public Set<String> getOperationNames() {
        return new LinkedHashSet<>(operationCounts.keySet());
    }

    /**
     * Formats the phase breakdown for logging, e.g. {@code repoOpen=1ms, fetch=50ms};
     * empty when no phase was ever recorded.
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (String phase : getPhaseNames()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(phase).append('=').append(getPhaseMillis(phase)).append("ms");
        }
        return sb.toString();
    }

    /**
     * Formats the operation counters for logging, e.g. {@code blobs=4, models=6};
     * empty when nothing was counted.
     */
    public String formatOperations() {
        StringBuilder sb = new StringBuilder();
        for (String operation : getOperationNames()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(operation).append('=').append(getOperationCount(operation));
        }
        return sb.toString();
    }
}
