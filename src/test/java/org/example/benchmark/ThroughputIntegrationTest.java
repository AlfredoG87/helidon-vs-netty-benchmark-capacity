// SPDX-License-Identifier: Apache-2.0
package org.example.benchmark;

import org.example.client.HelidonThroughputClient;
import org.example.client.NettyThroughputClient;
import org.example.client.ThroughputClient;
import org.example.server.HelidonThroughputServer;
import org.example.server.NettyThroughputServer;
import org.example.server.ThroughputServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class ThroughputIntegrationTest {
    private static final long MESSAGE_COUNT = 1_000;
    private static final int SIZE_500KB = 500 * 1024;
    private static final int SIZE_1MB = 1 * 1024 * 1024;

    enum Impl { NETTY, HELIDON }

    private record Result(Impl server, Impl client, int payloadBytes, double seconds, double mbps) { }

    private static final List<Result> RESULTS = Collections.synchronizedList(new ArrayList<>());

    static Stream<Arguments> combinations() {
        return Stream.of(Impl.NETTY, Impl.HELIDON)
                .flatMap(server -> Stream.of(Impl.NETTY, Impl.HELIDON)
                        .flatMap(client -> Stream.of(SIZE_500KB, SIZE_1MB)
                                .map(size -> Arguments.of(server, client, size))));
    }

    @ParameterizedTest(name = "{0} server ↔ {1} client [{2} bytes]")
    @MethodSource("combinations")
    void clientServerMatrix(Impl serverImpl, Impl clientImpl, int payloadBytes) throws Exception {
        ThroughputServer server = createServer(serverImpl);
        try {
            server.start();
            int actualPort = server.port();
            assertTrue(actualPort > 0, "Server port should be assigned");

            ThroughputClient client = createClient(clientImpl, actualPort);
            long started = System.nanoTime();
            client.run(MESSAGE_COUNT, payloadBytes);
            double seconds = (System.nanoTime() - started) / 1_000_000_000.0;
            double totalBytes = MESSAGE_COUNT * (double) payloadBytes;
            double mbps = seconds > 0 ? (totalBytes / (1024.0 * 1024.0)) / seconds : 0.0;
            RESULTS.add(new Result(serverImpl, clientImpl, payloadBytes, seconds, mbps));
        } finally {
            server.stop();
            server.awaitTermination(Duration.ofSeconds(10));
        }
    }

    private static ThroughputServer createServer(Impl impl) {
        return switch (impl) {
            case NETTY -> new NettyThroughputServer(0);
            case HELIDON -> new HelidonThroughputServer(0);
        };
    }

    private static ThroughputClient createClient(Impl impl, int port) {
        return switch (impl) {
            case NETTY -> new NettyThroughputClient("localhost", port);
            case HELIDON -> new HelidonThroughputClient("http://localhost:" + port);
        };
    }

    @AfterAll
    static void printSummary() {
        System.out.println("══════════════════════════════════════════════════════════════");
        System.out.println(" Integration test throughput summary");
        System.out.println("   server   client   payloadKB   duration(s)   MB/s");
        System.out.println("--------------------------------------------------------------");
        synchronized (RESULTS) {
            RESULTS.stream()
                    .sorted((a, b) -> {
                        int cmp = a.server().compareTo(b.server());
                        if (cmp != 0) return cmp;
                        cmp = a.client().compareTo(b.client());
                        if (cmp != 0) return cmp;
                        return Integer.compare(a.payloadBytes(), b.payloadBytes());
                    })
                    .forEach(r -> System.out.printf(
                            " %7s %8s %10.1f %12.3f %8.2f%n",
                            r.server(),
                            r.client(),
                            r.payloadBytes() / 1024.0,
                            r.seconds(),
                            r.mbps()));
        }
        System.out.println("══════════════════════════════════════════════════════════════");
    }
}
