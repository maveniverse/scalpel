/*
 * Copyright (c) Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.scalpel.extension3.internal;

import java.util.ArrayList;
import java.util.List;
import org.apache.maven.project.MavenProject;

final class Projects {
    private Projects() {}

    static String key(MavenProject project) {
        return project.getGroupId() + ":" + project.getArtifactId();
    }

    static List<String> keys(Iterable<MavenProject> projects) {
        List<String> keys = new ArrayList<>();
        for (MavenProject project : projects) {
            keys.add(key(project));
        }
        return keys;
    }

    /**
     * Returns {@code true} if the project matches at least one downstream exclusion pattern.
     * Patterns containing {@code ':'} are matched against the full {@code groupId:artifactId} key;
     * bare patterns are matched against the artifact ID only.
     */
    static boolean matchesDownstreamExclusion(MavenProject project, List<String> patterns) {
        for (String pattern : patterns) {
            if (pattern.contains(":")) {
                if (key(project).equals(pattern)) {
                    return true;
                }
            } else {
                if (project.getArtifactId().equals(pattern)) {
                    return true;
                }
            }
        }
        return false;
    }
}
