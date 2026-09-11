/*
 * Copyright (c) Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.scalpel.extension3.internal;

import static eu.maveniverse.maven.scalpel.extension3.internal.Projects.key;
import static eu.maveniverse.maven.scalpel.extension3.internal.Projects.matchesDownstreamExclusion;

import eu.maveniverse.maven.scalpel.core.ScalpelConfiguration;
import eu.maveniverse.maven.scalpel.core.ScalpelReport;
import eu.maveniverse.maven.scalpel.core.Timings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.project.MavenProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Assembles and writes Scalpel reports: the main JSON report, status-only reports,
 * shadow status documents, and full-build-triggered reports.
 */
class ReportAssembler {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    ReportAssembler() {}

    void writeReport(
            ScalpelConfiguration config,
            Path reactorRoot,
            List<MavenProject> allProjects,
            AnalysisContext ctx,
            PathFilters pathFilters,
            Timings timings,
            long analysisStartNano)
            throws MavenExecutionException {
        ScalpelReport.Builder builder = ScalpelReport.builder()
                .baseBranch(config.getBaseBranch())
                .decisionId(ctx.decisionId)
                .mergeBaseId(ctx.mergeBaseId)
                .headId(ctx.headId)
                .configFingerprint(ctx.configFingerprint)
                .fullBuildTriggered(false)
                .changedFiles(ctx.changedFiles)
                .changedProperties(ctx.changedProperties)
                .changedManagedDependencies(ctx.changedManagedDepGAs)
                .changedManagedPlugins(ctx.changedManagedPluginGAs)
                .unmatchedPomPaths(ctx.unmatchedPomPaths)
                .timings(timings, millisSince(analysisStartNano));

        logger.debug(
                "Building report: {} directly affected, {} transitively affected, trim result has {} upstream / {} downstream / {} downstream-test",
                ctx.directlyAffected.size(),
                ctx.transitivelyAffected.size(),
                ctx.trimResult != null ? ctx.trimResult.getUpstreamOnly().size() : 0,
                ctx.trimResult != null ? ctx.trimResult.getDownstreamOnly().size() : 0,
                ctx.trimResult != null ? ctx.trimResult.getDownstreamTestOnly().size() : 0);

        addDirectlyAffectedModules(builder, ctx, reactorRoot);
        addTransitivelyAffectedModules(builder, ctx, config, reactorRoot);
        int excludedUpstream = addTrimResultModules(builder, ctx, pathFilters, config, reactorRoot);
        builder.excludedUpstreamCount(excludedUpstream);
        addSkippedModules(builder, allProjects, ctx, reactorRoot);
        // The reactor partitions as affectedModules + excludedUpstreamCount + skippedModules:
        // emitting the build-set size and tested count makes the split readable directly
        // instead of inferred (#187). Among affected modules, only the downstream
        // exclusion (skipTestsForDownstreamModules) suppresses tests.
        int affected = ctx.directlyAffected.size() + ctx.transitivelyAffected.size();
        builder.buildSetSize(affected + excludedUpstream);
        int testSuppressed = 0;
        for (MavenProject project : ctx.transitivelyAffected.keySet()) {
            if (matchesDownstreamExclusion(project, config.getSkipTestsForDownstreamModules())) {
                testSuppressed++;
            }
        }
        builder.testedModulesCount(Math.max(affected - testSuppressed, 0));

        try {
            ScalpelReport report = builder.build();
            report.writeToFile(reactorRoot, config.getReportFile());
            logger.info("Scalpel: Report written to {}", config.getReportFile());
        } catch (IOException e) {
            handleWriteFailure(config, "Failed to write report", e);
        }
    }

    void writeStatusReport(ScalpelConfiguration config, Path reactorRoot, String status, String reason) {
        String baseBranch = config.getBaseBranch();
        try {
            if (config.isModeShadow() || config.isVerifyFullBuild()) {
                writeShadowStatus(reactorRoot, status, reason);
            }
            ScalpelReport report = ScalpelReport.builder()
                    .baseBranch(baseBranch != null ? baseBranch : "(unconfigured)")
                    .status(status)
                    .reason(reason)
                    .fullBuildTriggered(true)
                    .build();
            report.writeToFile(reactorRoot, config.getReportFile());
            logger.warn(
                    "Scalpel: Analysis did not complete (status={}, reason={}), report at {} overwritten",
                    status,
                    reason,
                    config.getReportFile());
        } catch (Exception e) {
            logger.warn("Scalpel: Could not overwrite report with {} status: {}", status, e.toString());
        }
    }

