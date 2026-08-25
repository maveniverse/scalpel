/*
 * Copyright (c) Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.scalpel.extension3.internal;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.maven.project.MavenProject;

/**
 * Groups analysis results passed between stages of the Scalpel lifecycle processing.
 */
final class AnalysisContext {

    final Set<String> changedFiles;
    final Set<String> changedProperties;
    final Set<String> changedManagedDepGAs;
    final Set<String> changedManagedPluginGAs;
    final Set<MavenProject> directlyAffected;
    final Set<MavenProject> affectedBySource;
    final Set<MavenProject> testOnlyBySource;
    final Set<MavenProject> affectedByPom;
    final Set<MavenProject> forceIncluded;
    final Map<MavenProject, List<String>> transitivelyAffected;
    final TrimResult trimResult;
    /**
     * The final build set actually passed to {@code session.setProjects()}, after
     * includePaths filtering. Null when not in trim mode or when includePaths is empty.
     */
    final List<MavenProject> filteredBuildSet;

    private AnalysisContext(Builder builder) {
        this.changedFiles = builder.changedFiles;
        this.changedProperties = builder.changedProperties;
        this.changedManagedDepGAs = builder.changedManagedDepGAs;
        this.changedManagedPluginGAs = builder.changedManagedPluginGAs;
        this.directlyAffected = builder.directlyAffected;
        this.affectedBySource = builder.affectedBySource;
        this.testOnlyBySource = builder.testOnlyBySource;
        this.affectedByPom = builder.affectedByPom;
        this.forceIncluded = builder.forceIncluded;
        this.transitivelyAffected = builder.transitivelyAffected;
        this.trimResult = builder.trimResult;
        this.filteredBuildSet = builder.filteredBuildSet;
    }

    static Builder builder(
            Set<String> changedFiles,
            Set<String> changedProperties,
            Set<String> changedManagedDepGAs,
            Set<String> changedManagedPluginGAs) {
        Builder b = new Builder();
        b.changedFiles = changedFiles;
        b.changedProperties = changedProperties;
        b.changedManagedDepGAs = changedManagedDepGAs;
        b.changedManagedPluginGAs = changedManagedPluginGAs;
        return b;
    }

    static AnalysisContext empty(
            Set<String> changedFiles,
            Set<String> changedProperties,
            Set<String> changedManagedDepGAs,
            Set<String> changedManagedPluginGAs) {
        return builder(changedFiles, changedProperties, changedManagedDepGAs, changedManagedPluginGAs)
                .build();
    }

    static final class Builder {
        private Set<String> changedFiles;
        private Set<String> changedProperties;
        private Set<String> changedManagedDepGAs;
        private Set<String> changedManagedPluginGAs;
        private Set<MavenProject> directlyAffected = Set.of();
        private Set<MavenProject> affectedBySource = Set.of();
        private Set<MavenProject> testOnlyBySource = Set.of();
        private Set<MavenProject> affectedByPom = Set.of();
        private Set<MavenProject> forceIncluded = Set.of();
        private Map<MavenProject, List<String>> transitivelyAffected = Map.of();
        private TrimResult trimResult;
        private List<MavenProject> filteredBuildSet;

        private Builder() {}

        Builder directlyAffected(Set<MavenProject> directlyAffected) {
            this.directlyAffected = directlyAffected;
            return this;
        }

        Builder affectedBySource(Set<MavenProject> affectedBySource) {
            this.affectedBySource = affectedBySource;
            return this;
        }

        Builder testOnlyBySource(Set<MavenProject> testOnlyBySource) {
            this.testOnlyBySource = testOnlyBySource;
            return this;
        }

        Builder affectedByPom(Set<MavenProject> affectedByPom) {
            this.affectedByPom = affectedByPom;
            return this;
        }

        Builder forceIncluded(Set<MavenProject> forceIncluded) {
            this.forceIncluded = forceIncluded;
            return this;
        }

        Builder transitivelyAffected(Map<MavenProject, List<String>> transitivelyAffected) {
            this.transitivelyAffected = transitivelyAffected;
            return this;
        }

        Builder trimResult(TrimResult trimResult) {
            this.trimResult = trimResult;
            return this;
        }

        Builder filteredBuildSet(List<MavenProject> filteredBuildSet) {
            this.filteredBuildSet = filteredBuildSet;
            return this;
        }

        AnalysisContext build() {
            return new AnalysisContext(this);
        }
    }
}
