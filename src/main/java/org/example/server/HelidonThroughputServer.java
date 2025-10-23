// SPDX-License-Identifier: Apache-2.0
package org.example.server;

import io.grpc.ServerServiceDefinition;
import io.helidon.webserver.WebServer;
import io.helidon.webserver.grpc.GrpcRouting;
import org.example.throughput.ThroughputServiceGrpc;

import java.time.Duration;

public final class HelidonThroughputServer implements ThroughputServer {
    private final int port;
    private WebServer server;

    public HelidonThroughputServer(int port) {
        this.port = port;
    }

    @Override
    public void start() {
        ServerServiceDefinition ssd = ThroughputServiceGrpc.bindService(new ThroughputServiceImpl("helidon"));
        GrpcRouting.Builder grpc = GrpcRouting.builder().service(ssd);
        server = WebServer.builder()
                .port(port)
                .addRouting(grpc)
                .build();
        server.start();
        System.out.printf("🚀 Helidon server listening on %d%n", port());
    }

    @Override
    public void blockUntilShutdown() throws InterruptedException {
        while (server != null && server.isRunning()) {
            Thread.sleep(1_000);
        }
    }

    @Override
    public int port() {
        return server != null ? server.port() : port;
    }

    @Override
    public void stop() {
        if (server != null) {
            server.stop();
        }
    }

    @Override
    public void awaitTermination(Duration timeout) throws InterruptedException {
        if (server == null) {
            return;
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        while (server.isRunning() && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }
    }
}
