/*
 * Copyright (c) Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.scalpel.extension3.internal;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import org.apache.maven.project.MavenProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Classifies changed files into POM changes vs source changes, and provides
 * shared utility methods for glob-pattern normalisation and relative-path computation.
 */
class ChangedFileClassifier {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    /**
     * Result of classifying changed files into POM changes and source changes.
     */
    static final class ClassificationResult {
        final Set<String> pomChanges;
        final Set<String> sourceChanges;

        ClassificationResult(Set<String> pomChanges, Set<String> sourceChanges) {
            this.pomChanges = pomChanges;
            this.sourceChanges = sourceChanges;
        }
    }

    /**
     * Separates the given changed files into POM changes and source changes.
     * Files under {@code .mvn/} are ignored (build infrastructure).
     */
    ClassificationResult classifyChanges(Set<String> changedFiles) {
        Set<String> pomChanges = new LinkedHashSet<>();
        Set<String> sourceChanges = new LinkedHashSet<>();
        for (String file : changedFiles) {
            if (file.endsWith("/pom.xml") || file.equals("pom.xml")) {
                pomChanges.add(file);
            } else if (file.startsWith(".mvn/")) {
                logger.debug("Ignoring build infrastructure file: {}", file);
            } else {
                sourceChanges.add(file);
            }
        }
        return new ClassificationResult(pomChanges, sourceChanges);
    }

    /**
     * Normalizes a user-supplied glob pattern so that bare patterns (those containing no path
     * separator) match files at any depth in the repository tree.
     */
    static String normalizeGlobPattern(String pattern) {
        if (pattern.contains("/")) {
            return pattern;
        }
        return "{" + pattern + ",**/" + pattern + "}";
    }

    /**
     * Computes the relative path from the (pre-normalized) reactor root to the project's
     * base directory.  Callers must pass a root that has already been normalized via
     * {@code Path.toAbsolutePath().normalize()} — this method does NOT re-normalize the
     * root on every call (#113).
     */
    static String relativePath(Path normalizedRoot, MavenProject project) {
        return normalizedRoot
                .relativize(project.getBasedir().toPath().toAbsolutePath().normalize())
                .toString()
                .replace('\\', '/');
    }
}
