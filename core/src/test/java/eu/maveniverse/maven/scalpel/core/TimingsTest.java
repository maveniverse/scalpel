/*
 * Copyright (c) Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.scalpel.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TimingsTest {

    @Test
    void startStopRecordsPhaseMillis() throws Exception {
        Timings timings = new Timings();
        timings.start(Timings.PHASE_DIFF);
        Thread.sleep(5);
        timings.stop(Timings.PHASE_DIFF);

        assertTrue(timings.getPhaseNames().contains(Timings.PHASE_DIFF), "stopped phase must be recorded");
        assertTrue(
                timings.getPhaseMillis(Timings.PHASE_DIFF) >= 5,
                "recorded millis must cover the slept time, was: " + timings.getPhaseMillis(Timings.PHASE_DIFF));
    }

    @Test
    void repeatedStartStopAccumulatesIntoSamePhase() throws Exception {
        Timings timings = new Timings();
        for (int i = 0; i < 2; i++) {
            timings.start(Timings.PHASE_DIFF);
            Thread.sleep(5);
            timings.stop(Timings.PHASE_DIFF);
        }

        assertEquals(1, timings.getPhaseNames().size(), "one phase name, not one per measurement");
        assertTrue(
                timings.getPhaseMillis(Timings.PHASE_DIFF) >= 10,
                "both measurement rounds must accumulate, was: " + timings.getPhaseMillis(Timings.PHASE_DIFF));
    }

    @Test
    void stopWithoutStartIsNoOp() {
        Timings timings = new Timings();
        timings.stop(Timings.PHASE_DIFF);

        assertTrue(timings.getPhaseNames().isEmpty(), "stop without start must record nothing");
        assertEquals(0, timings.getPhaseMillis(Timings.PHASE_DIFF));
    }

    @Test
    void runningPhaseReportsElapsedSoFar() throws Exception {
        Timings timings = new Timings();
        timings.start(Timings.PHASE_DIFF);
        Thread.sleep(5);

        assertTrue(
                timings.getPhaseMillis(Timings.PHASE_DIFF) >= 5,
                "a still-running phase must report elapsed millis so far");
        timings.stop(Timings.PHASE_DIFF);
    }

    @Test
    void incrementCountsOperations() {
        Timings timings = new Timings();
        timings.increment(Timings.OP_GIT_BLOBS_READ);
        timings.increment(Timings.OP_GIT_BLOBS_READ);
        timings.increment(Timings.OP_DEPENDENCY_RESOLVES, 7);

        assertEquals(2, timings.getOperationCount(Timings.OP_GIT_BLOBS_READ));
        assertEquals(7, timings.getOperationCount(Timings.OP_DEPENDENCY_RESOLVES));
        assertEquals(0, timings.getOperationCount(Timings.OP_RESOURCES_VISITED), "uncounted operations read as 0");
    }

    @Test
    void nonPositiveDeltasAreIgnored() {
        Timings timings = new Timings();
        timings.increment(Timings.OP_RESOURCES_VISITED, 0);
        timings.increment(Timings.OP_RESOURCES_VISITED, -3);

        assertTrue(timings.getOperationNames().isEmpty(), "zero/negative deltas must not create counter entries");
    }

    @Test
    void toStringFormatsPhasesInFirstSeenOrder() {
        Timings timings = new Timings();
        timings.start(Timings.PHASE_REPO_OPEN);
        timings.stop(Timings.PHASE_REPO_OPEN);
        timings.start(Timings.PHASE_FETCH);
        timings.stop(Timings.PHASE_FETCH);

        assertTrue(
                timings.toString().matches("repoOpen=\\d+ms, fetch=\\d+ms"),
                "phases format must be greppable name=millis in first-seen order, was: " + timings);
    }

    @Test
    void formatOperationsCountsInFirstSeenOrder() {
        Timings timings = new Timings();
        timings.increment(Timings.OP_GIT_BLOBS_READ, 3);
        timings.increment(Timings.OP_DEPENDENCY_RESOLVES);

        assertEquals("blobs=3, resolves=1", timings.formatOperations());
    }

    @Test
    void emptyTimingsFormatsAsEmptyStrings() {
        Timings timings = new Timings();

        assertEquals("", timings.toString());
        assertEquals("", timings.formatOperations());
    }
}
