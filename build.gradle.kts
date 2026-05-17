// 👇 REQUIRED for Kotlin DSL to resolve protobuf { ... }
import com.google.protobuf.gradle.*


plugins {
    id("java")
    id("application")
    id("com.google.protobuf") version "0.9.4"
}

group = "org.example"
version = "0.0.1-SNAPSHOT"

application {
    mainClass = "org.example.cli.ThroughputBench"
}

repositories {
    mavenCentral()
    mavenLocal()
}

val grpcVersion = "1.66.0"          // keep consistent across plugins + deps
val protocVersion = "3.25.5"        // matches your earlier choice

dependencies {
    // --- gRPC Java (protoc runtime) ---
    implementation("io.grpc:grpc-netty-shaded:$grpcVersion")
    implementation("io.grpc:grpc-protobuf:$grpcVersion")
    implementation("io.grpc:grpc-stub:$grpcVersion")
    implementation("javax.annotation:javax.annotation-api:1.3.2")


    // If you need protobuf runtime explicitly (often pulled transitively)
    implementation("com.google.protobuf:protobuf-java:$protocVersion")

    // helidon server
    implementation("io.helidon.webserver:helidon-webserver:4.4.1")
    implementation("io.helidon.webserver:helidon-webserver-grpc:4.4.1")
    // helidon client
    implementation("io.helidon.webclient:helidon-webclient-grpc:4.4.1")
    implementation("io.helidon.webclient:helidon-webclient-http2:4.4.1")

    // --- Test ---
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.testcontainers:junit-jupiter:1.21.3")
    testImplementation("org.testcontainers:testcontainers:1.21.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.slf4j:slf4j-simple:1.7.36")

}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.test {
    dependsOn("installDist")
    useJUnitPlatform {
        if (!project.hasProperty("includeDocker")) {
            excludeTags("docker")
        }
        if (!project.hasProperty("includeKind")) {
            excludeTags("kind")
        }
        if (!project.hasProperty("includeChaos")) {
            excludeTags("chaos")
        }
    }
    testLogging {
        showStandardStreams = true
    }
    // Forward chaos.* system properties set on the Gradle command line to the test JVM
    System.getProperties()
        .keys.map { it.toString() }
        .filter { it.startsWith("chaos.") }
        .forEach { key -> systemProperty(key, System.getProperty(key)) }
    minHeapSize = "1g"
    maxHeapSize = "4g"
    jvmArgs(
        "-Dio.netty.maxDirectMemory=2147483648",
        "-XX:MaxDirectMemorySize=2g"
    )
}

// --- protoc + gRPC Java codegen ---
protobuf {
    protoc { artifact = "com.google.protobuf:protoc:$protocVersion" }
    plugins {
        id("grpc") { artifact = "io.grpc:protoc-gen-grpc-java:$grpcVersion" }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins { id("grpc") }
        }
    }
}
