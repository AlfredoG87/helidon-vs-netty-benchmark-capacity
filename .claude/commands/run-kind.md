# Run Kind baseline benchmark

Runs the 16-combination throughput matrix against servers deployed in a local Kind cluster.
Creates the cluster if it doesn't exist, then tears it down after the run.

```bash
export DOCKER_HOST=unix://${HOME}/Library/Containers/com.docker.docker/Data/docker.raw.sock

./gradlew test -PincludeKind \
  --tests org.example.benchmark.KindThroughputIntegrationTest \
  --rerun-tasks
```

To reuse an existing cluster across runs, start the cluster manually and pass
`--no-teardown` if using `run-benchmark.sh` instead.
