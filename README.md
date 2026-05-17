# Helidon vs Netty — gRPC Throughput Benchmark

Compares gRPC bidirectional-streaming throughput between **Helidon 4.4.1** and **Netty 1.66.0** across four dimensions: server implementation, client implementation, payload size, and network chaos profile.

Three test suites are provided, each building on the previous:

| Suite | Where it runs | What it measures |
|-------|--------------|------------------|
| `ThroughputIntegrationTest` | local JVM | peak throughput, in-process |
| `KindThroughputIntegrationTest` | Kind (K8s) | throughput over virtual cluster network |
| `KindChaosIntegrationTest` | Kind (K8s) | resilience under latency / packet loss / bandwidth limits |

---

## Prerequisites

| Tool | Version | Install |
|------|---------|---------|
| Java | 25 (Zulu or Temurin) | `sdk install java 25.0.2-zulu` |
| Gradle | 9.1 (wrapper included) | `./gradlew` |
| Docker Desktop | any recent | [docker.com/desktop](https://www.docker.com/products/docker-desktop/) |
| kind | ≥ 0.24 | `brew install kind` |
| kubectl | ≥ 1.30 | `brew install kubectl` |

> **macOS Docker socket note:** Docker Desktop on macOS routes through an internal
> proxy at `192.168.65.1:3128`. If you see `kind` or `docker` commands failing,
> export the raw socket before running:
> ```bash
> export DOCKER_HOST=unix://${HOME}/Library/Containers/com.docker.docker/Data/docker.raw.sock
> ```
> The test classes auto-detect this path on macOS, so the export is only needed for
> manual shell commands and `k8s/run-benchmark.sh`.

---

## Project Layout

```
├── src/main/java/org/example/
│   ├── cli/            ThroughputBench.java    — CLI entrypoint (server & client modes)
│   ├── client/         NettyThroughputClient, HelidonThroughputClient, ClientRunner
│   ├── server/         NettyThroughputServer, HelidonThroughputServer, ThroughputServiceImpl
│   └── common/         Pretty.java             — console reporting helpers
├── src/test/java/org/example/benchmark/
│   ├── AbstractThroughputMatrixTest.java       — shared 4×2×4 parameterised matrix
│   ├── ThroughputIntegrationTest.java          — local JVM servers
│   ├── DockerizedThroughputIntegrationTest.java — Docker container servers
│   ├── KindThroughputIntegrationTest.java      — Kind cluster, port-forward client
│   └── KindChaosIntegrationTest.java           — Kind cluster, in-cluster client, tc-netem chaos
├── docker/
│   ├── Dockerfile                              — single image, server + client mode
│   └── build.sh                               — convenience build script
├── k8s/
│   ├── kind-config.yaml                        — 2-node cluster (control-plane + worker)
│   ├── namespace.yaml                          — benchmark namespace
│   ├── netty-server.yaml / helidon-server.yaml — server Deployments + Services
│   ├── client-job.yaml                         — client Job template (reference)
│   └── run-benchmark.sh                        — one-shot end-to-end script
└── charts/benchmark/                           — Helm chart for server deployments
```

---

## Quick Start — Local JVM

```bash
# Run all local tests (no Docker or Kubernetes required)
./gradlew test

# Run a server manually
./gradlew run --args="server netty 50051"
./gradlew run --args="server helidon 50052"

# Run a client manually
./gradlew run --args="client netty localhost:50051 1000 64"    # 1000 msgs × 64 KB
./gradlew run --args="client helidon http://localhost:50052 500 128"
```

---

## Running the Test Suites

### 1. Local JVM — `ThroughputIntegrationTest`

Starts servers in the test JVM; no external dependencies.

```bash
./gradlew test --tests org.example.benchmark.ThroughputIntegrationTest
```

### 2. Kind Cluster Baseline — `KindThroughputIntegrationTest`

Servers run as pods; the gRPC client connects via `kubectl port-forward`.

```bash
# One-shot (build → cluster → run → tear down)
bash k8s/run-benchmark.sh

# Or manually (reuse an existing cluster)
export DOCKER_HOST=unix://${HOME}/Library/Containers/com.docker.docker/Data/docker.raw.sock
./gradlew test -PincludeKind \
  --tests org.example.benchmark.KindThroughputIntegrationTest \
  --rerun-tasks
```

The test creates the `benchmark` cluster if it does not exist, deploys both servers,
runs all 16 combinations, then deletes the cluster.

### 3. Chaos Scenarios — `KindChaosIntegrationTest`

Both client **and** server run inside the Kind cluster. Network chaos is injected with
`tc netem` via `docker exec` into the Kind worker node — no external chaos operator needed.

```bash
# One-shot with default parameters
bash k8s/run-benchmark.sh --chaos

# Keep cluster alive after the run (useful for manual inspection)
bash k8s/run-benchmark.sh --chaos --no-teardown

# Tune message count and payload sizes
bash k8s/run-benchmark.sh --chaos --no-teardown \
  --test-args "-Dchaos.messages=500 -Dchaos.payloads=50,500"

# Or invoke Gradle directly (cluster must already be running)
export DOCKER_HOST=unix://${HOME}/Library/Containers/com.docker.docker/Data/docker.raw.sock
./gradlew test -PincludeChaos \
  --tests org.example.benchmark.KindChaosIntegrationTest \
  --rerun-tasks \
  -Dchaos.messages=200 \
  -Dchaos.payloads=5,50,500
```

#### Chaos profiles

| Profile | Added latency | Packet loss | Bandwidth cap |
|---------|--------------|-------------|---------------|
| `BASELINE` | — | — | — |
| `HIGH_LATENCY` | 100 ms ± 20 ms | — | — |
| `LOSSY` | — | 10 % | — |
| `CONGESTED` | — | — | 10 Mbps |
| `FULL_CHAOS` | 100 ms ± 20 ms | 10 % | 10 Mbps |

Profiles are composable constants in `KindChaosIntegrationTest`. Add or modify a
`ChaosProfile` record to experiment with different parameters — no other changes needed.

#### Tunable parameters

| System property | Default | Description |
|----------------|---------|-------------|
| `chaos.messages` | `50` | Messages sent per client Job |
| `chaos.payloads` | `5,50,500` | Comma-separated payload sizes in KB |

Pass with `-D` on the Gradle command line (see examples above).

---

## Rebuilding After Code Changes

Docker's layer cache can hide changes. Always use this sequence:

```bash
./gradlew distTar
export DOCKER_HOST=unix://${HOME}/Library/Containers/com.docker.docker/Data/docker.raw.sock

docker build --no-cache \
  --build-arg IMPLEMENTATION=netty --build-arg PORT=50051 \
  -t helidon-vs-netty-benchmark:0.0.1-SNAPSHOT-netty \
  -f docker/Dockerfile .

docker build --no-cache \
  --build-arg IMPLEMENTATION=helidon --build-arg PORT=50052 \
  -t helidon-vs-netty-benchmark:0.0.1-SNAPSHOT-helidon \
  -f docker/Dockerfile .

kind load docker-image helidon-vs-netty-benchmark:0.0.1-SNAPSHOT-netty   --name benchmark
kind load docker-image helidon-vs-netty-benchmark:0.0.1-SNAPSHOT-helidon --name benchmark
```

---

## Results

All results captured on the same host machine (macOS, Apple Silicon equivalent, Java 25 Zulu).
Protocol: gRPC bidirectional streaming over plaintext HTTP/2.

### Local JVM (1 000 messages)

```
══════════════════════════════════════════════════════════════
 Integration test throughput summary (local JVM servers)
   server   client   payloadKB   messages   duration(s)   MB/s
--------------------------------------------------------------
   NETTY    NETTY      500.0       1000        0.549    890
   NETTY    NETTY     1024.0       1000        0.538   1859
   NETTY  HELIDON      500.0       1000        0.792    616
   NETTY  HELIDON     1024.0       1000        0.887   1128
 HELIDON    NETTY      500.0       1000        0.802    609
 HELIDON    NETTY     1024.0       1000        1.520    658
 HELIDON  HELIDON      500.0       1000        0.712    686
 HELIDON  HELIDON     1024.0       1000        1.589    629
══════════════════════════════════════════════════════════════
```

### Kind Cluster — Baseline (200 messages, in-cluster client)

```
══════════════════════════════════════════════════════════════════════════════
 Summary — BASELINE
──────────────────────────────────────────────────────────────────────────────
  server   client  payloadKB  delivered/attempted     MB/s  error
──────────────────────────────────────────────────────────────────────────────
   NETTY    NETTY          5      200 / 200          6.03  OK
   NETTY    NETTY         50      200 / 200         59.43  OK
   NETTY    NETTY        500      200 / 200        427.41  OK
   NETTY  HELIDON          5      200 / 200          0.48  OK
   NETTY  HELIDON         50      200 / 200         55.33  OK
   NETTY  HELIDON        500      200 / 200        445.17  OK
 HELIDON    NETTY          5      200 / 200          6.27  OK
 HELIDON    NETTY         50      200 / 200         59.66  OK
 HELIDON    NETTY        500      200 / 200        445.99  OK
 HELIDON  HELIDON          5      200 / 200          0.44  OK
 HELIDON  HELIDON         50      200 / 200         62.55  OK
 HELIDON  HELIDON        500      200 / 200        422.13  OK
══════════════════════════════════════════════════════════════════════════════
```

### Kind Cluster — Chaos Scenarios (200 messages per profile)

#### HIGH_LATENCY — 100 ms added delay ± 20 ms

| server | client | 5 KB | 50 KB | 500 KB |
|--------|--------|------|-------|--------|
| Netty | Netty | 0.17 MB/s | 1.67 MB/s | 14.19 MB/s |
| Netty | Helidon | 0.08 MB/s | 1.32 MB/s | 12.60 MB/s |
| Helidon | Netty | 0.17 MB/s | 1.56 MB/s | 13.86 MB/s |
| Helidon | Helidon | 0.08 MB/s | 1.26 MB/s | 12.25 MB/s |

#### LOSSY — 10 % packet loss

| server | client | 5 KB | 50 KB | 500 KB |
|--------|--------|------|-------|--------|
| Netty | Netty | 1.19 MB/s | 9.63 MB/s | 22.66 MB/s |
| Netty | Helidon | 0.16 MB/s | 9.52 MB/s | 25.02 MB/s |
| Helidon | Netty | 0.80 MB/s | 5.87 MB/s | 16.03 MB/s |
| Helidon | Helidon | 0.21 MB/s | 3.85 MB/s | 35.75 MB/s |

#### CONGESTED — 10 Mbps bandwidth cap

| server | client | 5 KB | 50 KB | 500 KB |
|--------|--------|------|-------|--------|
| Netty | Netty | 1.03 MB/s | 1.17 MB/s | 1.19 MB/s |
| Netty | Helidon | 0.62 MB/s | 1.17 MB/s | 1.19 MB/s |
| Helidon | Netty | 1.01 MB/s | 1.17 MB/s | 1.19 MB/s |
| Helidon | Helidon | 0.30 MB/s | 1.16 MB/s | 1.19 MB/s |

#### FULL_CHAOS — 100 ms + 10 % loss + 10 Mbps

| server | client | 5 KB | 50 KB | 500 KB |
|--------|--------|------|-------|--------|
| Netty | Netty | 0.11 MB/s | 0.75 MB/s | 0.85 MB/s |
| Netty | Helidon | 0.06 MB/s | 0.67 MB/s | 0.91 MB/s |
| Helidon | Netty | 0.11 MB/s | 0.72 MB/s | 0.88 MB/s |
| Helidon | Helidon | 0.05 MB/s | 0.62 MB/s | 0.81 MB/s |

---

## Key Findings

**1. Both stacks are resilient — zero dropped connections under chaos.**
All 200 messages were delivered in every combination across all chaos profiles
(12 000 total messages). Neither Netty nor Helidon dropped or half-closed a stream
under 10 % packet loss, 100 ms latency, or 10 Mbps bandwidth cap.

**2. Helidon's HTTP/2 WebClient carries higher per-message overhead at small payloads.**
At 5 KB baseline, the Helidon client achieves 0.44–0.48 MB/s versus 6 MB/s for the
Netty client — a 12× gap. At 50 KB the gap narrows to ~1.1×. At 500 KB it disappears
entirely (both exceed 420 MB/s in-cluster). The overhead is in request framing, not
serialization.

**3. The 10 Mbps bandwidth cap is a hard ceiling regardless of implementation.**
All server/client combinations converge to exactly ~1.19 MB/s at 500 KB under
`CONGESTED` — precisely 10 Mbps ÷ 8 = 1.25 MB/s minus protocol overhead. `tc netem`'s
rate limiting applies uniformly to both stacks.

**4. Latency hurts small messages disproportionately.**
The client in-flight window is capped at 4 concurrent messages. With 100 ms added
RTT, that limits throughput to 4 messages / 0.1 s = 40 msg/s. For 5 KB messages this
is only 0.2 MB/s; for 500 KB it is 200 MB/s — well above the bandwidth cap. Hence
`HIGH_LATENCY` at 500 KB yields 13–14 MB/s while at 5 KB it collapses to 0.08–0.17 MB/s.

**5. TCP congestion window behaviour differs under packet loss.**
Under `LOSSY`, Helidon-server + Helidon-client at 500 KB reaches 35.75 MB/s versus
Netty+Netty at 22.66 MB/s. The asymmetry likely reflects different TCP send-window
growth strategies between the two HTTP/2 implementations after loss-induced backoff.

---

## Troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| Tests skipped immediately | `kind`/`kubectl`/`docker` not on PATH | `brew install kind kubectl` |
| `kind` commands fail with 500 | Docker Desktop stub socket | `export DOCKER_HOST=unix://…/docker.raw.sock` |
| `image not found` in pod | New image not loaded into Kind | Re-run `kind load docker-image …` |
| Old code running in container | Docker build used stale cache | Use `docker build --no-cache` |
| Chaos test RESULT line missing | Client pod failed to schedule | `kubectl -n benchmark describe pod -l app=benchmark-client` |
| `tc: qdisc …` error | Previous chaos rule not cleaned up | `docker exec benchmark-worker tc qdisc del dev <veth> root` |
| Cluster already exists | Leftover from a previous run | `kind delete cluster --name benchmark` |
