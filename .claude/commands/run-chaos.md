# Run chaos benchmark

Runs the full chaos test suite: 5 profiles × (serverImpl × clientImpl × payloadSize) combinations.
Servers deploy as pods inside the Kind cluster; the client runs as K8s Jobs.
Chaos is injected via `tc netem` on the worker node's veth interface — no external operator needed.

Default: 200 messages per job, payloads 5 KB / 50 KB / 500 KB.

```bash
export DOCKER_HOST=unix://${HOME}/Library/Containers/com.docker.docker/Data/docker.raw.sock

./gradlew test -PincludeChaos \
  --tests org.example.benchmark.KindChaosIntegrationTest \
  --rerun-tasks \
  -Dchaos.messages=200 \
  -Dchaos.payloads=5,50,500
```

To use the one-shot script (builds images, creates cluster, runs, tears down):

```bash
bash k8s/run-benchmark.sh --chaos
```

Keep the cluster alive after the run:

```bash
bash k8s/run-benchmark.sh --chaos --no-teardown
```
