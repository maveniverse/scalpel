/*
 * Copyright (c) Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.scalpel.extension3.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import java.util.function.LongSupplier;
import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.execution.ExecutionListener;
import org.apache.maven.project.MavenProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shadow-mode build monitor (#92): wraps the session's {@link ExecutionListener}, records
 * per-module wall-clock for the full build that is actually running, and at session end
 * joins the measurement with the would-be trim decision taken earlier in
 * {@code afterProjectsRead}. The join yields the shadow document:
 *
 * <ul>
 *   <li>{@code estimatedSecondsSaved}: summed duration of the modules Scalpel would have
 *       skipped, from a single full build with no control group</li>
 *   <li>{@code wouldHaveSkippedButFailed}: modules Scalpel would have skipped that failed
 *       in the full build, the false-negative counter</li>
 * </ul>
 *
 * Every event is forwarded to the wrapped listener unchanged (a {@code null} delegate is
 * normalized to a no-op). Measurements use the supplied clock so tests are deterministic.
 * Module keys come from the supplied key function, which must produce the same key space
 * as the would-be decision; the lifecycle participant passes its {@code relativePath}
 * helper for both, so the two can never disagree. The shadow document and the JSONL
 * history line are written on {@link #sessionEnded(ExecutionEvent)} even when the build
 * failed, because that is precisely when the false-negative information is valuable.
 */
public final class ShadowBuildMonitor implements ExecutionListener {

    static final String SHADOW_FILE = "target/scalpel-shadow.json";
    static final String HISTORY_FILE = "target/scalpel-shadow-history.jsonl";

    private static final Logger logger = LoggerFactory.getLogger(ShadowBuildMonitor.class);

    /** Absorbs a {@code null} delegate so the event methods can forward unconditionally. */
    private static final ExecutionListener NOOP = new NoopExecutionListener();

    private final ExecutionListener delegate;
    private final Path reactorRoot;
    private final List<String> wouldHaveBuilt;
    private final Set<String> wouldHaveSkipped;
    private final String scalpelVersion;
    private final String baseBranch;
    private final List<String> changedFiles;
    private final LongSupplier nanoClock;
    private final Function<MavenProject, String> moduleKey;

    private final ConcurrentMap<String, Long> startNanos = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Long> durationNanos = new ConcurrentHashMap<>();
    private final Set<String> failedModules = ConcurrentHashMap.newKeySet();

    public ShadowBuildMonitor(
            ExecutionListener delegate,
            Path reactorRoot,
            Collection<String> wouldHaveBuilt,
            Collection<String> wouldHaveSkipped,
            String scalpelVersion,
            String baseBranch,
            Collection<String> changedFiles,
            LongSupplier nanoClock,
            Function<MavenProject, String> moduleKey) {
        this.delegate = delegate == null ? NOOP : delegate;
        this.reactorRoot = reactorRoot;
        this.wouldHaveBuilt = new ArrayList<>(wouldHaveBuilt);
        this.wouldHaveSkipped = new LinkedHashSet<>(wouldHaveSkipped);
        this.scalpelVersion = scalpelVersion;
        this.baseBranch = baseBranch;
        this.changedFiles = changedFiles == null ? List.of() : new ArrayList<>(changedFiles);
        this.nanoClock = nanoClock;
        this.moduleKey = moduleKey;
    }

    // ------------------------------------------------------------------
    // Recording
    // ------------------------------------------------------------------

    @Override
    public void projectStarted(ExecutionEvent event) {
        delegate.projectStarted(event);
        MavenProject project = event.getProject();
        if (project != null) {
            startNanos.put(moduleKey.apply(project), nanoClock.getAsLong());
        }
    }

    @Override
    public void projectSucceeded(ExecutionEvent event) {
        delegate.projectSucceeded(event);
        stopModule(event.getProject());
    }

    @Override
    public void projectFailed(ExecutionEvent event) {
        delegate.projectFailed(event);
        String module = stopModule(event.getProject());
        if (module != null) {
            failedModules.add(module);
        }
    }

    private String stopModule(MavenProject project) {
        if (project == null) {
            return null;
        }
        String module = moduleKey.apply(project);
        Long start = startNanos.remove(module);
        if (start != null) {
            durationNanos.merge(module, Math.max(0L, nanoClock.getAsLong() - start), Long::sum);
        }
        return module;
    }

    // ------------------------------------------------------------------
    // The join
    // ------------------------------------------------------------------

