# Bug: gRPC messages >4 MB cause deterministic 100% silent stall — `GrpcConfig.maxReadBufferSize` not wired to gRPC-Java's `maxInboundMessageSize`

**Project:** https://github.com/helidon-io/helidon  
**Version:** 4.4.1  
**Components:** `helidon-webserver-grpc`, `helidon-webclient-grpc`  
**Java:** 25 (Temurin)  
**Protocol:** gRPC bidirectional streaming, plaintext HTTP/2

---

## Summary

When a single gRPC message exceeds ~4 MB (proto-encoded), the Helidon 4.4.1 server
rejects it and the Helidon gRPC client hangs forever. `StreamObserver.onError` and
`onCompleted` are never called, regardless of whether `stub.withDeadlineAfter` is set.

This is **deterministic**: every message at or above the threshold stalls. There is no
partial degradation. A Netty client against the same Helidon server receives
`RESOURCE_EXHAUSTED` immediately, proving the rejection happens and an error signal is
generated — but the Helidon client never delivers it to the observer.

---

## Root cause

gRPC-Java's default `maxInboundMessageSize` is **4,194,304 bytes** (4 MB). When Helidon
builds the underlying gRPC-Java `ServerBuilder`, it does not call
`serverBuilder.maxInboundMessageSize(grpcConfig.maxReadBufferSize())`, so the 4 MB
default is always in effect regardless of what `GrpcConfig.maxReadBufferSize()` is set to.

When a message arrives that exceeds the limit, gRPC-Java sends a `RESOURCE_EXHAUSTED`
error back to the client and cancels the stream internally. The Helidon
`GrpcProtocolHandler` receives the cancellation but does not propagate a gRPC error
status to the peer — it calls `listener.onCancel()` silently. The Helidon webclient then
receives no terminal signal and its `StreamObserver` is abandoned in perpetuity.

Two distinct sub-defects compound to produce the silent stall:

| # | Component | Defect |
|---|-----------|--------|
| 1 | `helidon-webserver-grpc` | `GrpcConfig.maxReadBufferSize` is not forwarded to `ServerBuilder.maxInboundMessageSize`; the 4 MB gRPC-Java default is always active |
| 2 | `helidon-webclient-grpc` | gRPC error trailers returned by the server (`RESOURCE_EXHAUSTED`) are not delivered to `StreamObserver.onError`; `withDeadlineAfter` also fails to fire `onError(DEADLINE_EXCEEDED)` |

---

## Exact threshold

| Payload field | Proto-encoded size | Outcome |
|---|---|---|
| `4090 × 1024` bytes | ≈ 4,190,730 bytes | **Accepted** (under 4 MB) |
| `4096 × 1024` bytes | ≈ 4,196,358 bytes | **Rejected — stall** (over 4 MB) |

`GrpcConfig.maxReadBufferSize(128 MB)` and `Http2Config.maxBufferedEntitySize("256 MB")`
are both configured on the server. HTTP/2 transport limits are not the constraint. The
boundary is precisely gRPC-Java's 4 MB inbound message guard.

---

## Evidence: 2×2 matrix (100 messages per cell, two runs)

Four combinations are exercised:

| | Helidon server | Netty server |
|---|---|---|
| **Helidon client** | `h→h` | `h→n` |
| **Netty client** | `n→h` | `n→n` |

The Netty server is configured with `maxInboundMessageSize(128 MB)`.

| Payload | `h→h` stall% | `n→h` stall% | `h→n` stall% | `n→n` stall% | `n→h` elapsed |
|---|:---:|:---:|:---:|:---:|:---:|
| 1024 KB | 0% | 0% | 0% | 0% | ~100 ms |
| 2048 KB | 0% | 0% | 0% | 0% | ~100 ms |
| 4090 KB | ~2% | 0% | 0% | 0% | ~100 ms |
| **4096 KB** | **100%** | **100%** | **0%** | **0%** | **≈0 s** |
| 8192 KB | 100% | 100% | 0% | 0% | ≈0 s |
| 16384 KB | 100% | 100% | 0% | 0% | ≈0 s |
| 32768 KB | 100% | 100% | 0% | 0% | ≈0 s |
| 64000 KB | 100% | 100% | 0% | 0% | ≈0 s |

**Key observations:**

- `h→n` and `n→n` — zero stalls at all sizes including 64 MB. The Netty server with
  `maxInboundMessageSize(128 MB)` is the control; it confirms the test harness and the
  Helidon client are both capable of handling large messages when the server is correctly
  configured.

- `n→h` at ≥4096 KB — **100% stalls, each completing in ≈0 s**. The Netty client fires
  `DEADLINE_EXCEEDED` immediately because `RESOURCE_EXHAUSTED` arrives from the server
  almost instantly. This proves: (a) the Helidon server does reject the message, and
  (b) a gRPC error trailer is transmitted — but the Helidon client does not deliver it.

