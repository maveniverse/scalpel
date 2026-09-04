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
 * Every event is forwarded to the wrapped listener unchanged, and a {@code null} delegate
 * is tolerated. Measurements use the supplied clock so tests are deterministic; the module
 * key is the module path relative to the reactor root, the same key space as the would-be
 * decision. The shadow document and the JSONL history line are written on
 * {@link #sessionEnded(ExecutionEvent)} even when the build failed, because that is
 * precisely when the false-negative information is valuable.
 */
public final class ShadowBuildMonitor implements ExecutionListener {

    static final String SHADOW_FILE = "target/scalpel-shadow.json";
    static final String HISTORY_FILE = "target/scalpel-shadow-history.jsonl";

    private static final Logger logger = LoggerFactory.getLogger(ShadowBuildMonitor.class);

    private final ExecutionListener delegate;
    private final Path reactorRoot;
    private final List<String> wouldHaveBuilt;
    private final Set<String> wouldHaveSkipped;
    private final String scalpelVersion;
    private final String baseBranch;
    private final List<String> changedFiles;
    private final LongSupplier nanoClock;

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
            LongSupplier nanoClock) {
        this.delegate = delegate;
        this.reactorRoot = reactorRoot;
        this.wouldHaveBuilt = new ArrayList<>(wouldHaveBuilt);
        this.wouldHaveSkipped = new LinkedHashSet<>(wouldHaveSkipped);
        this.scalpelVersion = scalpelVersion;
        this.baseBranch = baseBranch;
        this.changedFiles = changedFiles == null ? List.of() : new ArrayList<>(changedFiles);
        this.nanoClock = nanoClock;
    }

    // ------------------------------------------------------------------
    // Recording
    // ------------------------------------------------------------------

    @Override
    public void projectStarted(ExecutionEvent event) {
        if (delegate != null) {
            delegate.projectStarted(event);
        }
        String module = moduleKey(event.getProject());
        if (module != null) {
            startNanos.put(module, nanoClock.getAsLong());
        }
    }

    @Override
    public void projectSucceeded(ExecutionEvent event) {
        if (delegate != null) {
            delegate.projectSucceeded(event);
        }
        stopModule(event.getProject());
    }

    @Override
    public void projectFailed(ExecutionEvent event) {
        if (delegate != null) {
            delegate.projectFailed(event);
        }
        String module = stopModule(event.getProject());
        if (module != null) {
            failedModules.add(module);
        }
    }

    private String stopModule(MavenProject project) {
        String module = moduleKey(project);
        if (module != null) {
            Long start = startNanos.remove(module);
            if (start != null) {
                durationNanos.merge(module, Math.max(0L, nanoClock.getAsLong() - start), Long::sum);
            }
        }
        return module;
    }

    private String moduleKey(MavenProject project) {
        if (project == null) {
            return null;
        }
        if (project.getFile() != null) {
            Path moduleDir = project.getFile().getParentFile().toPath();
            String relative = reactorRoot.relativize(moduleDir).toString().replace('\\', '/');
            return relative.isEmpty() ? "." : relative;
        }
        return project.getArtifactId();
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
        if (delegate != null) {
            delegate.sessionEnded(event);
        }
        try {
            writeOutputs();
        } catch (IOException e) {
            // Shadow measurement must never fail the build it is observing.
            logger.warn("Scalpel: Failed to write shadow outputs: {}", e.getMessage());
            logger.debug("Shadow output failure details", e);
        }
    }

    void writeOutputs() throws IOException {
        Files.createDirectories(reactorRoot.resolve(SHADOW_FILE).getParent());
        Files.write(reactorRoot.resolve(SHADOW_FILE), shadowJson().getBytes(StandardCharsets.UTF_8));
        String historyLine = historyLine();
        Files.write(
                reactorRoot.resolve(HISTORY_FILE),
                (historyLine + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    private String shadowJson() {
        Map<String, Long> moduleMillis = new TreeMap<>();
        for (String module : durationNanos.keySet()) {
            moduleMillis.put(module, getModuleMillis(module));
        }
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        appendField(sb, "version", "1");
        appendField(sb, "mode", "shadow");
        appendField(sb, "scalpelVersion", scalpelVersion);
        appendField(sb, "baseBranch", baseBranch);
        appendField(sb, "timestamp", Instant.now().toString());
        appendField(sb, "changedFilesCount", (long) changedFiles.size());
        appendStringArrayField(sb, "wouldHaveBuilt", wouldHaveBuilt);
        appendStringArrayField(sb, "wouldHaveSkipped", new ArrayList<>(wouldHaveSkipped));
        sb.append("  \"moduleMillis\": {");
        if (!moduleMillis.isEmpty()) {
            sb.append("\n");
            int i = 0;
            for (Map.Entry<String, Long> e : moduleMillis.entrySet()) {
                sb.append("    ").append(jsonString(e.getKey())).append(": ").append(e.getValue());
                if (++i < moduleMillis.size()) {
                    sb.append(",");
                }
                sb.append("\n");
            }
            sb.append("  },\n");
        } else {
            sb.append("},\n");
        }
        sb.append("  \"estimatedSecondsSaved\": ")
                .append(String.format(Locale.ROOT, "%.3f", getEstimatedSecondsSaved()))
                .append(",\n");
        appendStringArrayField(sb, "wouldHaveSkippedButFailed", new ArrayList<>(getWouldHaveSkippedButFailed()));
        sb.setLength(sb.length() - 2); // drop trailing ",\n" of the last field
        sb.append("\n}\n");
        return sb.toString();
    }

    private String historyLine() {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"timestamp\": ").append(jsonString(Instant.now().toString()));
        sb.append(", \"baseBranch\": ").append(jsonString(baseBranch));
        sb.append(", \"changedFilesCount\": ").append(changedFiles.size());
        sb.append(", \"estimatedSecondsSaved\": ")
                .append(String.format(Locale.ROOT, "%.3f", getEstimatedSecondsSaved()));
        sb.append(", \"wouldHaveBuiltCount\": ").append(wouldHaveBuilt.size());
        sb.append(", \"wouldHaveSkipped\": ").append(jsonStringArray(new ArrayList<>(wouldHaveSkipped)));
        sb.append(", \"wouldHaveSkippedButFailed\": ")
                .append(jsonStringArray(new ArrayList<>(getWouldHaveSkippedButFailed())));
        sb.append("}");
        return sb.toString();
    }

    private static void appendField(StringBuilder sb, String name, String value) {
        sb.append("  ")
                .append(jsonString(name))
                .append(": ")
                .append(jsonString(value))
                .append(",\n");
    }

    private static void appendField(StringBuilder sb, String name, long value) {
        sb.append("  ").append(jsonString(name)).append(": ").append(value).append(",\n");
    }

    private static void appendStringArrayField(StringBuilder sb, String name, List<String> values) {
        sb.append("  ")
                .append(jsonString(name))
                .append(": ")
                .append(jsonStringArray(values))
                .append(",\n");
    }

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
        if (delegate != null) {
            delegate.sessionStarted(event);
        }
    }

    @Override
    public void projectDiscoveryStarted(ExecutionEvent event) {
        if (delegate != null) {
            delegate.projectDiscoveryStarted(event);
        }
    }

    @Override
    public void projectSkipped(ExecutionEvent event) {
        if (delegate != null) {
            delegate.projectSkipped(event);
        }
    }

    @Override
    public void mojoStarted(ExecutionEvent event) {
        if (delegate != null) {
            delegate.mojoStarted(event);
        }
    }

    @Override
    public void mojoSucceeded(ExecutionEvent event) {
        if (delegate != null) {
            delegate.mojoSucceeded(event);
        }
    }

    @Override
    public void mojoFailed(ExecutionEvent event) {
        if (delegate != null) {
            delegate.mojoFailed(event);
        }
    }

    @Override
    public void mojoSkipped(ExecutionEvent event) {
        if (delegate != null) {
            delegate.mojoSkipped(event);
        }
    }

    @Override
    public void forkStarted(ExecutionEvent event) {
        if (delegate != null) {
            delegate.forkStarted(event);
        }
    }

    @Override
    public void forkSucceeded(ExecutionEvent event) {
        if (delegate != null) {
            delegate.forkSucceeded(event);
        }
    }

    @Override
    public void forkFailed(ExecutionEvent event) {
        if (delegate != null) {
            delegate.forkFailed(event);
        }
    }

    @Override
    public void forkedProjectStarted(ExecutionEvent event) {
        if (delegate != null) {
            delegate.forkedProjectStarted(event);
        }
    }

    @Override
    public void forkedProjectSucceeded(ExecutionEvent event) {
        if (delegate != null) {
            delegate.forkedProjectSucceeded(event);
        }
    }

    @Override
    public void forkedProjectFailed(ExecutionEvent event) {
        if (delegate != null) {
            delegate.forkedProjectFailed(event);
        }
    }
}
