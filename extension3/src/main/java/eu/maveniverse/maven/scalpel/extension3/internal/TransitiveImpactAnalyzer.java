/*
 * Copyright (c) Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.scalpel.extension3.internal;

import static eu.maveniverse.maven.scalpel.extension3.internal.Projects.key;
import static eu.maveniverse.maven.scalpel.extension3.internal.Projects.keys;

import eu.maveniverse.maven.scalpel.core.ScalpelReport;
import eu.maveniverse.maven.scalpel.core.Timings;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Model;
import org.apache.maven.project.DefaultDependencyResolutionRequest;
import org.apache.maven.project.DependencyResolutionException;
import org.apache.maven.project.DependencyResolutionResult;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectDependenciesResolver;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyFilter;
import org.eclipse.aether.graph.DependencyNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Performs transitive impact analysis: compares old vs new effective models and dependency
 * trees to find modules indirectly affected by changes in the reactor.
 */
class TransitiveImpactAnalyzer {

    static final String UNRESOLVED_GA = "(unresolved)";
    private static final String SCOPE_COMPILE = "compile";

    /**
     * A DependencyFilter that rejects all nodes, preventing artifact downloads
     * while still allowing the dependency graph to be collected.
     */
    static final DependencyFilter COLLECT_ONLY_FILTER = (node, parents) -> false;

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final ProjectDependenciesResolver dependenciesResolver;
    private final PomChangeAnalyzer pomChangeAnalyzer;

    TransitiveImpactAnalyzer(ProjectDependenciesResolver dependenciesResolver, PomChangeAnalyzer pomChangeAnalyzer) {
        this.dependenciesResolver = dependenciesResolver;
        this.pomChangeAnalyzer = pomChangeAnalyzer;
    }

    /**
     * Bundles the resolution-related parameters shared across transitive analysis methods.
     */
    record ResolutionContext(
            Path normalizedRoot,
            MavenSession session,
            Map<MavenProject, DependencyResolutionResult> collectCache,
            Map<MavenProject, DependencyResolutionResult> oldCollectCache,
            Timings timings) {}

    /**
     * Bundles effective model maps for old-vs-new comparison.
     */
    record EffectiveModels(Map<String, Model> oldModels, Map<String, Model> newModels) {}

    static final class TransitiveMatch {
        final List<String> reasons;
        final List<String> evidence;

        TransitiveMatch(List<String> reasons, List<String> evidence) {
            this.reasons = reasons;
            this.evidence = evidence;
        }
    }

    static final class ChangedDependencyMatch {
        final String ga;
        final String scope;

        ChangedDependencyMatch(String ga, String scope) {
            this.ga = ga;
            this.scope = scope;
        }
    }

    /**
     * Determine which non-directly-affected modules are <em>transitively</em> affected
     * by changes in the reactor. Uses a two-pass algorithm:
     *
     * <h4>Pass 1 — Effective model and dependency tree comparison</h4>
     * For each non-directly-affected module, compare old vs new effective models.
     *
     * <h4>Pass 2 — Reactor dependency propagation</h4>
     * Propagates through reactor dependencies to a fixed point.
     */
    Map<MavenProject, List<String>> computeTransitivelyAffected(
            List<MavenProject> allProjects,
            Set<MavenProject> directlyAffected,
            EffectiveModels models,
            ResolutionContext rctx,
            boolean explain,
            Map<MavenProject, List<String>> returnEvidence) {
        Map<MavenProject, List<String>> transitivelyAffected = new LinkedHashMap<>();
        Map<MavenProject, List<String>> transitiveEvidence = new LinkedHashMap<>();
        if (models.oldModels().isEmpty()) {
            logger.debug("Skipping transitive analysis: no effective models available");
            return transitivelyAffected;
        }
        logger.debug(
                "Computing transitively affected modules: comparing old vs new dependency trees for {} non-direct modules",
                allProjects.size() - directlyAffected.size());

        // First pass: compare effective models and dependency trees directly
        firstPassModelComparison(
                allProjects, directlyAffected, models, rctx, explain, transitivelyAffected, transitiveEvidence);

        // Second pass: propagate through reactor dependencies.
        secondPassReactorPropagation(
                allProjects, directlyAffected, rctx, explain, transitivelyAffected, transitiveEvidence);

        if (!transitivelyAffected.isEmpty()) {
            logger.info(
                    "Scalpel: {} modules transitively affected: {}",
                    transitivelyAffected.size(),
                    keys(transitivelyAffected.keySet()));
        }
        returnEvidence.putAll(transitiveEvidence);
        return transitivelyAffected;
    }

