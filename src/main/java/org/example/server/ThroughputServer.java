// SPDX-License-Identifier: Apache-2.0
package org.example.server;

import java.time.Duration;

public interface ThroughputServer {
    void start() throws Exception;
    void blockUntilShutdown() throws InterruptedException;
    int port();
    default void stop() {
        // optional
    }
    default void awaitTermination(Duration timeout) throws InterruptedException {
        // optional
    }
}
