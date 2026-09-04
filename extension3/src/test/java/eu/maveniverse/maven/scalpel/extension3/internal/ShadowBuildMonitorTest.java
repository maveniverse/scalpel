/*
 * Copyright (c) Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.scalpel.extension3.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.LongSupplier;

import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.execution.ExecutionListener;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for the shadow-mode build monitor: per-module wall-clock recording, the
 * would-have-skipped join (estimatedSecondsSaved, wouldHaveSkippedButFailed), transparent
 * delegation to any pre-existing ExecutionListener, and the shadow artifacts written at
 * session end (#92).
 */
class ShadowBuildMonitorTest {

    /** Deterministic clock: each read advances time by a fixed 100ms step. */
    private static final class SteppingClock implements LongSupplier {
        private long nanos;

        public long getAsLong() {
            long now = nanos;
            nanos += 100_000_000L; // 100ms
            return now;
        }
    }

    /** Records every callback so delegation transparency is asserted, not assumed. */
    private static final class RecordingDelegate implements ExecutionListener {
        final List<String> calls = new ArrayList<>();

        private void rec(String method) {
            calls.add(method);
        }

        public void sessionStarted(ExecutionEvent event) {
            rec("sessionStarted");
        }

        public void sessionEnded(ExecutionEvent event) {
            rec("sessionEnded");
        }

        public void projectStarted(ExecutionEvent event) {
            rec("projectStarted");
        }

        public void projectSucceeded(ExecutionEvent event) {
            rec("projectSucceeded");
        }

        public void projectFailed(ExecutionEvent event) {
            rec("projectFailed");
        }

        public void mojoStarted(ExecutionEvent event) {
            rec("mojoStarted");
        }

        public void mojoSucceeded(ExecutionEvent event) {
            rec("mojoSucceeded");
        }

        public void mojoFailed(ExecutionEvent event) {
            rec("mojoFailed");
        }

        public void forkStarted(ExecutionEvent event) {
            rec("forkStarted");
        }

        public void forkSucceeded(ExecutionEvent event) {
            rec("forkSucceeded");
        }

        public void forkFailed(ExecutionEvent event) {
            rec("forkFailed");
        }

        public void forkedProjectStarted(ExecutionEvent event) {
            rec("forkedProjectStarted");
        }

        public void forkedProjectSucceeded(ExecutionEvent event) {
            rec("forkedProjectSucceeded");
        }

        public void forkedProjectFailed(ExecutionEvent event) {
            rec("forkedProjectFailed");
        }
    }

    private static MavenProject project(Path reactorRoot, String relativePath) {
        MavenProject p = new MavenProject();
        p.setGroupId("com.example");
        p.setArtifactId(relativePath);
        p.setVersion("1.0");
        p.setFile(reactorRoot.resolve(relativePath).resolve("pom.xml").toFile());
        return p;
    }

    private static ExecutionEvent event(ExecutionEvent.Type type, MavenProject project) {
        return new ExecutionEvent() {
            public ExecutionEvent.Type getType() {
                return type;
            }

            public MavenSession getSession() {
                return null;
            }

            public MavenProject getProject() {
                return project;
            }

            public MojoExecution getMojo() {
                return null;
            }

            public Exception getException() {
                return null;
            }
        };
    }

