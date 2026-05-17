# Load images into Kind cluster

Loads both Docker images into the existing `benchmark` Kind cluster.
Build images first with `/build-images` if you have code changes.

```bash
export DOCKER_HOST=unix://${HOME}/Library/Containers/com.docker.docker/Data/docker.raw.sock

kind load docker-image helidon-vs-netty-benchmark:0.0.1-SNAPSHOT-netty   --name benchmark
kind load docker-image helidon-vs-netty-benchmark:0.0.1-SNAPSHOT-helidon --name benchmark

echo "Images loaded into Kind cluster."
```
