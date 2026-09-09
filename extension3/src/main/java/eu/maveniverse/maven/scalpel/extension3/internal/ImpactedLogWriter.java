/*
 * Copyright (c) Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.scalpel.extension3.internal;

import static eu.maveniverse.maven.scalpel.extension3.internal.Projects.key;

import eu.maveniverse.maven.scalpel.core.ScalpelConfiguration;
import eu.maveniverse.maven.scalpel.core.ScalpelReport;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.project.MavenProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes the impacted-module log file, validating paths for shell safety.
 */
class ImpactedLogWriter {

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final ReportAssembler reportAssembler;

    ImpactedLogWriter(ReportAssembler reportAssembler) {
        this.reportAssembler = reportAssembler;
    }

    /**
     * Writes the list of affected modules to the configured impacted-log file.
     */
    void writeImpactedLog(ScalpelConfiguration config, Path reactorRoot, Set<MavenProject> affectedModules)
            throws MavenExecutionException {
        String impactedLog = config.getImpactedLog();
        if (impactedLog == null || impactedLog.trim().isEmpty()) {
            return;
        }
        Path logPath;
        try {
            logPath = ScalpelReport.resolveContained(reactorRoot, impactedLog, ScalpelConfiguration.IMPACTED_LOG);
        } catch (IOException e) {
            reportAssembler.handleWriteFailure(config, "Rejected impacted log path", e);
            return;
        }
        try {
            Files.createDirectories(logPath.getParent());
            List<String> lines = new ArrayList<>();
            for (MavenProject project : affectedModules) {
                String relPath = ChangedFileClassifier.relativePath(reactorRoot, project);
                if (relPath.isEmpty()) {
                    relPath = ".";
                }
                if (!isSafeImpactedLogPath(relPath)) {
                    if (logger.isWarnEnabled()) {
                        logger.warn(
                                "Scalpel: Skipping module {} in impacted log: path '{}' uses characters outside the"
                                        + " safe set (letters, digits, '-', '_', '.', '/'; see README)",
                                key(project),
                                escapeControlChars(relPath));
                    }
                    continue;
                }
                lines.add(relPath);
            }
            Files.write(logPath, lines, StandardCharsets.UTF_8);
            logger.info("Scalpel: Impacted modules written to {}", config.getImpactedLog());
        } catch (IOException e) {
            reportAssembler.handleWriteFailure(config, "Failed to write impacted log", e);
        }
    }

    /**
     * Safe character set for impacted-log lines.
     */
    static boolean isSafeImpactedLogPath(String path) {
        if (path == null || path.isEmpty() || path.charAt(0) == '-') {
            return false;
        }
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '-'
                    || c == '_'
                    || c == '.'
                    || c == '/';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }

    /**
     * Escapes control characters so a rejected path cannot forge extra lines in the
     * skip warning itself.
     */
    static String escapeControlChars(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c)) {
                escaped.append("\\u%04x".formatted((int) c));
            } else {
                escaped.append(c);
            }
        }
        return escaped.toString();
    }
}
