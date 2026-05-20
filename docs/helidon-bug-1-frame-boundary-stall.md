# Bug: Helidon 4.4.1 gRPC — Probabilistic silent stall; deadline never surfaces `onError`

**Component:** `helidon-webclient-grpc`
**Version:** 4.4.1
**Java:** 25 (Temurin)
**Protocol:** gRPC bidirectional streaming, plaintext HTTP/2

---

## Summary

The Helidon gRPC client silently drops a small percentage of response streams (~1–4% per run):
the response `StreamObserver` never receives `onNext`, `onError`, or `onCompleted`, and
`withDeadlineAfter(N, SECONDS)` never fires `onError` — the stream hangs indefinitely.

The bug manifests regardless of the server implementation:

| client → server | BASELINE stall rate (1000 msgs, 64 KB – 4090 KB) |
|-----------------|:-------------------------------------------------:|
| Helidon → Helidon | **1–4%** |
| Helidon → Netty   | **1–4%** |
| Netty → Helidon   | **0%** |
| Netty → Netty     | **0%** |

The pattern is conclusive: stalls appear whenever the **Helidon client** is used, regardless of the
server. The Helidon server is not the root cause — it processes all messages correctly when a Netty
client connects (n→h = 0% across 7000 messages in K8s).

The stall rate is **probabilistic**, correlates with payload sizes that span multiple HTTP/2 DATA
frames (>64 KB), and is **completely suppressed by ≥10 ms network latency**, pointing to a
microsecond-scale timing race in the Helidon client's response-callback delivery path.

---

## Reproduction

**Test repository:** https://github.com/alfredo-gutierrez/helidon-vs-netty-benchmark-capacity

**Prerequisites:** Java 25, Gradle wrapper included.

```bash
git clone https://github.com/alfredo-gutierrez/helidon-vs-netty-benchmark-capacity
cd helidon-vs-netty-benchmark-capacity

# Reproduce: Helidon client → Helidon server (1–4% stall rate)
./gradlew test -PincludeStall \
  --tests "org.example.benchmark.HelidonGrpcStallConnectionsTest#helidonClient_silentStall" \
  --rerun-tasks

# Also reproduces: Helidon client → Netty server (same 1–4% stall rate)
./gradlew test -PincludeStall \
  --tests "org.example.benchmark.NettyGrpcStallConnectionsTest#helidonClient_noStalls" \
  --rerun-tasks

# Control (clean): Netty client → Helidon server — 0% stalls
./gradlew test -PincludeStall \
  --tests "org.example.benchmark.HelidonGrpcStallConnectionsTest#nettyClient_deadlineExceeded" \
  --rerun-tasks
```

**Expected:** 0 stalls in all three runs.

**Actual:**
- Helidon client → Helidon server: 1–4% stall rate (probabilistic, timing-sensitive).
- Helidon client → Netty server: same 1–4% stall rate — proving the client is the source.
- Netty client → Helidon server: 0% stalls — proving the Helidon server is not at fault.

Stalled streams produce no signal — `onError`/`onCompleted` are never called, and
`withDeadlineAfter(N)` never fires `onError` after the deadline expires.

---

## 2×2 isolation matrix

### Local JVM (100 messages per payload size, two independent runs)

| Payload | h→h | n→h | h→n | n→n |
|---------|:---:|:---:|:---:|:---:|
| 1024 KB | 1% / 1% | 0% / 0% | 0% / 0% | 0% / 0% |
| 2048 KB | 5% / 0% | 0% / 0% | 0% / 0% | 0% / 0% |
| 4090 KB | 2% / 0% | 0% / 0% | 0% / 0% | 0% / 0% |

At 100 messages the `h→n` rate is below the detection threshold. The K8s run with 1000 messages
resolves it clearly.

### Kubernetes (1000 messages per payload size, BASELINE — no injected latency)

| Payload | h→h | n→h | h→n | n→n |
|---------|:---:|:---:|:---:|:---:|
|    64 KB | **1%** | 0% | 0% | 0% |
|   128 KB | **2%** | 0% | **2%** | 0% |
|   256 KB | **3%** | 0% | **2%** | 0% |
|   512 KB | **3%** | 0% | **3%** | 0% |
|  1024 KB | **4%** | 0% | **3%** | 0% |
|  2048 KB | **4%** | 0% | **3%** | 0% |
|  4090 KB | **3%** | 0% | **2%** | 0% |

### Kubernetes with injected latency (1000 messages per payload size)

| Profile | h→h | n→h | h→n | n→n |
|---------|:---:|:---:|:---:|:---:|
| BASELINE | **1–4%** | 0% | **1–4%** | 0% |
| 10 ms | 0% | 0% | 0% | 0% |
| 20 ms | 0% | 0% | 0% | 0% |
| 30 ms | 0% | 0% | 0% | 0% |

Key isolations:

- `h→n` = **1–4%** (1000-message K8s run): the stall manifests with Helidon client against Netty
  server. **The server is not the root cause.**
