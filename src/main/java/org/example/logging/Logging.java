// SPDX-License-Identifier: Apache-2.0
package org.example.logging;

import java.io.InputStream;
import java.util.logging.LogManager;

public final class Logging {
    private Logging() {
    }

    public static void init() {
        try (InputStream in = Thread.currentThread()
                .getContextClassLoader()
                .getResourceAsStream("logging.properties")) {
            if (in != null) {
                LogManager.getLogManager().readConfiguration(in);
            } else {
                System.err.println("logging.properties not found on classpath");
            }
        } catch (Exception e) {
            System.err.println("Failed to load JUL config: " + e);
        }
    }
}
