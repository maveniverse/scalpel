/*
 * Copyright (c) Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.scalpel.core;

/**
 * Raised when Scalpel change detection fails. Observed when
 * {@link ScalpelConfiguration#isFailSafe()} is {@code false}; a fail-safe run (the default) logs the
 * error and falls back to a full build instead of propagating it.
 */
public class ScalpelException extends Exception {
    /**
     * Constructs a new exception with the specified detail message.
     *
     * @param message the detail message
     */
    public ScalpelException(String message) {
        super(message);
    }

    /**
     * Constructs a new exception with the specified detail message and cause.
     *
     * @param message the detail message
     * @param cause the cause
     */
    public ScalpelException(String message, Throwable cause) {
        super(message, cause);
    }
}
