FROM eclipse-temurin:25.0.2_10-jre-ubi10-minimal AS base

ARG VERSION=0.0.1-SNAPSHOT
ARG IMPLEMENTATION=netty
ARG PORT=50051
ARG DIST_TAR=helidon-vs-netty-benchmark-capacity-${VERSION}.tar

ENV VERSION=${VERSION} \
    IMPLEMENTATION=${IMPLEMENTATION} \
    PORT=${PORT} \
    MODE=server \
    CLIENT_IMPL=netty \
    SERVER_HOST=netty-server:50051 \
    NUM_MESSAGES=1000 \
    PAYLOAD_KB=1024

WORKDIR /app

COPY build/distributions/${DIST_TAR} /tmp/${DIST_TAR}
RUN tar -xf /tmp/${DIST_TAR} -C /app \
    && mv "helidon-vs-netty-benchmark-capacity-${VERSION}" runtime \
    && rm /tmp/${DIST_TAR}

EXPOSE ${PORT}

ENTRYPOINT ["sh", "-c", "\
  if [ \"$MODE\" = \"client\" ]; then \
    exec /app/runtime/bin/helidon-vs-netty-benchmark-capacity client \"$CLIENT_IMPL\" \"$SERVER_HOST\" \"$NUM_MESSAGES\" \"$PAYLOAD_KB\"; \
  else \
    exec /app/runtime/bin/helidon-vs-netty-benchmark-capacity server \"$IMPLEMENTATION\" \"$PORT\"; \
  fi"]
