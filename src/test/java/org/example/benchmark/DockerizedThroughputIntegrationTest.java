// SPDX-License-Identifier: Apache-2.0
package org.example.benchmark;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;

@Tag("docker")
class DockerizedThroughputIntegrationTest extends AbstractThroughputMatrixTest {

    private static final DockerImageName BASE_IMAGE = DockerImageName.parse("eclipse-temurin:21");

    @Override
    protected ServerHandle startServer(Impl impl) throws Exception {
        assumeDocker();
        Path installDir = installDistDirectory();
        Assumptions.assumeTrue(Files.isDirectory(installDir), "Application distribution not found at " + installDir);

        int containerPort = impl == Impl.NETTY ? 50051 : 50052;
        String command = String.format(Locale.ROOT,
                "/app/bin/helidon-vs-netty-benchmark-capacity server %s %d",
                impl.name().toLowerCase(Locale.ROOT),
                containerPort);

        GenericContainer<?> container = new GenericContainer<>(BASE_IMAGE)
                .withWorkingDirectory("/app")
                .withFileSystemBind(installDir.toString(), "/app", BindMode.READ_ONLY)
                .withCommand("sh", "-c", command)
                .withExposedPorts(containerPort)
                .waitingFor(Wait.forLogMessage(".*listening on.*\\n", 1)
                        .withStartupTimeout(Duration.ofSeconds(30)));

        container.start();
        int mappedPort = container.getMappedPort(containerPort);
        String host = container.getHost();

        return new ServerHandle(host, mappedPort, container::stop);
    }

    @Override
    protected String summaryLabel() {
        return "servers in Docker containers";
    }

    private static void assumeDocker() {
        boolean dockerAvailable;
        try {
            dockerAvailable = DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            dockerAvailable = false;
        }
        Assumptions.assumeTrue(dockerAvailable, "Docker is required for this test");
    }

    private static Path installDistDirectory() {
        return Path.of("build", "install", "helidon-vs-netty-benchmark-capacity").toAbsolutePath().normalize();
    }
}