    @Test
    void recordsDurationsJoinsSkippedSetAndDelegates(@TempDir Path tmp) throws IOException {
        SteppingClock clock = new SteppingClock();
        RecordingDelegate delegate = new RecordingDelegate();
        Path reactorRoot = tmp.resolve("reactor");
        Files.createDirectories(reactorRoot);

        // module-b would have been built; module-a and module-c would have been skipped.
        ShadowBuildMonitor monitor = new ShadowBuildMonitor(
                delegate,
                reactorRoot,
                Arrays.asList("module-b"),
                Arrays.asList("module-a", "module-c"),
                "0.3.11",
                "base",
                Arrays.asList("module-b/src/Foo.java"),
                clock);

        MavenProject a = project(reactorRoot, "module-a");
        MavenProject b = project(reactorRoot, "module-b");
        MavenProject c = project(reactorRoot, "module-c");

        monitor.sessionStarted(event(ExecutionEvent.Type.SessionStarted, null));
        monitor.projectStarted(event(ExecutionEvent.Type.ProjectStarted, a));
        monitor.projectStarted(event(ExecutionEvent.Type.ProjectStarted, b));
        monitor.projectSucceeded(event(ExecutionEvent.Type.ProjectSucceeded, b));
        monitor.projectSucceeded(event(ExecutionEvent.Type.ProjectSucceeded, a));
        monitor.projectStarted(event(ExecutionEvent.Type.ProjectStarted, c));
        monitor.projectFailed(event(ExecutionEvent.Type.ProjectFailed, c));
        monitor.sessionEnded(event(ExecutionEvent.Type.SessionEnded, null));

        // Per-module wall-clock: a ran 0ms->300ms (300ms), b 100ms->200ms (100ms), c 400ms->500ms (100ms)
        assertEquals(300L, monitor.getModuleMillis("module-a"));
        assertEquals(100L, monitor.getModuleMillis("module-b"));
        assertEquals(100L, monitor.getModuleMillis("module-c"));

        // Failure recording
        assertEquals(Set.of("module-c"), monitor.getFailedModules());

        // The join: only skipped modules count toward savings, only failed+skipped are false negatives
        assertEquals(Set.of("module-c"), monitor.getWouldHaveSkippedButFailed());
        assertEquals(0.4, monitor.getEstimatedSecondsSaved(), 1e-9);

        // Delegation: every event reached the wrapped listener untouched
        assertTrue(delegate.calls.contains("sessionStarted"));
        assertTrue(delegate.calls.contains("sessionEnded"));
        assertTrue(delegate.calls.containsAll(Arrays.asList("projectStarted", "projectSucceeded", "projectFailed")));

        // Artifacts written at session end
        Path shadowJson = reactorRoot.resolve("target/scalpel-shadow.json");
        assertTrue(Files.exists(shadowJson), "shadow json must be written at session end");
        String json = Files.readString(shadowJson);
        assertTrue(json.contains("\"mode\": \"shadow\""));
        assertTrue(json.contains("\"wouldHaveSkipped\": ["));
        assertTrue(json.contains("module-a"));
        assertTrue(json.contains("\"estimatedSecondsSaved\": 0.400"));
        assertTrue(json.contains("\"wouldHaveSkippedButFailed\": ["));
        assertTrue(json.contains("module-c"));

        Path history = reactorRoot.resolve("target/scalpel-shadow-history.jsonl");
        assertTrue(Files.exists(history), "history jsonl must be appended at session end");
        List<String> lines = Files.readAllLines(history);
        assertEquals(1, lines.size(), "one run must append exactly one jsonl line");
        assertTrue(lines.get(0).contains("module-a"));
    }

    @Test
    void appendsOneHistoryLinePerRun(@TempDir Path tmp) throws IOException {
        SteppingClock clock = new SteppingClock();
        Path reactorRoot = tmp.resolve("reactor");
        Files.createDirectories(reactorRoot);

        for (int run = 0; run < 2; run++) {
            ShadowBuildMonitor monitor = new ShadowBuildMonitor(
                    null,
                    reactorRoot,
                    Arrays.asList("module-b"),
                    Arrays.asList("module-a"),
                    "0.3.11",
                    "base",
                    Arrays.asList("module-b/src/Foo.java"),
                    clock);
            MavenProject b = project(reactorRoot, "module-b");
            monitor.projectStarted(event(ExecutionEvent.Type.ProjectStarted, b));
            monitor.projectSucceeded(event(ExecutionEvent.Type.ProjectSucceeded, b));
            monitor.sessionEnded(event(ExecutionEvent.Type.SessionEnded, null));
        }

        Path history = reactorRoot.resolve("target/scalpel-shadow-history.jsonl");
        List<String> lines = Files.readAllLines(history);
        assertEquals(2, lines.size(), "each shadow run must append one line, never truncate");
    }

    @Test
    void toleratesNullDelegateAndMissingMeasurements(@TempDir Path tmp) throws IOException {
        Path reactorRoot = tmp.resolve("reactor");
        Files.createDirectories(reactorRoot);
        ShadowBuildMonitor monitor = new ShadowBuildMonitor(
                null,
                reactorRoot,
                Arrays.asList("module-b"),
                Arrays.asList("module-a"),
                "0.3.11",
                null,
                null,
                new SteppingClock());

        monitor.sessionEnded(event(ExecutionEvent.Type.SessionEnded, null));

        assertEquals(0L, monitor.getModuleMillis("module-a"), "unmeasured module reports zero");
        assertEquals(0.0, monitor.getEstimatedSecondsSaved(), 1e-9);
        assertTrue(monitor.getFailedModules().isEmpty());
        assertTrue(monitor.getWouldHaveSkippedButFailed().isEmpty());

        Path shadowJson = reactorRoot.resolve("target/scalpel-shadow.json");
        assertTrue(Files.exists(shadowJson), "shadow json is still written with nothing measured");
        String json = Files.readString(shadowJson);
        assertNotNull(json);
        assertFalse(json.contains("estimatedSecondsSaved\": null"), "savings stay numeric");
    }
}
