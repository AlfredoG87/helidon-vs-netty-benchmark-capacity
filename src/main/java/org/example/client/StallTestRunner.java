// SPDX-License-Identifier: Apache-2.0
package org.example.client;

import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.StreamObserver;
import io.helidon.common.tls.Tls;
import io.helidon.webclient.api.WebClient;
import io.helidon.webclient.grpc.GrpcClient;
import io.helidon.webclient.grpc.GrpcClientProtocolConfig;
import io.helidon.webclient.http2.Http2ClientProtocolConfig;
import org.example.throughput.Ack;
import org.example.throughput.DataChunk;
import org.example.throughput.ThroughputServiceGrpc;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Sends N messages on separate streams to a gRPC server and counts stalls.
 * Supports both Helidon and Netty client implementations.
 * A stall = no ack/error/completion within the formula-based per-message deadline.
 *
 * Prints one result line per message, then a machine-readable STALL_RESULT line:
 *   STALL_RESULT stalls=2 total=100 payloadKB=4096 stallRate=2
 */
public final class StallTestRunner {

    private static final double ASSUMED_NETWORK_MB_PER_SEC = 25.0;
    private static final int STALL_GRACE_SECS = 5;

    private StallTestRunner() {
    }

    /** Backward-compatible entry point — defaults to Helidon client. */
    public static void run(String serverUrl, int numMessages, int payloadKB) throws InterruptedException {
        run("helidon", serverUrl, numMessages, payloadKB);
    }

    public static void run(String clientType, String serverUrl, int numMessages, int payloadKB)
            throws InterruptedException {
        int payloadBytes = payloadKB * 1024;
        int timeoutSecs = stallTimeoutSecs(payloadBytes);
        ByteString payload = ByteString.copyFrom(new byte[payloadBytes]);

        System.out.printf("[StallTest] %d messages × %d KB → %s (client: %s)%n",
                numMessages, payloadKB, serverUrl, clientType);
        System.out.printf("  stall timeout: %ds (%.1f MB @ %.0f MB/s + %ds grace)%n",
                timeoutSecs, payloadBytes / (1024.0 * 1024.0), ASSUMED_NETWORK_MB_PER_SEC, STALL_GRACE_SECS);

        int stalls;
        if ("netty".equals(clientType)) {
            stalls = runWithNettyClient(serverUrl, payload, numMessages, timeoutSecs);
        } else {
            stalls = runWithHelidonClient(serverUrl, payload, numMessages, timeoutSecs);
        }

        int stallRate = numMessages == 0 ? 0 : (int) Math.round(100.0 * stalls / numMessages);
        System.out.printf("STALL_RESULT stalls=%d total=%d payloadKB=%d stallRate=%d%n",
                stalls, numMessages, payloadKB, stallRate);
        System.out.flush();
    }

    private static int runWithHelidonClient(String serverUrl, ByteString payload, int numMessages, int timeoutSecs)
            throws InterruptedException {
        ThroughputServiceGrpc.ThroughputServiceStub stub = buildHelidonStub(serverUrl);
        int stalls = 0;
        for (int i = 0; i < numMessages; i++) {
            long startMs = System.currentTimeMillis();
            boolean stalled = sendOneMessage(stub, payload, i, timeoutSecs);
            long elapsedMs = System.currentTimeMillis() - startMs;

            if (stalled) {
                stalls++;
                // Recycle the connection: a stall exhausts the HTTP/2 connection-level flow
                // control window (server stops sending WINDOW_UPDATE), causing all subsequent
                // streams on the same connection to also stall. A fresh connection resets the
                // window and prevents cascade failures from inflating the stall count.
                stub = buildHelidonStub(serverUrl);
                System.out.printf("  [%d/%d] STALLED after %ds  — running: %d/%d stalled (%.0f%%)%n",
                        i + 1, numMessages, elapsedMs / 1_000,
                        stalls, i + 1, 100.0 * stalls / (i + 1));
            } else {
                System.out.printf("  [%d/%d] OK (%dms)%n", i + 1, numMessages, elapsedMs);
            }
            System.out.flush();
        }
        return stalls;
    }

