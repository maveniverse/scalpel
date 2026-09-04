/*
 * Copyright (c) Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.scalpel.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The outcome of a change-detection run: the changed file paths (from the diff between the base
 * and head commits, plus uncommitted and untracked files when enabled) and the base-commit contents
 * of changed POMs. The POM contents let later analysis decide whether a changed POM is materially
 * different (dependencies, plugins, properties) rather than treating every POM touch as a change.
 * Both collections are unmodifiable; the {@code byte[]} values inside the POM map are not
 * defensively copied and should be treated as read-only.
 */
public final class ChangeDetectionResult {

    private final Set<String> changedFiles;
    private final Map<String, byte[]> oldPomContents;

    /**
     * Constructs a change-detection result.
     *
     * @param changedFiles the changed file paths detected in the diff
     * @param oldPomContents base-commit contents of changed POMs, keyed by path
     */
    public ChangeDetectionResult(Set<String> changedFiles, Map<String, byte[]> oldPomContents) {
        this.changedFiles = Collections.unmodifiableSet(new LinkedHashSet<>(changedFiles));
        this.oldPomContents = Collections.unmodifiableMap(new LinkedHashMap<>(oldPomContents));
    }

    /** Returns the changed file paths detected in the diff. */
    public Set<String> getChangedFiles() {
        return changedFiles;
    }

    /** Returns the base-commit contents of changed POMs, keyed by path. */
    public Map<String, byte[]> getOldPomContents() {
        return oldPomContents;
    }
}
