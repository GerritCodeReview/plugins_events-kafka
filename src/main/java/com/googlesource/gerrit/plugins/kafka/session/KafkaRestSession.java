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

package com.googlesource.gerrit.plugins.kafka.session;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.googlesource.gerrit.plugins.kafka.config.KafkaProperties;
import com.googlesource.gerrit.plugins.kafka.publish.KafkaEventsPublisherMetrics;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class KafkaRestSession implements KafkaSession {

  private static final Logger LOGGER = LoggerFactory.getLogger(KafkaRestSession.class);
  private final KafkaProperties properties;
  private final Provider<KafkaProducer<String, String>> producerProvider;
  private final KafkaEventsPublisherMetrics publisherMetrics;

  @Inject
  public KafkaRestSession(
      Provider<KafkaProducer<String, String>> producerProvider,
      KafkaProperties properties,
      KafkaEventsPublisherMetrics publisherMetrics) {
    this.producerProvider = producerProvider;
    this.properties = properties;
    this.publisherMetrics = publisherMetrics;
  }

  /* (non-Javadoc)
   * @see com.googlesource.gerrit.plugins.kafka.session.KafkaSession#isOpen()
   */
  @Override
  public boolean isOpen() {
    throw new IllegalArgumentException("Unsupported method");
  }

  /* (non-Javadoc)
   * @see com.googlesource.gerrit.plugins.kafka.session.KafkaSession#connect()
   */
  @Override
  public void connect() {
    throw new IllegalArgumentException("Unsupported method");
  }

  /* (non-Javadoc)
   * @see com.googlesource.gerrit.plugins.kafka.session.KafkaSession#disconnect()
   */
  @Override
  public void disconnect() {
    throw new IllegalArgumentException("Unsupported method");
  }

  /* (non-Javadoc)
   * @see com.googlesource.gerrit.plugins.kafka.session.KafkaSession#publish(java.lang.String)
   */
  @Override
  public void publish(String messageBody) {
    publish(properties.getTopic(), messageBody);
  }

  /* (non-Javadoc)
   * @see com.googlesource.gerrit.plugins.kafka.session.KafkaSession#publish(java.lang.String, java.lang.String)
   */
  @Override
  public boolean publish(String topic, String messageBody) {
    if (properties.isSendAsync()) {
      return publishAsync(topic, messageBody) != null;
    }
    return publishSync(topic, messageBody);
  }

  private boolean publishSync(String topic, String messageBody) {

    try {
      Future<RecordMetadata> future = publishAsync(topic, messageBody);
      RecordMetadata metadata = future.get();
      LOGGER.debug("The offset of the record we just sent is: {}", metadata.offset());
      publisherMetrics.incrementBrokerPublishedMessage();
      return true;
    } catch (Throwable e) {
      LOGGER.error("Cannot send the message", e);
      publisherMetrics.incrementBrokerFailedToPublishMessage();
      return false;
    }
  }

  private Future<RecordMetadata> publishAsync(String topic, String messageBody) {
    try {
      Future<RecordMetadata> future =
          CompletableFuture.completedFuture(new RecordMetadata(null, 0, 0, 0, null, 0, 0));
      return future;
    } catch (Throwable e) {
      LOGGER.error("Cannot send the message", e);
      publisherMetrics.incrementBrokerFailedToPublishMessage();
      return CompletableFuture.failedFuture(e);
    }
  }
}