    void writeShadowStatus(Path reactorRoot, String status, String reason) {
        try {
            Files.createDirectories(
                    reactorRoot.resolve(ShadowBuildMonitor.SHADOW_FILE).getParent());
            Files.write(
                    reactorRoot.resolve(ShadowBuildMonitor.SHADOW_FILE),
                    ShadowBuildMonitor.statusDocument(status, reason).getBytes(StandardCharsets.UTF_8));
            logger.warn(
                    "Scalpel: Shadow document at {} overwritten with {} status",
                    ShadowBuildMonitor.SHADOW_FILE,
                    status);
        } catch (Exception e) {
            logger.warn("Scalpel: Could not overwrite shadow document with {} status: {}", status, e.toString());
        }
    }

    void writeFullBuildReport(
            ScalpelConfiguration config,
            Path reactorRoot,
            String triggerFile,
            Set<String> changedFiles,
            String decisionId)
            throws MavenExecutionException {
        ScalpelReport report = ScalpelReport.builder()
                .baseBranch(config.getBaseBranch())
                .decisionId(decisionId)
                .fullBuildTriggered(true)
                .triggerFile(triggerFile)
                .changedFiles(changedFiles)
                .build();
        try {
            if (config.isModeShadow() || config.isVerifyFullBuild()) {
                writeShadowStatus(reactorRoot, "skipped", "full build triggered by " + triggerFile);
            }
            report.writeToFile(reactorRoot, config.getReportFile());
            logger.info("Scalpel: Report written to {}", config.getReportFile());
        } catch (IOException e) {
            handleWriteFailure(config, "Failed to write report", e);
        }
    }

    void writeFailedStatusReport(ScalpelConfiguration config, Path reactorRoot, String reason) {
        writeStatusReport(config, reactorRoot, "failed", reason);
    }

    void handleWriteFailure(ScalpelConfiguration config, String message, IOException e) throws MavenExecutionException {
        if (config.isFailSafe()) {
            if (logger.isWarnEnabled()) {
                logger.warn("Scalpel: {} (failSafe=true, continuing build): {}", message, e.toString());
            }
        } else {
            throw new MavenExecutionException("Scalpel: " + message, e);
        }
    }

    private void addDirectlyAffectedModules(ScalpelReport.Builder builder, AnalysisContext ctx, Path reactorRoot) {
        for (MavenProject project : ctx.directlyAffected) {
            String path = ChangedFileClassifier.relativePath(reactorRoot, project);
            List<String> reasons = new ArrayList<>();
            String sourceSet = null;
            if (ctx.affectedBySource.contains(project)) {
                if (ctx.testOnlyBySource.contains(project)) {
                    reasons.add(ScalpelReport.REASON_TEST_CHANGE);
                    sourceSet = "test";
                } else {
                    reasons.add(ScalpelReport.REASON_SOURCE_CHANGE);
                    sourceSet = "main";
                }
            }
            if (ctx.affectedByPom.contains(project)) {
                reasons.add(ScalpelReport.REASON_POM_CHANGE);
            }
            if (ctx.forceIncluded.contains(project)) {
                reasons.add(ScalpelReport.REASON_FORCE_BUILD);
            }
            if (logger.isDebugEnabled()) {
                logger.debug("Report: {} -> category=DIRECT, reasons={}", key(project), reasons);
            }
            builder.addAffectedModule(ScalpelReport.AffectedModule.moduleBuilder(
                            project.getGroupId(), project.getArtifactId(), path, reasons)
                    .category(ScalpelReport.CATEGORY_DIRECT)
                    .sourceSet(sourceSet)
                    .evidence(ctx.evidence.get(project))
                    .build());
        }
    }

    private void addTransitivelyAffectedModules(
            ScalpelReport.Builder builder, AnalysisContext ctx, ScalpelConfiguration config, Path reactorRoot) {
        for (Map.Entry<MavenProject, List<String>> entry : ctx.transitivelyAffected.entrySet()) {
            MavenProject project = entry.getKey();
            String path = ChangedFileClassifier.relativePath(reactorRoot, project);
            String category = null;
            String testsSkippedReason = null;
            if (ctx.trimResult != null) {
                if (ctx.trimResult.getUpstreamOnly().contains(project)) {
                    category = ScalpelReport.CATEGORY_UPSTREAM;
                } else if (ctx.trimResult.getDownstreamOnly().contains(project)
                        || ctx.trimResult.getDownstreamTestOnly().contains(project)) {
                    category = ScalpelReport.CATEGORY_DOWNSTREAM;
                    if (matchesDownstreamExclusion(project, config.getSkipTestsForDownstreamModules())) {
                        testsSkippedReason = ScalpelReport.REASON_EXCLUDED_DOWNSTREAM;
                    }
                }
            }
            if (category == null) {
                category = ScalpelReport.CATEGORY_TRANSITIVE;
            }
            if (logger.isDebugEnabled()) {
                logger.debug("Report: {} -> category={}, reasons={}", key(project), category, entry.getValue());
            }
            builder.addAffectedModule(ScalpelReport.AffectedModule.moduleBuilder(
                            project.getGroupId(), project.getArtifactId(), path, entry.getValue())
                    .category(category)
                    .testsSkippedReason(testsSkippedReason)
                    .evidence(ctx.evidence.get(project))
                    .build());
        }
    }

