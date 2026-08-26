/*
 * Copyright (c) Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.scalpel.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class ScalpelReport {

    /**
     * Report schema version. Bump this whenever the report JSON structure changes
     * (new fields, removed fields, or semantic changes to existing fields).
     * <p>
     * v1 → v2: added {@code category}, {@code sourceSet}, {@code excludedUpstreamCount},
     *           {@code testsSkipped}, {@code testsSkippedReason}.
     */
    public static final String REPORT_VERSION = "2";

    public static final String REASON_SOURCE_CHANGE = "SOURCE_CHANGE";
    public static final String REASON_POM_CHANGE = "POM_CHANGE";
    public static final String REASON_TRANSITIVE_DEPENDENCY = "TRANSITIVE_DEPENDENCY";
    public static final String REASON_MANAGED_PLUGIN = "MANAGED_PLUGIN";
    public static final String REASON_FORCE_BUILD = "FORCE_BUILD";
    /**
     * @deprecated Upstream modules are no longer included in report mode (see #39).
     *             They are build-order prerequisites, not genuinely affected modules.
     */
    @Deprecated
    public static final String REASON_UPSTREAM_DEPENDENCY = "UPSTREAM_DEPENDENCY";

    public static final String REASON_DOWNSTREAM_DEPENDENT = "DOWNSTREAM_DEPENDENT";
    public static final String REASON_TEST_CHANGE = "TEST_CHANGE";
    public static final String REASON_DOWNSTREAM_TEST = "DOWNSTREAM_TEST";
    public static final String REASON_TRANSITIVE_DEPENDENCY_TEST = "TRANSITIVE_DEPENDENCY_TEST";
    public static final String REASON_EXCLUDED_DOWNSTREAM = "EXCLUDED_DOWNSTREAM";

    /**
     * Skip reason for a reactor module that was left out of the build set because it is
     * not affected by the changeset (not direct, transitive, upstream or downstream).
     */
    public static final String SKIP_REASON_NOT_AFFECTED = "NOT_AFFECTED";

    public static final String CATEGORY_DIRECT = "DIRECT";
    public static final String CATEGORY_UPSTREAM = "UPSTREAM";
    public static final String CATEGORY_DOWNSTREAM = "DOWNSTREAM";
    public static final String CATEGORY_TRANSITIVE = "TRANSITIVE";

    private final String baseBranch;
    private final String status;
    private final String reason;
    private final boolean fullBuildTriggered;
    private final String triggerFile;
    private final List<String> changedFiles;
    private final List<String> changedProperties;
    private final List<String> changedManagedDependencies;
    private final List<String> changedManagedPlugins;
    private final List<String> unmatchedPomPaths;
    private final List<AffectedModule> affectedModules;
    private final List<SkippedModule> skippedModules;
    private final int excludedUpstreamCount;

    private ScalpelReport(
            String baseBranch,
            String status,
            String reason,
            boolean fullBuildTriggered,
            String triggerFile,
            List<String> changedFiles,
            List<String> changedProperties,
            List<String> changedManagedDependencies,
            List<String> changedManagedPlugins,
            List<String> unmatchedPomPaths,
            List<AffectedModule> affectedModules,
            List<SkippedModule> skippedModules,
            int excludedUpstreamCount) {
        this.baseBranch = baseBranch;
        this.status = status;
        this.reason = reason;
        this.fullBuildTriggered = fullBuildTriggered;
        this.triggerFile = triggerFile;
        this.changedFiles = changedFiles;
        this.changedProperties = changedProperties;
        this.changedManagedDependencies = changedManagedDependencies;
        this.changedManagedPlugins = changedManagedPlugins;
        this.unmatchedPomPaths = unmatchedPomPaths;
        this.affectedModules = affectedModules;
        this.skippedModules = skippedModules;
        this.excludedUpstreamCount = excludedUpstreamCount;
    }

    public static class AffectedModule {
        private final String groupId;
        private final String artifactId;
        private final String path;
        private final List<String> reasons;
        private final String category;
        private final String sourceSet;
        private final String testsSkippedReason;
        private final List<String> evidence;

        public AffectedModule(String groupId, String artifactId, String path, List<String> reasons) {
            this(groupId, artifactId, path, reasons, null, null, null);
        }

        public AffectedModule(String groupId, String artifactId, String path, List<String> reasons, String category) {
            this(groupId, artifactId, path, reasons, category, null, null);
        }

        public AffectedModule(
                String groupId,
                String artifactId,
                String path,
                List<String> reasons,
                String category,
                String sourceSet) {
            this(groupId, artifactId, path, reasons, category, sourceSet, null);
        }

        public AffectedModule(
                String groupId,
                String artifactId,
                String path,
                List<String> reasons,
                String category,
                String sourceSet,
                String testsSkippedReason) {
            this(groupId, artifactId, path, reasons, category, sourceSet, testsSkippedReason, null);
        }

        public AffectedModule(
                String groupId,
                String artifactId,
                String path,
                List<String> reasons,
                String category,
                String sourceSet,
                String testsSkippedReason,
                List<String> evidence) {
            if (sourceSet != null && !"main".equals(sourceSet) && !"test".equals(sourceSet)) {
                throw new IllegalArgumentException("sourceSet must be 'main', 'test', or null");
            }
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.path = path;
            this.reasons = reasons;
            this.category = category;
            this.sourceSet = sourceSet;
            this.testsSkippedReason = testsSkippedReason;
            this.evidence = evidence;
        }

        public String getGroupId() {
            return groupId;
        }

        public String getArtifactId() {
            return artifactId;
        }

        public String getPath() {
            return path;
        }

        public List<String> getReasons() {
            return reasons;
        }

        public String getCategory() {
            return category;
        }

        public String getSourceSet() {
            return sourceSet;
        }

        public String getTestsSkippedReason() {
            return testsSkippedReason;
        }

        public List<String> getEvidence() {
            return evidence;
        }

        public static ModuleBuilder moduleBuilder(
                String groupId, String artifactId, String path, List<String> reasons) {
            return new ModuleBuilder(groupId, artifactId, path, reasons);
        }

        public static class ModuleBuilder {
            private final String groupId;
            private final String artifactId;
            private final String path;
            private final List<String> reasons;
            private String category;
            private String sourceSet;
            private String testsSkippedReason;
            private List<String> evidence;

            ModuleBuilder(String groupId, String artifactId, String path, List<String> reasons) {
                this.groupId = groupId;
                this.artifactId = artifactId;
                this.path = path;
                this.reasons = reasons;
            }

            public ModuleBuilder category(String category) {
                this.category = category;
                return this;
            }

            public ModuleBuilder sourceSet(String sourceSet) {
                this.sourceSet = sourceSet;
                return this;
            }

            public ModuleBuilder testsSkippedReason(String testsSkippedReason) {
                this.testsSkippedReason = testsSkippedReason;
                return this;
            }

            public ModuleBuilder evidence(List<String> evidence) {
                this.evidence = evidence;
                return this;
            }

            public AffectedModule build() {
                return new AffectedModule(
                        groupId, artifactId, path, reasons, category, sourceSet, testsSkippedReason, evidence);
            }
        }
    }

    /**
     * A reactor module that was left out of the build set, with the reason it was judged
     * safe to skip. Enumerating this set is what makes a green trimmed build reviewable.
     */
    public static final class SkippedModule {
        private final String groupId;
        private final String artifactId;
        private final String path;
        private final String reason;

        public SkippedModule(String groupId, String artifactId, String path, String reason) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.path = path;
            this.reason = reason;
        }

        public String getGroupId() {
            return groupId;
        }

        public String getArtifactId() {
            return artifactId;
        }

        public String getPath() {
            return path;
        }

        public String getReason() {
            return reason;
        }
    }

    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"version\": ").append(jsonString(REPORT_VERSION)).append(",\n");
        sb.append("  \"scalpelVersion\": ")
                .append(jsonString(Version.version()))
                .append(",\n");
        sb.append("  \"baseBranch\": ").append(jsonString(baseBranch)).append(",\n");
        if (status != null) {
            sb.append("  \"status\": ").append(jsonString(status)).append(",\n");
        }
        if (reason != null) {
            sb.append("  \"reason\": ").append(jsonString(reason)).append(",\n");
        }
        sb.append("  \"fullBuildTriggered\": ").append(fullBuildTriggered).append(",\n");
        sb.append("  \"triggerFile\": ").append(jsonString(triggerFile)).append(",\n");
        sb.append("  \"changedFiles\": ").append(jsonStringArray(changedFiles)).append(",\n");
        sb.append("  \"changedProperties\": ")
                .append(jsonStringArray(changedProperties))
                .append(",\n");
        sb.append("  \"changedManagedDependencies\": ")
                .append(jsonStringArray(changedManagedDependencies))
                .append(",\n");
        sb.append("  \"changedManagedPlugins\": ")
                .append(jsonStringArray(changedManagedPlugins))
                .append(",\n");
        if (unmatchedPomPaths != null && !unmatchedPomPaths.isEmpty()) {
            sb.append("  \"unmatchedPomPaths\": ")
                    .append(jsonStringArray(unmatchedPomPaths))
                    .append(",\n");
        }
        sb.append("  \"excludedUpstreamCount\": ").append(excludedUpstreamCount).append(",\n");
        sb.append("  \"affectedModules\": ");
        if (affectedModules.isEmpty()) {
            sb.append("[]");
        } else {
            sb.append("[\n");
            for (int i = 0; i < affectedModules.size(); i++) {
                appendModuleJson(sb, affectedModules.get(i));
                if (i < affectedModules.size() - 1) {
                    sb.append(",");
                }
                sb.append("\n");
            }
            sb.append("  ]");
        }
        if (!skippedModules.isEmpty()) {
            sb.append(",\n");
            sb.append("  \"skippedModules\": [\n");
            for (int i = 0; i < skippedModules.size(); i++) {
                appendSkippedModuleJson(sb, skippedModules.get(i));
                if (i < skippedModules.size() - 1) {
                    sb.append(",");
                }
                sb.append("\n");
            }
            sb.append("  ]");
        }
        sb.append("\n}\n");
        return sb.toString();
    }

    private static void appendSkippedModuleJson(StringBuilder sb, SkippedModule m) {
        sb.append("    {\n");
        sb.append("      \"groupId\": ").append(jsonString(m.groupId)).append(",\n");
        sb.append("      \"artifactId\": ").append(jsonString(m.artifactId)).append(",\n");
        sb.append("      \"path\": ").append(jsonString(m.path)).append(",\n");
        sb.append("      \"reason\": ").append(jsonString(m.reason)).append("\n");
        sb.append("    }");
    }

    private static void appendModuleJson(StringBuilder sb, AffectedModule m) {
        sb.append("    {\n");
        sb.append("      \"groupId\": ").append(jsonString(m.groupId)).append(",\n");
        sb.append("      \"artifactId\": ").append(jsonString(m.artifactId)).append(",\n");
        sb.append("      \"path\": ").append(jsonString(m.path)).append(",\n");
        sb.append("      \"reasons\": ").append(jsonStringArray(m.reasons));
        appendOptionalField(sb, "category", m.category);
        appendOptionalField(sb, "sourceSet", m.sourceSet);
        if (m.testsSkippedReason != null) {
            sb.append(",\n");
            sb.append("      \"testsSkipped\": true");
            appendOptionalField(sb, "testsSkippedReason", m.testsSkippedReason);
        }
        if (m.evidence != null) {
            sb.append(",\n");
            sb.append("      \"evidence\": ").append(jsonStringArray(m.evidence));
        }
        sb.append("\n");
        sb.append("    }");
    }

    private static void appendOptionalField(StringBuilder sb, String name, String value) {
        if (value != null) {
            sb.append(",\n");
            sb.append("      \"").append(name).append("\": ").append(jsonString(value));
        }
    }

    public void writeToFile(Path reactorRoot, String reportFile) throws IOException {
        Path path = reactorRoot.resolve(reportFile);
        Files.createDirectories(path.getParent());
        Files.write(path, toJson().getBytes(StandardCharsets.UTF_8));
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
                        sb.append("\\u%04x".formatted((int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append("\"");
        return sb.toString();
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
        sb.append("]");
        return sb.toString();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String baseBranch;
        private String status;
        private String reason;
        private boolean fullBuildTriggered;
        private String triggerFile;
        private final List<String> changedFiles = new ArrayList<>();
        private final List<String> changedProperties = new ArrayList<>();
        private final List<String> changedManagedDependencies = new ArrayList<>();
        private final List<String> changedManagedPlugins = new ArrayList<>();
        private final List<String> unmatchedPomPaths = new ArrayList<>();
        private final List<AffectedModule> affectedModules = new ArrayList<>();
        private final List<SkippedModule> skippedModules = new ArrayList<>();
        private int excludedUpstreamCount;

        public Builder baseBranch(String baseBranch) {
            this.baseBranch = baseBranch;
            return this;
        }

        public Builder status(String status) {
            this.status = status;
            return this;
        }

        public Builder reason(String reason) {
            this.reason = reason;
            return this;
        }

        public Builder fullBuildTriggered(boolean fullBuildTriggered) {
            this.fullBuildTriggered = fullBuildTriggered;
            return this;
        }

        public Builder triggerFile(String triggerFile) {
            this.triggerFile = triggerFile;
            return this;
        }

        public Builder changedFiles(Collection<String> files) {
            this.changedFiles.addAll(files);
            return this;
        }

        public Builder changedProperties(Collection<String> properties) {
            this.changedProperties.addAll(properties);
            return this;
        }

        public Builder changedManagedDependencies(Collection<String> deps) {
            this.changedManagedDependencies.addAll(deps);
            return this;
        }

        public Builder changedManagedPlugins(Collection<String> plugins) {
            this.changedManagedPlugins.addAll(plugins);
            return this;
        }

        public Builder unmatchedPomPaths(Collection<String> paths) {
            this.unmatchedPomPaths.addAll(paths);
            return this;
        }

        public Builder addAffectedModule(AffectedModule module) {
            this.affectedModules.add(module);
            return this;
        }

        public Builder addSkippedModule(SkippedModule module) {
            this.skippedModules.add(module);
            return this;
        }

        public Builder excludedUpstreamCount(int count) {
            this.excludedUpstreamCount = count;
            return this;
        }

        public ScalpelReport build() {
            if (baseBranch == null) {
                throw new IllegalStateException("baseBranch is required");
            }
            return new ScalpelReport(
                    baseBranch,
                    status,
                    reason,
                    fullBuildTriggered,
                    triggerFile,
                    changedFiles,
                    changedProperties,
                    changedManagedDependencies,
                    changedManagedPlugins,
                    unmatchedPomPaths,
                    affectedModules,
                    skippedModules,
                    excludedUpstreamCount);
        }
    }
}