    /** Measured wall-clock for the module, in millis; zero when it was not measured. */
    public long getModuleMillis(String modulePath) {
        return durationNanos.getOrDefault(modulePath, 0L) / 1_000_000;
    }

    /** Modules that failed in the full build. */
    public Set<String> getFailedModules() {
        return new LinkedHashSet<>(failedModules);
    }

    /** Modules Scalpel would have skipped that failed in the full build (false negatives). */
    public Set<String> getWouldHaveSkippedButFailed() {
        Set<String> result = new LinkedHashSet<>();
        for (String module : wouldHaveSkipped) {
            if (failedModules.contains(module)) {
                result.add(module);
            }
        }
        return result;
    }

    /** Summed duration of the would-have-skipped modules, in seconds. */
    public double getEstimatedSecondsSaved() {
        long nanos = 0;
        for (String module : wouldHaveSkipped) {
            nanos += durationNanos.getOrDefault(module, 0L);
        }
        return nanos / 1_000_000_000.0;
    }

    // ------------------------------------------------------------------
    // Artifact writes (session end)
    // ------------------------------------------------------------------

    @Override
    public void sessionEnded(ExecutionEvent event) {
        delegate.sessionEnded(event);
        try {
            writeOutputs();
        } catch (IOException e) {
            // Shadow measurement must never fail the build it is observing.
            logger.warn("Scalpel: Failed to write shadow outputs: {}", e.getMessage());
            logger.debug("Shadow output failure details", e);
        }
    }

