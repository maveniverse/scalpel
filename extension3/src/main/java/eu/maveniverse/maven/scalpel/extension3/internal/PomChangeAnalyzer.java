/*
 * Copyright (c) Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.scalpel.extension3.internal;

import static eu.maveniverse.maven.scalpel.extension3.internal.Projects.key;
import static java.util.Objects.requireNonNull;

import eu.maveniverse.maven.scalpel.core.ScalpelConfiguration;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.model.Profile;
import org.apache.maven.model.Repository;
import org.apache.maven.model.RepositoryPolicy;
import org.apache.maven.model.Resource;
import org.apache.maven.model.building.DefaultModelBuildingRequest;
import org.apache.maven.model.building.FileModelSource;
import org.apache.maven.model.building.ModelBuilder;
import org.apache.maven.model.building.ModelBuildingException;
import org.apache.maven.model.building.ModelBuildingRequest;
import org.apache.maven.model.building.ModelBuildingResult;
import org.apache.maven.model.building.ModelSource;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.model.resolution.InvalidRepositoryException;
import org.apache.maven.model.resolution.ModelResolver;
import org.apache.maven.model.resolution.UnresolvableModelException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.project.ProjectModelResolver;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.impl.RemoteRepositoryManager;
import org.eclipse.aether.repository.RemoteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
@Named
class PomChangeAnalyzer {

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final RepositorySystem repositorySystem;
    private final RemoteRepositoryManager remoteRepositoryManager;
    private final ModelBuilder modelBuilder;

    @Inject
    PomChangeAnalyzer(
            RepositorySystem repositorySystem,
            RemoteRepositoryManager remoteRepositoryManager,
            ModelBuilder modelBuilder) {
        this.repositorySystem = requireNonNull(repositorySystem, "repositorySystem");
        this.remoteRepositoryManager = requireNonNull(remoteRepositoryManager, "remoteRepositoryManager");
        this.modelBuilder = requireNonNull(modelBuilder, "modelBuilder");
    }

    /**
     * Result of POM change analysis.
     */
    static class Result {
        private final Set<MavenProject> affectedProjects;
        private final Set<String> changedManagedDependencyGAs;
        private final Set<String> changedManagedPluginGAs;
        private final Set<String> changedProperties;
        private final Map<MavenProject, Set<String>> evidence;
        private final List<String> unmatchedPomPaths;

        Result(
                Set<MavenProject> affectedProjects,
                Set<String> changedManagedDependencyGAs,
                Set<String> changedManagedPluginGAs,
                Set<String> changedProperties) {
            this(
                    affectedProjects,
                    changedManagedDependencyGAs,
                    changedManagedPluginGAs,
                    changedProperties,
                    Map.of(),
                    List.of());
        }

        Result(
                Set<MavenProject> affectedProjects,
                Set<String> changedManagedDependencyGAs,
                Set<String> changedManagedPluginGAs,
                Set<String> changedProperties,
                Map<MavenProject, Set<String>> evidence,
                List<String> unmatchedPomPaths) {
            this.affectedProjects = affectedProjects;
            this.changedManagedDependencyGAs = changedManagedDependencyGAs;
            this.changedManagedPluginGAs = changedManagedPluginGAs;
            this.changedProperties = changedProperties;
            this.evidence = evidence;
            this.unmatchedPomPaths = unmatchedPomPaths;
        }

        Set<MavenProject> getAffectedProjects() {
            return affectedProjects;
        }

        Set<String> getChangedManagedDependencyGAs() {
            return changedManagedDependencyGAs;
        }

        Set<String> getChangedManagedPluginGAs() {
            return changedManagedPluginGAs;
        }

        Set<String> getChangedProperties() {
            return changedProperties;
        }

        /**
         * Specific inputs that triggered each affected module (explain-mode evidence),
         * e.g. "property foo.version" or "managed dep g:a".
         */
        Map<MavenProject, Set<String>> getEvidence() {
            return evidence;
        }

        List<String> getUnmatchedPomPaths() {
            return unmatchedPomPaths;
        }
    }

    /**
     * Shared context for a single POM change analysis pass.
     * Groups lookup maps, effective models, and accumulators to avoid
     * threading many parameters through the analysis call chain.
     */
    private static class AnalysisContext {
        Map<String, MavenProject> projectByPomPath;
        Map<String, byte[]> oldPomContents;
        Map<String, Model> oldEffectiveModels;
        Map<String, Model> newEffectiveModels;
        Set<MavenProject> parents;
        Map<MavenProject, List<MavenProject>> bomImporters;
        List<MavenProject> allProjects;
        Path reactorRoot;
        long maxResourceFileSize;
        boolean explain;

        // Accumulators — populated during analysis
        final Set<MavenProject> affected = new LinkedHashSet<>();
        final Map<MavenProject, Set<String>> evidence = new LinkedHashMap<>();
        final Set<String> allChangedManagedDepGAs = new LinkedHashSet<>();
        final Set<String> allChangedManagedPluginGAs = new LinkedHashSet<>();
        final Set<String> allChangedProperties = new LinkedHashSet<>();
        final List<String> unmatchedPomPaths = new ArrayList<>();
    }

    /**
     * Groups Maven session state needed to resolve external parents and BOM imports
     * when building effective models from old POM content.
     */
    record ModelResolutionContext(
            Properties systemProperties,
            Properties userProperties,
            RepositorySystemSession repoSession,
            List<RemoteRepository> remoteRepositories) {}

    /**
     * Analyze POM changes and return the set of affected projects plus changed managed GAs.
     * <p>
     * For child/leaf POM changes: the module itself is marked as affected.
     * For parent/aggregator POM changes: only children that actually reference
     * changed properties, managed dependencies, or managed plugins are affected.
     * <p>
     * The changed managed dependency and plugin GAs are also returned so callers can check
     * transitive dependency trees and effective plugins for additional affected modules.
     */
    public Result analyzeChanges(
            Set<String> changedPomPaths,
            Map<String, byte[]> oldPomContents,
            List<MavenProject> allProjects,
            Path reactorRoot,
            long maxResourceFileSize,
            boolean explain,
            ModelResolutionContext resolutionCtx) {

        // Build a map of relative POM path -> MavenProject
        Map<String, MavenProject> projectByPomPath = new LinkedHashMap<>();
        for (MavenProject project : allProjects) {
            Path pomPath = project.getFile().toPath().toAbsolutePath().normalize();
            Path relativePom = reactorRoot.toAbsolutePath().normalize().relativize(pomPath);
            projectByPomPath.put(relativePom.toString().replace('\\', '/'), project);
        }

        // Build set of projects that have children in the reactor
        Set<MavenProject> parents = findParentProjects(allProjects);

        // Build map of reactor modules imported as BOMs by other reactor modules
        Map<MavenProject, List<MavenProject>> bomImporters = findBomImporters(allProjects);

        // Build effective models for the entire reactor using the same ModelBuilder
        // for both old and new states.  This symmetric approach ensures identical
        // lifecycle default plugin versions on both sides, preventing false positives.
        // Old state: changed POMs use their old bytes from git; unchanged POMs use current content.
        // New state: built from current POM files on disk.
        Map<String, Model> oldEffectiveModels =
                buildEffectiveModels(oldPomContents, allProjects, reactorRoot, resolutionCtx);
        Map<String, Model> newEffectiveModels = buildCurrentEffectiveModels(allProjects, reactorRoot, resolutionCtx);

        AnalysisContext ctx = new AnalysisContext();
        ctx.projectByPomPath = projectByPomPath;
        ctx.oldPomContents = oldPomContents;
        ctx.oldEffectiveModels = oldEffectiveModels;
        ctx.newEffectiveModels = newEffectiveModels;
        ctx.parents = parents;
        ctx.bomImporters = bomImporters;
        ctx.allProjects = allProjects;
        ctx.reactorRoot = reactorRoot;
        ctx.maxResourceFileSize = maxResourceFileSize;
        ctx.explain = explain;

        for (String changedPomPath : changedPomPaths) {
            analyzeChangedPom(changedPomPath, ctx);
        }

        if (!ctx.unmatchedPomPaths.isEmpty()) {
            logger.warn(
                    "Scalpel: {} changed POM(s) match no reactor project (profile-gated, excluded by -pl, "
                            + "or module removed); their changes are ignored: {}",
                    ctx.unmatchedPomPaths.size(),
                    ctx.unmatchedPomPaths);
        }
        logger.debug(
                "POM change analysis complete: {} affected modules, changedProperties={}, changedManagedDeps={}, changedManagedPlugins={}",
                ctx.affected.size(),
                ctx.allChangedProperties,
                ctx.allChangedManagedDepGAs,
                ctx.allChangedManagedPluginGAs);
        return new Result(
                ctx.affected,
                ctx.allChangedManagedDepGAs,
                ctx.allChangedManagedPluginGAs,
                ctx.allChangedProperties,
                ctx.evidence,
                ctx.unmatchedPomPaths);
    }

    private static void addEvidence(Map<MavenProject, Set<String>> evidence, MavenProject project, String item) {
        evidence.computeIfAbsent(project, k -> new LinkedHashSet<>()).add(item);
    }

    private void analyzeChangedPom(String changedPomPath, AnalysisContext ctx) {

        MavenProject project = ctx.projectByPomPath.get(changedPomPath);
        if (project == null) {
            ctx.unmatchedPomPaths.add(changedPomPath);
            logger.debug("Changed POM {} does not match any reactor project, skipping", changedPomPath);
            return;
        }

        // Determine all dependent modules (children via parent inheritance + BOM importers)
        List<MavenProject> dependents = collectDependents(project, ctx.parents, ctx.bomImporters, ctx.allProjects);

        if (dependents.isEmpty()) {
            // Leaf module: its own POM changed, mark it as affected
            logger.debug("Leaf module POM changed: {}", key(project));
            ctx.affected.add(project);
            if (ctx.explain) {
                addEvidence(ctx.evidence, project, "own pom " + changedPomPath + " changed");
            }
            return;
        }

        // Parent/BOM POM changed: analyze what actually changed
        byte[] oldPomBytes = ctx.oldPomContents.get(changedPomPath);
        if (oldPomBytes == null) {
            // New POM (didn't exist in base), mark all dependents as affected
            if (logger.isDebugEnabled()) {
                logger.debug("New parent/BOM POM: {}, marking all dependents as affected", key(project));
            }
            ctx.affected.add(project);
            ctx.affected.addAll(dependents);
            if (ctx.explain) {
                addEvidence(ctx.evidence, project, "new pom " + changedPomPath);
                for (MavenProject dependent : dependents) {
                    addEvidence(ctx.evidence, dependent, "new pom " + changedPomPath);
                }
            }
            return;
        }

        // Look up old effective model (built once at start)
        Model oldEffectiveModel = ctx.oldEffectiveModels.get(changedPomPath);
        if (oldEffectiveModel == null) {
            // Cannot build effective model, be conservative
            if (logger.isWarnEnabled()) {
                logger.warn(
                        "Cannot build effective model for old {}, marking all dependents as affected", key(project));
            }
            ctx.affected.add(project);
            ctx.affected.addAll(dependents);
            return;
        }

        try {
            analyzeParentPomChange(project, oldPomBytes, oldEffectiveModel, dependents, ctx);
        } catch (Exception e) {
            // If we can't parse the old POM, be conservative and mark all dependents
            logger.warn(
                    "Cannot parse old POM for {}, marking all dependents as affected: {}",
                    key(project),
                    e.getMessage());
            ctx.affected.add(project);
            ctx.affected.addAll(dependents);
            if (ctx.explain) {
                addEvidence(ctx.evidence, project, "unparseable old pom " + changedPomPath);
                for (MavenProject dependent : dependents) {
                    addEvidence(ctx.evidence, dependent, "unparseable old pom " + changedPomPath);
                }
            }
        }
    }

    private void analyzeParentPomChange(
            MavenProject parentProject,
            byte[] oldPomBytes,
            Model oldEffectiveModel,
            List<MavenProject> dependentProjects,
            AnalysisContext ctx)
            throws IOException, XmlPullParserException {

        // Raw models for parentSelfAffected checks (packaging, direct deps, etc.)
        MavenXpp3Reader reader = new MavenXpp3Reader();
        Model oldModel = reader.read(new ByteArrayInputStream(oldPomBytes));
        Model newModel = parentProject.getOriginalModel();

        boolean parentSelfAffected = false;

        // Check packaging
        if (!Objects.equals(oldModel.getPackaging(), newModel.getPackaging())) {
            logger.debug("Packaging changed in {}", key(parentProject));
            parentSelfAffected = true;
        }

        // Check direct dependencies (not managed)
        if (!equalDependencyLists(oldModel.getDependencies(), newModel.getDependencies())) {
            logger.debug("Direct dependencies changed in {}", key(parentProject));
            parentSelfAffected = true;
        }

        // Check direct plugins (not managed)
        if (!equalPluginLists(getPlugins(oldModel), getPlugins(newModel))) {
            logger.debug("Direct plugins changed in {}", key(parentProject));
            parentSelfAffected = true;
        }

        // Check source directories
        if (!equalSourceDirectories(oldModel, newModel)) {
            if (logger.isDebugEnabled()) {
                logger.debug("Source directories changed in {}", key(parentProject));
            }
            parentSelfAffected = true;
        }

        // Check repositories
        if (!equalRepositoryLists(safeRepositories(oldModel), safeRepositories(newModel))) {
            if (logger.isDebugEnabled()) {
                logger.debug("Repositories changed in {}", key(parentProject));
            }
            parentSelfAffected = true;
        }

        // Check plugin repositories
        if (!equalRepositoryLists(safePluginRepositories(oldModel), safePluginRepositories(newModel))) {
            if (logger.isDebugEnabled()) {
                logger.debug("Plugin repositories changed in {}", key(parentProject));
            }
            parentSelfAffected = true;
        }

        // Use effective models for property and managed dep/plugin diffs.
        // Effective models have properties interpolated and profiles merged.
        Model newEffectiveModel = ctx.newEffectiveModels.getOrDefault(
                ctx.reactorRoot
                        .toAbsolutePath()
                        .normalize()
                        .relativize(parentProject
                                .getFile()
                                .toPath()
                                .toAbsolutePath()
                                .normalize())
                        .toString()
                        .replace('\\', '/'),
                parentProject.getModel());
        Set<String> changedProperties =
                diffProperties(oldEffectiveModel.getProperties(), newEffectiveModel.getProperties());

        // Diff managed deps/plugins at parent level for transitive dependency tracking.
        // These GAs go into changedManagedDepGAs/changedManagedPluginGAs for
        // computeTransitivelyAffected — even if no direct child uses a managed dep,
        // it could be pulled in transitively by an external dependency.
        // Only report modifications/removals, not brand-new entries (issue #131).
        // Note: child-level impact uses effective dependency comparison (hasEffectiveChanges)
        // rather than GA matching against these sets — see the child analysis loop below.
        //
        // Managed deps: compare effective models (profile activation and parent inheritance
        // produce the correct merged dependency list).
        Set<String> changedManagedDeps =
                diffDependencies(getManagedDependencies(oldEffectiveModel), getManagedDependencies(newEffectiveModel));
        // Managed plugins: compare effective models directly.  Both old and new are
        // built by the same ModelBuilder so lifecycle defaults are identical on both
        // sides — no filtering against raw-model GAs is needed.
        Set<String> changedManagedPlugins =
                diffManagedPluginVersions(getManagedPlugins(oldEffectiveModel), getManagedPlugins(newEffectiveModel));

        ctx.allChangedManagedDepGAs.addAll(changedManagedDeps);
        ctx.allChangedManagedPluginGAs.addAll(changedManagedPlugins);

        // Check active profile direct deps/plugins for parentSelfAffected
        Set<String> activeProfileIds = getActiveProfileIds(parentProject);
        parentSelfAffected = parentSelfAffected || analyzeProfileChanges(oldModel, newModel, activeProfileIds);

        // Collect all changed properties
        ctx.allChangedProperties.addAll(changedProperties);

        if (parentSelfAffected) {
            ctx.affected.add(parentProject);
            if (ctx.explain) {
                addEvidence(
                        ctx.evidence,
                        parentProject,
                        "pom " + parentProject.getFile().getName() + " direct content changed");
            }
        }

        if (!changedProperties.isEmpty() || !changedManagedDeps.isEmpty() || !changedManagedPlugins.isEmpty()) {
            logger.debug(
                    "Parent {} has inherited changes: properties={}, managedDeps={}, managedPlugins={}",
                    key(parentProject),
                    changedProperties,
                    changedManagedDeps,
                    changedManagedPlugins);
        }

        Path absReactorRoot = ctx.reactorRoot.toAbsolutePath().normalize();

        // Check each dependent (child or BOM importer) for impact.
        // Compare effective dependencies and plugins (the resolved dependency tree and
        // merged plugin list, not managed declarations). This catches managed version
        // changes that actually affect the module's build, without false positives from
        // managed deps/plugins the module doesn't use (issue #131).
        for (MavenProject child : dependentProjects) {
            if (ctx.affected.contains(child)) {
                continue;
            }

            boolean childAffected = false;

            // PRIMARY: Compare effective dependencies and plugins (old vs new).
            // The effective model has dependencyManagement versions injected into
            // <dependencies>, so this detects managed version changes that flow
            // into the module's actual dependency tree.
            if (!childAffected) {
                childAffected = hasEffectiveChanges(child, absReactorRoot, ctx);
            }

            // Fallback: check if child POM text references any changed property.
            // Effective model comparison may not be available for all children
            // (e.g. when old effective models can't be built), so the text search
            // provides a safety net for property-driven changes.
            if (!childAffected && !changedProperties.isEmpty()) {
                String childPomText = readPomText(child);
                if (childPomText != null) {
                    for (String prop : changedProperties) {
                        if (childPomText.contains("${" + prop + "}")) {
                            logger.debug("Child {} references changed property {}", key(child), prop);
                            if (ctx.explain) {
                                addEvidence(ctx.evidence, child, "property " + prop);
                            }
                            childAffected = true;
                            break;
                        }
                    }
                }
            }

            // Check if child has filtered resources referencing changed properties.
            // Filtered resources are outside the POM model — effective model comparison
            // cannot detect property substitutions in resource files.
            if (!childAffected
                    && !changedProperties.isEmpty()
                    && hasFilteredResourcesWithChangedProperty(child, changedProperties, ctx.maxResourceFileSize)) {
                logger.debug("Child {} has filtered resources referencing changed properties", key(child));
                if (ctx.explain) {
                    addEvidence(ctx.evidence, child, "filtered resources referencing changed properties");
                }
                childAffected = true;
            }

            if (childAffected) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Child {} is DIRECTLY AFFECTED by parent {} change", key(child), key(parentProject));
                }
                ctx.affected.add(child);
                // Propagate managed dep/plugin version changes through affected child BOMs
                propagateEffectiveManagedChanges(child, absReactorRoot, ctx);
            } else {
                if (logger.isDebugEnabled()) {
                    logger.debug("Child {} is NOT affected by parent {} change", key(child), key(parentProject));
                }
            }
        }

        int affectedCount = 0;
        for (MavenProject dep : dependentProjects) {
            if (ctx.affected.contains(dep)) {
                affectedCount++;
            }
        }
        if (logger.isDebugEnabled()) {
            logger.debug(
                    "Parent {} analysis complete: {} of {} dependents affected",
                    key(parentProject),
                    affectedCount,
                    dependentProjects.size());
        }
    }

    /**
     * Propagate effective dependency/plugin changes through an affected child.
     * If the child's effective deps or plugins changed (e.g. due to an inherited
     * property change or managed version bump), the changed GAs are added to the
     * global sets for transitive dependency/plugin checking.
     */
    private void propagateEffectiveManagedChanges(MavenProject child, Path absReactorRoot, AnalysisContext ctx) {
        Path childPomPath = child.getFile().toPath().toAbsolutePath().normalize();
        String childRelPath = absReactorRoot.relativize(childPomPath).toString().replace('\\', '/');
        Model oldChildEffective = ctx.oldEffectiveModels.get(childRelPath);
        Model newChildEffective = ctx.newEffectiveModels.get(childRelPath);
        if (oldChildEffective == null || newChildEffective == null) {
            return;
        }

        // Effective dependencies: propagate changed GAs
        Set<String> childChangedDeps =
                diffDependencies(oldChildEffective.getDependencies(), newChildEffective.getDependencies());
        if (!childChangedDeps.isEmpty()) {
            if (logger.isDebugEnabled()) {
                logger.debug("Child {} propagates changed effective deps: {}", key(child), childChangedDeps);
            }
            ctx.allChangedManagedDepGAs.addAll(childChangedDeps);
        }

        // Managed dependencies: propagate changed GAs (important for BOMs that provide
        // managed deps to other modules — version changes must be tracked for transitive checking)
        Set<String> childChangedManagedDeps =
                diffDependencies(getManagedDependencies(oldChildEffective), getManagedDependencies(newChildEffective));
        if (!childChangedManagedDeps.isEmpty()) {
            if (logger.isDebugEnabled()) {
                logger.debug("Child {} propagates changed managed deps: {}", key(child), childChangedManagedDeps);
            }
            ctx.allChangedManagedDepGAs.addAll(childChangedManagedDeps);
        }

        // Managed plugins: propagate changed GAs directly.  Both old and new models
        // are built by the same ModelBuilder so no lifecycle-default filtering is needed.
        Set<String> childChangedManagedPlugins =
                diffManagedPluginVersions(getManagedPlugins(oldChildEffective), getManagedPlugins(newChildEffective));
        if (!childChangedManagedPlugins.isEmpty()) {
            if (logger.isDebugEnabled()) {
                logger.debug("Child {} propagates changed managed plugins: {}", key(child), childChangedManagedPlugins);
            }
            ctx.allChangedManagedPluginGAs.addAll(childChangedManagedPlugins);
        }
    }

    /**
     * Check if a child's effective dependencies or plugins changed between old and new models.
     * Compares the resolved dependency tree (effective dependencies) and effective plugin
     * list — a module is only affected if its actual build inputs change, not merely
     * because an unused managed dependency was added (issue #131).
     * <p>
     * Both old and new effective models are built by the same {@link ModelBuilder}
     * so lifecycle default plugin versions are identical on both sides, making direct
     * comparison safe without GA filtering.
     */
    private boolean hasEffectiveChanges(MavenProject child, Path absReactorRoot, AnalysisContext ctx) {
        Path childPomPath = child.getFile().toPath().toAbsolutePath().normalize();
        String childRelPath = absReactorRoot.relativize(childPomPath).toString().replace('\\', '/');
        Model oldChildEffective = ctx.oldEffectiveModels.get(childRelPath);
        Model newChildEffective = ctx.newEffectiveModels.get(childRelPath);
        if (oldChildEffective == null || newChildEffective == null) {
            return false;
        }

        boolean changed = false;

        // Compare effective dependencies — dependencyManagement versions are injected
        // into <dependencies> during model building, so this catches managed dep version
        // changes that affect the module's actual dependency tree.
        Set<String> changedDeps =
                diffEffectiveDependencies(oldChildEffective.getDependencies(), newChildEffective.getDependencies());
        if (!changedDeps.isEmpty()) {
            if (logger.isDebugEnabled()) {
                logger.debug("Child {} has changed effective dependencies: {}", key(child), changedDeps);
            }
            if (ctx.explain) {
                for (String ga : changedDeps) {
                    addEvidence(ctx.evidence, child, "effective dep " + ga);
                }
            }
            changed = true;
        }

        // Compare effective plugin VERSIONS only (not configuration/executions).
        // Configuration may differ due to inherited property interpolation changes, but
        // plugin configuration changes are already covered by the POM diff (the parent
        // POM is in the changeset).  Only version changes from managed plugin updates
        // indicate a real build-input change for the child.
        Set<String> changedPlugins = diffManagedPluginVersions(
                getEffectivePlugins(oldChildEffective), getEffectivePlugins(newChildEffective));
        if (!changedPlugins.isEmpty()) {
            if (logger.isDebugEnabled()) {
                logger.debug("Child {} has changed effective plugins: {}", key(child), changedPlugins);
            }
            if (ctx.explain) {
                for (String ga : changedPlugins) {
                    addEvidence(ctx.evidence, child, "effective plugin " + ga);
                }
            }
            changed = true;
        }

        return changed;
    }

    /**
     * Analyze changes in active profiles between old and new raw POM models.
     * Returns true if the parent itself is affected (direct deps or plugins changed
     * in an active profile). Property, managed dep, and managed plugin changes are
     * handled by the effective model comparison and do not need accumulation here.
     */
    private boolean analyzeProfileChanges(Model oldModel, Model newModel, Set<String> activeProfileIds) {

        boolean selfAffected = false;

        Map<String, Profile> oldProfiles = new LinkedHashMap<>();
        for (Profile p : safeProfiles(oldModel)) {
            oldProfiles.put(p.getId(), p);
        }
        Map<String, Profile> newProfiles = new LinkedHashMap<>();
        for (Profile p : safeProfiles(newModel)) {
            newProfiles.put(p.getId(), p);
        }

        for (String profileId : activeProfileIds) {
            Profile oldProfile = oldProfiles.get(profileId);
            Profile newProfile = newProfiles.get(profileId);
            if (isProfileSelfAffected(oldProfile, newProfile, profileId)) {
                selfAffected = true;
            }
        }

        return selfAffected;
    }

    private boolean isProfileSelfAffected(Profile oldProfile, Profile newProfile, String profileId) {
        if (oldProfile == null && newProfile == null) {
            return false;
        }
        if (oldProfile == null || newProfile == null) {
            // Profile added or removed while active
            logger.debug("Active profile {} was {}", profileId, oldProfile == null ? "added" : "removed");
            Profile existing = oldProfile != null ? oldProfile : newProfile;
            return !existing.getDependencies().isEmpty()
                    || !getProfilePlugins(existing).isEmpty();
        }
        // Both exist — check if direct deps or plugins changed
        boolean affected = false;
        if (!equalDependencyLists(oldProfile.getDependencies(), newProfile.getDependencies())) {
            logger.debug("Direct dependencies changed in active profile {}", profileId);
            affected = true;
        }
        if (!equalPluginLists(getProfilePlugins(oldProfile), getProfilePlugins(newProfile))) {
            logger.debug("Direct plugins changed in active profile {}", profileId);
            affected = true;
        }
        return affected;
    }

    private Set<String> getActiveProfileIds(MavenProject project) {
        Set<String> ids = new LinkedHashSet<>();
        List<Profile> activeProfiles = project.getActiveProfiles();
        if (activeProfiles != null) {
            for (Profile profile : activeProfiles) {
                ids.add(profile.getId());
            }
        }
        return ids;
    }

    Set<String> diffProperties(Properties oldProps, Properties newProps) {
        Set<String> changed = new LinkedHashSet<>();
        Properties a = oldProps != null ? oldProps : new Properties();
        Properties b = newProps != null ? newProps : new Properties();

        // Check changed/removed properties
        for (String name : a.stringPropertyNames()) {
            if (!Objects.equals(a.getProperty(name), b.getProperty(name))) {
                changed.add(name);
            }
        }
        // Check added properties
        for (String name : b.stringPropertyNames()) {
            if (!a.containsKey(name)) {
                changed.add(name);
            }
        }
        return changed;
    }

    private Set<String> diffDependencies(List<Dependency> oldDeps, List<Dependency> newDeps) {
        Set<String> changed = new LinkedHashSet<>();
        Map<String, Dependency> oldMap = new LinkedHashMap<>();
        for (Dependency d : oldDeps) {
            oldMap.put(dependencyKey(d), d);
        }
        Map<String, Dependency> newMap = new LinkedHashMap<>();
        for (Dependency d : newDeps) {
            newMap.put(dependencyKey(d), d);
        }

        // Only report modifications and removals — not brand-new entries (issue #131).
        // New managed deps/plugins cannot affect existing modules; the dependency tree
        // resolution naturally handles any downstream impact.
        for (Map.Entry<String, Dependency> e : oldMap.entrySet()) {
            Dependency newDep = newMap.get(e.getKey());
            if (newDep == null || !equalDependency(e.getValue(), newDep)) {
                // Report at GA level: downstream module matching resolves dependencies by GA,
                // so any change to a managed GA (regardless of classifier/type variant) should
                // flag all modules depending on that GA.
                changed.add(e.getValue().getGroupId() + ":" + e.getValue().getArtifactId());
            }
        }
        return changed;
    }

    /**
     * Diff two dependency lists reporting all changes (additions, modifications, removals).
     * Unlike {@link #diffDependencies} which only reports modifications/removals for managed
     * dependency tracking, this reports every difference — appropriate for comparing effective
     * dependency trees where any change (including new dependencies) indicates a build impact.
     */
    private Set<String> diffEffectiveDependencies(List<Dependency> oldDeps, List<Dependency> newDeps) {
        Set<String> changed = new LinkedHashSet<>();
        Map<String, Dependency> oldMap = new LinkedHashMap<>();
        for (Dependency d : oldDeps) {
            oldMap.put(dependencyKey(d), d);
        }
        Map<String, Dependency> newMap = new LinkedHashMap<>();
        for (Dependency d : newDeps) {
            newMap.put(dependencyKey(d), d);
        }

        // Modifications and removals
        for (Map.Entry<String, Dependency> e : oldMap.entrySet()) {
            Dependency newDep = newMap.get(e.getKey());
            if (newDep == null || !equalDependency(e.getValue(), newDep)) {
                changed.add(e.getValue().getGroupId() + ":" + e.getValue().getArtifactId());
            }
        }
        // Additions
        for (Map.Entry<String, Dependency> e : newMap.entrySet()) {
            if (!oldMap.containsKey(e.getKey())) {
                changed.add(e.getValue().getGroupId() + ":" + e.getValue().getArtifactId());
            }
        }
        return changed;
    }

    /**
     * Returns a key that distinguishes dependencies with the same GA but different classifier/type.
     */
    private static String dependencyKey(Dependency d) {
        String key = d.getGroupId() + ":" + d.getArtifactId();
        String type = d.getType();
        String classifier = d.getClassifier();
        if ((type != null && !"jar".equals(type)) || classifier != null) {
            key += ":" + (type != null ? type : "jar") + ":" + (classifier != null ? classifier : "");
        }
        return key;
    }

    /**
     * Compare managed plugins by GA + version only, ignoring configuration and executions.
     * <p>
     * Old effective models built with a standalone {@code DefaultModelBuilder} may produce
     * subtly different Xpp3Dom configuration/execution structures compared to the live reactor
     * model (e.g. attribute handling, default configuration merging).  These are model-building
     * artifacts, not real POM changes.  Real plugin configuration changes are already detected
     * by the POM file diff (the parent POM appears in the changeset).
     * <p>
     * Only report modifications and removals — not brand-new entries (issue #131).
     */
    private Set<String> diffManagedPluginVersions(List<Plugin> oldPlugins, List<Plugin> newPlugins) {
        Set<String> changed = new LinkedHashSet<>();
        Map<String, String> oldVersions = new LinkedHashMap<>();
        for (Plugin p : oldPlugins) {
            oldVersions.put(p.getGroupId() + ":" + p.getArtifactId(), p.getVersion());
        }
        Map<String, String> newVersions = new LinkedHashMap<>();
        for (Plugin p : newPlugins) {
            newVersions.put(p.getGroupId() + ":" + p.getArtifactId(), p.getVersion());
        }

        for (Map.Entry<String, String> e : oldVersions.entrySet()) {
            if (!newVersions.containsKey(e.getKey())) {
                // Plugin removed in new model
                changed.add(e.getKey());
            } else if (!Objects.equals(e.getValue(), newVersions.get(e.getKey()))) {
                // Version changed (including null → non-null or vice versa)
                changed.add(e.getKey());
            }
        }
        return changed;
    }

    private boolean equalDependency(Dependency a, Dependency b) {
        return Objects.equals(a.getGroupId(), b.getGroupId())
                && Objects.equals(a.getArtifactId(), b.getArtifactId())
                && Objects.equals(a.getVersion(), b.getVersion())
                && Objects.equals(normalizeScope(a.getScope()), normalizeScope(b.getScope()))
                && Objects.equals(normalizeType(a.getType()), normalizeType(b.getType()))
                && Objects.equals(a.getClassifier(), b.getClassifier())
                && Objects.equals(String.valueOf(a.isOptional()), String.valueOf(b.isOptional()));
    }

    /**
     * Normalize dependency scope: Maven defaults null scope to "compile".
     * The DefaultModelBuilder normalizes this, but raw parsed models may leave it null.
     */
    private static String normalizeScope(String scope) {
        return scope == null ? "compile" : scope;
    }

    /**
     * Normalize dependency type: Maven defaults null type to "jar".
     * The DefaultModelBuilder normalizes this, but raw parsed models may leave it null.
     */
    private static String normalizeType(String type) {
        return type == null ? "jar" : type;
    }

    private boolean equalPlugin(Plugin a, Plugin b) {
        return Objects.equals(a.getGroupId(), b.getGroupId())
                && Objects.equals(a.getArtifactId(), b.getArtifactId())
                && Objects.equals(a.getVersion(), b.getVersion())
                && equalConfiguration(a.getConfiguration(), b.getConfiguration())
                && equalExecutions(a.getExecutions(), b.getExecutions());
    }

    private boolean equalConfiguration(Object a, Object b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        if (a instanceof Xpp3Dom && b instanceof Xpp3Dom) {
            return equalXpp3Dom((Xpp3Dom) a, (Xpp3Dom) b);
        }
        return Objects.equals(a.toString(), b.toString());
    }

    private boolean equalXpp3Dom(Xpp3Dom a, Xpp3Dom b) {
        if (!Objects.equals(a.getName(), b.getName())) {
            return false;
        }
        // Compare values, treating null and empty/whitespace-only as equivalent
        String aVal = a.getValue() != null ? a.getValue().trim() : null;
        String bVal = b.getValue() != null ? b.getValue().trim() : null;
        if (aVal != null && aVal.isEmpty()) {
            aVal = null;
        }
        if (bVal != null && bVal.isEmpty()) {
            bVal = null;
        }
        if (!Objects.equals(aVal, bVal)) {
            return false;
        }
        // Compare attributes
        String[] aAttrs = a.getAttributeNames();
        String[] bAttrs = b.getAttributeNames();
        if (aAttrs.length != bAttrs.length) {
            return false;
        }
        for (String attr : aAttrs) {
            if (!Objects.equals(a.getAttribute(attr), b.getAttribute(attr))) {
                return false;
            }
        }
        // Compare children recursively
        if (a.getChildCount() != b.getChildCount()) {
            return false;
        }
        for (int i = 0; i < a.getChildCount(); i++) {
            if (!equalXpp3Dom(a.getChild(i), b.getChild(i))) {
                return false;
            }
        }
        return true;
    }

    private boolean equalExecutions(List<PluginExecution> a, List<PluginExecution> b) {
        if (a.size() != b.size()) {
            return false;
        }
        Map<String, PluginExecution> mapA = new LinkedHashMap<>();
        for (PluginExecution e : a) {
            mapA.put(e.getId(), e);
        }
        for (PluginExecution e : b) {
            PluginExecution other = mapA.remove(e.getId());
            if (other == null || !equalExecution(other, e)) {
                return false;
            }
        }
        return mapA.isEmpty();
    }

    private boolean equalExecution(PluginExecution a, PluginExecution b) {
        return Objects.equals(a.getId(), b.getId())
                && Objects.equals(a.getPhase(), b.getPhase())
                && Objects.equals(a.getGoals(), b.getGoals())
                && equalConfiguration(a.getConfiguration(), b.getConfiguration());
    }

    private boolean equalDependencyLists(List<Dependency> a, List<Dependency> b) {
        if (a.size() != b.size()) {
            return false;
        }
        Map<String, Dependency> mapA = new LinkedHashMap<>();
        for (Dependency dep : a) {
            mapA.put(dependencyKey(dep), dep);
        }
        for (Dependency dep : b) {
            Dependency other = mapA.remove(dependencyKey(dep));
            if (other == null || !equalDependency(other, dep)) {
                return false;
            }
        }
        return mapA.isEmpty();
    }

    private boolean equalPluginLists(List<Plugin> a, List<Plugin> b) {
        if (a.size() != b.size()) {
            return false;
        }
        Map<String, Plugin> mapA = new LinkedHashMap<>();
        for (Plugin plugin : a) {
            mapA.put(plugin.getGroupId() + ":" + plugin.getArtifactId(), plugin);
        }
        for (Plugin plugin : b) {
            String key = plugin.getGroupId() + ":" + plugin.getArtifactId();
            Plugin other = mapA.remove(key);
            if (other == null || !equalPlugin(other, plugin)) {
                return false;
            }
        }
        return mapA.isEmpty();
    }

    private boolean equalSourceDirectories(Model oldModel, Model newModel) {
        return Objects.equals(getSourceDirectory(oldModel), getSourceDirectory(newModel))
                && Objects.equals(getTestSourceDirectory(oldModel), getTestSourceDirectory(newModel))
                && Objects.equals(getScriptSourceDirectory(oldModel), getScriptSourceDirectory(newModel))
                && equalResourceLists(getResourcesList(oldModel), getResourcesList(newModel))
                && equalResourceLists(getTestResourcesList(oldModel), getTestResourcesList(newModel));
    }

    private String getSourceDirectory(Model model) {
        return model.getBuild() != null ? model.getBuild().getSourceDirectory() : null;
    }

    private String getTestSourceDirectory(Model model) {
        return model.getBuild() != null ? model.getBuild().getTestSourceDirectory() : null;
    }

    private String getScriptSourceDirectory(Model model) {
        return model.getBuild() != null ? model.getBuild().getScriptSourceDirectory() : null;
    }

    private List<Resource> getResourcesList(Model model) {
        if (model.getBuild() != null && model.getBuild().getResources() != null) {
            return model.getBuild().getResources();
        }
        return List.of();
    }

    private List<Resource> getTestResourcesList(Model model) {
        if (model.getBuild() != null && model.getBuild().getTestResources() != null) {
            return model.getBuild().getTestResources();
        }
        return List.of();
    }

    private boolean equalResourceLists(List<Resource> a, List<Resource> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!equalResource(a.get(i), b.get(i))) {
                return false;
            }
        }
        return true;
    }

    private boolean equalResource(Resource a, Resource b) {
        return Objects.equals(a.getDirectory(), b.getDirectory())
                && Objects.equals(a.getTargetPath(), b.getTargetPath())
                && equalStringListsUnordered(a.getIncludes(), b.getIncludes())
                && equalStringListsUnordered(a.getExcludes(), b.getExcludes())
                && a.isFiltering() == b.isFiltering();
    }

    private boolean equalStringListsUnordered(List<String> a, List<String> b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        if (a.size() != b.size()) {
            return false;
        }
        List<String> sortedA = new ArrayList<>(a);
        List<String> sortedB = new ArrayList<>(b);
        sortedA.sort(String::compareTo);
        sortedB.sort(String::compareTo);
        return sortedA.equals(sortedB);
    }

    private boolean equalRepositoryLists(List<Repository> a, List<Repository> b) {
        if (a.size() != b.size()) {
            return false;
        }
        Map<String, Repository> mapA = new LinkedHashMap<>();
        for (Repository repo : a) {
            mapA.put(repo.getId(), repo);
        }
        for (Repository repo : b) {
            Repository other = mapA.remove(repo.getId());
            if (other == null || !equalRepository(other, repo)) {
                return false;
            }
        }
        return mapA.isEmpty();
    }

    private boolean equalRepository(Repository a, Repository b) {
        return Objects.equals(a.getId(), b.getId())
                && Objects.equals(a.getUrl(), b.getUrl())
                && Objects.equals(a.getLayout(), b.getLayout())
                && equalRepositoryPolicy(a.getReleases(), b.getReleases())
                && equalRepositoryPolicy(a.getSnapshots(), b.getSnapshots());
    }

    private boolean equalRepositoryPolicy(RepositoryPolicy a, RepositoryPolicy b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return Objects.equals(a.isEnabled(), b.isEnabled())
                && Objects.equals(a.getUpdatePolicy(), b.getUpdatePolicy())
                && Objects.equals(a.getChecksumPolicy(), b.getChecksumPolicy());
    }

    private List<Repository> safeRepositories(Model model) {
        List<Repository> repos = model.getRepositories();
        return repos != null ? repos : List.of();
    }

    private List<Repository> safePluginRepositories(Model model) {
        List<Repository> repos = model.getPluginRepositories();
        return repos != null ? repos : List.of();
    }

    private List<Plugin> getPlugins(Model model) {
        if (model.getBuild() != null && model.getBuild().getPlugins() != null) {
            return model.getBuild().getPlugins();
        }
        return new ArrayList<>();
    }

    /**
     * Returns the effective plugins from an effective model.
     * On effective models, getBuild().getPlugins() already contains the merged result
     * of direct plugins + pluginManagement-resolved plugins.
     */
    private List<Plugin> getEffectivePlugins(Model model) {
        return getPlugins(model);
    }

    private List<Dependency> getManagedDependencies(Model model) {
        if (model.getDependencyManagement() != null
                && model.getDependencyManagement().getDependencies() != null) {
            return model.getDependencyManagement().getDependencies();
        }
        return new ArrayList<>();
    }

    private List<Plugin> getManagedPlugins(Model model) {
        if (model.getBuild() != null
                && model.getBuild().getPluginManagement() != null
                && model.getBuild().getPluginManagement().getPlugins() != null) {
            return model.getBuild().getPluginManagement().getPlugins();
        }
        return new ArrayList<>();
    }

    private List<Profile> safeProfiles(Model model) {
        List<Profile> profiles = model.getProfiles();
        return profiles != null ? profiles : List.of();
    }

    private List<Plugin> getProfilePlugins(Profile profile) {
        if (profile.getBuild() != null && profile.getBuild().getPlugins() != null) {
            return profile.getBuild().getPlugins();
        }
        return List.of();
    }

    /**
     * Build old effective models for all reactor POMs by reconstructing the old POM hierarchy
     * in a temporary directory. Changed POMs use their old content from git; unchanged POMs
     * use their current content. This is done ONCE at the start of analysis.
     * <p>
     * The effective models handle property interpolation, parent inheritance, and profile
     * activation — eliminating the need for manual property-to-managed-dep chasing.
     */
    Map<String, Model> buildEffectiveModels(
            Map<String, byte[]> oldPomContents,
            List<MavenProject> allProjects,
            Path reactorRoot,
            ModelResolutionContext resolutionCtx) {
        if (oldPomContents.isEmpty()) {
            return Map.of();
        }

        // Collect ALL active profile IDs across the entire reactor so parent-level
        // profiles propagate correctly to child modules during standalone model building.
        List<String> allActiveProfileIds = collectAllActiveProfileIds(allProjects);

        Path tempDir = null;
        try {
            tempDir = createSecureTempDirectory("scalpel-old-poms-");
            Path absRoot = reactorRoot.toAbsolutePath().normalize();

            // Reconstruct old POM hierarchy: changed POMs get old bytes,
            // unchanged POMs get their current content.
            reconstructPomHierarchy(tempDir, absRoot, allProjects, oldPomContents);

            // Build a GAV→file map for resolving reactor-local parents and BOM imports.
            Map<String, File> reactorPomsByGAV = new LinkedHashMap<>();
            for (MavenProject project : allProjects) {
                Path pomPath = project.getFile().toPath().toAbsolutePath().normalize();
                Path relativePom = absRoot.relativize(pomPath);
                Path tempPomPath = tempDir.resolve(relativePom);
                String gav = project.getGroupId() + ":" + project.getArtifactId() + ":" + project.getVersion();
                reactorPomsByGAV.put(gav, tempPomPath.toFile());
            }

            Map<String, Model> result = new LinkedHashMap<>();

            for (MavenProject project : allProjects) {
                Path pomPath = project.getFile().toPath().toAbsolutePath().normalize();
                Path relativePom = absRoot.relativize(pomPath);
                String relPath = relativePom.toString().replace('\\', '/');
                Path tempPomFile = tempDir.resolve(relativePom);

                Model model = buildSingleEffectiveModel(
                        this.modelBuilder, tempPomFile, relPath, allActiveProfileIds, resolutionCtx, reactorPomsByGAV);
                if (model != null) {
                    result.put(relPath, model);
                }
            }

            return result;
        } catch (IOException e) {
            logger.warn("Cannot create temp directory for old POM models: {}", e.getMessage());
            return Map.of();
        } finally {
            if (tempDir != null) {
                deleteRecursive(tempDir);
            }
        }
    }

    /**
     * Build effective models from the current (new) POM files on disk using the same
     * standalone ModelBuilder used for old models.  This ensures symmetric comparison:
     * both sides use identical lifecycle default plugin versions, so diffing effective
     * models produces no false positives from model-builder asymmetry.
     */
    Map<String, Model> buildCurrentEffectiveModels(
            List<MavenProject> allProjects, Path reactorRoot, ModelResolutionContext resolutionCtx) {
        List<String> allActiveProfileIds = collectAllActiveProfileIds(allProjects);
        Path absRoot = reactorRoot.toAbsolutePath().normalize();

        // Build a GAV→file map pointing to the actual (current) POM files.
        Map<String, File> reactorPomsByGAV = new LinkedHashMap<>();
        for (MavenProject project : allProjects) {
            String gav = project.getGroupId() + ":" + project.getArtifactId() + ":" + project.getVersion();
            reactorPomsByGAV.put(gav, project.getFile());
        }

        Map<String, Model> result = new LinkedHashMap<>();
        for (MavenProject project : allProjects) {
            Path pomPath = project.getFile().toPath().toAbsolutePath().normalize();
            String relPath = absRoot.relativize(pomPath).toString().replace('\\', '/');

            Model model = buildSingleEffectiveModel(
                    this.modelBuilder, pomPath, relPath, allActiveProfileIds, resolutionCtx, reactorPomsByGAV);
            if (model != null) {
                result.put(relPath, model);
            }
        }
        return result;
    }

    /**
     * Collect all active profile IDs from every project in the reactor.
     * Passing the full set to each ModelBuilder request ensures that parent-level
     * profiles (e.g. a profile in the root POM that adds managed dependencies)
     * are activated when building child effective models — the ModelBuilder
     * silently ignores IDs that don't match any profile in the current POM.
     */
    private static List<String> collectAllActiveProfileIds(List<MavenProject> allProjects) {
        Set<String> ids = new LinkedHashSet<>();
        for (MavenProject project : allProjects) {
            if (project.getActiveProfiles() != null) {
                for (Profile profile : project.getActiveProfiles()) {
                    ids.add(profile.getId());
                }
            }
        }
        return new ArrayList<>(ids);
    }

    private void reconstructPomHierarchy(
            Path tempDir, Path absRoot, List<MavenProject> allProjects, Map<String, byte[]> oldPomContents)
            throws IOException {
        for (MavenProject project : allProjects) {
            Path pomPath = project.getFile().toPath().toAbsolutePath().normalize();
            Path relativePom = absRoot.relativize(pomPath);
            Path tempPomFile = tempDir.resolve(relativePom);
            Files.createDirectories(tempPomFile.getParent());
            String relPath = relativePom.toString().replace('\\', '/');
            byte[] oldBytes = oldPomContents.get(relPath);
            if (oldBytes != null) {
                Files.write(tempPomFile, oldBytes);
            } else {
                Files.copy(pomPath, tempPomFile);
            }
        }
    }

    private Model buildSingleEffectiveModel(
            ModelBuilder modelBuilder,
            Path pomFile,
            String relPath,
            List<String> activeProfileIds,
            ModelResolutionContext resolutionCtx,
            Map<String, File> reactorPomsByGAV) {
        try {
            DefaultModelBuildingRequest request = new DefaultModelBuildingRequest();
            request.setPomFile(pomFile.toFile());
            request.setValidationLevel(ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL);

            ProjectModelResolver repoResolver = new ProjectModelResolver(
                    resolutionCtx.repoSession(),
                    null,
                    repositorySystem,
                    remoteRepositoryManager,
                    resolutionCtx.remoteRepositories() != null ? resolutionCtx.remoteRepositories() : List.of(),
                    ProjectBuildingRequest.RepositoryMerging.POM_DOMINANT,
                    null);
            request.setModelResolver(new ReactorFirstModelResolver(reactorPomsByGAV, repoResolver));
            request.setProcessPlugins(true);

            if (resolutionCtx.systemProperties() != null) {
                request.setSystemProperties(resolutionCtx.systemProperties());
            }
            if (resolutionCtx.userProperties() != null) {
                request.setUserProperties(resolutionCtx.userProperties());
            }

            if (!activeProfileIds.isEmpty()) {
                request.setActiveProfileIds(activeProfileIds);
            }

            ModelBuildingResult buildResult = modelBuilder.build(request);
            return buildResult.getEffectiveModel();
        } catch (ModelBuildingException e) {
            if (e.getResult() != null && e.getResult().getEffectiveModel() != null) {
                logger.debug("Partial effective model for old {}: {}", relPath, e.getMessage());
                return e.getResult().getEffectiveModel();
            }
            logger.debug("Cannot build effective model for old {}: {}", relPath, e.getMessage());
            return null;
        } catch (Exception e) {
            logger.debug("Cannot build effective model for old {}: {}", relPath, e.getMessage());
            return null;
        }
    }

    /**
     * Creates a temp directory with owner-only permissions on POSIX systems.
     * Falls back to default permissions on non-POSIX systems (Windows).
     */
    @SuppressWarnings("java:S5443") // False positive: Windows default temp dirs are already user-scoped via ACLs
    private static Path createSecureTempDirectory(String prefix) throws IOException {
        try {
            FileAttribute<Set<PosixFilePermission>> attr = PosixFilePermissions.asFileAttribute(Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
            return Files.createTempDirectory(prefix, attr);
        } catch (UnsupportedOperationException e) {
            // Non-POSIX filesystem (e.g. Windows) — fall back to default permissions
            return Files.createTempDirectory(prefix);
        }
    }

    private void deleteRecursive(Path dir) {
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                    Files.delete(d);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            logger.debug("Cannot clean up temp directory {}: {}", dir, e.getMessage());
        }
    }

    private Set<MavenProject> findParentProjects(List<MavenProject> allProjects) {
        Map<String, MavenProject> projectByGA = new LinkedHashMap<>();
        for (MavenProject project : allProjects) {
            projectByGA.put(key(project), project);
        }
        Set<MavenProject> parents = new LinkedHashSet<>();
        for (MavenProject project : allProjects) {
            MavenProject parent = project.getParent();
            if (parent != null) {
                MavenProject reactorParent = projectByGA.get(key(parent));
                if (reactorParent != null) {
                    parents.add(reactorParent);
                }
            }
        }
        return parents;
    }

    /**
     * Collect all modules that depend on the given project, combining reactor children
     * (via parent inheritance) and BOM importers (via import-scope dependency management).
     */
    private List<MavenProject> collectDependents(
            MavenProject project,
            Set<MavenProject> parents,
            Map<MavenProject, List<MavenProject>> bomImporters,
            List<MavenProject> allProjects) {
        List<MavenProject> dependents = new ArrayList<>();
        int childCount = 0;
        if (parents.contains(project)) {
            List<MavenProject> children = findChildren(project, allProjects);
            dependents.addAll(children);
            childCount = children.size();
            if (logger.isDebugEnabled()) {
                logger.debug("{} has {} child modules via parent inheritance", key(project), childCount);
            }
        }
        int bomCount = 0;
        List<MavenProject> importers = bomImporters.get(project);
        if (importers != null) {
            for (MavenProject importer : importers) {
                if (!dependents.contains(importer)) {
                    dependents.add(importer);
                    bomCount++;
                }
            }
            if (bomCount > 0 && logger.isDebugEnabled()) {
                logger.debug("{} has {} BOM importers", key(project), bomCount);
            }
        }
        if (childCount > 0 && bomCount > 0 && logger.isDebugEnabled()) {
            logger.debug(
                    "{} total dependents: {} ({} children + {} BOM importers)",
                    key(project),
                    dependents.size(),
                    childCount,
                    bomCount);
        }
        return dependents;
    }

    /**
     * Find reactor modules that are imported as BOMs by other reactor modules.
     * Scans each module's raw POM {@code <dependencyManagement>} for entries with
     * {@code <type>pom</type><scope>import</scope>} that reference another reactor module.
     *
     * @return map of BOM module to the list of modules that import it
     */
    Map<MavenProject, List<MavenProject>> findBomImporters(List<MavenProject> allProjects) {
        Map<String, MavenProject> projectByGA = new LinkedHashMap<>();
        for (MavenProject project : allProjects) {
            projectByGA.put(project.getGroupId() + ":" + project.getArtifactId(), project);
        }

        Map<MavenProject, List<MavenProject>> result = new LinkedHashMap<>();
        for (MavenProject project : allProjects) {
            collectBomImportsFrom(project, projectByGA, result);
        }
        return result;
    }

    private void collectBomImportsFrom(
            MavenProject project, Map<String, MavenProject> projectByGA, Map<MavenProject, List<MavenProject>> result) {
        for (Dependency dep : getManagedDependencies(project.getOriginalModel())) {
            if (!isImportScopeBom(dep)) {
                continue;
            }
            String ga = dep.getGroupId() + ":" + dep.getArtifactId();
            MavenProject bomProject = projectByGA.get(ga);
            if (bomProject != null && bomProject != project) {
                List<MavenProject> importers = result.computeIfAbsent(bomProject, k -> new ArrayList<>());
                if (!importers.contains(project)) {
                    importers.add(project);
                }
            }
        }
    }

    private boolean isImportScopeBom(Dependency dep) {
        return "pom".equals(dep.getType()) && "import".equals(dep.getScope());
    }

    private List<MavenProject> findChildren(MavenProject parent, List<MavenProject> allProjects) {
        List<MavenProject> children = new ArrayList<>();
        for (MavenProject project : allProjects) {
            if (project != parent && isDescendantOf(project, parent)) {
                children.add(project);
            }
        }
        return children;
    }

    private boolean isDescendantOf(MavenProject project, MavenProject ancestor) {
        String ancestorKey = key(ancestor);
        MavenProject current = project.getParent();
        while (current != null) {
            if (key(current).equals(ancestorKey)) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }

    private boolean hasFilteredResourcesWithChangedProperty(
            MavenProject project, Set<String> changedProperties, long maxResourceFileSize) {
        List<String> refs = new ArrayList<>();
        for (String prop : changedProperties) {
            refs.add("${" + prop + "}");
        }

        List<Resource> allResources = new ArrayList<>();
        if (project.getResources() != null) {
            allResources.addAll(project.getResources());
        }
        if (project.getTestResources() != null) {
            allResources.addAll(project.getTestResources());
        }

        int filteredDirCount = 0;
        for (Resource resource : allResources) {
            if (!resource.isFiltering()) {
                continue;
            }
            String dir = resource.getDirectory();
            if (dir == null) {
                continue;
            }
            Path resourceDir = Path.of(dir);
            if (!resourceDir.isAbsolute()) {
                resourceDir = project.getBasedir().toPath().resolve(resourceDir);
            }
            if (!Files.isDirectory(resourceDir)) {
                continue;
            }
            filteredDirCount++;
            logger.debug("Scanning filtered resource directory {} of {} for property refs", resourceDir, key(project));
            if (scanDirectoryForPropertyRefs(resourceDir, refs, maxResourceFileSize)) {
                logger.debug(
                        "Found property reference in filtered resources of {} (dir={})", key(project), resourceDir);
                return true;
            }
        }
        if (filteredDirCount > 0) {
            logger.debug(
                    "No property references found in {} filtered resource directories of {}",
                    filteredDirCount,
                    key(project));
        }
        return false;
    }

    private boolean scanDirectoryForPropertyRefs(Path dir, List<String> refs, long maxResourceFileSize) {
        List<Path> stack = new ArrayList<>();
        stack.add(dir);
        while (!stack.isEmpty()) {
            Path current = stack.remove(stack.size() - 1);
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(current)) {
                for (Path entry : stream) {
                    if (Files.isDirectory(entry)) {
                        stack.add(entry);
                    } else if (Files.isRegularFile(entry)
                            && checkFileForPropertyRefs(entry, refs, maxResourceFileSize)) {
                        return true;
                    }
                }
            } catch (IOException e) {
                // Skip unreadable directories
            }
        }
        return false;
    }

    private boolean checkFileForPropertyRefs(Path entry, List<String> refs, long maxResourceFileSize) {
        try {
            long size = Files.size(entry);
            // Read the first 8000 bytes to detect binary files (same heuristic as git)
            if (isBinaryFile(entry, size)) {
                logger.debug("Skipping binary file: {}", entry);
                return false;
            }
            if (size > maxResourceFileSize) {
                // Conservative: treat oversized text files as potentially affected
                logger.warn(
                        "Filtered resource {} ({} bytes) exceeds size limit ({} bytes)."
                                + " Module will be conservatively marked as affected."
                                + " Increase the limit with -D{}=<bytes> or disable filtering"
                                + " for this resource.",
                        entry,
                        size,
                        maxResourceFileSize,
                        ScalpelConfiguration.MAX_RESOURCE_FILE_SIZE);
                return true;
            }
            String content = new String(Files.readAllBytes(entry), StandardCharsets.UTF_8);
            for (String ref : refs) {
                if (content.contains(ref)) {
                    return true;
                }
            }
        } catch (IOException e) {
            // Skip unreadable files
        }
        return false;
    }

    /**
     * Detect binary files using the same heuristic as git: scan for a NUL byte (0x00)
     * in the first 8000 bytes of the file.
     */
    private static boolean isBinaryFile(Path file, long fileSize) {
        int bytesToRead = (int) Math.min(fileSize, 8000);
        if (bytesToRead == 0) {
            return false;
        }
        byte[] buffer = new byte[bytesToRead];
        try (InputStream in = Files.newInputStream(file)) {
            int read = in.read(buffer, 0, bytesToRead);
            for (int i = 0; i < read; i++) {
                if (buffer[i] == 0) {
                    return true;
                }
            }
        } catch (IOException e) {
            // If we can't read the file, treat as non-binary (will fail later when reading full content)
        }
        return false;
    }

    private String readPomText(MavenProject project) {
        try {
            return new String(Files.readAllBytes(project.getFile().toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.debug("Cannot read POM file for {}: {}", key(project), e.getMessage());
            return null;
        }
    }

    /**
     * ModelResolver that checks the reactor's temp dir for POM files before falling back
     * to the delegate resolver (which resolves from the local/remote repository).
     * This ensures that reactor-internal parents and BOM imports resolve from the temp dir
     * (which contains old POM content during effective model building) rather than from
     * the current local repository.
     */
    static class ReactorFirstModelResolver implements ModelResolver {
        private final Map<String, File> reactorPomsByGAV;
        private final ModelResolver delegate;

        ReactorFirstModelResolver(Map<String, File> reactorPomsByGAV, ModelResolver delegate) {
            this.reactorPomsByGAV = reactorPomsByGAV;
            this.delegate = delegate;
        }

        @Override
        public ModelSource resolveModel(String groupId, String artifactId, String version)
                throws UnresolvableModelException {
            File f = reactorPomsByGAV.get(groupId + ":" + artifactId + ":" + version);
            if (f != null && f.exists()) {
                return new FileModelSource(f);
            }
            return delegate.resolveModel(groupId, artifactId, version);
        }

        @Override
        public ModelSource resolveModel(Parent parent) throws UnresolvableModelException {
            return resolveModel(parent.getGroupId(), parent.getArtifactId(), parent.getVersion());
        }

        @Override
        public ModelSource resolveModel(Dependency dependency) throws UnresolvableModelException {
            return resolveModel(dependency.getGroupId(), dependency.getArtifactId(), dependency.getVersion());
        }

        @Override
        public void addRepository(Repository repository) throws InvalidRepositoryException {
            delegate.addRepository(repository);
        }

        @Override
        public void addRepository(Repository repository, boolean replace) throws InvalidRepositoryException {
            delegate.addRepository(repository, replace);
        }

        @Override
        public ModelResolver newCopy() {
            return new ReactorFirstModelResolver(reactorPomsByGAV, delegate.newCopy());
        }
    }
}