    private void firstPassModelComparison(
            List<MavenProject> allProjects,
            Set<MavenProject> directlyAffected,
            EffectiveModels models,
            ResolutionContext rctx,
            boolean explain,
            Map<MavenProject, List<String>> transitivelyAffected,
            Map<MavenProject, List<String>> transitiveEvidence) {
        for (MavenProject project : allProjects) {
            if (directlyAffected.contains(project)) {
                continue;
            }
            TransitiveMatch match = computeTransitiveMatch(project, models, rctx, explain);
            if (!match.reasons.isEmpty()) {
                transitivelyAffected.put(project, match.reasons);
                if (explain) {
                    transitiveEvidence.put(project, match.evidence);
                }
            }
        }
    }

    private void secondPassReactorPropagation(
            List<MavenProject> allProjects,
            Set<MavenProject> directlyAffected,
            ResolutionContext rctx,
            boolean explain,
            Map<MavenProject, List<String>> transitivelyAffected,
            Map<MavenProject, List<String>> transitiveEvidence) {
        Set<String> affectedGAs = new LinkedHashSet<>();
        for (MavenProject p : directlyAffected) {
            affectedGAs.add(p.getGroupId() + ":" + p.getArtifactId());
        }
        for (MavenProject p : transitivelyAffected.keySet()) {
            affectedGAs.add(p.getGroupId() + ":" + p.getArtifactId());
        }
        boolean propagated = true;
        while (propagated) {
            propagated = false;
            for (MavenProject project : allProjects) {
                if (directlyAffected.contains(project) || transitivelyAffected.containsKey(project)) {
                    continue;
                }
                propagated |= propagateToProject(
                        project, rctx, affectedGAs, explain, transitivelyAffected, transitiveEvidence);
            }
        }
    }

    /**
     * Attempts to propagate the affected status to a single project during the
     * reactor-dependency propagation pass. Returns {@code true} if the project
     * was newly marked as affected.
     */
    private boolean propagateToProject(
            MavenProject project,
            ResolutionContext rctx,
            Set<String> affectedGAs,
            boolean explain,
            Map<MavenProject, List<String>> transitivelyAffected,
            Map<MavenProject, List<String>> transitiveEvidence) {
        DependencyResolutionResult depResult =
                resolveProjectDependencies(project, rctx.session(), rctx.collectCache(), rctx.timings());
        if (depResult == null || depResult.getDependencyGraph() == null) {
            return handleUnresolvableProject(project, affectedGAs, explain, transitivelyAffected, transitiveEvidence);
        }
        return matchAffectedDependency(
                project, depResult, affectedGAs, explain, transitivelyAffected, transitiveEvidence);
    }

    private boolean handleUnresolvableProject(
            MavenProject project,
            Set<String> affectedGAs,
            boolean explain,
            Map<MavenProject, List<String>> transitivelyAffected,
            Map<MavenProject, List<String>> transitiveEvidence) {
        if (affectedGAs.isEmpty()) {
            return false;
        }
        if (logger.isWarnEnabled()) {
            logger.warn(
                    "Cannot resolve dependencies of {} while propagating changes, conservatively marking as affected",
                    key(project));
        }
        transitivelyAffected.put(
                project, new ArrayList<>(List.of(ScalpelReport.REASON_TRANSITIVE_DEPENDENCY_UNRESOLVED)));
        affectedGAs.add(project.getGroupId() + ":" + project.getArtifactId());
        if (explain) {
            transitiveEvidence.put(
                    project, List.of("dependency resolution failed; conservatively treated as affected"));
        }
        return true;
    }

