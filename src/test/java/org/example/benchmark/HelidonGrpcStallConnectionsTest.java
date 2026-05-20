// SPDX-License-Identifier: Apache-2.0
package org.example.benchmark;

import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.StreamObserver;
import io.helidon.common.Size;
import io.helidon.common.tls.Tls;
import io.helidon.webclient.api.WebClient;
import io.helidon.webclient.grpc.GrpcClient;
import io.helidon.webclient.grpc.GrpcClientProtocolConfig;
import io.helidon.webclient.http2.Http2ClientProtocolConfig;
import io.helidon.webserver.WebServer;
import io.helidon.webserver.grpc.GrpcConfig;
import io.helidon.webserver.grpc.GrpcRouting;
import io.helidon.webserver.http2.Http2Config;
import io.grpc.ServerServiceDefinition;
import org.example.throughput.Ack;
import org.example.throughput.DataChunk;
import org.example.throughput.ThroughputServiceGrpc;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Helidon gRPC server — both client implementations.
 *
 * <p>Part of the 2×2 stall-connection matrix:
 * <pre>
 *                │ Helidon server (this file)   │ Netty server
 * ───────────────┼──────────────────────────────┼──────────────────────
 * Helidon client │ helidonClient_silentStall     │ NettyGrpcStallConnectionsTest
 * Netty client   │ nettyClient_deadlineExceeded  │ NettyGrpcStallConnectionsTest
 * </pre>
 *
 * <p>The two methods expose two distinct defects in Helidon 4.4.1:
 * <ol>
 *   <li>{@code helidonClient_silentStall} — the Helidon gRPC client never calls
 *       {@code onError}/{@code onCompleted} when the server stalls; even the gRPC
 *       deadline fails to surface an error to the observer.</li>
 *   <li>{@code nettyClient_deadlineExceeded} — the Netty client correctly fires
 *       {@code DEADLINE_EXCEEDED}, proving the stall is server-side. Comparing the
 *       two methods on the same payload isolates the second defect to Helidon's
 *       client-side deadline propagation.</li>
 * </ol>
 *
 * <pre>./gradlew test --tests org.example.benchmark.HelidonGrpcStallConnectionsTest --rerun-tasks</pre>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HelidonGrpcStallConnectionsTest {

    @BeforeAll
    static void suppressHelidonLogging() {
        Logger.getLogger("io.helidon").setLevel(Level.OFF);
    }

    private static final double ASSUMED_NETWORK_MB_PER_SEC = 25.0;
    private static final int STALL_GRACE_SECS = 3;

    private static int stallTimeoutSecs(int payloadBytes) {
        double transferSecs = (payloadBytes / (1024.0 * 1024.0)) / ASSUMED_NETWORK_MB_PER_SEC;
        int computed = (int) Math.ceil(transferSecs) + STALL_GRACE_SECS;
        return Math.max(computed, 5);
    }

    // ── Helidon client → Helidon server ──────────────────────────────────────

    /**
     * Helidon gRPC client against Helidon server.
     * Stalled streams produce no signal at all — onError/onCompleted are never called,
     * even after the gRPC deadline fires.
     */
    @ParameterizedTest(name = "{0} messages × {1} KB")
    @CsvSource({
            "100, 1024",
            "100, 2048",
            "100, 4090",
            // "100, 4096",
            // "100, 8192",
            // "100, 16384",
            // "100, 32768",
            // "100, 64000",
    })
    @Timeout(value = 60, unit = TimeUnit.MINUTES)
    void helidonClient_silentStall(int messageCount, int payloadKB) throws Exception {
        WebServer server = startHelidonServer();
        try {
            int stalls = measureWithHelidonClient(server.port(), payloadKB * 1024, messageCount);
            System.out.printf("%n[RESULT] %d/%d messages stalled (%s%%) — %d KB payload%n%n",
                    stalls, messageCount, pct(stalls, messageCount), payloadKB);
            assertEquals(0, stalls,
                    stalls + "/" + messageCount + " messages stalled silently against Helidon server. "
                            + "onError/onCompleted were never called — "
                            + "Helidon 4.4.1 defect: server-side errors are not propagated to the client observer.");
        } finally {
            server.stop();
        }
    }

    // ── Netty client → Helidon server ────────────────────────────────────────

    /**
     * Netty gRPC client against Helidon server.
     * Stalled streams surface as DEADLINE_EXCEEDED, confirming the stall is server-side.
     * Compare stall rates with {@link #helidonClient_silentStall} on the same payload.
     */
    @ParameterizedTest(name = "{0} messages × {1} KB")
    @CsvSource({
            "1000, 1024",
            "1000, 2048",
            "1000, 4090",
            //"100, 4096",
            //"100, 8192",
            //"100, 16384",
            //"100, 32768",
            //"100, 64000",
    })
    @Timeout(value = 60, unit = TimeUnit.MINUTES)
    void nettyClient_deadlineExceeded(int messageCount, int payloadKB) throws Exception {
        WebServer server = startHelidonServer();
        try {
            int stalls = measureWithNettyClient(server.port(), payloadKB * 1024, messageCount);
            System.out.printf("%n[RESULT] %d/%d messages stalled (%s%%) — %d KB payload%n%n",
                    stalls, messageCount, pct(stalls, messageCount), payloadKB);
            assertEquals(0, stalls,
                    stalls + "/" + messageCount + " messages received DEADLINE_EXCEEDED from Helidon server. "
                            + "The Netty client reports the stall correctly; the Helidon server is the root cause.");
        } finally {
            server.stop();
        }
    }

    // ── Server ────────────────────────────────────────────────────────────────

    private static WebServer startHelidonServer() {
        ServerServiceDefinition ssd = ThroughputServiceGrpc.bindService(new QuietEchoService());
        WebServer server = WebServer.builder()
                .port(0)
                .addProtocol(GrpcConfig.builder()
                        .enableCompression(false)
                        .enableMetrics(false)
                        .maxReadBufferSize(128 * 1024 * 1024)
                        .build())
                .addProtocol(Http2Config.builder()
                        .initialWindowSize(32 * 1024 * 1024)
                        .maxFrameSize(8 * 1024 * 1024)
                        .maxBufferedEntitySize(Size.parse("256 MB"))
                        .build())
                .addRouting(GrpcRouting.builder().service(ssd))
                .build();
        server.start();
        return server;
    }

    private static final class QuietEchoService extends ThroughputServiceGrpc.ThroughputServiceImplBase {
        @Override
        public StreamObserver<DataChunk> stream(StreamObserver<Ack> out) {
            return new StreamObserver<>() {
                @Override
                public void onNext(DataChunk chunk) {
                    out.onNext(Ack.newBuilder().setSeq(chunk.getSeq()).setOk(true).build());
                }

                @Override
                public void onError(Throwable t) {
                    out.onError(t);
                }

                @Override
                public void onCompleted() {
                    out.onCompleted();
                }
            };
        }
    }

    // ── Measurement ───────────────────────────────────────────────────────────

    private static int measureWithHelidonClient(int port, int payloadBytes, int messageCount)
            throws InterruptedException {
        ByteString payload = ByteString.copyFrom(new byte[payloadBytes]);

        WebClient webClient = WebClient.builder()
                .baseUri("http://localhost:" + port)
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

        ThroughputServiceGrpc.ThroughputServiceStub stub =
                ThroughputServiceGrpc.newStub(webClient.client(GrpcClient.PROTOCOL).channel());

        return runMeasurement(stub, null, payload, messageCount, "helidonClient→helidonServer");
    }

    private static int measureWithNettyClient(int port, int payloadBytes, int messageCount)
            throws InterruptedException {
        ByteString payload = ByteString.copyFrom(new byte[payloadBytes]);

        ManagedChannel channel = NettyChannelBuilder.forAddress("localhost", port)
                .usePlaintext()
                .flowControlWindow(8 * 1024 * 1024)
                .maxInboundMessageSize(128 * 1024 * 1024)
                .build();

        ThroughputServiceGrpc.ThroughputServiceStub stub =
                ThroughputServiceGrpc.newStub(channel);

        try {
            return runMeasurement(stub, channel, payload, messageCount, "nettyClient→helidonServer");
        } finally {
            channel.shutdownNow();
            channel.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static int runMeasurement(
            ThroughputServiceGrpc.ThroughputServiceStub stub,
            ManagedChannel channelToShutdown,
            ByteString payload,
            int messageCount,
            String label) throws InterruptedException {
        int payloadBytes = payload.size();
        int timeoutSecs = stallTimeoutSecs(payloadBytes);

        System.out.printf("%n[%s] %d messages × %d KB%n", label, messageCount, payloadBytes / 1024);
        System.out.printf("  stall timeout: %ds (%.1f MB @ %.0f MB/s + %ds grace)%n",
                timeoutSecs, payloadBytes / (1024.0 * 1024.0), ASSUMED_NETWORK_MB_PER_SEC, STALL_GRACE_SECS);

        int stalls = 0;
        for (int i = 0; i < messageCount; i++) {
            long startMs = System.currentTimeMillis();
            boolean stalled = sendOneMessage(stub, payload, i, timeoutSecs);
            long elapsedMs = System.currentTimeMillis() - startMs;

            if (stalled) {
                stalls++;
                System.out.printf("  [%d/%d] STALLED after %ds  — running: %d/%d stalled (%.0f%%)%n",
                        i + 1, messageCount, elapsedMs / 1_000,
                        stalls, i + 1, 100.0 * stalls / (i + 1));
            } else {
                System.out.printf("  [%d/%d] OK (%dms)%n", i + 1, messageCount, elapsedMs);
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
        return error.get() != null;
    }

    private static String pct(int stalls, int total) {
        return total == 0 ? "N/A" : String.format("%.0f", 100.0 * stalls / total);
    }
}
