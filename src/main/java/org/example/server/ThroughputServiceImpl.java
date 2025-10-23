// SPDX-License-Identifier: Apache-2.0
package org.example.server;

import io.grpc.stub.StreamObserver;
import org.example.common.Pretty;
import org.example.throughput.Ack;
import org.example.throughput.DataChunk;
import org.example.throughput.ThroughputServiceGrpc;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Shared streaming service implementation for both Netty and Helidon servers.
 * Handles per-second reporting and ACKs each received chunk.
 */
public final class ThroughputServiceImpl extends ThroughputServiceGrpc.ThroughputServiceImplBase {
    private final String implName;

    public ThroughputServiceImpl(String implName) {
        this.implName = implName;
    }

    @Override
    public StreamObserver<DataChunk> stream(StreamObserver<Ack> out) {
        final long startedNs = System.nanoTime();
        final AtomicLong totalBytes = new AtomicLong();
        final AtomicLong totalMsgs = new AtomicLong();
        final AtomicLong lastBytes = new AtomicLong();
        final AtomicLong lastMsgs = new AtomicLong();
        final AtomicLong lastTime = new AtomicLong(System.nanoTime());
        final AtomicLong lastSize = new AtomicLong();

        ScheduledExecutorService reporter = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, implName + "-server-reporter");
            t.setDaemon(true);
            return t;
        });
        reporter.scheduleAtFixedRate(() -> {
            long now = System.nanoTime();
            long dt = now - lastTime.getAndSet(now);
            long bytes = totalBytes.get();
            long deltaBytes = bytes - lastBytes.getAndSet(bytes);
            long deltaMsgs = totalMsgs.get() - lastMsgs.getAndSet(totalMsgs.get());
            double sec = dt / 1_000_000_000.0;
            double mbps = sec > 0 ? (deltaBytes / (1024.0 * 1024.0)) / sec : 0.0;
            Pretty.tick("server", implName,
                    (System.nanoTime() - startedNs) / 1_000_000_000L,
                    mbps,
                    deltaMsgs,
                    deltaBytes / (1024.0 * 1024.0));
        }, 1, 1, TimeUnit.SECONDS);

        return new StreamObserver<>() {
            @Override
            public void onNext(DataChunk chunk) {
                int sz = chunk.getPayload().size();
                lastSize.set(sz);
                totalBytes.addAndGet(sz);
                totalMsgs.incrementAndGet();
                out.onNext(Ack.newBuilder().setSeq(chunk.getSeq()).setOk(true).build());
            }

            @Override
            public void onError(Throwable t) {
                reporter.shutdownNow();
            }

            @Override
            public void onCompleted() {
                double sec = (System.nanoTime() - startedNs) / 1_000_000_000.0;
                Pretty.summary("server", implName, totalMsgs.get(), lastSize.get(), totalBytes.get(), sec);
                out.onCompleted();
                reporter.shutdown();
            }
        };
    }
}
