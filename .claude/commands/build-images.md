# Build Docker images

Builds both server Docker images from source. Always runs `distTar` first and uses
`--no-cache` to prevent stale-layer issues.

```bash
export DOCKER_HOST=unix://${HOME}/Library/Containers/com.docker.docker/Data/docker.raw.sock

./gradlew distTar

docker build --no-cache \
  --build-arg IMPLEMENTATION=netty --build-arg PORT=50051 \
  -t helidon-vs-netty-benchmark:0.0.1-SNAPSHOT-netty \
  -f docker/Dockerfile .

docker build --no-cache \
  --build-arg IMPLEMENTATION=helidon --build-arg PORT=50052 \
  -t helidon-vs-netty-benchmark:0.0.1-SNAPSHOT-helidon \
  -f docker/Dockerfile .

echo "Images built successfully."
```
