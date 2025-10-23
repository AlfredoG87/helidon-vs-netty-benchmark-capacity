// SPDX-License-Identifier: Apache-2.0
package org.example.common;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Shared console reporting helpers for throughput metrics.
 */
public final class Pretty {
    private static final AtomicLong LAST_HEADER_MS = new AtomicLong();
    private static final long HEADER_EVERY_MS = 10_000;

    private Pretty() {
    }

    private static void headerIfNeeded() {
        long now = System.currentTimeMillis();
        long last = LAST_HEADER_MS.get();
        if (last == 0 || now - last >= HEADER_EVERY_MS) {
            if (LAST_HEADER_MS.compareAndSet(last, now)) {
                System.out.println("  side  impl     t(+s) |   MB/s");
                System.out.println("--------------------------------");
            }
        }
    }

    public static void tick(String side, String impl, long elapsedSec, double mbPerSec,
                            long msgs, double mbThisInterval) {
        headerIfNeeded();
        System.out.printf(Locale.ROOT, " %6s %-7s %7d | %7.2f   (%d msgs, %.2f MB)%n",
                side, impl, elapsedSec, mbPerSec, msgs, mbThisInterval);
    }

    public static void summary(String side, String impl, long messages, long sizeBytes,
                               long totalBytes, double seconds) {
        double mbps = seconds > 0 ? (totalBytes / (1024.0 * 1024.0)) / seconds : 0.0;
        String line = "══════════════════════════════════════════════════════════════";
        System.out.println(line);
        System.out.printf(Locale.ROOT, " SUMMARY  %s / %s%n", side, impl);
        System.out.printf(Locale.ROOT, "   messages     : %,d%n", messages);
        System.out.printf(Locale.ROOT, "   message size : %,d bytes%n", sizeBytes);
        System.out.printf(Locale.ROOT, "   total time   : %.3f s%n", seconds);
        System.out.printf(Locale.ROOT, "   throughput   : %.2f MB/s%n", mbps);
        System.out.println(line);
    }
}
