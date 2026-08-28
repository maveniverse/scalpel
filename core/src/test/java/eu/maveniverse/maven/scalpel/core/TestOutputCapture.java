/*
 * Copyright (c) Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.scalpel.core;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * Test utility capturing {@code System.err} while an action runs, for asserting on
 * log output (the test logging backend writes there and resolves the stream per call).
 */
final class TestOutputCapture {

    private TestOutputCapture() {}

    /**
     * Runs the action with {@code System.err} redirected to an in-memory buffer
     * and returns everything that was written during its execution.
     */
    static String captureStderr(Runnable action) {
        PrintStream originalErr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            action.run();
        } finally {
            System.setErr(originalErr);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }
}