    void writeOutputs() throws IOException {
        Set<String> wouldHaveSkippedButFailed = getWouldHaveSkippedButFailed();
        String estimatedSecondsSaved = String.format(Locale.ROOT, "%.3f", getEstimatedSecondsSaved());
        Files.createDirectories(reactorRoot.resolve(SHADOW_FILE).getParent());
        Files.write(
                reactorRoot.resolve(SHADOW_FILE),
                shadowJson(wouldHaveSkippedButFailed, estimatedSecondsSaved).getBytes(StandardCharsets.UTF_8));
        Files.write(
                reactorRoot.resolve(HISTORY_FILE),
                (historyLine(wouldHaveSkippedButFailed, estimatedSecondsSaved) + System.lineSeparator())
                        .getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    private String shadowJson(Set<String> wouldHaveSkippedButFailed, String estimatedSecondsSaved) {
        List<String> fields = new ArrayList<>();
        fields.add(field("version", "1"));
        fields.add(field("mode", "shadow"));
        fields.add(field("scalpelVersion", scalpelVersion));
        fields.add(field("baseBranch", baseBranch));
        fields.add(field("timestamp", Instant.now().toString()));
        fields.add("\"changedFilesCount\": " + changedFiles.size());
        fields.add(arrayField("wouldHaveBuilt", wouldHaveBuilt));
        fields.add(arrayField("wouldHaveSkipped", new ArrayList<>(wouldHaveSkipped)));
        fields.add("\"moduleMillis\": " + moduleMillisJson());
        fields.add("\"estimatedSecondsSaved\": " + estimatedSecondsSaved);
        fields.add(arrayField("wouldHaveSkippedButFailed", new ArrayList<>(wouldHaveSkippedButFailed)));
        return "{\n  " + String.join(",\n  ", fields) + "\n}\n";
    }

    /**
     * Minimal status-only shadow document, the shadow twin of the report's status document
     * (#89 semantics): written whenever a shadow run bails out before the measurement, so a
     * previous run's shadow document cannot be mistaken for current results. The history
     * file is appended only by measured runs, so a gap there means "not measured", never
     * "measured zero".
     */
    public static String statusDocument(String status, String reason) {
        return "{\n"
                + "  \"version\": \"1\",\n"
                + "  \"mode\": \"shadow\",\n"
                + "  \"status\": "
                + jsonString(status)
                + ",\n"
                + "  \"reason\": "
                + jsonString(reason)
                + "\n}\n";
    }

    private String moduleMillisJson() {
        Map<String, Long> moduleMillis = new TreeMap<>();
        for (String module : durationNanos.keySet()) {
            moduleMillis.put(module, getModuleMillis(module));
        }
        if (moduleMillis.isEmpty()) {
            return "{}";
        }
        List<String> entries = new ArrayList<>();
        for (Map.Entry<String, Long> e : moduleMillis.entrySet()) {
            entries.add(jsonString(e.getKey()) + ": " + e.getValue());
        }
        return "{\n    " + String.join(",\n    ", entries) + "\n  }";
    }

    private String historyLine(Set<String> wouldHaveSkippedButFailed, String estimatedSecondsSaved) {
        return "{"
                + "\"timestamp\": " + jsonString(Instant.now().toString())
                + ", \"baseBranch\": " + jsonString(baseBranch)
                + ", \"changedFilesCount\": " + changedFiles.size()
                + ", \"estimatedSecondsSaved\": " + estimatedSecondsSaved
                + ", \"wouldHaveBuiltCount\": " + wouldHaveBuilt.size()
                + ", \"wouldHaveSkipped\": " + jsonStringArray(new ArrayList<>(wouldHaveSkipped))
                + ", \"wouldHaveSkippedButFailed\": "
                + jsonStringArray(new ArrayList<>(wouldHaveSkippedButFailed))
                + "}";
    }

    private static String field(String name, String value) {
        return jsonString(name) + ": " + jsonString(value);
    }

    private static String arrayField(String name, List<String> values) {
        return jsonString(name) + ": " + jsonStringArray(values);
    }

    // Keep in sync with the twins in ScalpelReport: the shadow document and the JSON
    // report must escape and format identically so consumers can parse both the same way.
    private static String jsonStringArray(List<String> values) {
        if (values.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(jsonString(values.get(i)));
        }
        return sb.append("]").toString();
    }

    private static String jsonString(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append("\"").toString();
    }

    // ------------------------------------------------------------------
    // Pure delegation: shadow observation must not change any other behaviour
    // ------------------------------------------------------------------

    @Override
    public void sessionStarted(ExecutionEvent event) {
        delegate.sessionStarted(event);
    }

    @Override
    public void projectDiscoveryStarted(ExecutionEvent event) {
        delegate.projectDiscoveryStarted(event);
    }

    @Override
    public void projectSkipped(ExecutionEvent event) {
        delegate.projectSkipped(event);
    }

    @Override
    public void mojoStarted(ExecutionEvent event) {
        delegate.mojoStarted(event);
    }

    @Override
    public void mojoSucceeded(ExecutionEvent event) {
        delegate.mojoSucceeded(event);
    }

    @Override
    public void mojoFailed(ExecutionEvent event) {
        delegate.mojoFailed(event);
    }

    @Override
    public void mojoSkipped(ExecutionEvent event) {
        delegate.mojoSkipped(event);
    }

    @Override
    public void forkStarted(ExecutionEvent event) {
        delegate.forkStarted(event);
    }

    @Override
    public void forkSucceeded(ExecutionEvent event) {
        delegate.forkSucceeded(event);
    }

    @Override
    public void forkFailed(ExecutionEvent event) {
        delegate.forkFailed(event);
    }

    @Override
    public void forkedProjectStarted(ExecutionEvent event) {
        delegate.forkedProjectStarted(event);
    }

    @Override
    public void forkedProjectSucceeded(ExecutionEvent event) {
        delegate.forkedProjectSucceeded(event);
    }

    @Override
    public void forkedProjectFailed(ExecutionEvent event) {
        delegate.forkedProjectFailed(event);
    }

    private static final class NoopExecutionListener implements ExecutionListener {
        @Override
        public void projectDiscoveryStarted(ExecutionEvent event) {}

        @Override
        public void sessionStarted(ExecutionEvent event) {}

        @Override
        public void sessionEnded(ExecutionEvent event) {}

        @Override
        public void projectSkipped(ExecutionEvent event) {}

        @Override
        public void projectStarted(ExecutionEvent event) {}

        @Override
        public void projectSucceeded(ExecutionEvent event) {}

        @Override
        public void projectFailed(ExecutionEvent event) {}

        @Override
        public void mojoSkipped(ExecutionEvent event) {}

        @Override
        public void mojoStarted(ExecutionEvent event) {}

        @Override
        public void mojoSucceeded(ExecutionEvent event) {}

        @Override
        public void mojoFailed(ExecutionEvent event) {}

        @Override
        public void forkStarted(ExecutionEvent event) {}

        @Override
        public void forkSucceeded(ExecutionEvent event) {}

        @Override
        public void forkFailed(ExecutionEvent event) {}

        @Override
        public void forkedProjectStarted(ExecutionEvent event) {}

        @Override
        public void forkedProjectSucceeded(ExecutionEvent event) {}

        @Override
        public void forkedProjectFailed(ExecutionEvent event) {}
    }
}