    private boolean matchAffectedDependency(
            MavenProject project,
            DependencyResolutionResult depResult,
            Set<String> affectedGAs,
            boolean explain,
            Map<MavenProject, List<String>> transitivelyAffected,
            Map<MavenProject, List<String>> transitiveEvidence) {
        Map<String, String> depScopes = collectDependencyScopes(depResult.getDependencyGraph());
        for (Map.Entry<String, String> dep : depScopes.entrySet()) {
            if (affectedGAs.contains(dep.getKey())) {
                List<String> reasons = new ArrayList<>();
                reasons.add(
                        "test".equals(dep.getValue())
                                ? ScalpelReport.REASON_TRANSITIVE_DEPENDENCY_TEST
                                : ScalpelReport.REASON_TRANSITIVE_DEPENDENCY);
                transitivelyAffected.put(project, reasons);
                affectedGAs.add(project.getGroupId() + ":" + project.getArtifactId());
                if (explain) {
                    transitiveEvidence.put(project, List.of("depends on affected reactor module " + dep.getKey()));
                }
                return true;
            }
        }
        return false;
    }

    /**
     * Compare old vs new effective models and dependency trees for a single module.
     */
    TransitiveMatch computeTransitiveMatch(
            MavenProject project, EffectiveModels models, ResolutionContext rctx, boolean explain) {
        List<String> reasons = new ArrayList<>();
        List<String> evidence = explain ? new ArrayList<>() : List.of();

        String relPath = rctx.normalizedRoot()
                .relativize(project.getFile().toPath().toAbsolutePath().normalize())
                .toString()
                .replace('\\', '/');
        Model oldModel = models.oldModels().get(relPath);
        Model newModel = models.newModels().get(relPath);
        if (oldModel == null || newModel == null) {
            return new TransitiveMatch(reasons, evidence);
        }

        // Compare effective plugins directly from the fully-interpolated models
        Set<String> changedPlugins = pomChangeAnalyzer.diffManagedPluginVersions(
                pomChangeAnalyzer.getEffectivePlugins(oldModel), pomChangeAnalyzer.getEffectivePlugins(newModel));
        if (!changedPlugins.isEmpty()) {
            reasons.add(ScalpelReport.REASON_MANAGED_PLUGIN);
            if (explain) {
                evidence.add("effective plugin " + changedPlugins.iterator().next());
            }
        }

        // Compare resolved dependency trees: old effective model vs current project
        ChangedDependencyMatch depMatch = findChangedDependencyInTree(
                project, oldModel, rctx.session(), rctx.collectCache(), rctx.oldCollectCache(), rctx.timings());
        if (depMatch != null) {
            if (UNRESOLVED_GA.equals(depMatch.ga)) {
                reasons.add(ScalpelReport.REASON_TRANSITIVE_DEPENDENCY_UNRESOLVED);
            } else if ("test".equals(depMatch.scope)) {
                reasons.add(ScalpelReport.REASON_TRANSITIVE_DEPENDENCY_TEST);
            } else {
                reasons.add(ScalpelReport.REASON_TRANSITIVE_DEPENDENCY);
            }
            if (explain) {
                evidence.add("dependency tree diff " + depMatch.ga);
            }
        }

        return new TransitiveMatch(reasons, evidence);
    }

    /**
     * Checks whether a module's effective plugins or resolved dependency tree changed
     * between old and new effective models.
     */
    boolean hasEffectiveModelChanges(MavenProject project, EffectiveModels models, ResolutionContext rctx) {
        String relPath = rctx.normalizedRoot()
                .relativize(project.getFile().toPath().toAbsolutePath().normalize())
                .toString()
                .replace('\\', '/');
        Model oldModel = models.oldModels().get(relPath);
        Model newModel = models.newModels().get(relPath);
        if (oldModel == null || newModel == null) {
            return false;
        }

        // Check effective plugins
        Set<String> changedPlugins = pomChangeAnalyzer.diffManagedPluginVersions(
                pomChangeAnalyzer.getEffectivePlugins(oldModel), pomChangeAnalyzer.getEffectivePlugins(newModel));
        if (!changedPlugins.isEmpty()) {
            return true;
        }

        // Check dependency tree
        return findChangedDependencyInTree(
                        project, oldModel, rctx.session(), rctx.collectCache(), rctx.oldCollectCache(), rctx.timings())
                != null;
    }

