/*
 * Copyright (c) Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.scalpel.extension3.internal;

import static eu.maveniverse.maven.scalpel.extension3.internal.Projects.key;

import eu.maveniverse.maven.scalpel.core.BoundedRegexMatcher;
import java.util.List;
import org.apache.maven.project.MavenProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Matches projects against {@code forceBuildModules} regex patterns using a bounded
 * regex matcher to defend against adversarial input.
 */
class ForceBuildMatcher {

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final BoundedRegexMatcher regexMatcher = new BoundedRegexMatcher();

    /**
     * Returns the matching pattern if the project's artifactId matches any of the
     * given patterns, or {@code null} if no match.
     */
    String matchesForceBuild(MavenProject project, List<String> patterns) {
        for (String pattern : patterns) {
            if (regexMatcher.matches(project.getArtifactId(), pattern, "forceBuildModules", logger)) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Scalpel: Force-including module {} (matches {})", key(project), pattern);
                }
                return pattern;
            }
        }
        return null;
    }
}
