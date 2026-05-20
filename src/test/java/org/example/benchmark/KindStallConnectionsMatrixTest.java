// SPDX-License-Identifier: Apache-2.0
package org.example.benchmark;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Runs the full 2×2 stall-connection matrix in the Kind cluster under three latency profiles.
 *
 * <pre>
 *                │ Helidon server  │ Netty server
 * ───────────────┼─────────────────┼─────────────
 * Helidon client │ h→h             │ h→n
 * Netty client   │ n→h             │ n→n
 * </pre>
 *
 * Profiles: BASELINE (0 ms), LATENCY_10MS (10 ms), LATENCY_20MS (20 ms).
 *
 * <pre>
 * ./gradlew test -PincludeStall \
 *   --tests org.example.benchmark.KindStallConnectionsMatrixTest --rerun-tasks \
 *   -Dstall.messages=30 -Dstall.payloads=2048,4090,4096,8192
 * </pre>
 */
@Tag("stall")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KindStallConnectionsMatrixTest {

    // ── Inner types ──────────────────────────────────────────────────────────

    record LatencyProfile(String name, int latencyMs) {
        boolean hasChaos() {
            return latencyMs > 0;
        }
    }

    record MatrixKey(String clientType, String serverType) {
        String label() {
            return clientType.charAt(0) + "→" + serverType.charAt(0);
        }
    }

    record MatrixResult(LatencyProfile profile, MatrixKey cell, int payloadKb, int stalls, int total) {
        double stallRate() {
            return total == 0 ? 0.0 : 100.0 * stalls / total;
        }
    }

    // ── Profiles ─────────────────────────────────────────────────────────────

    static final LatencyProfile BASELINE      = new LatencyProfile("BASELINE",  0);
    static final LatencyProfile LATENCY_10MS  = new LatencyProfile("10ms",     10);
    static final LatencyProfile LATENCY_20MS  = new LatencyProfile("20ms",     20);
    static final LatencyProfile LATENCY_30MS  = new LatencyProfile("30ms",     30);

    static final List<LatencyProfile> PROFILES = List.of(BASELINE, LATENCY_10MS, LATENCY_20MS, LATENCY_30MS);

    // ── 2×2 matrix cells ─────────────────────────────────────────────────────

    static final MatrixKey H_TO_H = new MatrixKey("helidon", "helidon");
    static final MatrixKey N_TO_H = new MatrixKey("netty",   "helidon");
    static final MatrixKey H_TO_N = new MatrixKey("helidon", "netty");
    static final MatrixKey N_TO_N = new MatrixKey("netty",   "netty");

    static final List<MatrixKey> CELLS = List.of(H_TO_H, N_TO_H, H_TO_N, N_TO_N);

    // ── Matrix parameters (tunable via system properties) ────────────────────

    static final int   NUM_MESSAGES = Integer.getInteger("stall.messages", 30);
    // Only probabilistic sizes (≤4090 KB). The ≥4096 KB case is deterministic (100% stall due to
    // gRPC-Java 4 MB message size limit) and does not need K8s characterisation.
    static final int[] PAYLOAD_KBS  = parsePayloads(System.getProperty("stall.payloads", "64,128,256,512,1024,2048,4090"));

    // ── Cluster constants ────────────────────────────────────────────────────

    static final String CLUSTER_NAME  = "benchmark";
    static final String WORKER_NODE   = "benchmark-worker";
    static final String KUBECONFIG    = System.getProperty("user.home") + "/.kube/config";
    static final String NAMESPACE     = "benchmark";
    static final String VERSION       = "0.0.1-SNAPSHOT";
    static final String HELIDON_IMAGE = "helidon-vs-netty-benchmark:" + VERSION + "-helidon";
    static final String NETTY_IMAGE   = "helidon-vs-netty-benchmark:" + VERSION + "-netty";
    static final int    HELIDON_PORT  = 50052;
    static final int    NETTY_PORT    = 50051;
    static final String DOCKER_HOST   = detectDockerHost();

    // ── Test entry point ─────────────────────────────────────────────────────

    @BeforeAll
    void checkPrerequisites() {
        Assumptions.assumeTrue(isOnPath("kind"),    "kind is required");
        Assumptions.assumeTrue(isOnPath("kubectl"), "kubectl is required");
        Assumptions.assumeTrue(isOnPath("docker"),  "docker is required for tc-netem injection");
        Assumptions.assumeTrue(clusterExists(),     "Kind cluster 'benchmark' must be running");
    }

    @Test
    void measureStallMatrixUnderLatency() throws Exception {
        ensureBothServersDeployed();

        List<MatrixResult> allResults = new ArrayList<>();

        for (LatencyProfile profile : PROFILES) {
            System.out.printf("%n╔═══════════════════════════════════════════════════════════════╗%n");
            System.out.printf("║ Latency profile: %-44s ║%n", profile.name());
            if (profile.hasChaos()) {
                System.out.printf("║   latency: %dms%57s%n", profile.latencyMs(), "║");
            } else {
                System.out.printf("║   no chaos%61s%n", "║");
            }
            System.out.printf("╚═══════════════════════════════════════════════════════════════╝%n");

            // Group by server to apply netem once per server per profile.
            for (String serverType : List.of("helidon", "netty")) {
                String serverVeth = findServerVeth(serverType);
                applyLatency(profile, serverVeth);
                try {
                    for (String clientType : List.of("helidon", "netty")) {
                        MatrixKey cell = new MatrixKey(clientType, serverType);
                        for (int payloadKb : PAYLOAD_KBS) {
                            System.out.printf("  [%s] %d KB × %d messages ...%n",
                                    cell.label(), payloadKb, NUM_MESSAGES);
                            MatrixResult result = runStallJob(profile, cell, payloadKb);
                            System.out.printf("    stalls=%d/%d (%.0f%%)%n",
                                    result.stalls(), result.total(), result.stallRate());
                            allResults.add(result);
                        }
                    }
                } finally {
                    removeLatency(serverVeth);
                    Thread.sleep(300);
                }
            }

            printProfileTable(profile, allResults);
        }

        printCrossProfileSummary(allResults);
    }

    // ── Cluster / deployment helpers ──────────────────────────────────────────

    private void ensureBothServersDeployed() throws Exception {
        if (!isDeploymentReady("helidon-server")) {
            System.out.println("Loading " + HELIDON_IMAGE + " into Kind...");
            runWithDockerHost("kind", "load", "docker-image", HELIDON_IMAGE, "--name", CLUSTER_NAME);
            run("kubectl", "--kubeconfig", KUBECONFIG, "-n", NAMESPACE,
                    "apply", "-f", "k8s/helidon-server.yaml");
            run("kubectl", "--kubeconfig", KUBECONFIG, "-n", NAMESPACE,
                    "rollout", "status", "deployment/helidon-server", "--timeout=120s");
        }
        if (!isDeploymentReady("netty-server")) {
            System.out.println("Loading " + NETTY_IMAGE + " into Kind...");
            runWithDockerHost("kind", "load", "docker-image", NETTY_IMAGE, "--name", CLUSTER_NAME);
            run("kubectl", "--kubeconfig", KUBECONFIG, "-n", NAMESPACE,
                    "apply", "-f", "k8s/netty-server.yaml");
            run("kubectl", "--kubeconfig", KUBECONFIG, "-n", NAMESPACE,
                    "rollout", "status", "deployment/netty-server", "--timeout=120s");
        }
    }

    private boolean isDeploymentReady(String name) {
        try {
            String ready = runCapture(
                    "kubectl", "--kubeconfig", KUBECONFIG, "-n", NAMESPACE,
                    "get", "deployment", name,
                    "-o", "jsonpath={.status.readyReplicas}").trim();
            return "1".equals(ready);
        } catch (Exception e) {
            return false;
        }
    }

    // ── tc-netem chaos injection ──────────────────────────────────────────────

    private String findServerVeth(String serverType) throws Exception {
        String appLabel = serverType + "-server";
        String podIp = runCapture(
                "kubectl", "--kubeconfig", KUBECONFIG, "-n", NAMESPACE,
                "get", "pod", "-l", "app=" + appLabel,
                "-o", "jsonpath={.items[0].status.podIP}").trim();

        String route = runCaptureWithDockerHost(
                "docker", "exec", WORKER_NODE, "ip", "route", "show", podIp).trim();

        String[] parts = route.split("\\s+");
        for (int i = 0; i < parts.length - 1; i++) {
            if ("dev".equals(parts[i])) {
                return parts[i + 1];
            }
        }
        throw new IllegalStateException(
                "Could not find veth for " + appLabel + " pod IP " + podIp + " in route: " + route);
    }

    private void applyLatency(LatencyProfile profile, String veth) throws Exception {
        if (!profile.hasChaos()) {
            return;
        }
        runWithDockerHost("docker", "exec", WORKER_NODE,
                "tc", "qdisc", "add", "dev", veth, "root", "netem",
                "delay", profile.latencyMs() + "ms");
    }

    private void removeLatency(String veth) {
        try {
            runWithDockerHost("docker", "exec", WORKER_NODE,
                    "tc", "qdisc", "del", "dev", veth, "root");
        } catch (Exception ignored) {
        }
    }

    // ── K8s Job execution ─────────────────────────────────────────────────────

    private MatrixResult runStallJob(LatencyProfile profile, MatrixKey cell, int payloadKb) throws Exception {
        String jobName = String.format("stall-%s-%dkb-%s-%s",
                cell.label().replace("→", "-to-"),
                payloadKb,
                profile.name().toLowerCase(Locale.ROOT),
                UUID.randomUUID().toString().replace("-", "").substring(0, 8));

        String serverHost = "helidon".equals(cell.serverType())
                ? "helidon-server:" + HELIDON_PORT
                : "netty-server:" + NETTY_PORT;

        String clientImage = "helidon".equals(cell.clientType()) ? HELIDON_IMAGE : NETTY_IMAGE;

        String yaml = buildStallJobYaml(jobName, cell.clientType(), serverHost, payloadKb, clientImage);
        applyYaml(yaml);

        try {
            waitForJobDone(jobName);
            String logs = getJobLogs(jobName);
            return parseStallResult(logs, profile, cell, payloadKb);
        } finally {
            deleteJob(jobName);
        }
    }

    private String buildStallJobYaml(
            String jobName, String clientType, String serverHost, int payloadKb, String image) {
        return String.format("""
                apiVersion: batch/v1
                kind: Job
                metadata:
                  name: %s
                  namespace: %s
                spec:
                  backoffLimit: 0
                  ttlSecondsAfterFinished: 300
                  template:
                    spec:
                      restartPolicy: Never
                      containers:
                        - name: stall-client
                          image: %s
                          imagePullPolicy: Never
                          env:
                            - name: MODE
                              value: "stall"
                            - name: STALL_CLIENT_TYPE
                              value: "%s"
                            - name: STALL_SERVER_HOST
                              value: "%s"
                            - name: STALL_MESSAGES
                              value: "%d"
                            - name: STALL_PAYLOAD_KB
                              value: "%d"
                """,
                jobName, NAMESPACE, image, clientType, serverHost, NUM_MESSAGES, payloadKb);
    }

    private void applyYaml(String yaml) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("kubectl", "--kubeconfig", KUBECONFIG, "apply", "-f", "-");
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        proc.getOutputStream().write(yaml.getBytes(StandardCharsets.UTF_8));
        proc.getOutputStream().close();
        String output = new String(proc.getInputStream().readAllBytes());
        boolean done = proc.waitFor(30, TimeUnit.SECONDS);
        if (!done || proc.exitValue() != 0) {
            throw new RuntimeException("kubectl apply failed:\n" + output);
        }
    }

    private void waitForJobDone(String jobName) throws Exception {
        long deadline = System.currentTimeMillis() + 1_800_000; // 30 minutes
        while (System.currentTimeMillis() < deadline) {
            String json = runCapture(
                    "kubectl", "--kubeconfig", KUBECONFIG, "-n", NAMESPACE,
                    "get", "job", jobName,
                    "-o", "jsonpath={.status.succeeded},{.status.failed}").trim();
            String[] parts = json.split(",", -1);
            int succeeded = parts.length > 0 && !parts[0].isEmpty() ? Integer.parseInt(parts[0]) : 0;
            int failed    = parts.length > 1 && !parts[1].isEmpty() ? Integer.parseInt(parts[1]) : 0;
            if (succeeded > 0 || failed > 0) {
                return;
            }
            Thread.sleep(2_000);
        }
        throw new RuntimeException("Job " + jobName + " did not finish within 10 minutes");
    }

    private String getJobLogs(String jobName) throws Exception {
        try {
            return runCapture("kubectl", "--kubeconfig", KUBECONFIG, "-n", NAMESPACE,
                    "logs", "job/" + jobName);
        } catch (Exception e) {
            return "";
        }
    }

    private void deleteJob(String jobName) {
        try {
            run("kubectl", "--kubeconfig", KUBECONFIG, "-n", NAMESPACE,
                    "delete", "job", jobName, "--ignore-not-found=true");
        } catch (Exception ignored) {
        }
    }

    // ── Result parsing ────────────────────────────────────────────────────────

    private MatrixResult parseStallResult(String logs, LatencyProfile profile, MatrixKey cell, int payloadKb) {
        for (String line : logs.split("\n")) {
            if (line.startsWith("STALL_RESULT ")) {
                Map<String, String> fields = new HashMap<>();
                for (String token : line.substring("STALL_RESULT ".length()).split("\\s+")) {
                    String[] kv = token.split("=", 2);
                    if (kv.length == 2) {
                        fields.put(kv[0], kv[1]);
                    }
                }
                int stalls = Integer.parseInt(fields.getOrDefault("stalls", "0"));
                int total  = Integer.parseInt(fields.getOrDefault("total", String.valueOf(NUM_MESSAGES)));
                return new MatrixResult(profile, cell, payloadKb, stalls, total);
            }
        }
        return new MatrixResult(profile, cell, payloadKb, NUM_MESSAGES, NUM_MESSAGES);
    }

    // ── Summary printing ──────────────────────────────────────────────────────

    private void printProfileTable(LatencyProfile profile, List<MatrixResult> allResults) {
        List<MatrixResult> rows = allResults.stream()
                .filter(r -> r.profile().equals(profile))
                .toList();

        System.out.println();
        System.out.printf("  ┌─────────────────────────────────────────────────────┐%n");
        System.out.printf("  │ %s — stall rates                                    │%n", profile.name());
        System.out.printf("  ├──────────┬──────────┬──────────┬──────────┬──────────┤%n");
        System.out.printf("  │ payload  │  h→h     │  n→h     │  h→n     │  n→n     │%n");
        System.out.printf("  ├──────────┼──────────┼──────────┼──────────┼──────────┤%n");

        for (int payloadKb : PAYLOAD_KBS) {
            System.out.printf("  │ %7dK │", payloadKb);
            for (MatrixKey cell : CELLS) {
                double rate = rows.stream()
                        .filter(r -> r.cell().equals(cell) && r.payloadKb() == payloadKb)
                        .mapToDouble(MatrixResult::stallRate)
                        .findFirst().orElse(-1.0);
                if (rate < 0) {
                    System.out.printf(" %8s │", "—");
                } else {
                    System.out.printf(" %7.0f%% │", rate);
                }
            }
            System.out.println();
        }
        System.out.printf("  └──────────┴──────────┴──────────┴──────────┴──────────┘%n");
    }

    private void printCrossProfileSummary(List<MatrixResult> allResults) {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║                Cross-profile summary (h→h and n→h — the Helidon defects)    ║");
        System.out.println("╠══════════════════════════════════════════════════════════════════════════════╣");
        System.out.printf(Locale.ROOT, "  %-10s  %-6s  %-9s  %-7s  %-9s  %-7s%n",
                "profile", "payload", "h→h stalls", "h→h rate", "n→h stalls", "n→h rate");
        System.out.println("  ──────────────────────────────────────────────────────────────────────────");

        for (LatencyProfile profile : PROFILES) {
            for (int payloadKb : PAYLOAD_KBS) {
                int finalPayloadKb = payloadKb;
                double hhRate = allResults.stream()
                        .filter(r -> r.profile().equals(profile) && r.cell().equals(H_TO_H) && r.payloadKb() == finalPayloadKb)
                        .mapToDouble(MatrixResult::stallRate).findFirst().orElse(-1.0);
                double nhRate = allResults.stream()
                        .filter(r -> r.profile().equals(profile) && r.cell().equals(N_TO_H) && r.payloadKb() == finalPayloadKb)
                        .mapToDouble(MatrixResult::stallRate).findFirst().orElse(-1.0);
                MatrixResult hhR = allResults.stream()
                        .filter(r -> r.profile().equals(profile) && r.cell().equals(H_TO_H) && r.payloadKb() == finalPayloadKb)
                        .findFirst().orElse(null);
                MatrixResult nhR = allResults.stream()
                        .filter(r -> r.profile().equals(profile) && r.cell().equals(N_TO_H) && r.payloadKb() == finalPayloadKb)
                        .findFirst().orElse(null);

                System.out.printf(Locale.ROOT, "  %-10s  %5dK  %4d/%-4d  %6.0f%%  %4d/%-4d  %6.0f%%%n",
                        profile.name(), payloadKb,
                        hhR != null ? hhR.stalls() : 0, hhR != null ? hhR.total() : 0, hhRate,
                        nhR != null ? nhR.stalls() : 0, nhR != null ? nhR.total() : 0, nhRate);
            }
        }
        System.out.println("╚══════════════════════════════════════════════════════════════════════════════╝");
    }

    // ── Process execution helpers ─────────────────────────────────────────────

    private static void run(String... command) throws Exception {
        Process proc = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        String output = new String(proc.getInputStream().readAllBytes());
        boolean done  = proc.waitFor(120, TimeUnit.SECONDS);
        if (!done || proc.exitValue() != 0) {
            throw new RuntimeException("Command failed: " + Arrays.toString(command) + "\n" + output);
        }
    }

    private static void runWithDockerHost(String... command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
        if (!DOCKER_HOST.isEmpty()) {
            pb.environment().put("DOCKER_HOST", DOCKER_HOST);
        }
        Process proc = pb.start();
        String output = new String(proc.getInputStream().readAllBytes());
        boolean done  = proc.waitFor(120, TimeUnit.SECONDS);
        if (!done || proc.exitValue() != 0) {
            throw new RuntimeException("Command failed: " + Arrays.toString(command) + "\n" + output);
        }
    }

    private static String runCapture(String... command) throws Exception {
        Process proc = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        String output = new String(proc.getInputStream().readAllBytes());
        boolean done  = proc.waitFor(30, TimeUnit.SECONDS);
        if (!done || proc.exitValue() != 0) {
            throw new RuntimeException("Command failed: " + Arrays.toString(command) + "\n" + output);
        }
        return output;
    }

    private static String runCaptureWithDockerHost(String... command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
        if (!DOCKER_HOST.isEmpty()) {
            pb.environment().put("DOCKER_HOST", DOCKER_HOST);
        }
        Process proc = pb.start();
        String output = new String(proc.getInputStream().readAllBytes());
        boolean done  = proc.waitFor(30, TimeUnit.SECONDS);
        if (!done || proc.exitValue() != 0) {
            throw new RuntimeException("Command failed: " + Arrays.toString(command) + "\n" + output);
        }
        return output;
    }

    // ── Utility helpers ───────────────────────────────────────────────────────

    private static String detectDockerHost() {
        String env = System.getenv("DOCKER_HOST");
        if (env != null && !env.isEmpty()) {
            return env;
        }
        String rawSocket = System.getProperty("user.home")
                + "/Library/Containers/com.docker.docker/Data/docker.raw.sock";
        if (new File(rawSocket).exists()) {
            return "unix://" + rawSocket;
        }
        return "";
    }

    private static boolean clusterExists() {
        try {
            ProcessBuilder pb = new ProcessBuilder("kind", "get", "clusters");
            if (!DOCKER_HOST.isEmpty()) {
                pb.environment().put("DOCKER_HOST", DOCKER_HOST);
            }
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes());
            return proc.waitFor(10, TimeUnit.SECONDS) && output.contains(CLUSTER_NAME);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isOnPath(String executable) {
        try {
            Process proc = new ProcessBuilder("which", executable).start();
            return proc.waitFor(5, TimeUnit.SECONDS) && proc.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static int[] parsePayloads(String csv) {
        return Arrays.stream(csv.split(","))
                .mapToInt(s -> Integer.parseInt(s.trim()))
                .toArray();
    }
}
