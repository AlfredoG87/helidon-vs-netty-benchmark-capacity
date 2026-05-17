# CLAUDE.md — Helidon vs Netty Benchmark

## Build commands

```bash
# Compile + unit tests (no Docker/K8s)
./gradlew build

# Build fat distribution TAR (required before any Docker build)
./gradlew distTar

# Build Docker images (always --no-cache after code changes)
export DOCKER_HOST=unix://${HOME}/Library/Containers/com.docker.docker/Data/docker.raw.sock
docker build --no-cache --build-arg IMPLEMENTATION=netty   --build-arg PORT=50051 \
  -t helidon-vs-netty-benchmark:0.0.1-SNAPSHOT-netty   -f docker/Dockerfile .
docker build --no-cache --build-arg IMPLEMENTATION=helidon --build-arg PORT=50052 \
  -t helidon-vs-netty-benchmark:0.0.1-SNAPSHOT-helidon -f docker/Dockerfile .
```

## Test commands

```bash
# Local JVM (no external deps)
./gradlew test --tests org.example.benchmark.ThroughputIntegrationTest

# Kind cluster baseline (builds cluster, deploys, tears down)
export DOCKER_HOST=unix://${HOME}/Library/Containers/com.docker.docker/Data/docker.raw.sock
./gradlew test -PincludeKind --tests org.example.benchmark.KindThroughputIntegrationTest --rerun-tasks

# Kind cluster chaos (5 profiles × 60 combos)
./gradlew test -PincludeChaos --tests org.example.benchmark.KindChaosIntegrationTest --rerun-tasks \
  -Dchaos.messages=200 -Dchaos.payloads=5,50,500

# One-shot end-to-end scripts
bash k8s/run-benchmark.sh          # baseline
bash k8s/run-benchmark.sh --chaos  # chaos profiles
```

## Critical build invariant: distTar before docker build

The Dockerfile copies `build/distributions/*.tar` — created only by `./gradlew distTar`.
Running `installDist` instead produces `build/install/` which the Dockerfile ignores.
Always run `distTar` + `docker build --no-cache` together; the layer cache will otherwise
serve a stale image with the same SHA and `kind load` will silently skip the reload.

## DOCKER_HOST on macOS

Docker Desktop on macOS routes through `192.168.65.1:3128` by default. The raw socket
at `~/Library/Containers/com.docker.docker/Data/docker.raw.sock` bypasses the proxy.
This is required for `kind`, `docker build`, and `docker exec` commands.
All test classes auto-detect this path; for manual shell use set the export above.

## Architecture

```
src/main/java/org/example/
  cli/     ThroughputBench.java   — entrypoint: server <impl> <port> | client <impl> <host> <msgs> <kb>
  client/  NettyThroughputClient, HelidonThroughputClient, ClientRunner
  server/  NettyThroughputServer, HelidonThroughputServer, ThroughputServiceImpl
  common/  Pretty.java            — console + RESULT line output

src/test/java/org/example/benchmark/
  AbstractThroughputMatrixTest     — shared parameterised matrix (server × client × payload)
  ThroughputIntegrationTest        — in-process servers
  DockerizedThroughputIntegrationTest — Docker container servers
  KindThroughputIntegrationTest    — Kind cluster, kubectl port-forward
  KindChaosIntegrationTest         — Kind cluster, in-cluster Jobs, tc netem chaos

k8s/
  kind-config.yaml    — 2-node cluster (control-plane + worker)
  namespace.yaml      — benchmark namespace
  *-server.yaml       — server Deployments + ClusterIP Services
  client-job.yaml     — client Job template
  run-benchmark.sh    — one-shot end-to-end script

charts/benchmark/     — Helm chart for server deployments
docker/Dockerfile     — dual-mode: MODE=server|client
```

## Chaos mechanism (tc netem, no external operator)

Chaos is injected via `docker exec benchmark-worker tc qdisc add dev <veth> root netem ...`
where `<veth>` is found by `ip route show <serverPodIP>` on the worker node.
Applied per-profile before each batch; removed with `tc qdisc del` after.
Chaos Mesh was evaluated but abandoned — its images are hosted on ghcr.io which is
unreachable through the Docker Desktop proxy on this machine.

## RESULT line format (parsed by KindChaosIntegrationTest)

```
RESULT mbps=114.900 delivered=200 attempted=200 payloadBytes=524288 duration=0.917 error=OK
```

Emitted by `Pretty.resultLine()` at the end of every client run. The test reads this
from `kubectl logs` after the Job completes.

## Gradle properties

| Property | Effect |
|----------|--------|
| `-PincludeKind` | Enables `KindThroughputIntegrationTest` (tag: `kind`) |
| `-PincludeChaos` | Enables `KindChaosIntegrationTest` (tag: `chaos`) |
| `-Dchaos.messages=N` | Messages per client Job (default 50) |
| `-Dchaos.payloads=a,b,c` | Comma-separated payload sizes in KB (default 5,50,500) |
