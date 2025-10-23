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

# start a Helidon server on port 50052
./gradlew run --args="server helidon 50052"

# drive it with the Netty client (host:port form)
./gradlew run --args="client netty localhost:50052 500 128"

# or using an HTTP URL when targeting Helidon
./gradlew run --args="client netty http://localhost:50052 500 128"
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

## Docker Images & Compose

To run the benchmark servers inside containers outside the test harness:

```bash
# 1. Build the distribution if you haven’t already
./gradlew distTar

# 2. Build images for the desired implementation (netty or helidon)
./docker/build.sh 0.0.1-SNAPSHOT netty
./docker/build.sh 0.0.1-SNAPSHOT helidon

# 3. (Optional) spin them up via docker compose
docker compose -f docker/docker-compose.yaml up --build
```

The compose file publishes Netty on `50051` and Helidon on `50052`. You can then point the local clients at `localhost:<port>` manually or through the integration test with `-PincludeDocker`.

---

## Example Output

Each run prints per-side summaries and an aggregated table. Below are captured summaries for both scenarios using the default payloads (1 000 messages × 500 KB and 1 000 messages × 1 MB).

### Local JVM Servers (sample run)

```
══════════════════════════════════════════════════════════════
 Integration test throughput summary (local JVM servers)
   server   client   payloadKB   messages   duration(s)   MB/s
--------------------------------------------------------------
   NETTY    NETTY      500.0       1000        0.549   890.04
   NETTY    NETTY     1024.0       1000        0.538  1859.22
   NETTY  HELIDON      500.0       1000        0.792   616.17
   NETTY  HELIDON     1024.0       1000        0.887  1127.74
 HELIDON    NETTY      500.0       1000        0.802   609.14
 HELIDON    NETTY     1024.0       1000        1.520   657.74
 HELIDON  HELIDON      500.0       1000        0.712   685.87
 HELIDON  HELIDON     1024.0       1000        1.589   629.14
══════════════════════════════════════════════════════════════
```

### Servers in Docker Containers (sample run)

```
══════════════════════════════════════════════════════════════
 Integration test throughput summary (servers in Docker containers)
   server   client   payloadKB   messages   duration(s)   MB/s
--------------------------------------------------------------
   NETTY    NETTY      500.0       1000        1.248   391.23
   NETTY    NETTY     1024.0       1000        1.510   662.11
   NETTY  HELIDON      500.0       1000        1.288   379.09
   NETTY  HELIDON     1024.0       1000        1.601   624.61
 HELIDON    NETTY      500.0       1000      119.379     4.09
 HELIDON    NETTY     1024.0       1000      109.930     9.10
 HELIDON  HELIDON      500.0       1000       89.173     5.48
 HELIDON  HELIDON     1024.0       1000      176.276     5.67
══════════════════════════════════════════════════════════════
```

Actual values fluctuate with hardware, Docker networking mode, and JVM headroom, but the relative regression (Helidon server dropping from ~600 MB/s locally to single-digit MB/s over a bridge network) has been consistent across runs.

---

## Notes

- Docker tests require a running Docker daemon and will pull the `eclipse-temurin:21` base image if not present.
- `./gradlew test` automatically generates the distribution (`installDist`) so the docker suite can mount it.
- gRPC clients limit to 4 concurrent in-flight messages to avoid overwhelming remote flow control windows.

Happy benchmarking!
