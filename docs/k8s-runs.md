# Stall-Connection Tests — Kubernetes (Kind) Runs

Characterises the Helidon 4.4.1 gRPC stall bugs in a two-node Kind cluster under injected
network latency. Complements the [local JVM results](local-runs.md).

---

## Prerequisites

| Tool | Version | Install |
|------|---------|---------|
| Java | 25 (Temurin) | `sdk install java 25.0.2-hs-tem` |
| Gradle | 9.1 (wrapper included) | `./gradlew` |
| Docker Desktop | any recent | required for Kind |
| kind | ≥ 0.24 | `brew install kind` |
| kubectl | ≥ 1.30 | `brew install kubectl` |

**macOS Docker socket:**
```bash
export DOCKER_HOST=unix://${HOME}/Library/Containers/com.docker.docker/Data/docker.raw.sock
```
The test auto-detects this path; only needed for manual shell commands.

---

## Cluster Setup

```bash
export DOCKER_HOST=unix://${HOME}/Library/Containers/com.docker.docker/Data/docker.raw.sock

# Create the benchmark cluster (2 nodes: control-plane + worker)
kind create cluster --config k8s/kind-config.yaml --name benchmark

# Create namespace
kubectl apply -f k8s/namespace.yaml

# Build distribution and Docker images
./gradlew distTar
docker build --no-cache --build-arg IMPLEMENTATION=helidon --build-arg PORT=50052 \
  -t helidon-vs-netty-benchmark:0.0.1-SNAPSHOT-helidon -f docker/Dockerfile .
docker build --no-cache --build-arg IMPLEMENTATION=netty --build-arg PORT=50051 \
  -t helidon-vs-netty-benchmark:0.0.1-SNAPSHOT-netty -f docker/Dockerfile .

# Load images into Kind
kind load docker-image helidon-vs-netty-benchmark:0.0.1-SNAPSHOT-helidon --name benchmark
kind load docker-image helidon-vs-netty-benchmark:0.0.1-SNAPSHOT-netty   --name benchmark

# Deploy both servers
kubectl -n benchmark apply -f k8s/helidon-server.yaml
kubectl -n benchmark apply -f k8s/netty-server.yaml
kubectl -n benchmark rollout status deployment/helidon-server deployment/netty-server --timeout=120s
```

**After any code change**, rebuild and reload before re-running tests:
```bash
./gradlew distTar
docker build --no-cache ...  # both images
kind load docker-image ...   # both images
kubectl -n benchmark rollout restart deployment/helidon-server deployment/netty-server
kubectl -n benchmark rollout status deployment/helidon-server deployment/netty-server --timeout=120s
```

---

## Running the Matrix Test

```bash
export DOCKER_HOST=unix://${HOME}/Library/Containers/com.docker.docker/Data/docker.raw.sock

./gradlew test -PincludeStall \
  --tests "org.example.benchmark.KindStallConnectionsMatrixTest" \
  --rerun-tasks \
  -Dstall.messages=30 \
  -Dstall.payloads=64,128,256,512,1024,2048,4090
```

The test tag `stall` is excluded by default. `-PincludeStall` enables it.

### Tunable parameters

| System property | Default | Description |
|----------------|---------|-------------|
| `stall.messages` | `30` | Messages sent per K8s Job |
| `stall.payloads` | `64,128,256,512,1024,2048,4090` | Comma-separated payload sizes in KB. Exclude ≥4096 KB — that case is deterministic (see [Bug 2](helidon-bug-2-message-size-limit.md)). |

### Latency profiles (fixed in the test)

| Profile | Injected delay | Mechanism |
|---------|---------------|-----------|
| BASELINE | none | — |
| 10ms | 10 ms | `tc netem delay 10ms` on server pod veth |
| 20ms | 20 ms | `tc netem delay 20ms` on server pod veth |
| 30ms | 30 ms | `tc netem delay 30ms` on server pod veth |

Chaos is applied to the target server's virtual Ethernet interface (`veth`) on the Kind worker node
using `docker exec benchmark-worker tc qdisc add dev <veth> root netem delay Xms`.

---

## Test Matrix

Each run executes all 4 × 4 combinations: 4 latency profiles × (4 client/server pairs × N payload sizes).

```
                │ Helidon server                    │ Netty server (control)
────────────────┼───────────────────────────────────┼────────────────────────
Helidon client  │ h→h  (KindStallConnectionsMatrixTest) │ h→n
Netty client    │ n→h                               │ n→n
```

Each combination is run as a separate K8s Job using the Docker image in server mode
(for the server pods, deployed once) and client mode (spawned per job). The client
prints a machine-readable `STALL_RESULT` line, parsed by the test runner.

---

## Results

### Run 1 — 30 messages × payloads: 1024, 2048, 4090 KB

> Only three payload sizes were used here to validate the test harness.
> See Run 2 for the expanded payload range and higher message count.

#### BASELINE (no latency)

| payload | h→h | n→h | h→n | n→n |
|---------|:---:|:---:|:---:|:---:|
| 1024 KB | **3%** | 0% | 0% | 0% |
| 2048 KB | **3%** | 0% | 0% | 0% |
| 4090 KB | **3%** | 0% | 0% | 0% |

#### 10 ms latency

