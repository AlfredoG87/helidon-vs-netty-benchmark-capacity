// SPDX-License-Identifier: Apache-2.0
package org.example.client;

import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import org.example.throughput.ThroughputServiceGrpc;

public final class NettyThroughputClient implements ThroughputClient {
    private final String host;
    private final int port;

    public NettyThroughputClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    @Override
    public void run(long numMessages, int sizeBytes) throws Exception {
        ManagedChannel channel = NettyChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .flowControlWindow(32 * 1024 * 1024)
                .maxInboundMessageSize(32 * 1024 * 1024)
                .build();
        try {
            ThroughputServiceGrpc.ThroughputServiceStub stub = ThroughputServiceGrpc.newStub(channel);
            ClientRunner.run("netty", stub, numMessages, sizeBytes);
        } finally {
            channel.shutdownNow();
        }
    }
}
