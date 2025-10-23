// SPDX-License-Identifier: Apache-2.0
package org.example.benchmark;

import org.example.server.HelidonThroughputServer;
import org.example.server.NettyThroughputServer;
import org.example.server.ThroughputServer;
import org.junit.jupiter.api.DisplayName;

import java.time.Duration;

class ThroughputIntegrationTest extends AbstractThroughputMatrixTest {

    @Override
    protected ServerHandle startServer(Impl impl) throws Exception {
        ThroughputServer server = switch (impl) {
            case NETTY -> new NettyThroughputServer(0);
            case HELIDON -> new HelidonThroughputServer(0);
        };
        server.start();
        int port = server.port();
        return new ServerHandle("localhost", port, () -> {
            server.stop();
            server.awaitTermination(Duration.ofSeconds(10));
        });
    }

    @Override
    protected String summaryLabel() {
        return "local JVM servers";
    }
}
