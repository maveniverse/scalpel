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

    public static final String REASON_SOURCE_CHANGE = "SOURCE_CHANGE";
    public static final String REASON_POM_CHANGE = "POM_CHANGE";
    public static final String REASON_TRANSITIVE_DEPENDENCY = "TRANSITIVE_DEPENDENCY";
    public static final String REASON_MANAGED_PLUGIN = "MANAGED_PLUGIN";
    public static final String REASON_FORCE_BUILD = "FORCE_BUILD";
    public static final String REASON_UPSTREAM_DEPENDENCY = "UPSTREAM_DEPENDENCY";
    public static final String REASON_DOWNSTREAM_DEPENDENT = "DOWNSTREAM_DEPENDENT";
    public static final String REASON_TEST_CHANGE = "TEST_CHANGE";
    public static final String REASON_DOWNSTREAM_TEST = "DOWNSTREAM_TEST";
    public static final String REASON_TRANSITIVE_DEPENDENCY_TEST = "TRANSITIVE_DEPENDENCY_TEST";

    public static final String CATEGORY_DIRECT = "DIRECT";
    public static final String CATEGORY_UPSTREAM = "UPSTREAM";
    public static final String CATEGORY_DOWNSTREAM = "DOWNSTREAM";

    private final String baseBranch;
    private final boolean fullBuildTriggered;
    private final String triggerFile;
    private final List<String> changedFiles;
    private final List<String> changedProperties;
    private final List<String> changedManagedDependencies;
    private final List<String> changedManagedPlugins;
    private final List<AffectedModule> affectedModules;

    private ScalpelReport(
            String baseBranch,
            boolean fullBuildTriggered,
            String triggerFile,
            List<String> changedFiles,
            List<String> changedProperties,
            List<String> changedManagedDependencies,
            List<String> changedManagedPlugins,
            List<AffectedModule> affectedModules) {
        this.baseBranch = baseBranch;
        this.fullBuildTriggered = fullBuildTriggered;
        this.triggerFile = triggerFile;
        this.changedFiles = changedFiles;
        this.changedProperties = changedProperties;
        this.changedManagedDependencies = changedManagedDependencies;
        this.changedManagedPlugins = changedManagedPlugins;
        this.affectedModules = affectedModules;
    }

    public static class AffectedModule {
        private final String groupId;
        private final String artifactId;
        private final String path;
        private final List<String> reasons;
        private final String category;

        public AffectedModule(String groupId, String artifactId, String path, List<String> reasons) {
            this(groupId, artifactId, path, reasons, null);
        }

        public AffectedModule(String groupId, String artifactId, String path, List<String> reasons, String category) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.path = path;
            this.reasons = reasons;
            this.category = category;
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
    }

    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"version\": \"1\",\n");
        sb.append("  \"scalpelVersion\": ")
                .append(jsonString(Version.version()))
                .append(",\n");
        sb.append("  \"baseBranch\": ").append(jsonString(baseBranch)).append(",\n");
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
        sb.append("  \"affectedModules\": ");
        if (affectedModules.isEmpty()) {
            sb.append("[]");
        } else {
            sb.append("[\n");
            for (int i = 0; i < affectedModules.size(); i++) {
                AffectedModule m = affectedModules.get(i);
                sb.append("    {\n");
                sb.append("      \"groupId\": ").append(jsonString(m.groupId)).append(",\n");
                sb.append("      \"artifactId\": ")
                        .append(jsonString(m.artifactId))
                        .append(",\n");
                sb.append("      \"path\": ").append(jsonString(m.path)).append(",\n");
                sb.append("      \"reasons\": ").append(jsonStringArray(m.reasons));
                if (m.category != null) {
                    sb.append(",\n");
                    sb.append("      \"category\": ")
                            .append(jsonString(m.category))
                            .append("\n");
                } else {
                    sb.append("\n");
                }
                sb.append("    }");
                if (i < affectedModules.size() - 1) {
                    sb.append(",");
                }
                sb.append("\n");
            }
            sb.append("  ]");
        }
        sb.append("\n}\n");
        return sb.toString();
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
                        sb.append(String.format("\\u%04x", (int) c));
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
        private boolean fullBuildTriggered;
        private String triggerFile;
        private final List<String> changedFiles = new ArrayList<>();
        private final List<String> changedProperties = new ArrayList<>();
        private final List<String> changedManagedDependencies = new ArrayList<>();
        private final List<String> changedManagedPlugins = new ArrayList<>();
        private final List<AffectedModule> affectedModules = new ArrayList<>();

        public Builder baseBranch(String baseBranch) {
            this.baseBranch = baseBranch;
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

        public Builder addAffectedModule(AffectedModule module) {
            this.affectedModules.add(module);
            return this;
        }

        public ScalpelReport build() {
            if (baseBranch == null) {
                throw new IllegalStateException("baseBranch is required");
            }
            return new ScalpelReport(
                    baseBranch,
                    fullBuildTriggered,
                    triggerFile,
                    changedFiles,
                    changedProperties,
                    changedManagedDependencies,
                    changedManagedPlugins,
                    affectedModules);
        }
    }
}