    /**
     * Resolve dependency trees from both old effective model and current project,
     * then diff them.
     */
    ChangedDependencyMatch findChangedDependencyInTree(
            MavenProject project,
            Model oldEffectiveModel,
            MavenSession session,
            Map<MavenProject, DependencyResolutionResult> collectCache,
            Map<MavenProject, DependencyResolutionResult> oldCollectCache,
            Timings timings) {

        // Resolve new (current) dependency tree
        DependencyResolutionResult newResult = resolveProjectDependencies(project, session, collectCache, timings);
        if (newResult == null || newResult.getDependencyGraph() == null) {
            return new ChangedDependencyMatch(UNRESOLVED_GA, SCOPE_COMPILE);
        }

        // Resolve old dependency tree from old effective model
        DependencyResolutionResult oldResult =
                resolveModelDependencies(oldEffectiveModel, project, session, oldCollectCache, timings);
        if (oldResult == null || oldResult.getDependencyGraph() == null) {
            return new ChangedDependencyMatch(UNRESOLVED_GA, SCOPE_COMPILE);
        }

        // Collect (GA → version) from both trees and diff
        Map<String, String> oldVersions = collectDependencyVersions(oldResult.getDependencyGraph());
        Map<String, String> newVersions = collectDependencyVersions(newResult.getDependencyGraph());

        // Find changed GAs and determine scope from the new tree
        Map<String, String> newScopes = collectDependencyScopes(newResult.getDependencyGraph());

        ChangedDependencyMatch changed = findChangedVersions(project, oldVersions, newVersions, newScopes);
        if (changed != null) {
            return changed;
        }
        return findNewDependencies(project, oldVersions, newVersions, newScopes);
    }

    private ChangedDependencyMatch findChangedVersions(
            MavenProject project,
            Map<String, String> oldVersions,
            Map<String, String> newVersions,
            Map<String, String> newScopes) {
        String narrowestGa = null;
        String narrowestScope = null;

        for (Map.Entry<String, String> e : oldVersions.entrySet()) {
            if (!Objects.equals(e.getValue(), newVersions.get(e.getKey()))) {
                String ga = e.getKey();
                String scope = newScopes.getOrDefault(ga, SCOPE_COMPILE);
                if (logger.isDebugEnabled()) {
                    logger.debug("Module {} has changed dependency {} (scope={})", key(project), ga, scope);
                }
                if (!"test".equals(scope)) {
                    return new ChangedDependencyMatch(ga, scope);
                }
                if (narrowestScope == null) {
                    narrowestScope = "test";
                    narrowestGa = ga;
                }
            }
        }
        return narrowestScope != null ? new ChangedDependencyMatch(narrowestGa, narrowestScope) : null;
    }

    private ChangedDependencyMatch findNewDependencies(
            MavenProject project,
            Map<String, String> oldVersions,
            Map<String, String> newVersions,
            Map<String, String> newScopes) {
        String narrowestGa = null;
        String narrowestScope = null;

        for (String ga : newVersions.keySet()) {
            if (!oldVersions.containsKey(ga)) {
                String scope = newScopes.getOrDefault(ga, SCOPE_COMPILE);
                if (logger.isDebugEnabled()) {
                    logger.debug("Module {} has new dependency {} (scope={})", key(project), ga, scope);
                }
                if (!"test".equals(scope)) {
                    return new ChangedDependencyMatch(ga, scope);
                }
                if (narrowestScope == null) {
                    narrowestScope = "test";
                    narrowestGa = ga;
                }
            }
        }
        return narrowestScope != null ? new ChangedDependencyMatch(narrowestGa, narrowestScope) : null;
    }

    /**
     * Resolve the current project's dependency tree (cached).
     */
    DependencyResolutionResult resolveProjectDependencies(
            MavenProject project,
            MavenSession session,
            Map<MavenProject, DependencyResolutionResult> cache,
            Timings timings) {
        DependencyResolutionResult result = cache.get(project);
        if (result != null) {
            timings.increment(Timings.OP_RESOLVE_CACHE_HITS);
            return result;
        }
        try {
            DefaultDependencyResolutionRequest request =
                    new DefaultDependencyResolutionRequest(project, session.getRepositorySession());
            request.setResolutionFilter(COLLECT_ONLY_FILTER);
            result = dependenciesResolver.resolve(request);
        } catch (DependencyResolutionException e) {
            logger.warn(
                    "Cannot collect dependencies for {}, conservatively treating module as affected: {}",
                    key(project),
                    e.getMessage());
            return null;
        }
        timings.increment(Timings.OP_DEPENDENCY_RESOLVES);
        cache.put(project, result);
        return result;
    }

