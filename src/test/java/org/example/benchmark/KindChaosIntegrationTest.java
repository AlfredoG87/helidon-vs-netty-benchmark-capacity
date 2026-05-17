// SPDX-License-Identifier: Apache-2.0
package org.example.benchmark;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Runs the gRPC benchmark under composable network chaos scenarios using tc-netem injected
 * directly into the Kind worker node — no external chaos tooling required.
 *
 * <p>Requires a running Kind cluster named "benchmark" with both server images loaded.
 * Run independently (needs no port-forwards; client executes as a K8s Job in-cluster):
 *
 * <pre>
 *   DOCKER_HOST=unix://…/docker.raw.sock \
 *   ./gradlew test -PincludeChaos --tests org.example.benchmark.KindChaosIntegrationTest
 * </pre>
 *
 * <p>Tunable via system properties:
 * <ul>
 *   <li>{@code chaos.messages}  — messages per Job run (default 50)
 *   <li>{@code chaos.payloads}  — comma-separated payloadKB list (default 5,50,500)
 * </ul>
 */
@Tag("chaos")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KindChaosIntegrationTest {

    // ── Inner types ──────────────────────────────────────────────────────────

    record ChaosProfile(String name, int latencyMs, int jitterMs, int lossPercent, long bandwidthKbps) {
        boolean hasChoas() {
            return latencyMs > 0 || lossPercent > 0 || bandwidthKbps > 0;
        }
    }

    record ChaosResult(ChaosProfile profile, Impl serverImpl, Impl clientImpl,
                       int payloadKb, double mbps, long delivered, long attempted, String errorCode) {
    }

    enum Impl {
        NETTY, HELIDON;

        int port() {
            return this == NETTY ? NETTY_PORT : HELIDON_PORT;
        }
    }

    // ── Profiles ─────────────────────────────────────────────────────────────

    static final ChaosProfile BASELINE     = new ChaosProfile("BASELINE",    0,   0,  0,      0);
    static final ChaosProfile HIGH_LATENCY = new ChaosProfile("HIGH_LATENCY", 100, 20, 0,     0);
    static final ChaosProfile LOSSY        = new ChaosProfile("LOSSY",        0,   0, 10,     0);
    static final ChaosProfile CONGESTED    = new ChaosProfile("CONGESTED",    0,   0,  0, 10_000);
    static final ChaosProfile FULL_CHAOS   = new ChaosProfile("FULL_CHAOS",  100, 20, 10, 10_000);

    static final List<ChaosProfile> PROFILES = List.of(BASELINE, HIGH_LATENCY, LOSSY, CONGESTED, FULL_CHAOS);

    // ── Matrix parameters (tunable via system properties) ────────────────────

    static final long NUM_MESSAGES = Long.getLong("chaos.messages", 50);
    static final int[] PAYLOAD_KBS = parsePayloads(System.getProperty("chaos.payloads", "5,50,500"));

    // ── Cluster constants ────────────────────────────────────────────────────

    static final String CLUSTER_NAME  = "benchmark";
    static final String WORKER_NODE   = "benchmark-worker";
    static final String KUBECONFIG    = System.getProperty("user.home") + "/.kube/config";
    static final String NAMESPACE     = "benchmark";
    static final String VERSION       = "0.0.1-SNAPSHOT";
    static final String IMAGE_PREFIX  = "helidon-vs-netty-benchmark:" + VERSION + "-";
    static final int    NETTY_PORT    = 50051;
    static final int    HELIDON_PORT  = 50052;
    static final String DOCKER_HOST   = detectDockerHost();

    // ── State ────────────────────────────────────────────────────────────────

    private final Map<Impl, Boolean> deployed = new EnumMap<>(Impl.class);

    // ── Test entry point ─────────────────────────────────────────────────────

    @BeforeAll
    void checkPrerequisites() {
        Assumptions.assumeTrue(isOnPath("kind"),    "kind is required");
        Assumptions.assumeTrue(isOnPath("kubectl"), "kubectl is required");
        Assumptions.assumeTrue(isOnPath("docker"),  "docker is required for tc-netem injection");
        Assumptions.assumeTrue(clusterExists(),     "Kind cluster 'benchmark' must be running");
    }

    @Test
    void runChaosMatrix() throws Exception {
        ensureServersDeployed();

        List<ChaosResult> allResults = new ArrayList<>();

        for (ChaosProfile profile : PROFILES) {
            System.out.printf("%n╔═══════════════════════════════════════════════════════════════╗%n");
            System.out.printf("║ Profile: %-52s ║%n", profile.name());
            if (profile.latencyMs() > 0) {
                System.out.printf("║   latency:    %dms ± %dms%n", profile.latencyMs(), profile.jitterMs());
            }
            if (profile.lossPercent() > 0) {
                System.out.printf("║   packet loss: %d%%%n", profile.lossPercent());
            }
            if (profile.bandwidthKbps() > 0) {
                System.out.printf("║   bandwidth:   %,d kbps (%,.1f MB/s)%n",
                        profile.bandwidthKbps(), profile.bandwidthKbps() / 8.0 / 1024.0);
            }
            System.out.printf("╚═══════════════════════════════════════════════════════════════╝%n");

            List<ChaosResult> profileResults = new ArrayList<>();

            for (Impl serverImpl : Impl.values()) {
                String serverVeth = findServerVeth(serverImpl);

                for (Impl clientImpl : Impl.values()) {
                    for (int payloadKb : PAYLOAD_KBS) {
                        System.out.printf("  → %s server / %s client / %d KB ...%n",
                                serverImpl, clientImpl, payloadKb);

                        applyChaos(profile, serverVeth);
                        ChaosResult result;
                        try {
                            result = runClientJob(profile, serverImpl, clientImpl, payloadKb);
                        } finally {
                            removeChaos(serverVeth);
                            Thread.sleep(300); // let network settle
                        }

                        System.out.printf("    mbps=%.2f delivered=%d/%d error=%s%n",
                                result.mbps(), result.delivered(), result.attempted(), result.errorCode());

                        profileResults.add(result);
                        allResults.add(result);
                    }
                }
            }

            printProfileSummary(profile, profileResults);
        }

        printFinalComparison(allResults);
    }

    // ── Cluster / deployment helpers ──────────────────────────────────────────

    private void ensureServersDeployed() throws Exception {
        for (Impl impl : Impl.values()) {
            if (!isDeploymentReady(impl)) {
                String image = IMAGE_PREFIX + impl.name().toLowerCase(Locale.ROOT);
                System.out.println("Loading " + image + " into Kind...");
                runWithDockerHost("kind", "load", "docker-image", image, "--name", CLUSTER_NAME);
                String manifest = Path.of("k8s/" + impl.name().toLowerCase(Locale.ROOT) + "-server.yaml")
                        .toAbsolutePath().toString();
                run("kubectl", "--kubeconfig", KUBECONFIG, "-n", NAMESPACE, "apply", "-f", manifest);
                run("kubectl", "--kubeconfig", KUBECONFIG, "-n", NAMESPACE,
                        "rollout", "status",
                        "deployment/" + impl.name().toLowerCase(Locale.ROOT) + "-server",
                        "--timeout=120s");
            }
            deployed.put(impl, Boolean.TRUE);
        }
    }

    private boolean isDeploymentReady(Impl impl) {
        try {
            String name = impl.name().toLowerCase(Locale.ROOT) + "-server";
            String ready = runCapture(
                    "kubectl", "--kubeconfig", KUBECONFIG, "-n", NAMESPACE,
                    "get", "deployment", name,
                    "-o", "jsonpath={.status.readyReplicas}").trim();
            return "1".equals(ready);
        } catch (Exception e) {
            return false;
        }
    }

    // ── tc-netem chaos injection ───────────────────────────────────────────────

    private String findServerVeth(Impl serverImpl) throws Exception {
        String label = "app=" + serverImpl.name().toLowerCase(Locale.ROOT) + "-server";
        String podIp = runCapture(
                "kubectl", "--kubeconfig", KUBECONFIG, "-n", NAMESPACE,
                "get", "pod", "-l", label,
                "-o", "jsonpath={.items[0].status.podIP}").trim();

        // "10.244.1.4 dev veth3cd193ea scope host" → veth3cd193ea
        String route = runCaptureWithDockerHost(
                "docker", "exec", WORKER_NODE, "ip", "route", "show", podIp).trim();

        String[] parts = route.split("\\s+");
        for (int i = 0; i < parts.length - 1; i++) {
            if ("dev".equals(parts[i])) {
                return parts[i + 1];
            }
        }
        throw new IllegalStateException(
                "Could not find veth for pod IP " + podIp + " in route output: " + route);
    }

    private void applyChaos(ChaosProfile profile, String veth) throws Exception {
        if (!profile.hasChoas()) {
            return;
        }
        List<String> cmd = new ArrayList<>(
                List.of("docker", "exec", WORKER_NODE, "tc", "qdisc", "add", "dev", veth, "root", "netem"));

        if (profile.latencyMs() > 0) {
            cmd.add("delay");
            cmd.add(profile.latencyMs() + "ms");
            if (profile.jitterMs() > 0) {
                cmd.add(profile.jitterMs() + "ms");
            }
        }
        if (profile.lossPercent() > 0) {
            cmd.add("loss");
            cmd.add(profile.lossPercent() + "%");
        }
        if (profile.bandwidthKbps() > 0) {
            cmd.add("rate");
            cmd.add(profile.bandwidthKbps() + "kbit");
        }

        runWithDockerHost(cmd.toArray(new String[0]));
    }

    private void removeChaos(String veth) {
        try {
            runWithDockerHost("docker", "exec", WORKER_NODE,
                    "tc", "qdisc", "del", "dev", veth, "root");
        } catch (Exception ignored) {
            // Already clean — no qdisc to remove
        }
    }

    // ── K8s Job execution ─────────────────────────────────────────────────────

    private ChaosResult runClientJob(ChaosProfile profile, Impl serverImpl,
                                     Impl clientImpl, int payloadKb) throws Exception {
        String jobName = String.format("bench-%s-%s-%dkb-%s",
                serverImpl.name().toLowerCase(Locale.ROOT),
                clientImpl.name().toLowerCase(Locale.ROOT),
                payloadKb,
                UUID.randomUUID().toString().replace("-", "").substring(0, 8));

        String yaml = buildJobYaml(jobName, clientImpl, serverImpl, NUM_MESSAGES, payloadKb);
        applyYaml(yaml);

        try {
            waitForJobDone(jobName);
            String logs = getJobLogs(jobName);
            return parseResult(logs, profile, serverImpl, clientImpl, payloadKb);
        } finally {
            deleteJob(jobName);
        }
    }

    private static String buildJobYaml(String jobName, Impl clientImpl, Impl serverImpl,
                                        long numMessages, int payloadKb) {
        String image = IMAGE_PREFIX + clientImpl.name().toLowerCase(Locale.ROOT);
        String serverHost = serverImpl.name().toLowerCase(Locale.ROOT) + "-server:" + serverImpl.port();
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
                        - name: client
                          image: %s
                          imagePullPolicy: Never
                          env:
                            - name: MODE
                              value: "client"
                            - name: CLIENT_IMPL
                              value: "%s"
                            - name: SERVER_HOST
                              value: "%s"
                            - name: NUM_MESSAGES
                              value: "%d"
                            - name: PAYLOAD_KB
                              value: "%d"
                """,
                jobName, NAMESPACE, image,
                clientImpl.name().toLowerCase(Locale.ROOT),
                serverHost, numMessages, payloadKb);
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
        long deadline = System.currentTimeMillis() + 300_000; // 5-minute timeout per job
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
        throw new RuntimeException("Job " + jobName + " did not finish within 5 minutes");
    }

    private String getJobLogs(String jobName) throws Exception {
        try {
            return runCapture("kubectl", "--kubeconfig", KUBECONFIG, "-n", NAMESPACE, "logs", "job/" + jobName);
        } catch (Exception e) {
            return ""; // Pod may not have started — no logs available
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

    private ChaosResult parseResult(String logs, ChaosProfile profile,
                                    Impl serverImpl, Impl clientImpl, int payloadKb) {
        for (String line : logs.split("\n")) {
            if (line.startsWith("RESULT ")) {
                return parseResultLine(line.trim(), profile, serverImpl, clientImpl, payloadKb);
            }
        }
        return new ChaosResult(profile, serverImpl, clientImpl, payloadKb,
                0.0, 0, NUM_MESSAGES, "NO_RESULT");
    }

    private ChaosResult parseResultLine(String line, ChaosProfile profile,
                                        Impl serverImpl, Impl clientImpl, int payloadKb) {
        Map<String, String> fields = new HashMap<>();
        for (String token : line.substring("RESULT ".length()).split("\\s+")) {
            String[] kv = token.split("=", 2);
            if (kv.length == 2) {
                fields.put(kv[0], kv[1]);
            }
        }
        double mbps      = Double.parseDouble(fields.getOrDefault("mbps", "0"));
        long delivered   = Long.parseLong(fields.getOrDefault("delivered", "0"));
        long attempted   = Long.parseLong(fields.getOrDefault("attempted", String.valueOf(NUM_MESSAGES)));
        String errorCode = fields.getOrDefault("error", "UNKNOWN");
        return new ChaosResult(profile, serverImpl, clientImpl, payloadKb,
                mbps, delivered, attempted, errorCode);
    }

    // ── Summary printing ──────────────────────────────────────────────────────

    private void printProfileSummary(ChaosProfile profile, List<ChaosResult> results) {
        System.out.println();
        System.out.println("══════════════════════════════════════════════════════════════════════════════");
        System.out.printf(Locale.ROOT, " Summary — %s%n", profile.name());
        System.out.println("──────────────────────────────────────────────────────────────────────────────");
        System.out.println("  server   client  payloadKB  delivered/attempted     MB/s  error");
        System.out.println("──────────────────────────────────────────────────────────────────────────────");
        results.stream()
                .sorted(Comparator.comparing(ChaosResult::serverImpl)
                        .thenComparing(ChaosResult::clientImpl)
                        .thenComparingInt(ChaosResult::payloadKb))
                .forEach(r -> System.out.printf(Locale.ROOT,
                        " %7s  %7s  %9d  %7d / %-7d  %7.2f  %s%n",
                        r.serverImpl(), r.clientImpl(), r.payloadKb(),
                        r.delivered(), r.attempted(), r.mbps(), r.errorCode()));
        System.out.println("══════════════════════════════════════════════════════════════════════════════");
    }

    private void printFinalComparison(List<ChaosResult> allResults) {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║                    Cross-profile comparison (avg MB/s)                       ║");
        System.out.println("╠══════════════════════════════════════════════════════════════════════════════╣");
        System.out.printf(Locale.ROOT, " %-14s  %-8s  %-8s  %-9s  %s%n",
                "profile", "server", "client", "payloadKB", "MB/s");
        System.out.println("────────────────────────────────────────────────────────────────────────────────");

        allResults.stream()
                .sorted(Comparator.comparing((ChaosResult r) -> r.profile().name())
                        .thenComparing(ChaosResult::serverImpl)
                        .thenComparing(ChaosResult::clientImpl)
                        .thenComparingInt(ChaosResult::payloadKb))
                .forEach(r -> System.out.printf(Locale.ROOT,
                        " %-14s  %-8s  %-8s  %9d  %7.2f  %s%n",
                        r.profile().name(), r.serverImpl(), r.clientImpl(),
                        r.payloadKb(), r.mbps(),
                        "OK".equals(r.errorCode()) ? "" : "⚠ " + r.errorCode()));
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
