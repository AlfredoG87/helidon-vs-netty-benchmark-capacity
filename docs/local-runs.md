# Local JVM Stall-Connection Tests

This document covers the local (in-process JVM) stall-connection test suite that
characterises two distinct bugs in Helidon 4.4.1 gRPC. No Docker or Kubernetes is
required. The tests start servers on random ports and run entirely inside the test JVM.

---

## Prerequisites

- Java 21+
- Gradle wrapper (`./gradlew`) — no separate Gradle installation needed

No Docker, no Kind cluster, no external services.

---

## How to Run

Run the Helidon-server tests (exercises both bugs):

```bash
./gradlew test --tests org.example.benchmark.HelidonGrpcStallConnectionsTest --rerun-tasks
```

Run the Netty-server control tests (expected: zero stalls):

```bash
./gradlew test --tests org.example.benchmark.NettyGrpcStallConnectionsTest --rerun-tasks
```

Run both together:

```bash
./gradlew test \
  --tests org.example.benchmark.HelidonGrpcStallConnectionsTest \
  --tests org.example.benchmark.NettyGrpcStallConnectionsTest \
  --rerun-tasks
```

`--rerun-tasks` is needed because Gradle caches passing tests; without it a prior pass
will be reported as UP-TO-DATE and the test will not execute.

Each parameterised method runs 100 messages per payload size. The full suite takes
roughly 10–30 minutes depending on how many stalls occur at the larger payload sizes
(each stalled message waits `timeoutSecs + 1` seconds before being counted).

---

## Understanding the Test Matrix

The suite is a 2×2 matrix of server implementation × client implementation:

```
                │ Helidon server                     │ Netty server (control)
────────────────┼────────────────────────────────────┼──────────────────────────
Helidon client  │ helidonClient_silentStall           │ helidonClient_noStalls
Netty client    │ nettyClient_deadlineExceeded        │ nettyClient_noStalls
```

### Test classes

| Class | Server | Purpose |
|---|---|---|
| `HelidonGrpcStallConnectionsTest` | Helidon 4.4.1 | Exposes both bugs |
| `NettyGrpcStallConnectionsTest` | Netty (gRPC-Java) | Control — must be zero stalls |

### Payload sizes tested

1024, 2048, 4090, 4096, 8192, 16384, 32768, 64000 KB × 100 messages each.

The 4090 / 4096 KB boundary is deliberate — see the Analysis section.

### Stall definition

A message is counted as stalled if, within `timeoutSecs + 1` seconds, the response
observer receives no signal at all (`CountDownLatch.await` returns false) **or** any
error is received (`error.get() != null`). The timeout formula:

```java
private static int stallTimeoutSecs(int payloadBytes) {
    double transferSecs = (payloadBytes / (1024.0 * 1024.0)) / 25.0; // 25 MB/s assumed
    int computed = (int) Math.ceil(transferSecs) + 3;  // +3s grace
    return Math.max(computed, 5);                       // minimum 5s
}
```

At 4096 KB the timeout is 5 s; at 64000 KB it is 6 s.

---

## Server and Client Configuration

### Helidon server

```java
WebServer.builder()
    .port(0)
    .addProtocol(GrpcConfig.builder()
        .enableCompression(false)
        .enableMetrics(false)
        .maxReadBufferSize(128 * 1024 * 1024)
        .build())
    .addProtocol(Http2Config.builder()
        .initialWindowSize(32 * 1024 * 1024)
        .maxFrameSize(8 * 1024 * 1024)
        .maxBufferedEntitySize(Size.parse("256 MB"))
        .build())
    .addRouting(GrpcRouting.builder().service(ssd))
    .build();
```

`maxReadBufferSize(128 MB)` is set explicitly. As shown in the results, this does **not**
override the underlying gRPC-Java `maxInboundMessageSize` default — see Bug B analysis.

### Helidon client

```java
WebClient.builder()
    .baseUri("http://localhost:" + port)
    .tls(Tls.builder().enabled(false).build())
    .protocolConfigs(List.of(
        Http2ClientProtocolConfig.builder()
            .priorKnowledge(true)
            .maxFrameSize(8 * 1024 * 1024)
            .initialWindowSize(8 * 1024 * 1024)
            .build(),
        GrpcClientProtocolConfig.builder()
            .initBufferSize(8 * 1024 * 1024)
            .build()))
    .build();
```

### Netty client

```java
NettyChannelBuilder.forAddress("localhost", port)
    .usePlaintext()
    .flowControlWindow(8 * 1024 * 1024)
    .maxInboundMessageSize(128 * 1024 * 1024)
    .build();
```

### Netty server (control)

```java
NettyServerBuilder.forPort(0)
    .flowControlWindow(8 * 1024 * 1024)
    .maxInboundMessageSize(128 * 1024 * 1024)
    .addService(new QuietEchoService())
    .build()
    .start();
```

---

## Results