    /**
     * Resolve the dependency tree from an old effective model.
     */
    DependencyResolutionResult resolveModelDependencies(
            Model oldModel,
            MavenProject currentProject,
            MavenSession session,
            Map<MavenProject, DependencyResolutionResult> cache,
            Timings timings) {
        DependencyResolutionResult result = cache.get(currentProject);
        if (result != null) {
            timings.increment(Timings.OP_RESOLVE_CACHE_HITS);
            return result;
        }
        try {
            MavenProject tempProject = new MavenProject(currentProject);
            tempProject.setModel(oldModel);
            tempProject.setDependencies(oldModel.getDependencies());
            DefaultDependencyResolutionRequest request =
                    new DefaultDependencyResolutionRequest(tempProject, session.getRepositorySession());
            request.setResolutionFilter(COLLECT_ONLY_FILTER);
            result = dependenciesResolver.resolve(request);
        } catch (DependencyResolutionException e) {
            logger.warn(
                    "Cannot collect old dependencies for {}, conservatively treating module as affected: {}",
                    key(currentProject),
                    e.getMessage());
            return null;
        }
        timings.increment(Timings.OP_DEPENDENCY_RESOLVES);
        cache.put(currentProject, result);
        return result;
    }

    /**
     * Walk the dependency graph and collect (GA → version) for all nodes.
     */
    static Map<String, String> collectDependencyVersions(DependencyNode root) {
        Map<String, String> versions = new LinkedHashMap<>();
        walkDependencyGraph(
                root, (ga, dep) -> versions.put(ga, dep.getArtifact().getVersion()));
        return versions;
    }

    /**
     * Walk the dependency graph and collect (GA → scope) for all nodes.
     */
    static Map<String, String> collectDependencyScopes(DependencyNode root) {
        Map<String, String> scopes = new LinkedHashMap<>();
        walkDependencyGraph(root, (ga, dep) -> scopes.put(ga, dep.getScope() != null ? dep.getScope() : SCOPE_COMPILE));
        return scopes;
    }

    /**
     * Generic depth-first walk over a dependency graph, invoking the consumer for each
     * unique GA encountered.
     */
    private static void walkDependencyGraph(
            DependencyNode root, java.util.function.BiConsumer<String, Dependency> consumer) {
        List<DependencyNode> stack = new ArrayList<>(root.getChildren());
        Set<String> visited = new HashSet<>();
        while (!stack.isEmpty()) {
            DependencyNode node = stack.remove(stack.size() - 1);
            Dependency dep = node.getDependency();
            if (dep == null) {
                continue;
            }
            String ga = dep.getArtifact().getGroupId() + ":" + dep.getArtifact().getArtifactId();
            if (visited.add(ga)) {
                consumer.accept(ga, dep);
                stack.addAll(node.getChildren());
            }
        }
    }

    /**
     * Derive changed managed dependency GAs from effective models (for report purposes only).
     */
    Set<String> deriveChangedManagedDeps(Map<String, Model> oldEffectiveModels, Map<String, Model> newEffectiveModels) {
        Set<String> changed = new LinkedHashSet<>();
        for (Map.Entry<String, Model> entry : oldEffectiveModels.entrySet()) {
            Model newModel = newEffectiveModels.get(entry.getKey());
            if (newModel != null) {
                changed.addAll(pomChangeAnalyzer.diffDependencies(
                        pomChangeAnalyzer.getManagedDependencies(entry.getValue()),
                        pomChangeAnalyzer.getManagedDependencies(newModel)));
            }
        }
        return changed;
    }

    /**
     * Derive changed managed plugin GAs from effective models (for report purposes only).
     */
    Set<String> deriveChangedManagedPlugins(
            Map<String, Model> oldEffectiveModels, Map<String, Model> newEffectiveModels) {
        Set<String> changed = new LinkedHashSet<>();
        for (Map.Entry<String, Model> entry : oldEffectiveModels.entrySet()) {
            Model newModel = newEffectiveModels.get(entry.getKey());
            if (newModel != null) {
                changed.addAll(pomChangeAnalyzer.diffManagedPluginVersions(
                        pomChangeAnalyzer.getManagedPlugins(entry.getValue()),
                        pomChangeAnalyzer.getManagedPlugins(newModel)));
            }
        }
        return changed;
    }
}
