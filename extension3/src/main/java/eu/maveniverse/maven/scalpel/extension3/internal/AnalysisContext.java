/*
 * Copyright (c) Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.scalpel.extension3.internal;

import java.util.Collections;
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

    AnalysisContext(
            Set<String> changedFiles,
            Set<String> changedProperties,
            Set<String> changedManagedDepGAs,
            Set<String> changedManagedPluginGAs,
            Set<MavenProject> directlyAffected,
            Set<MavenProject> affectedBySource,
            Set<MavenProject> testOnlyBySource,
            Set<MavenProject> affectedByPom,
            Set<MavenProject> forceIncluded,
            Map<MavenProject, List<String>> transitivelyAffected,
            TrimResult trimResult) {
        this.changedFiles = changedFiles;
        this.changedProperties = changedProperties;
        this.changedManagedDepGAs = changedManagedDepGAs;
        this.changedManagedPluginGAs = changedManagedPluginGAs;
        this.directlyAffected = directlyAffected;
        this.affectedBySource = affectedBySource;
        this.testOnlyBySource = testOnlyBySource;
        this.affectedByPom = affectedByPom;
        this.forceIncluded = forceIncluded;
        this.transitivelyAffected = transitivelyAffected;
        this.trimResult = trimResult;
    }

    static AnalysisContext empty(
            Set<String> changedFiles,
            Set<String> changedProperties,
            Set<String> changedManagedDepGAs,
            Set<String> changedManagedPluginGAs) {
        return new AnalysisContext(
                changedFiles,
                changedProperties,
                changedManagedDepGAs,
                changedManagedPluginGAs,
                Collections.<MavenProject>emptySet(),
                Collections.<MavenProject>emptySet(),
                Collections.<MavenProject>emptySet(),
                Collections.<MavenProject>emptySet(),
                Collections.<MavenProject>emptySet(),
                Collections.<MavenProject, List<String>>emptyMap(),
                null);
    }
}
