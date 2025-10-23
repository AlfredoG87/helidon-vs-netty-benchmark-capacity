# syntax=docker/dockerfile:1.4

FROM eclipse-temurin:21-jre AS base

ARG VERSION=0.0.1-SNAPSHOT
ARG IMPLEMENTATION=netty
ARG PORT=50051
ARG DIST_TAR=helidon-vs-netty-benchmark-capacity-${VERSION}.tar

ENV VERSION=${VERSION} \
    IMPLEMENTATION=${IMPLEMENTATION} \
    PORT=${PORT}

WORKDIR /app

COPY build/distributions/${DIST_TAR} /tmp/${DIST_TAR}
RUN tar -xf /tmp/${DIST_TAR} -C /app \
    && mv "helidon-vs-netty-benchmark-capacity-${VERSION}" runtime \
    && rm /tmp/${DIST_TAR}

EXPOSE ${PORT}

ENTRYPOINT ["sh","-c","/app/runtime/bin/helidon-vs-netty-benchmark-capacity server ${IMPLEMENTATION} ${PORT}"]