    private static ThroughputServiceGrpc.ThroughputServiceStub buildHelidonStub(String serverUrl) {
        WebClient webClient = WebClient.builder()
                .baseUri(serverUrl)
                .tls(Tls.builder().enabled(false).build())
                .protocolConfigs(List.of(
                        Http2ClientProtocolConfig.builder()
                                .priorKnowledge(true)
                                .maxFrameSize(8 * 1024 * 1024)
                                .initialWindowSize(8 * 1024 * 1024)
                                .build(),
                        GrpcClientProtocolConfig.builder()
                                .initBufferSize(8 * 1024 * 1024)
                                .build()))
                .build();
        return ThroughputServiceGrpc.newStub(webClient.client(GrpcClient.PROTOCOL).channel());
    }

    private static int runWithNettyClient(String serverUrl, ByteString payload, int numMessages, int timeoutSecs)
            throws InterruptedException {
        String hostPort = serverUrl.contains("://") ? serverUrl.substring(serverUrl.indexOf("://") + 3) : serverUrl;
        String[] parts = hostPort.split(":", 2);
        String host = parts[0];
        int port = Integer.parseInt(parts[1]);

        ManagedChannel channel = NettyChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .flowControlWindow(8 * 1024 * 1024)
                .maxInboundMessageSize(128 * 1024 * 1024)
                .build();

        ThroughputServiceGrpc.ThroughputServiceStub stub = ThroughputServiceGrpc.newStub(channel);
        try {
            return measurementLoop(stub, payload, numMessages, timeoutSecs);
        } finally {
            channel.shutdownNow();
            channel.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static int measurementLoop(
            ThroughputServiceGrpc.ThroughputServiceStub stub,
            ByteString payload,
            int numMessages,
            int timeoutSecs) throws InterruptedException {
        int stalls = 0;
        for (int i = 0; i < numMessages; i++) {
            long startMs = System.currentTimeMillis();
            boolean stalled = sendOneMessage(stub, payload, i, timeoutSecs);
            long elapsedMs = System.currentTimeMillis() - startMs;

            if (stalled) {
                stalls++;
                System.out.printf("  [%d/%d] STALLED after %ds  — running: %d/%d stalled (%.0f%%)%n",
                        i + 1, numMessages, elapsedMs / 1_000,
                        stalls, i + 1, 100.0 * stalls / (i + 1));
            } else {
                System.out.printf("  [%d/%d] OK (%dms)%n", i + 1, numMessages, elapsedMs);
            }
            System.out.flush();
        }
        return stalls;
    }

    private static boolean sendOneMessage(
            ThroughputServiceGrpc.ThroughputServiceStub stub,
            ByteString payload,
            int seq,
            int timeoutSecs) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();

        ThroughputServiceGrpc.ThroughputServiceStub timedStub =
                stub.withDeadlineAfter(timeoutSecs, TimeUnit.SECONDS);

        StreamObserver<DataChunk> requestObserver = timedStub.stream(new StreamObserver<>() {
            @Override
            public void onNext(Ack ack) {
                done.countDown();
            }

            @Override
            public void onError(Throwable t) {
                error.set(t);
                done.countDown();
            }

            @Override
            public void onCompleted() {
                done.countDown();
            }
        });

        requestObserver.onNext(DataChunk.newBuilder().setSeq(seq).setPayload(payload).build());
        requestObserver.onCompleted();

        boolean anySignal = done.await(timeoutSecs + 1, TimeUnit.SECONDS);
        if (!anySignal) {
            return true;
        }

        // Any error means the message was not delivered — count as stall.
        return error.get() != null;
    }

    private static int stallTimeoutSecs(int payloadBytes) {
        double transferSecs = (payloadBytes / (1024.0 * 1024.0)) / ASSUMED_NETWORK_MB_PER_SEC;
        int computed = (int) Math.ceil(transferSecs) + STALL_GRACE_SECS;
        return Math.max(computed, 5);
    }
}