    private int addTrimResultModules(
            ScalpelReport.Builder builder,
            AnalysisContext ctx,
            PathFilters pathFilters,
            ScalpelConfiguration config,
            Path reactorRoot) {
        if (ctx.trimResult == null) {
            return 0;
        }
        int upstreamCount = 0;
        for (MavenProject project : ctx.trimResult.getUpstreamOnly()) {
            if (!ctx.directlyAffected.contains(project) && !ctx.transitivelyAffected.containsKey(project)) {
                upstreamCount++;
                if (logger.isDebugEnabled()) {
                    logger.debug(
                            "Excluding upstream build-prerequisite {} from report (not genuinely affected)",
                            key(project));
                }
            }
        }
        if (upstreamCount > 0) {
            logger.info(
                    "Scalpel: {} upstream build-prerequisite modules excluded from report (use trim/skip-tests mode for full build set)",
                    upstreamCount);
        }
        addDownstreamModules(
                builder,
                ctx,
                pathFilters,
                config,
                reactorRoot,
                ctx.trimResult.getDownstreamOnly(),
                ScalpelReport.REASON_DOWNSTREAM_DEPENDENT);
        addDownstreamModules(
                builder,
                ctx,
                pathFilters,
                config,
                reactorRoot,
                ctx.trimResult.getDownstreamTestOnly(),
                ScalpelReport.REASON_DOWNSTREAM_TEST);
        return upstreamCount;
    }

    private void addDownstreamModules(
            ScalpelReport.Builder builder,
            AnalysisContext ctx,
            PathFilters pathFilters,
            ScalpelConfiguration config,
            Path reactorRoot,
            Set<MavenProject> downstreamProjects,
            String reason) {
        for (MavenProject project : downstreamProjects) {
            if (ctx.directlyAffected.contains(project) || ctx.transitivelyAffected.containsKey(project)) {
                continue;
            }
            addSingleDownstreamModule(builder, ctx, config, reactorRoot, pathFilters, project, reason);
        }
    }

    private void addSingleDownstreamModule(
            ScalpelReport.Builder builder,
            AnalysisContext ctx,
            ScalpelConfiguration config,
            Path reactorRoot,
            PathFilters pathFilters,
            MavenProject project,
            String reason) {
        // Skip downstream modules outside includePaths scope
        if (pathFilters.hasIncludeFilters() && !pathFilters.matchesIncludePaths(project, reactorRoot)) {
            if (logger.isDebugEnabled()) {
                logger.debug("Excluding downstream module {} from report (outside includePaths)", key(project));
            }
            return;
        }
        String path = ChangedFileClassifier.relativePath(reactorRoot, project);
        String testsSkippedReason = matchesDownstreamExclusion(project, config.getSkipTestsForDownstreamModules())
                ? ScalpelReport.REASON_EXCLUDED_DOWNSTREAM
                : null;
        if (logger.isDebugEnabled()) {
            logger.debug("Report: {} -> category=DOWNSTREAM, reason={}", key(project), reason);
        }
        builder.addAffectedModule(ScalpelReport.AffectedModule.moduleBuilder(
                        project.getGroupId(), project.getArtifactId(), path, List.of(reason))
                .category(ScalpelReport.CATEGORY_DOWNSTREAM)
                .testsSkippedReason(testsSkippedReason)
                .evidence(ctx.evidence.get(project))
                .build());
    }

    private void addSkippedModules(
            ScalpelReport.Builder builder, List<MavenProject> allProjects, AnalysisContext ctx, Path reactorRoot) {
        Set<MavenProject> included = new LinkedHashSet<>(ctx.directlyAffected);
        included.addAll(ctx.transitivelyAffected.keySet());
        if (ctx.trimResult != null) {
            if (ctx.filteredBuildSet != null) {
                included.addAll(ctx.filteredBuildSet);
            } else {
                included.addAll(ctx.trimResult.getBuildSet());
            }
        }
        for (MavenProject project : allProjects) {
            if (!included.contains(project)) {
                String path = ChangedFileClassifier.relativePath(reactorRoot, project);
                builder.addSkippedModule(new ScalpelReport.SkippedModule(
                        project.getGroupId(), project.getArtifactId(), path, ScalpelReport.SKIP_REASON_NOT_AFFECTED));
            }
        }
    }

    static long millisSince(long startNano) {
        return (System.nanoTime() - startNano) / 1_000_000;
    }
}
