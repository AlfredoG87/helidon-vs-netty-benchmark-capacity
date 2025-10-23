// SPDX-License-Identifier: Apache-2.0
package org.example.client;

public interface ThroughputClient {
    void run(long numMessages, int sizeBytes) throws Exception;
}
