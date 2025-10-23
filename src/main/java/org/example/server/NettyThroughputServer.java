// SPDX-License-Identifier: Apache-2.0
package org.example.server;

import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;

import java.io.IOException;
import java.time.Duration;

public final class NettyThroughputServer implements ThroughputServer {
    private final int port;
    private Server server;

    public NettyThroughputServer(int port) {
        this.port = port;
    }

    @Override
    public void start() throws IOException {
        server = NettyServerBuilder.forPort(port)
                .flowControlWindow(32 * 1024 * 1024)
                .maxInboundMessageSize(32 * 1024 * 1024)
                .addService(new ThroughputServiceImpl("netty"))
                .build();
        server.start();
        System.out.printf("🚀 Netty server listening on %d%n", port());
    }

    @Override
    public void blockUntilShutdown() throws InterruptedException {
        if (server != null) {
            server.awaitTermination();
        }
    }

    @Override
    public int port() {
        return server != null ? server.getPort() : port;
    }

    @Override
    public void stop() {
        if (server != null) {
            server.shutdown();
        }
    }

    @Override
    public void awaitTermination(Duration timeout) throws InterruptedException {
        if (server != null) {
            server.awaitTermination(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        }
    }
}
