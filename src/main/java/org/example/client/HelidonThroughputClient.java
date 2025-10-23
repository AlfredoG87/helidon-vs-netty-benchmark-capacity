// SPDX-License-Identifier: Apache-2.0
package org.example.client;

import io.helidon.common.tls.Tls;
import io.helidon.webclient.api.WebClient;
import io.helidon.webclient.grpc.GrpcClient;
import io.helidon.webclient.http2.Http2ClientProtocolConfig;
import org.example.throughput.ThroughputServiceGrpc;

import java.util.List;

public final class HelidonThroughputClient implements ThroughputClient {
    private final String baseUri;

    public HelidonThroughputClient(String baseUri) {
        this.baseUri = baseUri.startsWith("http") ? baseUri : "http://" + baseUri;
    }

    @Override
    public void run(long numMessages, int sizeBytes) throws Exception {
        WebClient webClient = WebClient.builder()
                .baseUri(baseUri)
                .tls(Tls.builder().enabled(false).build())
                .protocolConfigs(List.of(Http2ClientProtocolConfig.builder()
                        .priorKnowledge(true)
                        .build()))
                .build();
        GrpcClient grpcClient = webClient.client(GrpcClient.PROTOCOL);
        ThroughputServiceGrpc.ThroughputServiceStub stub = ThroughputServiceGrpc.newStub(grpcClient.channel());
        ClientRunner.run("helidon", stub, numMessages, sizeBytes);
    }
}
