// SPDX-License-Identifier: Apache-2.0
package org.example.cli;

import org.example.client.HelidonThroughputClient;
import org.example.client.NettyThroughputClient;
import org.example.client.ThroughputClient;
import org.example.logging.Logging;
import org.example.server.HelidonThroughputServer;
import org.example.server.NettyThroughputServer;
import org.example.server.ThroughputServer;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

public final class ThroughputBench {
    private ThroughputBench() {
    }

    public static void main(String[] args) throws Exception {
        Logging.init();
        if (args.length < 1) {
            usage();
            return;
        }
        String mode = args[0].toLowerCase(Locale.ROOT);
        switch (mode) {
            case "server" -> runServer(args);
            case "client" -> runClient(args);
            default -> usage();
        }
    }

    private static void runServer(String[] args) throws Exception {
        if (args.length < 3) {
            usage();
            return;
        }
        String type = args[1].toLowerCase(Locale.ROOT);
        int port = Integer.parseInt(args[2]);

        ThroughputServer server = switch (type) {
            case "netty" -> new NettyThroughputServer(port);
            case "helidon" -> new HelidonThroughputServer(port);
            default -> null;
        };
        if (server == null) {
            usage();
            return;
        }

        Runtime.getRuntime().addShutdownHook(new Thread(server::stop, type + "-server-shutdown"));
        server.start();
        server.blockUntilShutdown();
    }

    private static void runClient(String[] args) throws Exception {
        if (args.length < 5) {
            usage();
            return;
        }
        String type = args[1].toLowerCase(Locale.ROOT);
        String target = args[2];
        long numMsg = Long.parseLong(args[3]);
        long sizeKB = Long.parseLong(args[4]);
        int sizeBytes = Math.toIntExact(Math.min(sizeKB * 1024, (long) Integer.MAX_VALUE));

        ThroughputClient client;
        if (Objects.equals(type, "netty")) {
            String host;
            int port;
            if (target.startsWith("http")) {
                URI uri = URI.create(target);
                host = uri.getHost();
                port = uri.getPort() < 0 ? 80 : uri.getPort();
            } else {
                String[] hp = target.split(":", 2);
                host = hp[0];
                port = Integer.parseInt(hp[1]);
            }
            client = new NettyThroughputClient(host, port);
        } else if (Objects.equals(type, "helidon")) {
            client = new HelidonThroughputClient(target);
        } else {
            usage();
            return;
        }

        System.out.printf("🚀 Running %s client → %s — %,d msgs of %,d bytes%n",
                type, target, numMsg, sizeBytes);
        client.run(numMsg, sizeBytes);
    }

    private static void usage() {
        System.out.println("""
                Usage:
                  server <netty|helidon> <port>
                  client <netty|helidon> <host:port|url> <numMsg> <sizeKB>
                Examples:
                  ./gradlew run --args="server netty 9090"
                  ./gradlew run --args="server helidon 9090"
                  ./gradlew run --args="client netty localhost:9090 1000 64"
                  ./gradlew run --args="client helidon http://localhost:9090 1000 64"
                """);
    }
}
