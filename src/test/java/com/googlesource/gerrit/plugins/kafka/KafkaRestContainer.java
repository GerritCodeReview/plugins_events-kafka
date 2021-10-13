// Copyright (C) 2021 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.googlesource.gerrit.plugins.kafka;

import org.junit.Ignore;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

@Ignore
public class KafkaRestContainer extends GenericContainer<KafkaRestContainer> {

  private static final String KAFKA_REST_PROXY_HOSTNAME = "restproxy";

  public static final int KAFKA_REST_PORT = 8086;

  public KafkaRestContainer(KafkaContainer kafkaContainer) {
    super(restProxyImageFor(kafkaContainer));

    withNetwork(kafkaContainer.getNetwork());

    withExposedPorts(KAFKA_REST_PORT);
    String bootstrapServers =
        String.format(
            "PLAINTEXT://%s:%s",
            kafkaContainer.getNetworkAliases().get(0), KafkaContainerProvider.KAFKA_PORT_INTERNAL);
    withEnv("KAFKA_REST_BOOTSTRAP_SERVERS", bootstrapServers);
    withEnv("KAFKA_REST_LISTENERS", "https://0.0.0.0:" + KAFKA_REST_PORT);
    withEnv("KAFKA_REST_CLIENT_SECURITY_PROTOCOL", "PLAINTEXT");
    withEnv("KAFKA_REST_HOST_NAME", KAFKA_REST_PROXY_HOSTNAME);
    withCreateContainerCmdModifier(cmd -> cmd.withHostName(KAFKA_REST_PROXY_HOSTNAME));
  }

  private static DockerImageName restProxyImageFor(KafkaContainer kafkaContainer) {
    String[] kafkaImageNameParts = kafkaContainer.getDockerImageName().split(":");
    return DockerImageName.parse(kafkaImageNameParts[0] + "-rest").withTag(kafkaImageNameParts[1]);
  }
}
