// SPDX-License-Identifier: Apache-2.0
package org.example.client;

import com.google.protobuf.ByteString;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.example.common.Pretty;

import java.util.Locale;
import org.example.throughput.Ack;
import org.example.throughput.DataChunk;
import org.example.throughput.ThroughputServiceGrpc;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

final class ClientRunner {
    private ClientRunner() {
    }

    static void run(String implName,
                    ThroughputServiceGrpc.ThroughputServiceStub stub,
                    long numMsg,
                    int sizeBytes) throws InterruptedException {

        ByteString payload = ByteString.copyFrom(new byte[sizeBytes]);

        long startedNs = System.nanoTime();
        AtomicLong totalAcks = new AtomicLong();
        AtomicLong lastBytes = new AtomicLong();
        AtomicLong lastAcks = new AtomicLong();
        AtomicLong lastTime = new AtomicLong(System.nanoTime());
        AtomicReference<Throwable> error = new AtomicReference<>();

        // Cap in-flight at 1 for messages >= 2 MB to avoid HTTP/2 connection-window exhaustion
        // on implementations (e.g. Helidon) that don't auto-update the connection-level window.
        int maxInFlight = sizeBytes >= 2 * 1024 * 1024
                ? 1
                : Math.max(1, (int) Math.min(4, (32L * 1024 * 1024) / Math.max(1, sizeBytes)));
        Semaphore inFlight = new Semaphore(maxInFlight);

        ScheduledExecutorService reporter = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, implName + "-client-reporter");
            t.setDaemon(true);
            return t;
        });
        reporter.scheduleAtFixedRate(() -> {
            long now = System.nanoTime();
            long dt = now - lastTime.getAndSet(now);
            long acks = totalAcks.get();
            long deltaAcks = acks - lastAcks.getAndSet(acks);
            long bytes = acks * (long) sizeBytes;
            long deltaBytes = bytes - lastBytes.getAndSet(bytes);
            double sec = dt / 1_000_000_000.0;
            double mbps = sec > 0 ? (deltaBytes / (1024.0 * 1024.0)) / sec : 0.0;
            Pretty.tick("client", implName,
                    (System.nanoTime() - startedNs) / 1_000_000_000L,
                    mbps,
                    deltaAcks,
                    deltaBytes / (1024.0 * 1024.0));
        }, 1, 1, TimeUnit.SECONDS);

        CountDownLatch done = new CountDownLatch(1);
        StreamObserver<DataChunk> in = stub.stream(new StreamObserver<>() {
            @Override
            public void onNext(Ack ack) {
                totalAcks.incrementAndGet();
                inFlight.release();
            }

            @Override
            public void onError(Throwable t) {
                error.compareAndSet(null, t);
                done.countDown();
                reporter.shutdownNow();
                inFlight.release(maxInFlight);
            }

            @Override
            public void onCompleted() {
                done.countDown();
                reporter.shutdown();
            }
        });

        for (long i = 0; i < numMsg; i++) {
            boolean acquired = inFlight.tryAcquire(30, TimeUnit.SECONDS);
            if (!acquired) {
                error.compareAndSet(null, new RuntimeException("No ack within 30s — server stalled"));
            }
            Throwable err = error.get();
            if (err != null) {
                inFlight.release();
                break;
            }
            in.onNext(DataChunk.newBuilder()
                    .setSeq(i)
                    .setPayload(payload)
                    .build());
        }
        if (error.get() == null) {
            in.onCompleted();
        }
        boolean finished = done.await(120, TimeUnit.SECONDS);
        if (!finished) {
            error.compareAndSet(null, new RuntimeException("Stream timed out after 120s"));
        }

        double sec = (System.nanoTime() - startedNs) / 1_000_000_000.0;
        long delivered = totalAcks.get();
        Throwable err = error.get();

        Pretty.summary("client", implName, err == null ? numMsg : delivered,
                sizeBytes, delivered * (long) sizeBytes, sec);
        Pretty.resultLine(delivered, numMsg, sizeBytes, sec, classifyError(err));

        if (err != null) {
            if (err instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException("gRPC stream failed", err);
        }
    }

    private static String classifyError(Throwable t) {
        if (t == null) {
            return null;
        }
        if (t instanceof StatusRuntimeException sre) {
            return sre.getStatus().getCode().name();
        }
        return t.getClass().getSimpleName().toUpperCase(Locale.ROOT);
    }
}
