/*
 * Copyright (c) Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.scalpel.extension3.internal;

import java.util.List;
import java.util.Objects;
import java.util.Properties;
import javax.inject.Named;
import javax.inject.Singleton;
import org.apache.maven.model.Build;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
@Named
class EffectiveModelComparator {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    public boolean hasRelevantDifferences(Model oldModel, Model newModel) {
        if (!Objects.equals(oldModel.getPackaging(), newModel.getPackaging())) {
            logger.debug("Packaging changed: {} -> {}", oldModel.getPackaging(), newModel.getPackaging());
            return true;
        }

        if (!equalDependencies(oldModel.getDependencies(), newModel.getDependencies())) {
            logger.debug("Dependencies changed for {}:{}", newModel.getGroupId(), newModel.getArtifactId());
            return true;
        }

        if (oldModel.getDependencyManagement() != null || newModel.getDependencyManagement() != null) {
            List<Dependency> oldDm = oldModel.getDependencyManagement() != null
                    ? oldModel.getDependencyManagement().getDependencies()
                    : null;
            List<Dependency> newDm = newModel.getDependencyManagement() != null
                    ? newModel.getDependencyManagement().getDependencies()
                    : null;
            if (!equalDependencies(oldDm, newDm)) {
                logger.debug("DependencyManagement changed for {}:{}", newModel.getGroupId(), newModel.getArtifactId());
                return true;
            }
        }

        if (!equalProperties(oldModel.getProperties(), newModel.getProperties())) {
            logger.debug("Properties changed for {}:{}", newModel.getGroupId(), newModel.getArtifactId());
            return true;
        }

        if (!equalBuilds(oldModel.getBuild(), newModel.getBuild())) {
            logger.debug("Build changed for {}:{}", newModel.getGroupId(), newModel.getArtifactId());
            return true;
        }

        if (!equalRepositories(oldModel.getRepositories(), newModel.getRepositories())) {
            logger.debug("Repositories changed for {}:{}", newModel.getGroupId(), newModel.getArtifactId());
            return true;
        }

        if (!equalRepositories(oldModel.getPluginRepositories(), newModel.getPluginRepositories())) {
            logger.debug("PluginRepositories changed for {}:{}", newModel.getGroupId(), newModel.getArtifactId());
            return true;
        }

        return false;
    }

    private boolean equalDependencies(List<Dependency> a, List<Dependency> b) {
        if (a == null && b == null) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            Dependency da = a.get(i);
            Dependency db = b.get(i);
            if (!Objects.equals(da.getGroupId(), db.getGroupId())
                    || !Objects.equals(da.getArtifactId(), db.getArtifactId())
                    || !Objects.equals(da.getVersion(), db.getVersion())
                    || !Objects.equals(da.getScope(), db.getScope())
                    || !Objects.equals(da.getType(), db.getType())
                    || !Objects.equals(da.getClassifier(), db.getClassifier())
                    || !Objects.equals(da.isOptional(), db.isOptional())) {
                return false;
            }
        }
        return true;
    }

    private boolean equalProperties(Properties a, Properties b) {
        if (a == null && b == null) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return a.equals(b);
    }

    private boolean equalBuilds(Build a, Build b) {
        if (a == null && b == null) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        if (!Objects.equals(a.getSourceDirectory(), b.getSourceDirectory())) {
            return false;
        }
        if (!Objects.equals(a.getTestSourceDirectory(), b.getTestSourceDirectory())) {
            return false;
        }
        if (!equalPlugins(a.getPlugins(), b.getPlugins())) {
            return false;
        }
        if (a.getPluginManagement() != null || b.getPluginManagement() != null) {
            List<Plugin> oldPm =
                    a.getPluginManagement() != null ? a.getPluginManagement().getPlugins() : null;
            List<Plugin> newPm =
                    b.getPluginManagement() != null ? b.getPluginManagement().getPlugins() : null;
            if (!equalPlugins(oldPm, newPm)) {
                return false;
            }
        }
        return true;
    }

    private boolean equalPlugins(List<Plugin> a, List<Plugin> b) {
        if (a == null && b == null) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            Plugin pa = a.get(i);
            Plugin pb = b.get(i);
            if (!Objects.equals(pa.getGroupId(), pb.getGroupId())
                    || !Objects.equals(pa.getArtifactId(), pb.getArtifactId())
                    || !Objects.equals(pa.getVersion(), pb.getVersion())) {
                return false;
            }
            if (!Objects.equals(
                    pa.getConfiguration() != null ? pa.getConfiguration().toString() : null,
                    pb.getConfiguration() != null ? pb.getConfiguration().toString() : null)) {
                return false;
            }
        }
        return true;
    }

    private boolean equalRepositories(List<Repository> a, List<Repository> b) {
        if (a == null && b == null) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            Repository ra = a.get(i);
            Repository rb = b.get(i);
            if (!Objects.equals(ra.getId(), rb.getId())
                    || !Objects.equals(ra.getUrl(), rb.getUrl())
                    || !Objects.equals(ra.getLayout(), rb.getLayout())) {
                return false;
            }
        }
        return true;
    }
}
