# Helidon vs Netty Throughput Bench

This project compares gRPC streaming throughput between Netty and Helidon using the same service implementation. It offers a single CLI entrypoint, reusable server/client components, and an automated test matrix that exercises every server/client combination at different payload sizes.

The suite can run entirely in a single JVM (baseline) or with the servers hosted inside Docker containers to highlight performance differences over real/virtual networks (known to impact Helidon heavily).

---

## Quick Start

```bash
# build everything and generate the runnable distribution under build/install
./gradlew build

# run a Netty server on port 50051
./gradlew run --args="server netty 50051"

# in another terminal, run a Helidon client against that server
./gradlew run --args="client helidon localhost:50051 1000 64"
```

Usage (from the CLI help):
```
server <netty|helidon> <port>
client <netty|helidon> <host:port|url> <numMsg> <sizeKB>
```

---

## Test Matrix

Two parameterized integration suites live under `src/test/java/org/example/benchmark/`:

| Test class | Description | How to run |
|------------|-------------|------------|
| `ThroughputIntegrationTest` | Spins up Netty/Helidon servers in-process and drives the 4×2 matrix of (server, client, payload) combinations. | `./gradlew test --tests org.example.benchmark.ThroughputIntegrationTest` |
| `DockerizedThroughputIntegrationTest` | Starts each server inside an `eclipse-temurin:21` container (mounts the local distribution), clients stay local. Tagged with `docker`. | `./gradlew test -PincludeDocker --tests org.example.benchmark.DockerizedThroughputIntegrationTest` |

> **Note** Docker tests are excluded from the default `./gradlew test`. Opt in via the `-PincludeDocker` property.

### Adjusting Message Count / Payload

Both suites share constants defined in `AbstractThroughputMatrixTest`:

```java
protected static final long MESSAGE_COUNT = 1_000;
protected static final int SIZE_500KB = 500 * 1024;
protected static final int SIZE_1MB = 1 * 1024 * 1024;
```

Change these values to customize the workloads; every combination (Netty ↔ Helidon × 500KB/1MB) will pick up the new settings automatically.

If you’d like to add more payload variants, adjust the `combinations()` stream in the same class.

---

## Example Output

Each run prints per-side summaries and an aggregated table. Below are captured summaries for both scenarios using the default payloads (1 000 messages × 500 KB and 1 000 messages × 1 MB).

### Local JVM Servers

```
══════════════════════════════════════════════════════════════
 Integration test throughput summary (local JVM servers)
   server   client   payloadKB   messages   duration(s)   MB/s
--------------------------------------------------------------
   NETTY    NETTY      500.0       1000        0.538   907.76
   NETTY    NETTY     1024.0       1000        0.558  1792.60
   NETTY  HELIDON      500.0       1000        0.862   566.69
   NETTY  HELIDON     1024.0       1000        0.784  1275.43
 HELIDON    NETTY      500.0       1000        0.782   624.40
 HELIDON    NETTY     1024.0       1000        1.521   657.49
 HELIDON  HELIDON      500.0       1000        0.808   604.47
 HELIDON  HELIDON     1024.0       1000        1.668   599.41
══════════════════════════════════════════════════════════════
```

### Servers in Docker Containers

```
══════════════════════════════════════════════════════════════
 Integration test throughput summary (servers in Docker containers)
   server   client   payloadKB   messages   duration(s)   MB/s
--------------------------------------------------------------
   NETTY    NETTY      500.0       1000        1.267   385.29
   NETTY    NETTY     1024.0       1000        1.677   596.43
   NETTY  HELIDON      500.0       1000        3.016   161.89
   NETTY  HELIDON     1024.0       1000        6.050   165.30
 HELIDON    NETTY      500.0       1000       62.026     7.87
 HELIDON    NETTY     1024.0       1000      166.324     6.29
 HELIDON  HELIDON      500.0       1000        6.037   160.38
 HELIDON  HELIDON     1024.0       1000        6.678   148.31
══════════════════════════════════════════════════════════════
```

These results highlight the significant regressions observed when Helidon serves traffic across a docker/network boundary (Helidon server ↔ Netty client drops to single-digit MB/s).

---

## Notes

- Docker tests require a running Docker daemon and will pull the `eclipse-temurin:21` base image if not present.
- `./gradlew test` automatically generates the distribution (`installDist`) so the docker suite can mount it.
- gRPC clients limit to 4 concurrent in-flight messages to avoid overwhelming remote flow control windows.

Happy benchmarking!