| payload | h→h | n→h | h→n | n→n |
|---------|:---:|:---:|:---:|:---:|
| 1024 KB | 0% | 0% | 0% | 0% |
| 2048 KB | 0% | 0% | 0% | 0% |
| 4090 KB | 0% | 0% | 0% | 0% |

#### 20 ms latency

| payload | h→h | n→h | h→n | n→n |
|---------|:---:|:---:|:---:|:---:|
| 1024 KB | 0% | 0% | 0% | 0% |
| 2048 KB | 0% | 0% | 0% | 0% |
| 4090 KB | 0% | 0% | 0% | 0% |

**Observation:** With 30 messages the stall rate is 1/30 = ~3% in baseline and 0/30 = 0% under
latency — these are within the margin of statistical noise for a 1–5% true rate.
See Run 2 (1000 messages) for statistically significant data.

---

### Run 2 — 1000 messages × payloads: 64, 128, 256, 512, 1024, 2048, 4090 KB

Command used:
```bash
./gradlew test -PincludeStall \
  --tests "org.example.benchmark.KindStallConnectionsMatrixTest" \
  --rerun-tasks \
  -Dstall.messages=1000
```

#### BASELINE (no latency)

| payload | h→h | n→h | h→n | n→n |
|---------|:---:|:---:|:---:|:---:|
|    64 KB | **1%** | 0% | 0% | 0% |
|   128 KB | **2%** | 0% | **2%** | 0% |
|   256 KB | **3%** | 0% | **2%** | 0% |
|   512 KB | **3%** | 0% | **3%** | 0% |
|  1024 KB | **4%** | 0% | **3%** | 0% |
|  2048 KB | **4%** | 0% | **3%** | 0% |
|  4090 KB | **3%** | 0% | **2%** | 0% |

#### 10 ms latency

| payload | h→h | n→h | h→n | n→n |
|---------|:---:|:---:|:---:|:---:|
|    64 KB | 0% | 0% | 0% | 0% |
|   128 KB | 0% | 0% | 0% | 0% |
|   256 KB | 0% | 0% | 0% | 0% |
|   512 KB | 0% | 0% | 0% | 0% |
|  1024 KB | 0% | 0% | 0% | 0% |
|  2048 KB | 0% | 0% | 0% | 0% |
|  4090 KB | 0% | 0% | 0% | 0% |

#### 20 ms latency

| payload | h→h | n→h | h→n | n→n |
|---------|:---:|:---:|:---:|:---:|
|    64 KB | 0% | 0% | 0% | 0% |
|   128 KB | 0% | 0% | 0% | 0% |
|   256 KB | 0% | 0% | 0% | 0% |
|   512 KB | 0% | 0% | 0% | 0% |
|  1024 KB | 0% | 0% | 0% | 0% |
|  2048 KB | 0% | 0% | 0% | 0% |
|  4090 KB | 0% | 0% | 0% | 0% |

#### 30 ms latency

| payload | h→h | n→h | h→n | n→n |
|---------|:---:|:---:|:---:|:---:|
|    64 KB | 0% | 0% | 0% | 0% |
|   128 KB | 0% | 0% | 0% | 0% |
|   256 KB | 0% | 0% | 0% | 0% |
|   512 KB | 0% | 0% | 0% | 0% |
|  1024 KB | 0% | 0% | 0% | 0% |
|  2048 KB | 0% | 0% | 0% | 0% |
|  4090 KB | 0% | 0% | 0% | 0% |

---

## Interpretation

### Bug is in the Helidon gRPC client, not the server

The 1000-message K8s run reveals a finding that was masked at 100 messages in local runs:
**`h→n` stalls at 1–4%, the same rate as `h→h`**.

| pair | description | BASELINE stall rate |
|------|-------------|:---:|
| h→h | Helidon client → Helidon server | **1–4%** |
| n→h | Netty client → Helidon server | **0%** |
| h→n | Helidon client → Netty server | **1–4%** |
| n→n | Netty client → Netty server | **0%** |

The pattern is unambiguous:
- Stalls appear in **every pair that uses the Helidon client** (h→h, h→n).
- Stalls are **absent in every pair that uses the Netty client** (n→h, n→n).
- The **Helidon server is not the root cause** — n→h is 0% across all 7000 messages.

The root cause is in the Helidon gRPC client: it sometimes fails to invoke `onNext`,
`onError`, or `onCompleted` on the response `StreamObserver`, regardless of which server
it is talking to. The `withDeadlineAfter` deadline also never fires `onError` on a stalled
stream. See [bug report](helidon-bug-1-frame-boundary-stall.md) for updated analysis.

### Latency eliminates the stall — the race requires sub-10 ms timing

At 10 ms, 20 ms, and 30 ms injected latency, stalls drop to **0%** for all pairs including
`h→h` and `h→n`. This confirms the root cause is a timing-sensitive race condition in the
Helidon client's callback delivery path. Adding even minimal network latency breaks the
race, suggesting the window is on the order of microseconds to low milliseconds — consistent
with a frame-boundary read race in fast loopback or containerised networking.

### Netty client and server are clean in all conditions

`n→h` and `n→n` show 0% stalls under every latency profile and payload size. The Helidon
server correctly handles all message sizes (64 KB – 4090 KB) when a Netty client connects.

---

## Cleanup

```bash
export DOCKER_HOST=unix://${HOME}/Library/Containers/com.docker.docker/Data/docker.raw.sock
kind delete cluster --name benchmark
```
