/*
 * Copyright (c) Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.scalpel.core;

import java.util.Map;
import java.util.Set;

public final class ChangeDetectionResult {

    private final Set<String> changedFiles;
    private final Map<String, byte[]> oldPomContents;

    public ChangeDetectionResult(Set<String> changedFiles, Map<String, byte[]> oldPomContents) {
        this.changedFiles = changedFiles;
        this.oldPomContents = oldPomContents;
    }

    public Set<String> getChangedFiles() {
        return changedFiles;
    }

    public Map<String, byte[]> getOldPomContents() {
        return oldPomContents;
    }
}
