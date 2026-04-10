/*
 * Copyright (c) Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.scalpel.extension3.internal;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import javax.inject.Named;
import javax.inject.Singleton;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.model.Profile;
import org.apache.maven.model.Repository;
import org.apache.maven.model.RepositoryPolicy;
import org.apache.maven.model.Resource;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
@Named
class PomChangeAnalyzer {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    /**
     * Result of POM change analysis.
     */
    static class Result {
        private final Set<MavenProject> affectedProjects;
        private final Set<String> changedManagedDependencyGAs;
        private final Set<String> changedManagedPluginGAs;
        private final Set<String> changedProperties;

        Result(
                Set<MavenProject> affectedProjects,
                Set<String> changedManagedDependencyGAs,
                Set<String> changedManagedPluginGAs,
                Set<String> changedProperties) {
            this.affectedProjects = affectedProjects;
            this.changedManagedDependencyGAs = changedManagedDependencyGAs;
            this.changedManagedPluginGAs = changedManagedPluginGAs;
            this.changedProperties = changedProperties;
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
    }

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
            Path reactorRoot) {

        Set<MavenProject> affected = new LinkedHashSet<>();
        Set<String> allChangedManagedDepGAs = new LinkedHashSet<>();
        Set<String> allChangedManagedPluginGAs = new LinkedHashSet<>();
        Set<String> allChangedProperties = new LinkedHashSet<>();

        // Build a map of relative POM path -> MavenProject
        Map<String, MavenProject> projectByPomPath = new LinkedHashMap<>();
        for (MavenProject project : allProjects) {
            Path pomPath = project.getFile().toPath().toAbsolutePath().normalize();
            Path relativePom = reactorRoot.toAbsolutePath().normalize().relativize(pomPath);
            projectByPomPath.put(relativePom.toString(), project);
        }

        // Build set of projects that have children in the reactor
        Set<MavenProject> parents = findParentProjects(allProjects);

        // Build map of reactor modules imported as BOMs by other reactor modules
        Map<MavenProject, List<MavenProject>> bomImporters = findBomImporters(allProjects);

        for (String changedPomPath : changedPomPaths) {
            MavenProject project = projectByPomPath.get(changedPomPath);
            if (project == null) {
                logger.debug("Changed POM {} does not match any reactor project, skipping", changedPomPath);
                continue;
            }

            // Determine all dependent modules (children via parent inheritance + BOM importers)
            List<MavenProject> dependents = collectDependents(project, parents, bomImporters, allProjects);

            if (dependents.isEmpty()) {
                // Leaf module: its own POM changed, mark it as affected
                logger.debug("Leaf module POM changed: {}", key(project));
                affected.add(project);
                continue;
            }

            // Parent/BOM POM changed: analyze what actually changed
            byte[] oldPomBytes = oldPomContents.get(changedPomPath);
            if (oldPomBytes == null) {
                // New POM (didn't exist in base), mark all dependents as affected
                if (logger.isDebugEnabled()) {
                    logger.debug("New parent/BOM POM: {}, marking all dependents as affected", key(project));
                }
                affected.add(project);
                affected.addAll(dependents);
                continue;
            }

            try {
                analyzeParentPomChange(
                        project,
                        oldPomBytes,
                        dependents,
                        reactorRoot,
                        affected,
                        allChangedManagedDepGAs,
                        allChangedManagedPluginGAs,
                        allChangedProperties);
            } catch (Exception e) {
                // If we can't parse the old POM, be conservative and mark all dependents
                logger.warn(
                        "Cannot parse old POM for {}, marking all dependents as affected: {}",
                        key(project),
                        e.getMessage());
                affected.add(project);
                affected.addAll(dependents);
            }
        }

        return new Result(affected, allChangedManagedDepGAs, allChangedManagedPluginGAs, allChangedProperties);
    }

    private void analyzeParentPomChange(
            MavenProject parentProject,
            byte[] oldPomBytes,
            List<MavenProject> dependentProjects,
            Path reactorRoot,
            Set<MavenProject> affected,
            Set<String> allChangedManagedDepGAs,
            Set<String> allChangedManagedPluginGAs,
            Set<String> allChangedProperties)
            throws IOException, XmlPullParserException {

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

        // Find changed properties
        Set<String> changedProperties = diffProperties(oldModel.getProperties(), newModel.getProperties());

        // Find changed managed dependencies (by groupId:artifactId)
        Set<String> changedManagedDeps = diffDependencyManagement(oldModel, newModel);

        // Find changed managed plugins (by groupId:artifactId)
        Set<String> changedManagedPlugins = diffPluginManagement(oldModel, newModel);

        // Analyze active profile changes (inactive profile changes are ignored)
        Set<String> activeProfileIds = getActiveProfileIds(parentProject);
        parentSelfAffected = parentSelfAffected
                || analyzeProfileChanges(
                        oldModel,
                        newModel,
                        activeProfileIds,
                        changedProperties,
                        changedManagedDeps,
                        changedManagedPlugins);

        // Collect all changed properties (after profile analysis has augmented the set)
        allChangedProperties.addAll(changedProperties);

        if (parentSelfAffected) {
            affected.add(parentProject);
        }

        // Resolve property indirection: if a managed dep/plugin uses a changed property
        // in its version (e.g. <version>${spring.version}</version>), it's effectively changed
        // even though the raw XML string didn't change
        if (!changedProperties.isEmpty()) {
            augmentWithPropertyReferences(changedProperties, changedManagedDeps, getManagedDependencies(oldModel));
            augmentWithPropertyReferences(changedProperties, changedManagedDeps, getManagedDependencies(newModel));
            augmentWithPluginPropertyReferences(changedProperties, changedManagedPlugins, getManagedPlugins(oldModel));
            augmentWithPluginPropertyReferences(changedProperties, changedManagedPlugins, getManagedPlugins(newModel));
            // Also check active profile-level managed deps/plugins for property indirection
            for (Profile profile : safeProfiles(oldModel)) {
                if (activeProfileIds.contains(profile.getId())) {
                    augmentWithPropertyReferences(
                            changedProperties, changedManagedDeps, getProfileManagedDependencies(profile));
                    augmentWithPluginPropertyReferences(
                            changedProperties, changedManagedPlugins, getProfileManagedPlugins(profile));
                }
            }
            for (Profile profile : safeProfiles(newModel)) {
                if (activeProfileIds.contains(profile.getId())) {
                    augmentWithPropertyReferences(
                            changedProperties, changedManagedDeps, getProfileManagedDependencies(profile));
                    augmentWithPluginPropertyReferences(
                            changedProperties, changedManagedPlugins, getProfileManagedPlugins(profile));
                }
            }
        }

        // Collect all changed managed GAs for transitive dependency and plugin checking
        allChangedManagedDepGAs.addAll(changedManagedDeps);
        allChangedManagedPluginGAs.addAll(changedManagedPlugins);

        if (changedProperties.isEmpty() && changedManagedDeps.isEmpty() && changedManagedPlugins.isEmpty()) {
            logger.debug("No inherited changes in {}", key(parentProject));
            return;
        }

        logger.debug(
                "Parent {} has inherited changes: properties={}, managedDeps={}, managedPlugins={}",
                key(parentProject),
                changedProperties,
                changedManagedDeps,
                changedManagedPlugins);

        // Check each dependent (child or BOM importer) to see if it's affected
        for (MavenProject child : dependentProjects) {
            if (affected.contains(child)) {
                continue;
            }

            boolean childAffected = false;

            // Check if child POM references any changed property
            if (!changedProperties.isEmpty()) {
                String childPomText = readPomText(child);
                if (childPomText != null) {
                    for (String prop : changedProperties) {
                        if (childPomText.contains("${" + prop + "}")) {
                            logger.debug("Child {} references changed property {}", key(child), prop);
                            childAffected = true;
                            break;
                        }
                    }
                }
            }

            // Check if child uses any changed managed dependency
            if (!childAffected && !changedManagedDeps.isEmpty()) {
                Model childRawModel = child.getOriginalModel();
                for (Dependency dep : childRawModel.getDependencies()) {
                    String ga = dep.getGroupId() + ":" + dep.getArtifactId();
                    if (changedManagedDeps.contains(ga)) {
                        logger.debug("Child {} uses changed managed dependency {}", key(child), ga);
                        childAffected = true;
                        break;
                    }
                }
            }

            // Check if child uses any changed managed plugin
            if (!childAffected && !changedManagedPlugins.isEmpty()) {
                Model childRawModel = child.getOriginalModel();
                for (Plugin plugin : getPlugins(childRawModel)) {
                    String ga = plugin.getGroupId() + ":" + plugin.getArtifactId();
                    if (changedManagedPlugins.contains(ga)) {
                        logger.debug("Child {} uses changed managed plugin {}", key(child), ga);
                        childAffected = true;
                        break;
                    }
                }
            }

            // Check if child has filtered resources referencing changed properties
            if (!childAffected && !changedProperties.isEmpty()) {
                if (hasFilteredResourcesWithChangedProperty(child, changedProperties)) {
                    logger.debug("Child {} has filtered resources referencing changed properties", key(child));
                    childAffected = true;
                }
            }

            if (childAffected) {
                affected.add(child);
            }
        }
    }

    /**
     * Analyze changes in active profiles between old and new POM models.
     * Returns true if the parent itself is affected (direct deps or plugins changed).
     * Accumulates property, managed dep, and managed plugin changes into the provided sets.
     */
    private boolean analyzeProfileChanges(
            Model oldModel,
            Model newModel,
            Set<String> activeProfileIds,
            Set<String> changedProperties,
            Set<String> changedManagedDeps,
            Set<String> changedManagedPlugins) {

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

            if (oldProfile == null && newProfile == null) {
                continue;
            }

            if (oldProfile == null || newProfile == null) {
                // Profile added or removed while active
                logger.debug("Active profile {} was {}", profileId, oldProfile == null ? "added" : "removed");
                if (newProfile != null) {
                    changedProperties.addAll(diffProperties(null, newProfile.getProperties()));
                    changedManagedDeps.addAll(diffDependencies(
                            Collections.<Dependency>emptyList(), getProfileManagedDependencies(newProfile)));
                    changedManagedPlugins.addAll(
                            diffPlugins(Collections.<Plugin>emptyList(), getProfileManagedPlugins(newProfile)));
                    if (!newProfile.getDependencies().isEmpty()
                            || !getProfilePlugins(newProfile).isEmpty()) {
                        selfAffected = true;
                    }
                } else {
                    changedProperties.addAll(diffProperties(oldProfile.getProperties(), null));
                    changedManagedDeps.addAll(diffDependencies(
                            getProfileManagedDependencies(oldProfile), Collections.<Dependency>emptyList()));
                    changedManagedPlugins.addAll(
                            diffPlugins(getProfileManagedPlugins(oldProfile), Collections.<Plugin>emptyList()));
                    if (!oldProfile.getDependencies().isEmpty()
                            || !getProfilePlugins(oldProfile).isEmpty()) {
                        selfAffected = true;
                    }
                }
                continue;
            }

            // Both exist — diff them
            changedProperties.addAll(diffProperties(oldProfile.getProperties(), newProfile.getProperties()));
            changedManagedDeps.addAll(diffDependencies(
                    getProfileManagedDependencies(oldProfile), getProfileManagedDependencies(newProfile)));
            changedManagedPlugins.addAll(
                    diffPlugins(getProfileManagedPlugins(oldProfile), getProfileManagedPlugins(newProfile)));

            if (!equalDependencyLists(oldProfile.getDependencies(), newProfile.getDependencies())) {
                logger.debug("Direct dependencies changed in active profile {}", profileId);
                selfAffected = true;
            }

            if (!equalPluginLists(getProfilePlugins(oldProfile), getProfilePlugins(newProfile))) {
                logger.debug("Direct plugins changed in active profile {}", profileId);
                selfAffected = true;
            }
        }

        return selfAffected;
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

    Set<String> diffDependencyManagement(Model oldModel, Model newModel) {
        List<Dependency> oldDeps = oldModel.getDependencyManagement() != null
                ? oldModel.getDependencyManagement().getDependencies()
                : new ArrayList<Dependency>();
        List<Dependency> newDeps = newModel.getDependencyManagement() != null
                ? newModel.getDependencyManagement().getDependencies()
                : new ArrayList<Dependency>();
        return diffDependencies(oldDeps, newDeps);
    }

    Set<String> diffPluginManagement(Model oldModel, Model newModel) {
        List<Plugin> oldPlugins =
                oldModel.getBuild() != null && oldModel.getBuild().getPluginManagement() != null
                        ? oldModel.getBuild().getPluginManagement().getPlugins()
                        : new ArrayList<Plugin>();
        List<Plugin> newPlugins =
                newModel.getBuild() != null && newModel.getBuild().getPluginManagement() != null
                        ? newModel.getBuild().getPluginManagement().getPlugins()
                        : new ArrayList<Plugin>();
        return diffPlugins(oldPlugins, newPlugins);
    }

    private Set<String> diffDependencies(List<Dependency> oldDeps, List<Dependency> newDeps) {
        Set<String> changed = new LinkedHashSet<>();
        Map<String, Dependency> oldMap = new LinkedHashMap<>();
        for (Dependency d : oldDeps) {
            oldMap.put(d.getGroupId() + ":" + d.getArtifactId(), d);
        }
        Map<String, Dependency> newMap = new LinkedHashMap<>();
        for (Dependency d : newDeps) {
            newMap.put(d.getGroupId() + ":" + d.getArtifactId(), d);
        }

        for (Map.Entry<String, Dependency> e : oldMap.entrySet()) {
            Dependency newDep = newMap.get(e.getKey());
            if (newDep == null || !equalDependency(e.getValue(), newDep)) {
                changed.add(e.getKey());
            }
        }
        for (String ga : newMap.keySet()) {
            if (!oldMap.containsKey(ga)) {
                changed.add(ga);
            }
        }
        return changed;
    }

    private Set<String> diffPlugins(List<Plugin> oldPlugins, List<Plugin> newPlugins) {
        Set<String> changed = new LinkedHashSet<>();
        Map<String, Plugin> oldMap = new LinkedHashMap<>();
        for (Plugin p : oldPlugins) {
            oldMap.put(p.getGroupId() + ":" + p.getArtifactId(), p);
        }
        Map<String, Plugin> newMap = new LinkedHashMap<>();
        for (Plugin p : newPlugins) {
            newMap.put(p.getGroupId() + ":" + p.getArtifactId(), p);
        }

        for (Map.Entry<String, Plugin> e : oldMap.entrySet()) {
            Plugin newPlugin = newMap.get(e.getKey());
            if (newPlugin == null || !equalPlugin(e.getValue(), newPlugin)) {
                changed.add(e.getKey());
            }
        }
        for (String ga : newMap.keySet()) {
            if (!oldMap.containsKey(ga)) {
                changed.add(ga);
            }
        }
        return changed;
    }

    private boolean equalDependency(Dependency a, Dependency b) {
        return Objects.equals(a.getGroupId(), b.getGroupId())
                && Objects.equals(a.getArtifactId(), b.getArtifactId())
                && Objects.equals(a.getVersion(), b.getVersion())
                && Objects.equals(a.getScope(), b.getScope())
                && Objects.equals(a.getType(), b.getType())
                && Objects.equals(a.getClassifier(), b.getClassifier())
                && Objects.equals(String.valueOf(a.isOptional()), String.valueOf(b.isOptional()));
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
            mapA.put(dep.getGroupId() + ":" + dep.getArtifactId(), dep);
        }
        for (Dependency dep : b) {
            String key = dep.getGroupId() + ":" + dep.getArtifactId();
            Dependency other = mapA.remove(key);
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
        return Collections.<Resource>emptyList();
    }

    private List<Resource> getTestResourcesList(Model model) {
        if (model.getBuild() != null && model.getBuild().getTestResources() != null) {
            return model.getBuild().getTestResources();
        }
        return Collections.<Resource>emptyList();
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
        return new HashSet<>(a).equals(new HashSet<>(b));
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
        return repos != null ? repos : Collections.<Repository>emptyList();
    }

    private List<Repository> safePluginRepositories(Model model) {
        List<Repository> repos = model.getPluginRepositories();
        return repos != null ? repos : Collections.<Repository>emptyList();
    }

    private List<Plugin> getPlugins(Model model) {
        if (model.getBuild() != null && model.getBuild().getPlugins() != null) {
            return model.getBuild().getPlugins();
        }
        return new ArrayList<>();
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
        return profiles != null ? profiles : Collections.<Profile>emptyList();
    }

    private List<Dependency> getProfileManagedDependencies(Profile profile) {
        if (profile.getDependencyManagement() != null
                && profile.getDependencyManagement().getDependencies() != null) {
            return profile.getDependencyManagement().getDependencies();
        }
        return Collections.<Dependency>emptyList();
    }

    private List<Plugin> getProfileManagedPlugins(Profile profile) {
        if (profile.getBuild() != null
                && profile.getBuild().getPluginManagement() != null
                && profile.getBuild().getPluginManagement().getPlugins() != null) {
            return profile.getBuild().getPluginManagement().getPlugins();
        }
        return Collections.<Plugin>emptyList();
    }

    private List<Plugin> getProfilePlugins(Profile profile) {
        if (profile.getBuild() != null && profile.getBuild().getPlugins() != null) {
            return profile.getBuild().getPlugins();
        }
        return Collections.<Plugin>emptyList();
    }

    private void augmentWithPropertyReferences(
            Set<String> changedProperties, Set<String> changedGAs, List<Dependency> dependencies) {
        for (Dependency dep : dependencies) {
            String ga = dep.getGroupId() + ":" + dep.getArtifactId();
            if (!changedGAs.contains(ga) && referencesAnyProperty(dep, changedProperties)) {
                changedGAs.add(ga);
            }
        }
    }

    private void augmentWithPluginPropertyReferences(
            Set<String> changedProperties, Set<String> changedGAs, List<Plugin> plugins) {
        for (Plugin plugin : plugins) {
            String ga = plugin.getGroupId() + ":" + plugin.getArtifactId();
            if (!changedGAs.contains(ga) && referencesAnyProperty(plugin, changedProperties)) {
                changedGAs.add(ga);
            }
        }
    }

    private boolean referencesAnyProperty(Dependency dep, Set<String> properties) {
        for (String prop : properties) {
            String ref = "${" + prop + "}";
            if (containsRef(dep.getVersion(), ref)
                    || containsRef(dep.getGroupId(), ref)
                    || containsRef(dep.getArtifactId(), ref)
                    || containsRef(dep.getScope(), ref)
                    || containsRef(dep.getType(), ref)
                    || containsRef(dep.getClassifier(), ref)) {
                return true;
            }
        }
        return false;
    }

    private boolean referencesAnyProperty(Plugin plugin, Set<String> properties) {
        for (String prop : properties) {
            String ref = "${" + prop + "}";
            if (containsRef(plugin.getVersion(), ref)
                    || containsRef(plugin.getGroupId(), ref)
                    || containsRef(plugin.getArtifactId(), ref)) {
                return true;
            }
            // Check plugin configuration
            if (plugin.getConfiguration() != null
                    && containsRef(plugin.getConfiguration().toString(), ref)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsRef(String value, String ref) {
        return value != null && value.contains(ref);
    }

    private Set<MavenProject> findParentProjects(List<MavenProject> allProjects) {
        Set<MavenProject> parents = new LinkedHashSet<>();
        for (MavenProject project : allProjects) {
            MavenProject parent = project.getParent();
            if (parent != null && allProjects.contains(parent)) {
                parents.add(parent);
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
        if (parents.contains(project)) {
            dependents.addAll(findChildren(project, allProjects));
        }
        List<MavenProject> importers = bomImporters.get(project);
        if (importers != null) {
            for (MavenProject importer : importers) {
                if (!dependents.contains(importer)) {
                    dependents.add(importer);
                }
            }
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
        MavenProject current = project.getParent();
        while (current != null) {
            if (current.equals(ancestor)) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }

    private boolean hasFilteredResourcesWithChangedProperty(MavenProject project, Set<String> changedProperties) {
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

        for (Resource resource : allResources) {
            if (!resource.isFiltering()) {
                continue;
            }
            String dir = resource.getDirectory();
            if (dir == null) {
                continue;
            }
            Path resourceDir = Paths.get(dir);
            if (!resourceDir.isAbsolute()) {
                resourceDir = project.getBasedir().toPath().resolve(resourceDir);
            }
            if (!Files.isDirectory(resourceDir)) {
                continue;
            }
            if (scanDirectoryForPropertyRefs(resourceDir, refs)) {
                return true;
            }
        }
        return false;
    }

    private boolean scanDirectoryForPropertyRefs(Path dir, List<String> refs) {
        List<Path> stack = new ArrayList<>();
        stack.add(dir);
        while (!stack.isEmpty()) {
            Path current = stack.remove(stack.size() - 1);
            DirectoryStream<Path> stream = null;
            try {
                stream = Files.newDirectoryStream(current);
                for (Path entry : stream) {
                    if (Files.isDirectory(entry)) {
                        stack.add(entry);
                    } else if (Files.isRegularFile(entry)) {
                        try {
                            String content = new String(Files.readAllBytes(entry), StandardCharsets.UTF_8);
                            for (String ref : refs) {
                                if (content.contains(ref)) {
                                    return true;
                                }
                            }
                        } catch (IOException e) {
                            // Skip binary or unreadable files
                        }
                    }
                }
            } catch (IOException e) {
                // Skip unreadable directories
            } finally {
                if (stream != null) {
                    try {
                        stream.close();
                    } catch (IOException e) {
                        // ignore
                    }
                }
            }
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

    private static String key(MavenProject project) {
        return project.getGroupId() + ":" + project.getArtifactId();
    }
}