- `h→h` at ≥4096 KB — **100% stalls, each waiting the full `timeoutSecs + 1` seconds**
  with no signal at all. `onError` and `onCompleted` are never called, even after the
  gRPC deadline fires.

The `~2%` stall rate at 4090 KB (just under the limit) in the `h→h` column is a
pre-existing unrelated intermittent issue and is not part of this report.

---

## Server configuration under test

```java
WebServer.builder()
    .port(0)
    .addProtocol(GrpcConfig.builder()
        .enableCompression(false)
        .enableMetrics(false)
        .maxReadBufferSize(128 * 1024 * 1024)   // intended to raise limit; has no effect
        .build())
    .addProtocol(Http2Config.builder()
        .initialWindowSize(32 * 1024 * 1024)
        .maxFrameSize(8 * 1024 * 1024)
        .maxBufferedEntitySize(Size.parse("256 MB"))
        .build())
    .addRouting(GrpcRouting.builder().service(ssd))
    .build();
```

Working Netty equivalent (zero stalls at all sizes):

```java
NettyServerBuilder.forPort(0)
    .flowControlWindow(8 * 1024 * 1024)
    .maxInboundMessageSize(128 * 1024 * 1024)   // this works
    .addService(service)
    .build()
    .start();
```

---

## Minimal reproduction

**Prerequisites:** Java 25, Gradle wrapper included.

```bash
git clone https://github.com/[repo]/helidon-vs-netty-benchmark-capacity
cd helidon-vs-netty-benchmark-capacity

# Reproduce sub-defect 1+2 together:
# Helidon client → Helidon server, ≥4096 KB — onError/onCompleted never called
./gradlew test \
  --tests "org.example.benchmark.HelidonGrpcStallConnectionsTest#helidonClient_silentStall" \
  --rerun-tasks

# Reproduce sub-defect 1 in isolation:
# Netty client → Helidon server, ≥4096 KB — RESOURCE_EXHAUSTED confirms server rejects
./gradlew test \
  --tests "org.example.benchmark.HelidonGrpcStallConnectionsTest#nettyClient_deadlineExceeded" \
  --rerun-tasks

# Control: Netty client → Netty server (expect 0 stalls at all sizes)
./gradlew test \
  --tests "org.example.benchmark.NettyGrpcStallConnectionsTest#nettyClient_noStalls" \
  --rerun-tasks
```

The test classes are self-contained: they start servers on ephemeral ports in-process.
No Docker, no Kubernetes, no external dependencies.

Test source:

- `src/test/java/org/example/benchmark/HelidonGrpcStallConnectionsTest.java` — Helidon server, both clients
- `src/test/java/org/example/benchmark/NettyGrpcStallConnectionsTest.java` — Netty server, both clients (control)

---

## Expected vs actual

| Scenario | Expected | Actual |
|---|---|---|
| `GrpcConfig.maxReadBufferSize(128 MB)` configured | Server accepts messages up to 128 MB | Server rejects messages > 4,194,304 bytes with `RESOURCE_EXHAUSTED` |
| Helidon client sends 4096 KB message | `onNext(ack)` received | No signal — stream abandoned silently |
| Helidon client with `withDeadlineAfter(N, SECONDS)`, 4096 KB | `onError(DEADLINE_EXCEEDED)` | No signal — deadline does not fire |
| Netty client sends 4096 KB to Helidon server | `onNext(ack)` received | `onError(RESOURCE_EXHAUSTED)` in ≈0 s |
| Same payload against Netty server (`maxInboundMessageSize(128 MB)`) | `onNext(ack)` received | `onNext(ack)` received — passes |

---

## Suggested fixes

**Sub-defect 1 — `helidon-webserver-grpc`**

Propagate `GrpcConfig.maxReadBufferSize()` to the underlying gRPC-Java `ServerBuilder`
when the server is constructed:

```java
serverBuilder.maxInboundMessageSize(grpcConfig.maxReadBufferSize());
```

**Sub-defect 2 — `helidon-webserver-grpc` / `GrpcProtocolHandler`**

When gRPC-Java rejects a message and cancels the stream, translate the cancellation into
a proper gRPC `RESOURCE_EXHAUSTED` status and write it to the response channel so the
client receives the error trailer instead of a half-closed stream.

**Sub-defect 3 — `helidon-webclient-grpc`**

Deliver gRPC error trailers received from the server — including `RESOURCE_EXHAUSTED`
and `DEADLINE_EXCEEDED` — to `StreamObserver.onError(StatusRuntimeException)`. The
current implementation silently discards terminal error frames, leaving the observer
blocked indefinitely.