- `n→h` = **0%**: the Helidon server correctly handles all messages when a Netty client connects.
  The server is not involved in the stall.
- Latency suppresses the stall completely at ≥10 ms. The race condition requires sub-10 ms timing,
  consistent with a frame-boundary read race in low-latency containerised or loopback networking.

---

## Server configuration used in tests

```java
WebServer server = WebServer.builder()
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

Note: `maxReadBufferSize` is explicitly raised to 128 MB — the related deterministic
`maxReadBufferSize` overflow bug (filed separately) is therefore not the trigger here.

## Client configuration used in tests

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

## Service implementation

A minimal bidirectional echo — no business logic, no thread switching:

```java
public StreamObserver<DataChunk> stream(StreamObserver<Ack> out) {
    return new StreamObserver<>() {
        public void onNext(DataChunk chunk) {
            out.onNext(Ack.newBuilder().setSeq(chunk.getSeq()).setOk(true).build());
        }
        public void onError(Throwable t) { out.onError(t); }
        public void onCompleted()        { out.onCompleted(); }
    };
}
```

---

## Two sub-defects (both in the Helidon gRPC client)

### Sub-defect 1 — Client: response callbacks silently dropped on multi-frame messages

When a gRPC response arrives across multiple HTTP/2 DATA frames, the Helidon client sometimes
fails to invoke `onNext`, `onError`, or `onCompleted` on the response `StreamObserver`. The
stream hangs indefinitely without any signal. This manifests at 1–4% of streams for payloads
that span more than one HTTP/2 DATA frame (>64 KB in practice).

The stall manifests regardless of server implementation (Helidon or Netty), which proves the
bug is in the client's response-reading or callback-dispatch path.

Suspected code path (Helidon 4.4.1):
- `io.helidon.webclient.grpc.GrpcClientImpl` or its HTTP/2 stream handler — specifically the
  code path that assembles multi-frame gRPC messages and dispatches them to the
  `StreamObserver`.

The stall is suppressed by ≥10 ms network latency, pointing to a race condition between
the frame reassembly loop and the callback dispatch. A candidate: if a DATA frame arrives
before the stream handler is fully registered, the frame may be consumed without triggering
the callback.

**Suggested fix:** Review the Helidon HTTP/2 client's inbound DATA frame handling for a
race between frame arrival and observer registration. Ensure that every fully-assembled gRPC
message is dispatched to the registered `StreamObserver`, even if frames arrive before or
concurrently with observer registration.

### Sub-defect 2 — Client: deadline fires but `onError` is never called on the response observer

Even with `withDeadlineAfter(N, SECONDS)` set on the stub, and even after N seconds elapse,
the Helidon gRPC client **never calls `onError(StatusRuntimeException{DEADLINE_EXCEEDED})`**
on the response `StreamObserver`. The test's `CountDownLatch` awaits `N + 1` seconds and
times out — no signal arrives at all.

A Netty client under identical conditions correctly receives `DEADLINE_EXCEEDED` on the same
stalled stream. The deadline is enforced at the transport level; Helidon's client simply does
not deliver the cancellation signal to the `StreamObserver`.

Suspected code path:
- `io.helidon.webclient.grpc.GrpcClientImpl` — the deadline executor cancels the stream at
  the HTTP/2 transport layer but does not invoke `StreamObserver.onError`.

**Suggested fix:** When the deadline fires and the stream is still open, invoke:

```java
responseObserver.onError(
    new StatusRuntimeException(Status.DEADLINE_EXCEEDED));
```

---

## Expected vs actual

| Scenario | Expected | Actual |
|---|---|---|
| Helidon client → Helidon server, ≤4090 KB | 0 stalls | **1–4% stall rate (probabilistic)** |
| Helidon client → Netty server, ≤4090 KB | 0 stalls | **1–4% stall rate — same rate as h→h** |
| Netty client → Helidon server, ≤4090 KB | 0 stalls | 0% — server is clean |
| Stalled stream signal | `onError` or `onCompleted` | **No signal ever delivered** |
| `withDeadlineAfter(N)` on stalled stream | `onError(DEADLINE_EXCEEDED)` | **No signal — deadline silently ignored** |
| All pairs at ≥10 ms injected latency | 0 stalls | 0% — race suppressed by latency |

---

## Related

- A second, **deterministic** stall occurs when messages exceed `GrpcConfig.maxReadBufferSize`
  (default 2 MB). Every message over the limit stalls at 100%. Characterised in
  `HelidonGrpcMaxReadBufferTest#exceedingDefaultMaxReadBuffer_silentlyStalls`. The root cause
  there is that `GrpcProtocolHandler` throws `IllegalStateException("gRPC message size exceeds max
  read buffer size")` which is caught and never converted to a `RESOURCE_EXHAUSTED` gRPC status.
  That is a separate bug; this report covers the **probabilistic** frame-boundary stall that
  persists even when `maxReadBufferSize` is raised to 128 MB.
