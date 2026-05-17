// SPDX-License-Identifier: Apache-2.0
package org.example.benchmark;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs the throughput matrix with servers deployed as pods in a local Kind cluster.
 * Both servers are deployed once at startup and stay up for all 16 test combinations —
 * only port-forwards are created/torn down per test case.
 *
 * Run with: ./gradlew test -PincludeKind --tests org.example.benchmark.KindThroughputIntegrationTest
 * See: k8s/run-benchmark.sh for a full end-to-end script.
 */
@Tag("kind")
class KindThroughputIntegrationTest extends AbstractThroughputMatrixTest {

    private static final String CLUSTER_NAME = "benchmark";
    private static final String VERSION      = "0.0.1-SNAPSHOT";
    private static final String KUBECONFIG  = System.getProperty("user.home") + "/.kube/config";
    private static final String NAMESPACE   = "benchmark";

    // Populated once in ensureClusterReady(); reused by every startServer() call.
    private static final Map<Impl, Boolean> DEPLOYED = new EnumMap<>(Impl.class);

    // Docker socket — required by `kind load docker-image`. Auto-detects docker.raw.sock on macOS.
    private static final String DOCKER_HOST = detectDockerHost();

    @Override
    protected ServerHandle startServer(Impl impl) throws Exception {
        ensureClusterReady(impl);

        int containerPort = impl == Impl.NETTY ? 50051 : 50052;
        int localPort     = findFreePort();
        Process portForward = startPortForward(impl, containerPort, localPort);
        waitForPortForward(localPort);

        return new ServerHandle("localhost", localPort, portForward::destroyForcibly);
    }

    @AfterAll
    void deleteCluster() {
        try {
            runWithDockerHost("kind", "delete", "cluster", "--name", CLUSTER_NAME);
        } catch (Exception e) {
            System.err.println("Warning: failed to delete Kind cluster: " + e.getMessage());
        }
    }

    @Override
    protected String summaryLabel() {
        return "servers in Kind (Kubernetes) cluster";
    }

    // -----------------------------------------------------------------
    // Cluster + deployment lifecycle (runs once per impl, not per test)
    // -----------------------------------------------------------------

    private static void ensureClusterReady(Impl impl) throws Exception {
        assumeKind();
        ensureClusterExists();
        if (!DEPLOYED.containsKey(impl)) {
            String image = "helidon-vs-netty-benchmark:" + VERSION + "-" + impl.name().toLowerCase(Locale.ROOT);
            loadImage(image);
            applyManifest(impl);
            waitForRollout(impl);
            DEPLOYED.put(impl, Boolean.TRUE);
        }
    }

    private static void assumeKind() {
        Assumptions.assumeTrue(isOnPath("kind"),    "kind is required for this test");
        Assumptions.assumeTrue(isOnPath("kubectl"), "kubectl is required for this test");
    }

    private static void ensureClusterExists() throws Exception {
        ProcessBuilder check = new ProcessBuilder("kind", "get", "clusters");
        if (!DOCKER_HOST.isEmpty()) {
            check.environment().put("DOCKER_HOST", DOCKER_HOST);
        }
        check.redirectErrorStream(true);
        Process proc = check.start();
        String output = new String(proc.getInputStream().readAllBytes());
        proc.waitFor(10, TimeUnit.SECONDS);
        if (!output.contains(CLUSTER_NAME)) {
            System.out.println("Creating Kind cluster '" + CLUSTER_NAME + "'...");
            runWithDockerHost("kind", "create", "cluster",
                    "--config", Path.of("k8s/kind-config.yaml").toAbsolutePath().toString());
            run("kubectl", "--kubeconfig", KUBECONFIG, "apply", "-f",
                    Path.of("k8s/namespace.yaml").toAbsolutePath().toString());
        }
    }

    private static void loadImage(String image) throws Exception {
        System.out.println("Loading " + image + " into Kind...");
        runWithDockerHost("kind", "load", "docker-image", image, "--name", CLUSTER_NAME);
    }

    private static void applyManifest(Impl impl) throws Exception {
        String manifest = Path.of("k8s/" + impl.name().toLowerCase(Locale.ROOT) + "-server.yaml")
                .toAbsolutePath().toString();
        run("kubectl", "--kubeconfig", KUBECONFIG, "-n", NAMESPACE, "apply", "-f", manifest);
    }

    private static void waitForRollout(Impl impl) throws Exception {
        String deployment = impl.name().toLowerCase(Locale.ROOT) + "-server";
        System.out.println("Waiting for deployment " + deployment + " to roll out...");
        run("kubectl", "--kubeconfig", KUBECONFIG, "-n", NAMESPACE,
                "rollout", "status", "deployment/" + deployment, "--timeout=60s");
    }

    // -----------------------------------------------------------------
    // Port-forward helpers (created/destroyed per test case)
    // -----------------------------------------------------------------

    private static Process startPortForward(Impl impl, int containerPort, int localPort) throws Exception {
        String service = impl.name().toLowerCase(Locale.ROOT) + "-server";
        return new ProcessBuilder(
                "kubectl", "--kubeconfig", KUBECONFIG, "-n", NAMESPACE,
                "port-forward", "service/" + service,
                localPort + ":" + containerPort)
                .redirectErrorStream(true)
                .start();
    }

    private static void waitForPortForward(int localPort) throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            try (ServerSocket probe = new ServerSocket()) {
                probe.setReuseAddress(true);
                probe.bind(new InetSocketAddress("localhost", localPort));
            } catch (IOException ignored) {
                return; // port is taken — port-forward is up
            }
            Thread.sleep(200);
        }
        throw new IllegalStateException(
                "kubectl port-forward did not bind on port " + localPort + " within 15 s");
    }

    // -----------------------------------------------------------------
    // Utilities
    // -----------------------------------------------------------------

    private static int findFreePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            s.setReuseAddress(true);
            return s.getLocalPort();
        }
    }

    private static void run(String... command) throws Exception {
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        String output  = new String(process.getInputStream().readAllBytes());
        boolean done   = process.waitFor(120, TimeUnit.SECONDS);
        if (!done || process.exitValue() != 0) {
            throw new RuntimeException(
                    "Command failed: " + List.of(command) + "\nOutput: " + output);
        }
    }

    private static void runWithDockerHost(String... command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
        if (!DOCKER_HOST.isEmpty()) {
            pb.environment().put("DOCKER_HOST", DOCKER_HOST);
        }
        Process process = pb.start();
        String output   = new String(process.getInputStream().readAllBytes());
        boolean done    = process.waitFor(300, TimeUnit.SECONDS);
        if (!done || process.exitValue() != 0) {
            throw new RuntimeException(
                    "Command failed: " + List.of(command) + "\nOutput: " + output);
        }
    }

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

    private static boolean isOnPath(String executable) {
        try {
            Process proc = new ProcessBuilder("which", executable).start();
            return proc.waitFor(5, TimeUnit.SECONDS) && proc.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
