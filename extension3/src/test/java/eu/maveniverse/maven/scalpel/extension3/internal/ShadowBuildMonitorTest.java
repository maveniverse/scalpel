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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Proxy;
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
 * session end (#92). Module keys come from the injected key function; these fixtures set
 * artifactId to the module path, so {@code MavenProject::getArtifactId} is the key
 * function. The production key function (the participant's relativePath helper) is
 * exercised end to end by the shadow integration tests.
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

    /** A delegate that records every callback name, so delegation transparency is asserted, not assumed. */
    private static ExecutionListener recordingDelegate(List<String> calls) {
        return (ExecutionListener) Proxy.newProxyInstance(
                ExecutionListener.class.getClassLoader(),
                new Class<?>[] {ExecutionListener.class},
                (proxy, method, args) -> {
                    calls.add(method.getName());
                    return null;
                });
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

            public MojoExecution getMojoExecution() {
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
        List<String> delegateCalls = new ArrayList<>();
        Path reactorRoot = tmp.resolve("reactor");
        Files.createDirectories(reactorRoot);

        // module-b would have been built; module-a and module-c would have been skipped.
        ShadowBuildMonitor monitor = new ShadowBuildMonitor(
                recordingDelegate(delegateCalls),
                reactorRoot,
                Arrays.asList("module-b"),
                Arrays.asList("module-a", "module-c"),
                "0.3.11",
                "base",
                Arrays.asList("module-b/src/Foo.java"),
                clock,
                MavenProject::getArtifactId);

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
        assertTrue(delegateCalls.contains("sessionStarted"));
        assertTrue(delegateCalls.contains("sessionEnded"));
        assertTrue(delegateCalls.containsAll(Arrays.asList("projectStarted", "projectSucceeded", "projectFailed")));

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
        // Drift guard: the emitted top-level fields are exactly this set, in this order
        java.util.regex.Matcher topLevel = java.util.regex.Pattern.compile(
                        "^  \"([A-Za-z]+)\":", java.util.regex.Pattern.MULTILINE)
                .matcher(json);
        List<String> emitted = new ArrayList<>();
        while (topLevel.find()) {
            emitted.add(topLevel.group(1));
        }
        assertEquals(
                Arrays.asList(
                        "version",
                        "mode",
                        "scalpelVersion",
                        "baseBranch",
                        "timestamp",
                        "changedFilesCount",
                        "wouldHaveBuilt",
                        "wouldHaveSkipped",
                        "moduleMillis",
                        "estimatedSecondsSaved",
                        "wouldHaveSkippedButFailed"),
                emitted);

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
                    clock,
                    MavenProject::getArtifactId);
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
                new SteppingClock(),
                MavenProject::getArtifactId);

        monitor.sessionEnded(event(ExecutionEvent.Type.SessionEnded, null));

        assertEquals(0L, monitor.getModuleMillis("module-a"), "unmeasured module reports zero");
        assertEquals(0.0, monitor.getEstimatedSecondsSaved(), 1e-9);
        assertTrue(monitor.getFailedModules().isEmpty());
        assertTrue(monitor.getWouldHaveSkippedButFailed().isEmpty());

        Path shadowJson = reactorRoot.resolve("target/scalpel-shadow.json");
        assertTrue(Files.exists(shadowJson), "shadow json is still written with nothing measured");
        assertFalse(Files.readString(shadowJson).contains("estimatedSecondsSaved\": null"), "savings stay numeric");
    }
}
