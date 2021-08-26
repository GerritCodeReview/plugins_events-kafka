// Copyright (C) 2016 The Android Open Source Project
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

package com.googlesource.gerrit.plugins.kafka.config;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.CaseFormat;
import com.google.common.base.Strings;
import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.server.config.PluginConfig;
import com.google.gerrit.server.config.PluginConfigFactory;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;

@Singleton
public class KafkaProperties extends java.util.Properties {
  private static final long serialVersionUID = 0L;
  public static final String SEND_STREAM_EVENTS_CONFIG_FIELD = "sendStreamEvents";
  public static final String STREAM_EVENTS_TOPIC_FIELD = "topic";
  public static final String SEND_ASYNC_FIELD = "sendAsync";

  public static final Boolean SEND_STREAM_EVENTS_DEFAULT = false;
  public static final String STREAM_EVENTS_TOPIC_DEFAULT = "gerrit";
  public static final Boolean SEND_ASYNC_DEFAULT = true;

  public static final String KAFKA_STRING_SERIALIZER = StringSerializer.class.getName();

  private final String topic;
  private final boolean sendAsync;
  private final boolean sendStreamEvents;

  @Inject
  public KafkaProperties(PluginConfigFactory configFactory, @PluginName String pluginName) {
    super();
    setDefaults();
    PluginConfig fromGerritConfig = configFactory.getFromGerritConfig(pluginName);
    topic = fromGerritConfig.getString(STREAM_EVENTS_TOPIC_FIELD, STREAM_EVENTS_TOPIC_DEFAULT);
    sendAsync = fromGerritConfig.getBoolean(SEND_ASYNC_FIELD, SEND_ASYNC_DEFAULT);
    this.sendStreamEvents =
        fromGerritConfig.getBoolean(SEND_STREAM_EVENTS_CONFIG_FIELD, SEND_STREAM_EVENTS_DEFAULT);
    applyConfig(fromGerritConfig);
    initDockerizedKafkaServer();
  }

  @VisibleForTesting
  public KafkaProperties(boolean sendAsync) {
    super();
    setDefaults();
    topic = "gerrit";
    this.sendAsync = sendAsync;
    this.sendStreamEvents = true;
    initDockerizedKafkaServer();
  }

  private void setDefaults() {
    put("acks", "all");
    put("retries", 0);
    put("batch.size", 16384);
    put("linger.ms", 1);
    put("buffer.memory", 33554432);
    put("key.serializer", KAFKA_STRING_SERIALIZER);
    put("value.serializer", KAFKA_STRING_SERIALIZER);
    put("reconnect.backoff.ms", 5000L);
  }

  private void applyConfig(PluginConfig config) {
    for (String name : config.getNames()) {
      Object value = config.getString(name);
      String propName =
          CaseFormat.LOWER_CAMEL.to(CaseFormat.LOWER_HYPHEN, name).replaceAll("-", ".");
      put(propName, value);
    }
  }

  /** Bootstrap initialization of dockerized Kafka server environment */
  private void initDockerizedKafkaServer() {
    String testBootstrapServer = System.getProperty(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG);
    if (!Strings.isNullOrEmpty(testBootstrapServer)) {
      this.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, testBootstrapServer);
      this.put(ProducerConfig.CLIENT_ID_CONFIG, UUID.randomUUID().toString());
      this.put(ConsumerConfig.GROUP_ID_CONFIG, "tc-" + UUID.randomUUID());
      this.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, "1000");
      this.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    }
  }

  public String getTopic() {
    return topic;
  }

  public boolean isSendAsync() {
    return sendAsync;
  }

  public String getBootstrapServers() {
    return getProperty(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG);
  }

  public boolean isSendStreamEvents() {
    return sendStreamEvents;
  }
}
