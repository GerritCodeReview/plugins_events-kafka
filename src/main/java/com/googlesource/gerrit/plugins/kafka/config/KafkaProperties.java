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
import com.google.gerrit.common.Nullable;
import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.server.config.PluginConfig;
import com.google.gerrit.server.config.PluginConfigFactory;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;

@Singleton
public class KafkaProperties extends java.util.Properties {
  private static final String PROPERTY_HTTP_WIRE_LOG = "httpWireLog";
  private static final boolean DEFAULT_HTTP_WIRE_LOG = false;
  private static final String PROPERTY_REST_API_URI = "restApiUri";
  private static final String PROPERTY_CLIENT_TYPE = "clientType";
  private static final ClientType DEFAULT_CLIENT_TYPE = ClientType.NATIVE;
  private static final String PROPERTY_SEND_ASYNC = "sendAsync";
  private static final boolean DEFAULT_SEND_ASYNC = true;
  private static final String PROPERTY_STREAM_EVENTS_TOPIC_NAME = "topic";
  private static final String DEFAULT_STREAM_EVENTS_TOPIC_NAME = "gerrit";

  private static final long serialVersionUID = 0L;

  public static final String KAFKA_STRING_SERIALIZER = StringSerializer.class.getName();

  public enum ClientType {
    NATIVE,
    REST;
  }

  private final String topic;
  private final boolean sendAsync;
  private final ClientType clientType;
  private final URI restApiUri;
  private final boolean httpWireLog;

  @Inject
  public KafkaProperties(PluginConfigFactory configFactory, @PluginName String pluginName) {
    super();
    setDefaults();
    PluginConfig fromGerritConfig = configFactory.getFromGerritConfig(pluginName);
    topic =
        fromGerritConfig.getString(
            PROPERTY_STREAM_EVENTS_TOPIC_NAME, DEFAULT_STREAM_EVENTS_TOPIC_NAME);
    sendAsync = fromGerritConfig.getBoolean(PROPERTY_SEND_ASYNC, DEFAULT_SEND_ASYNC);
    clientType = fromGerritConfig.getEnum(PROPERTY_CLIENT_TYPE, DEFAULT_CLIENT_TYPE);

    switch (clientType) {
      case REST:
        String restApiUriString = fromGerritConfig.getString(PROPERTY_REST_API_URI);
        if (Strings.isNullOrEmpty(restApiUriString)) {
          throw new IllegalArgumentException("Missing REST API URI in Kafka properties");
        }

        try {
          restApiUri = new URI(restApiUriString);
        } catch (URISyntaxException e) {
          throw new IllegalArgumentException("Invalid Kafka REST API URI: " + restApiUriString, e);
        }
        httpWireLog = fromGerritConfig.getBoolean(PROPERTY_HTTP_WIRE_LOG, DEFAULT_HTTP_WIRE_LOG);
        break;
      case NATIVE:
      default:
        restApiUri = null;
        httpWireLog = false;
        break;
    }

    applyConfig(fromGerritConfig);
    initDockerizedKafkaServer();
  }

  @VisibleForTesting
  public KafkaProperties(boolean sendAsync, ClientType clientType, @Nullable URI restApiURI) {
    super();
    setDefaults();
    topic = DEFAULT_STREAM_EVENTS_TOPIC_NAME;
    this.sendAsync = sendAsync;
    this.clientType = clientType;
    this.restApiUri = restApiURI;
    initDockerizedKafkaServer();
    this.httpWireLog = false;
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

  public ClientType getClientType() {
    return clientType;
  }

  public URI getRestApiUri() {
    return restApiUri;
  }

  public boolean isHttpWireLog() {
    return httpWireLog;
  }
}