Stall rate = stalled messages / 100 messages × 100%.

Column headers: **h→h** = Helidon client → Helidon server, **n→h** = Netty client →
Helidon server, **h→n** = Helidon client → Netty server, **n→n** = Netty client → Netty
server.

### Run 1

| Payload | h→h | n→h | h→n | n→n |
|---------|:---:|:---:|:---:|:---:|
| 1024 KB | 1% | 0% | 0% | 0% |
| 2048 KB | 5% | 0% | 0% | 0% |
| 4090 KB | 2% | 0% | 1%† | 0% |
| 4096 KB | **100%** | **100%** | 0% | 0% |
| 8192 KB | **100%** | **100%** | 0% | 0% |
| 16384 KB | **100%** | **100%** | 0% | 0% |
| 32768 KB | **100%** | **100%** | 0% | 0% |
| 64000 KB | **100%** | **100%** | 0% | 0% |

### Run 2

| Payload | h→h | n→h | h→n | n→n |
|---------|:---:|:---:|:---:|:---:|
| 1024 KB | 1% | 0% | 0% | 0% |
| 2048 KB | 0% | 0% | 2%† | 0% |
| 4090 KB | 0% | 0% | 0% | 0% |
| 4096 KB | **100%** | **100%** | 0% | 0% |
| 8192 KB | **100%** | **100%** | 0% | 0% |
| 16384 KB | **100%** | **100%** | 0% | 0% |
| 32768 KB | **100%** | **100%** | 0% | 0% |
| 64000 KB | **100%** | **100%** | 0% | 0% |

† Single occurrence across 100 messages — noise.

---

## Analysis

### Bug A — Probabilistic silent stall (payloads ≤ 4090 KB, h→h only)

At payload sizes below the 4096 KB boundary, the h→h cell shows a low but non-zero
stall rate (0–5%, varying run to run). All other cells are 0%.

Characteristics:
- Only occurs with Helidon client + Helidon server together.
- The Netty client against the same Helidon server shows 0% at these sizes, which rules
  out a pure server-side parse bug at this payload range.
- Stall type: `onError` and `onCompleted` are **never** called. The `CountDownLatch`
  times out with no signal at all — a true silent hang.
- `stub.withDeadlineAfter(timeoutSecs, ...)` fires internally, but the deadline expiry is
  never surfaced to the response observer. The latch is never decremented.

The defect is in Helidon's gRPC webclient: when the server encounters a frame-boundary
parse problem (intermittent at sub-4 MB sizes), `GrpcProtocolHandler` calls
`listener.onCancel()` without sending a gRPC error status. The Helidon client receives
this cancellation but neither calls `onError` nor forwards the deadline expiry to the
observer.

### Bug B — Deterministic stall at 4096 KB threshold (both n→h and h→h)

At exactly 4096 KB and all larger sizes, both the Netty client and the Helidon client
stall 100% of the time against the Helidon server. The Netty and Helidon control columns
remain at 0%.

The 4096 KB threshold maps to gRPC-Java's default inbound message size limit of
4,194,304 bytes (4 MB). A `DataChunk` protobuf message with 4090 × 1024 bytes of payload
encodes to slightly under 4 MB; with 4096 × 1024 bytes it exceeds 4 MB and gRPC-Java
rejects it on the server side.

**`GrpcConfig.maxReadBufferSize(128 MB)` does not override this limit.** The Helidon
gRPC server wraps gRPC-Java but does not call `ServerBuilder.maxInboundMessageSize()`.
The 4 MB default remains in effect regardless of `maxReadBufferSize`.

Behaviour differences between clients against the Helidon server at ≥4096 KB:

| | n→h | h→h |
|---|---|---|
| Error surfaced? | Yes — RESOURCE_EXHAUSTED, 0 s elapsed | No — stream hangs silently for `timeoutSecs + 1` s |
| `onError` called? | Yes | Never |

The Netty client correctly receives a RESOURCE_EXHAUSTED status (confirming the server
generates *some* signal at the gRPC-Java level). The Helidon client never sees it. This
is the same client-side defect described under Bug A: Helidon's gRPC webclient does not
propagate the RST_STREAM / gRPC status to the response observer.

### Summary

| | Root cause | Helidon component |
|---|---|---|
| Bug A | Frame-boundary parse error (sub-4 MB, intermittent) | `GrpcProtocolHandler` — cancels without error status |
| Bug B | gRPC-Java 4 MB inbound limit not overridden by `maxReadBufferSize` | `GrpcProtocolHandler` + Helidon gRPC webclient |
| Shared client defect | Deadline / cancellation never reaches the observer | Helidon gRPC webclient (`onError`/`onCompleted` not called) |

The matrix design is essential for isolating these two layers. Without the Netty client
column, Bug B would appear to be purely a client-side issue. Without the Netty server
column, Bug A's probabilistic failures could be attributed to the test harness rather
than to the Helidon client/server interaction.
