// SPDX-License-Identifier: Apache-2.0
package org.example.benchmark;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import io.helidon.common.Size;
import io.helidon.webclient.api.WebClient;
import io.helidon.webclient.grpc.GrpcClient;
import io.helidon.webclient.grpc.GrpcClientProtocolConfig;
import io.helidon.webclient.http2.Http2ClientProtocolConfig;
import io.helidon.webserver.WebServer;
import io.helidon.webserver.grpc.GrpcConfig;
import io.helidon.webserver.grpc.GrpcRouting;
import io.helidon.webserver.http2.Http2Config;
import com.google.protobuf.ByteString;
import io.helidon.common.tls.Tls;
import io.grpc.ServerServiceDefinition;
import org.example.throughput.Ack;
import org.example.throughput.DataChunk;
import org.example.throughput.ThroughputServiceGrpc;
import org.junit.jupiter.api.BeforeAll;
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
 * Characterises the deterministic Helidon 4.4.1 gRPC defect where messages exceeding
 * GrpcConfig.maxReadBufferSize (default 2 MB) cause a silent client stall — onError/onCompleted
 * are never invoked.
 * Root cause: GrpcProtocolHandler throws IllegalStateException("gRPC message size exceeds max
 * read buffer size") which is caught internally and never converted to a gRPC RESOURCE_EXHAUSTED
 * status. Every message over the limit stalls — hence deterministic.
 * A DataChunk with 2 MB payload serialises to 2,097,157 bytes (+5 bytes protobuf framing) —
 * 5 bytes over the 2,097,152-byte default limit.
 * <pre>./gradlew test --tests org.example.benchmark.HelidonGrpcMaxReadBufferTest --rerun-tasks</pre>
 */
class HelidonGrpcMaxReadBufferTest {

    @BeforeAll
    static void suppressAllHelidonLogging() {
        // Suppress all Helidon logging: frame hex dumps, flow-control timeouts, server start/stop, features.
        Logger.getLogger("io.helidon").setLevel(Level.OFF);
    }

    /** Assumed network bandwidth for timeout sizing (conservative; covers both local and K8s runs). */
    private static final double ASSUMED_NETWORK_MB_PER_SEC = 25.0;
    /** Grace period added on top of the computed transfer time before declaring a stall. */
    private static final int STALL_GRACE_SECS = 3;

    /** Computes a per-message stall timeout: transfer time at 25 MB/s + 3 s grace. */
    private static int stallTimeoutSecs(int payloadBytes) {
        double transferSecs = (payloadBytes / (1024.0 * 1024.0)) / ASSUMED_NETWORK_MB_PER_SEC;
        return (int) Math.ceil(transferSecs) + STALL_GRACE_SECS;
    }

    /**
     * Sends messages to a server with DEFAULT GrpcConfig (maxReadBufferSize = 2 MB) and HTTP/2
     * limits raised to 32 MB so only the gRPC-layer size limit is under test.
     * Expected: 1,024 KB → 0% stalls; 2,048 KB and 8,192 KB → 100% stalls.
     */
    @ParameterizedTest(name = "{0} messages × {1} KB — default config")
    @CsvSource({
            "100, 1024",
            "100, 2048",
            "100, 8192",
    })
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void exceedingDefaultMaxReadBuffer_silentlyStalls(int messageCount, int payloadKB) throws Exception {
        WebServer server = startServer(defaultGrpcConfig(), raisedHttp2Config());
        try {
            int stalls = measureStallProbability(server.port(), payloadKB * 1024, messageCount);
            System.out.printf("%n[RESULT] %d/%d messages stalled (%s%%) — %d KB payload%n%n",
                    stalls, messageCount, pct(stalls, messageCount), payloadKB);
            assertEquals(0, stalls,
                    stalls + "/" + messageCount + " messages stalled silently. "
                            + "Helidon 4.4.1 bug: GrpcProtocolHandler throws IllegalStateException "
                            + "(\"gRPC message size exceeds max read buffer size\") which is never "
                            + "propagated as a gRPC status. Fix: GrpcConfig.maxReadBufferSize(32 MB).");
        } finally {
            server.stop();
        }
    }

    // ── Server configs ────────────────────────────────────────────────────────

    /** Default GrpcConfig — triggers the maxReadBufferSize defect for messages > 2 MB. */
    private static GrpcConfig defaultGrpcConfig() {
        return GrpcConfig.builder()
                .enableCompression(false)
                .enableMetrics(false)
                .build();
    }

    /** Raises HTTP/2 transport limits to 32 MB so only the gRPC layer size limit is under test. */
    private static Http2Config raisedHttp2Config() {
        return Http2Config.builder()
                .initialWindowSize(32 * 1024 * 1024)
                .maxFrameSize(2 * 1024 * 1024)
                .maxBufferedEntitySize(Size.parse("32 MB"))
                .build();
    }

    private static WebServer startServer(GrpcConfig grpcConfig, Http2Config http2Config) {
        ServerServiceDefinition ssd = ThroughputServiceGrpc.bindService(new QuietEchoService());
        WebServer server = WebServer.builder()
                .port(0)
                .addProtocol(grpcConfig)
                .addProtocol(http2Config)
                .addRouting(GrpcRouting.builder().service(ssd))
                .build();
        server.start();
        return server;
    }

    /** Minimal service that acks each chunk with no reporting or scheduling overhead. */
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

    // ── Measurement helper ────────────────────────────────────────────────────

    /**
     * Sends messageCount messages each on its own stream, counting stall events independently.
     * Prints one result line per message the moment it completes or stalls.
     */
    private static int measureStallProbability(int port, int payloadBytes, int messageCount)
            throws InterruptedException {
        ByteString payload = ByteString.copyFrom(new byte[payloadBytes]);

        WebClient webClient = WebClient.builder()
                .baseUri("http://localhost:" + port)
                .tls(Tls.builder().enabled(false).build())
                .protocolConfigs(List.of(
                        Http2ClientProtocolConfig.builder()
                                .priorKnowledge(true)
                                .maxFrameSize(2 * 1024 * 1024)
                                .initialWindowSize(2 * 1024 * 1024)
                                .build(),
                        GrpcClientProtocolConfig.builder()
                                .initBufferSize(2 * 1024 * 1024)
                                .build()))
                .build();

        ThroughputServiceGrpc.ThroughputServiceStub stub =
                ThroughputServiceGrpc.newStub(webClient.client(GrpcClient.PROTOCOL).channel());

        System.out.printf("%n[HelidonGrpcMaxReadBufferTest] %d messages × %d KB%n",
                messageCount, payloadBytes / 1024);

        int timeoutSecs = stallTimeoutSecs(payloadBytes);
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

    /**
     * Opens one bidirectional stream, sends one message, and awaits the ack.
     * Returns true if no signal arrived within timeoutSecs (stall detected).
     * A DEADLINE_EXCEEDED error from our own deadline also counts as a stall.
     * Any other gRPC error (e.g. RESOURCE_EXHAUSTED) is treated as a non-stall signal.
     */
    private static boolean sendOneMessage(
            ThroughputServiceGrpc.ThroughputServiceStub stub,
            ByteString payload,
            int seq,
            int timeoutSecs) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();

        // Deadline ensures the stalled stream is properly cleaned up and onError fires.
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

        // Extra second buffer for the deadline signal to propagate after firing.
        boolean anySignal = done.await(timeoutSecs + 1, TimeUnit.SECONDS);
        if (!anySignal) {
            return true; // deadline mechanism failed to propagate — treat as stall
        }

        Throwable t = error.get();
        if (t instanceof StatusRuntimeException sre) {
            // DEADLINE_EXCEEDED means our own deadline fired — that is a stall.
            return sre.getStatus().getCode() == Status.Code.DEADLINE_EXCEEDED;
        }
        // onNext (ack) or onCompleted or any other error — not a stall.
        return false;
    }

    private static String pct(int stalls, int total) {
        return total == 0 ? "N/A" : String.format("%.0f", 100.0 * stalls / total);
    }
}
