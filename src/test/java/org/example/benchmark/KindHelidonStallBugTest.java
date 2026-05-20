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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Measures the Helidon 4.4.1 silent-stall probability under injected network latency.
 *
 * Deploys only the Helidon server to the Kind cluster, then for each latency profile applies
 * tc-netem on the worker node, spawns a stall-test client Job, and parses the STALL_RESULT line.
 * Prints a comparison table of stall rates across latency levels and payload sizes.
 *
 * <pre>
 * ./gradlew test -PincludeStall --tests org.example.benchmark.KindHelidonStallBugTest --rerun-tasks \
 *   -Dstall.messages=100 -Dstall.payloads=2048,4096,8192
 * </pre>
 */
@Tag("stall")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KindHelidonStallBugTest {

    // ── Inner types ──────────────────────────────────────────────────────────

    record LatencyProfile(String name, int latencyMs, int jitterMs) {
        boolean hasChaos() {
            return latencyMs > 0;
        }
    }

    record StallResult(LatencyProfile profile, int payloadKb, int stalls, int total) {
        double stallRate() {
            return total == 0 ? 0.0 : 100.0 * stalls / total;
        }
    }

    // ── Profiles ─────────────────────────────────────────────────────────────

    static final LatencyProfile BASELINE = new LatencyProfile("BASELINE",  0,  0);
    static final LatencyProfile LIGHT    = new LatencyProfile("LIGHT",    25,  5);
    static final LatencyProfile MEDIUM   = new LatencyProfile("MEDIUM",   50, 10);
    static final LatencyProfile HIGH     = new LatencyProfile("HIGH",    100, 20);

    static final List<LatencyProfile> PROFILES = List.of(BASELINE, LIGHT, MEDIUM, HIGH);

    // ── Matrix parameters (tunable via system properties) ────────────────────

    static final int   NUM_MESSAGES = Integer.getInteger("stall.messages", 30);
    static final int[] PAYLOAD_KBS  = parsePayloads(System.getProperty("stall.payloads", "2048,4096,9216,16384,32768"));

    // ── Cluster constants ────────────────────────────────────────────────────

    static final String CLUSTER_NAME  = "benchmark";
    static final String WORKER_NODE   = "benchmark-worker";
    static final String KUBECONFIG    = System.getProperty("user.home") + "/.kube/config";
    static final String NAMESPACE     = "benchmark";
    static final String VERSION       = "0.0.1-SNAPSHOT";
    static final String HELIDON_IMAGE = "helidon-vs-netty-benchmark:" + VERSION + "-helidon";
    static final int    HELIDON_PORT  = 50052;
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
    void measureStallRateUnderLatency() throws Exception {
        ensureHelidonServerDeployed();

        List<StallResult> allResults = new ArrayList<>();

        for (LatencyProfile profile : PROFILES) {
            System.out.printf("%n╔═══════════════════════════════════════════════════════════════╗%n");
            System.out.printf("║ Latency profile: %-44s ║%n", profile.name());
            if (profile.latencyMs() > 0) {
                System.out.printf("║   latency: %dms ± %dms%55s%n", profile.latencyMs(), profile.jitterMs(), "║");
            } else {
                System.out.printf("║   no chaos%61s%n", "║");
            }
            System.out.printf("╚═══════════════════════════════════════════════════════════════╝%n");

            String serverVeth = findServerVeth();

            for (int payloadKb : PAYLOAD_KBS) {
                System.out.printf("  → %d KB × %d messages ...%n", payloadKb, NUM_MESSAGES);

                applyLatency(profile, serverVeth);
                StallResult result;
                try {
                    result = runStallJob(profile, payloadKb);
                } finally {
                    removeLatency(serverVeth);
                    Thread.sleep(300);
                }

                System.out.printf("    stalls=%d/%d (%.0f%%)%n",
                        result.stalls(), result.total(), result.stallRate());
                allResults.add(result);
            }
        }

        printComparisonTable(allResults);
    }

    // ── Cluster / deployment helpers ──────────────────────────────────────────

    private void ensureHelidonServerDeployed() throws Exception {
        if (!isDeploymentReady("helidon-server")) {
            System.out.println("Loading " + HELIDON_IMAGE + " into Kind...");
            runWithDockerHost("kind", "load", "docker-image", HELIDON_IMAGE, "--name", CLUSTER_NAME);
            run("kubectl", "--kubeconfig", KUBECONFIG, "-n", NAMESPACE,
                    "apply", "-f", "k8s/helidon-server.yaml");
            run("kubectl", "--kubeconfig", KUBECONFIG, "-n", NAMESPACE,
                    "rollout", "status", "deployment/helidon-server", "--timeout=120s");
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

    private String findServerVeth() throws Exception {
        String podIp = runCapture(
                "kubectl", "--kubeconfig", KUBECONFIG, "-n", NAMESPACE,
                "get", "pod", "-l", "app=helidon-server",
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
                "Could not find veth for pod IP " + podIp + " in route: " + route);
    }

    private void applyLatency(LatencyProfile profile, String veth) throws Exception {
        if (!profile.hasChaos()) {
            return;
        }
        List<String> cmd = new ArrayList<>(
                List.of("docker", "exec", WORKER_NODE, "tc", "qdisc", "add", "dev", veth, "root", "netem"));
        cmd.add("delay");
        cmd.add(profile.latencyMs() + "ms");
        if (profile.jitterMs() > 0) {
            cmd.add(profile.jitterMs() + "ms");
        }
        runWithDockerHost(cmd.toArray(new String[0]));
    }

    private void removeLatency(String veth) {
        try {
            runWithDockerHost("docker", "exec", WORKER_NODE,
                    "tc", "qdisc", "del", "dev", veth, "root");
        } catch (Exception ignored) {
        }
    }

    // ── K8s Job execution ─────────────────────────────────────────────────────

    private StallResult runStallJob(LatencyProfile profile, int payloadKb) throws Exception {
        String jobName = String.format("stall-%dkb-%s-%s",
                payloadKb,
                profile.name().toLowerCase(Locale.ROOT),
                UUID.randomUUID().toString().replace("-", "").substring(0, 8));

        String yaml = buildStallJobYaml(jobName, payloadKb);
        applyYaml(yaml);

        try {
            waitForJobDone(jobName);
            String logs = getJobLogs(jobName);
            return parseStallResult(logs, profile, payloadKb);
        } finally {
            deleteJob(jobName);
        }
    }

    private String buildStallJobYaml(String jobName, int payloadKb) {
        String serverHost = "helidon-server:" + HELIDON_PORT;
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
                              value: "helidon"
                            - name: STALL_SERVER_HOST
                              value: "%s"
                            - name: STALL_MESSAGES
                              value: "%d"
                            - name: STALL_PAYLOAD_KB
                              value: "%d"
                """,
                jobName, NAMESPACE, HELIDON_IMAGE, serverHost, NUM_MESSAGES, payloadKb);
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
        long deadline = System.currentTimeMillis() + 600_000; // 10-minute timeout per job
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

    private StallResult parseStallResult(String logs, LatencyProfile profile, int payloadKb) {
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
                return new StallResult(profile, payloadKb, stalls, total);
            }
        }
        // No STALL_RESULT line — job likely failed to start or crashed
        return new StallResult(profile, payloadKb, NUM_MESSAGES, NUM_MESSAGES);
    }

    // ── Summary printing ──────────────────────────────────────────────────────

    private void printComparisonTable(List<StallResult> results) {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║         Stall rate by latency profile and payload size           ║");
        System.out.println("╠══════════════════════════════════════════════════════════════════╣");
        System.out.printf(Locale.ROOT, " %-10s  %9s  %8s  %8s%n", "profile", "payloadKB", "stalls", "rate");
        System.out.println("──────────────────────────────────────────────────────────────────");

        results.stream()
                .sorted(Comparator.comparing((StallResult r) -> r.profile().name())
                        .thenComparingInt(StallResult::payloadKb))
                .forEach(r -> System.out.printf(Locale.ROOT,
                        " %-10s  %9d  %4d/%-4d  %7.1f%%%n",
                        r.profile().name(), r.payloadKb(),
                        r.stalls(), r.total(), r.stallRate()));

        System.out.println("╚══════════════════════════════════════════════════════════════════╝");
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
